package com.marketplace.backend.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app")
data class AppProperties(
    @field:Valid val jwt: JwtProps,
    @field:Valid val auth: AuthProps,
    @field:Valid val cors: CorsProps,
    @field:Valid val rateLimit: RateLimitProps,
    @field:Valid val sms: SmsProps,
    @field:Valid val media: MediaProps = MediaProps(),
    @field:Valid val realtimeSnapshot: RealtimeSnapshotProps = RealtimeSnapshotProps()
) {
    data class JwtProps(
        @field:NotBlank
        @field:Size(min = 32, message = "JWT_SECRET must be at least 32 characters for HS256")
        val secret: String,
        @field:Min(60_000)
        val expirationMillis: Long = 604_800_000L
    )

    data class AuthProps(
        @field:NotBlank val adminPhone: String = "09120000000",
        @field:NotBlank val adminBootstrapPassword: String = "1234",
        @field:Min(30) val otpTtlSeconds: Long = 180,
        @field:Min(1) val otpMaxAttempts: Int = 5,
        val exposeSandboxOtp: Boolean = false,
        val requireOtpOnRegister: Boolean = false
    )

    data class CorsProps(
        val allowedOrigins: List<String> = listOf("http://localhost:3000", "http://localhost:8080")
    )

    data class RateLimitProps(
        @field:Min(10) val defaultRequestsPerMinute: Int = 120,
        @field:Min(3) val authRequestsPerMinute: Int = 20
    )

    data class SmsProps(
        @field:NotBlank val baseUrl: String = "https://api.sms.ir/v1/send/verify",
        val apiKey: String = "",
        val templateId: Int = 100000,
        @field:Min(1) val maxRetries: Int = 3
    )

    data class MediaProps(
        @field:NotBlank val uploadDir: String = "uploads",
        @field:Min(1024) val maxFileSizeBytes: Long = 5L * 1024 * 1024
    )

    data class RealtimeSnapshotProps(
        val enabled: Boolean = false,
        val filePath: String = "realtime_database.txt"
    )
}