package com.marketplace.backend.service

import com.marketplace.backend.config.AppProperties
import com.marketplace.backend.domain.*
import com.marketplace.backend.repository.OtpVerificationRepository
import com.marketplace.backend.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.LocalDateTime

data class OtpDispatchResult(
    val message: String,
    val sandboxOtpHint: String?,
    val queuedForSmsIr: Boolean
)

data class AuthResult(
    val token: String,
    val user: User
)

@Service
class AuthService(
    private val appProperties: AppProperties,
    private val userRepository: UserRepository,
    private val otpRepository: OtpVerificationRepository,
    private val smsGateway: SmsGateway,
    private val jwtService: JwtService,
    private val passwordEncoder: PasswordEncoder
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()
    private val dummyBcryptHash: String = requireNotNull(passwordEncoder.encode("hermes-dummy-timing-guard"))

    private fun hashSecret(raw: String): String =
        requireNotNull(passwordEncoder.encode(raw)) { "Failed to hash secret" }

    @Transactional
    fun sendOtp(rawPhone: String): OtpDispatchResult {
        val cleanPhone = InputValidator.requireValidPhone(rawPhone)
        val rawOtp = (1000 + secureRandom.nextInt(9000)).toString()
        val expiresAt = LocalDateTime.now().plusSeconds(appProperties.auth.otpTtlSeconds)

        val existing = otpRepository.findByPhone(cleanPhone)
        if (existing != null) {
            existing.hashedCode = hashSecret(rawOtp)
            existing.expiresAt = expiresAt
            existing.failedAttempts = 0
            existing.createdAt = LocalDateTime.now()
            otpRepository.save(existing)
        } else {
            otpRepository.save(
                OtpVerification(
                    phone = cleanPhone,
                    hashedCode = hashSecret(rawOtp),
                    expiresAt = expiresAt
                )
            )
        }

        val configured = smsGateway.isConfigured()
        smsGateway.dispatchOtpAsync(cleanPhone, rawOtp)

        val message = if (configured) {
            "کد تایید در صف ارسال SMS.ir قرار گرفت"
        } else {
            "کد تایید یکبار مصرف (محیط تست Sandbox) تولید شد"
        }

        return OtpDispatchResult(
            message = message,
            sandboxOtpHint = if (appProperties.auth.exposeSandboxOtp) rawOtp else null,
            queuedForSmsIr = configured
        )
    }

    @Transactional(noRollbackFor = [UnauthorizedException::class])
    fun register(rawPhone: String, rawFullName: String, requestedRole: UserRole, rawCredential: String): AuthResult {
        val cleanPhone = InputValidator.requireValidPhone(rawPhone)
        val cleanCredential = rawCredential.trim()

        if (cleanCredential.length < 4) {
            throw BadRequestException("رمز عبور یا کد تایید پیامکی باید حداقل ۴ کاراکتر باشد")
        }
        if (userRepository.existsByPhone(cleanPhone)) {
            throw ConflictException("این شماره موبایل قبلاً ثبت شده است. لطفاً وارد شوید.")
        }

        val otpVerified = verifyAndConsumeOtp(cleanPhone, cleanCredential)
        if (appProperties.auth.requireOtpOnRegister && !otpVerified) {
            throw UnauthorizedException("کد تایید پیامکی نامعتبر یا منقضی شده است")
        }

        val effectiveRole = when {
            cleanPhone == appProperties.auth.adminPhone -> UserRole.ADMIN
            requestedRole == UserRole.ADMIN -> UserRole.BUYER
            else -> requestedRole
        }

        val defaultName = when (effectiveRole) {
            UserRole.ADMIN -> "مدیر کل هرمس"
            UserRole.SELLER -> "غرفه‌دار هرمس"
            UserRole.BUYER -> "کاربر هرمس"
        }
        val finalName = rawFullName.trim().takeIf { it.isNotBlank() }
            ?.let { InputValidator.requireValidHumanName(it) }
            ?: defaultName

        val saved = userRepository.save(
            User(
                phone = cleanPhone,
                fullName = finalName,
                password = hashSecret(cleanCredential),
                role = effectiveRole
            )
        )
        return AuthResult(token = jwtService.generateToken(saved), user = saved)
    }

    @Transactional(noRollbackFor = [UnauthorizedException::class])
    fun login(rawPhone: String, rawCredential: String): AuthResult {
        val cleanPhone = InputValidator.requireValidPhone(rawPhone)
        val cleanCredential = rawCredential.trim()

        if (cleanCredential.length < 4) {
            throw BadRequestException("رمز عبور یا کد تایید باید حداقل ۴ کاراکتر باشد")
        }

        val user = userRepository.findByPhone(cleanPhone)
        if (user == null) {
            passwordEncoder.matches(cleanCredential, dummyBcryptHash)
            throw UnauthorizedException("شماره موبایل یا رمز عبور / کد تایید اشتباه است")
        }

        val isOtpValid = verifyAndConsumeOtp(cleanPhone, cleanCredential)
        val isPasswordValid = !isOtpValid && passwordEncoder.matches(cleanCredential, user.password)

        if (!isOtpValid && !isPasswordValid) {
            throw UnauthorizedException("شماره موبایل یا رمز عبور / کد تایید اشتباه است")
        }

        return AuthResult(token = jwtService.generateToken(user), user = user)
    }

    @Transactional(noRollbackFor = [UnauthorizedException::class])
    fun changePassword(rawPhone: String, rawCurrentCredential: String, rawNewPassword: String) {
        val cleanPhone = InputValidator.requireValidPhone(rawPhone)
        val currentCredential = rawCurrentCredential.trim()
        val newPassword = rawNewPassword.trim()

        if (newPassword.length < 4) {
            throw BadRequestException("رمز عبور جدید باید حداقل ۴ کاراکتر باشد")
        }

        val user = userRepository.findByPhone(cleanPhone)
        if (user == null) {
            passwordEncoder.matches(currentCredential, dummyBcryptHash)
            throw UnauthorizedException("اطلاعات احراز هویت نامعتبر است")
        }

        val isOtpValid = verifyAndConsumeOtp(cleanPhone, currentCredential)
        val isCurrentPassValid = !isOtpValid && passwordEncoder.matches(currentCredential, user.password)

        if (!isOtpValid && !isCurrentPassValid) {
            throw UnauthorizedException("اطلاعات احراز هویت نامعتبر است")
        }

        user.password = hashSecret(newPassword)
        userRepository.save(user)
    }

    @Transactional
    internal fun verifyAndConsumeOtp(phone: String, rawCode: String): Boolean {
        val record = otpRepository.findByPhone(phone) ?: return false
        if (LocalDateTime.now().isAfter(record.expiresAt)) {
            otpRepository.delete(record)
            return false
        }
        if (passwordEncoder.matches(rawCode, record.hashedCode)) {
            otpRepository.delete(record)
            return true
        }
        record.failedAttempts += 1
        if (record.failedAttempts >= appProperties.auth.otpMaxAttempts) {
            otpRepository.delete(record)
        } else {
            otpRepository.save(record)
        }
        return false
    }

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    fun cleanupExpiredOtps() {
        val removed = otpRepository.deleteAllExpired(LocalDateTime.now())
        if (removed > 0) {
            logger.debug("Cleaned up {} expired OTP records", removed)
        }
    }
}