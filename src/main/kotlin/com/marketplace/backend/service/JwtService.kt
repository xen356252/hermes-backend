package com.marketplace.backend.service

import com.marketplace.backend.config.AppProperties
import com.marketplace.backend.domain.User
import com.marketplace.backend.domain.UserRole
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.util.*

data class AuthenticatedActor(
    val userId: Long,
    val role: UserRole
) {
    fun isAdmin(): Boolean = role == UserRole.ADMIN
}

@Service
class JwtService(
    private val appProperties: AppProperties
) {
    private val key = Keys.hmacShaKeyFor(appProperties.jwt.secret.toByteArray(StandardCharsets.UTF_8))

    fun generateToken(user: User): String = Jwts.builder()
        .subject(user.id.toString())
        .claim("phone", user.phone)
        .claim("role", user.role.name)
        .issuedAt(Date())
        .expiration(Date(System.currentTimeMillis() + appProperties.jwt.expirationMillis))
        .signWith(key)
        .compact()

    fun parseAndValidate(token: String): AuthenticatedActor? = try {
        val claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload

        val userId = claims.subject?.toLongOrNull() ?: return null
        val role = claims["role"]?.toString()?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }
            ?: UserRole.BUYER
        AuthenticatedActor(userId = userId, role = role)
    } catch (_: Exception) {
        null
    }
}