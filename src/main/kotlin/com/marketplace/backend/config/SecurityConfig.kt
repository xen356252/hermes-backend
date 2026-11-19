package com.marketplace.backend.config

import com.marketplace.backend.service.JwtService
import com.marketplace.backend.web.HermesRequestContextFilter
import com.marketplace.backend.web.JwtAuthenticationFilter
import com.marketplace.backend.web.RateLimitFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val appProperties: AppProperties,
    private val jwtService: JwtService
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val requestContextFilter = HermesRequestContextFilter()
        val rateLimitFilter = RateLimitFilter(appProperties)
        val jwtAuthenticationFilter = JwtAuthenticationFilter(jwtService)

        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { ex ->
                ex.authenticationEntryPoint { _, response, _ ->
                    response.status = HttpStatus.UNAUTHORIZED.value()
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.characterEncoding = "UTF-8"
                    response.writer.write("""{"status":401,"error":"UNAUTHORIZED","message":"برای دسترسی به این بخش ابتدا وارد حساب کاربری شوید"}""")
                }
                ex.accessDeniedHandler { _, response, _ ->
                    response.status = HttpStatus.FORBIDDEN.value()
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.characterEncoding = "UTF-8"
                    response.writer.write("""{"status":403,"error":"FORBIDDEN","message":"شما مجوز دسترسی به این عملیات را ندارید"}""")
                }
            }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/api/v1/auth/send-otp",
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/change-password"
                ).permitAll()

                auth.requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/vitrine/guest",
                    "/api/v1/banners",
                    "/api/v1/categories/**",
                    "/api/v1/products/**",
                    "/api/v1/booths",
                    "/api/v1/booths/{boothId}",
                    "/api/v1/booths/{boothId}/products",
                    "/api/v1/media/**"
                ).permitAll()

                auth.requestMatchers(HttpMethod.POST, "/api/v1/banners").hasRole("ADMIN")
                auth.requestMatchers(HttpMethod.DELETE, "/api/v1/banners/**").hasRole("ADMIN")

                auth.anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter::class.java)
            .addFilterBefore(requestContextFilter, RateLimitFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): UrlBasedCorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = appProperties.cors.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "X-Request-ID")
            exposedHeaders = listOf("X-Request-ID")
            allowCredentials = false
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }
}