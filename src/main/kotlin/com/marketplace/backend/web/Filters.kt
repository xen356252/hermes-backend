package com.marketplace.backend.web

import com.marketplace.backend.config.AppProperties
import com.marketplace.backend.service.JwtService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class HermesRequestContextFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val requestId = request.getHeader("X-Request-ID")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 64 }
            ?: UUID.randomUUID().toString()

        val startNanos = System.nanoTime()
        MDC.put("requestId", requestId)
        RequestContextHolder.set(RequestContext(requestId = requestId))
        response.setHeader("X-Request-ID", requestId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            log.info("{} {} -> {} ({} ms)", request.method, request.requestURI, response.status, elapsedMs)
            RequestContextHolder.clear()
            MDC.remove("requestId")
        }
    }
}

class RateLimitFilter(
    private val appProperties: AppProperties
) : OncePerRequestFilter() {

    private data class WindowCounter(
        @Volatile var windowStartMillis: Long,
        val count: AtomicInteger = AtomicInteger(0)
    )

    private val buckets = ConcurrentHashMap<String, WindowCounter>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val path = request.requestURI
        if (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) {
            filterChain.doFilter(request, response)
            return
        }

        val isAuthRoute = path.startsWith("/api/v1/auth/")
        val limit = if (isAuthRoute) {
            appProperties.rateLimit.authRequestsPerMinute
        } else {
            appProperties.rateLimit.defaultRequestsPerMinute
        }

        val clientIp = request.remoteAddr ?: "unknown"
        val bucketKey = "${if (isAuthRoute) "auth" else "api"}:$clientIp"
        val now = System.currentTimeMillis()

        val counter = buckets.compute(bucketKey) { _, existing ->
            if (existing == null || now - existing.windowStartMillis >= 60_000L) {
                WindowCounter(windowStartMillis = now, count = AtomicInteger(1))
            } else {
                existing.count.incrementAndGet()
                existing
            }
        }!!

        if (buckets.size > 10_000) {
            buckets.entries.removeIf { now - it.value.windowStartMillis >= 120_000L }
        }

        if (counter.count.get() > limit) {
            val requestId = RequestContextHolder.get()?.requestId.orEmpty()
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.setHeader("Retry-After", "60")
            response.writer.write(
                """{"status":429,"error":"TOO_MANY_REQUESTS","message":"تعداد درخواست‌های شما بیش از حد مجاز است. لطفاً کمی بعد تلاش کنید.","requestId":"$requestId"}"""
            )
            return
        }

        filterChain.doFilter(request, response)
    }
}

class JwtAuthenticationFilter(
    private val jwtService: JwtService
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authHeader = request.getHeader("Authorization")
        if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ")) {
            val token = authHeader.removePrefix("Bearer ").trim()
            val actor = jwtService.parseAndValidate(token)
            if (actor != null) {
                val existingCtx = RequestContextHolder.get()
                if (existingCtx != null) {
                    RequestContextHolder.set(existingCtx.copy(actor = actor))
                }
                if (SecurityContextHolder.getContext().authentication == null) {
                    val auth = UsernamePasswordAuthenticationToken(
                        actor,
                        null,
                        listOf(SimpleGrantedAuthority("ROLE_${actor.role.name}"))
                    )
                    SecurityContextHolder.getContext().authentication = auth
                }
            }
        }
        filterChain.doFilter(request, response)
    }
}