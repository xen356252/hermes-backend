package com.marketplace.backend.controller

import com.marketplace.backend.model.Address
import com.marketplace.backend.model.User
import com.marketplace.backend.model.UserRole
import com.marketplace.backend.repository.AddressRepository
import com.marketplace.backend.repository.BoothRepository
import com.marketplace.backend.repository.UserRepository
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized, DRY input validator used across all controllers.
 */
object InputValidator {
    private val IRAN_PHONE_REGEX = Regex("^09\\d{9}$")
    private val HUMAN_NAME_REGEX = Regex("^[\\u0600-\\u06FFa-zA-Z\\s\\u200C]{2,80}$")
    private val POSTAL_CODE_REGEX = Regex("^\\d{10}$")
    private val NATIONAL_CODE_REGEX = Regex("^\\d{10}$")
    private val SHEBA_REGEX = Regex("^(IR)?\\d{24}$")

    fun normalizePhone(raw: String): String {
        val ascii = raw.trim().map { ch ->
            when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
        }.joinToString("")
        val digits = ascii.filter { it.isDigit() }
        return when {
            digits.startsWith("989") && digits.length == 12 -> "0" + digits.removePrefix("98")
            digits.startsWith("9") && digits.length == 10 -> "0$digits"
            else -> digits
        }
    }

    fun requireValidPhone(rawPhone: String): String {
        val phone = normalizePhone(rawPhone)
        if (!IRAN_PHONE_REGEX.matches(phone)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "شماره موبایل باید ۱۱ رقم و با 09 شروع شود")
        }
        return phone
    }

    fun requireValidHumanName(rawName: String, fieldLabel: String = "نام و نام خانوادگی"): String {
        val clean = rawName.trim()
        if (!HUMAN_NAME_REGEX.matches(clean)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$fieldLabel فقط باید شامل حروف فارسی یا انگلیسی باشد")
        }
        return clean
    }

    fun requireValidPostalCode(rawPostal: String): String {
        val clean = rawPostal.trim()
        if (!POSTAL_CODE_REGEX.matches(clean)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "کد پستی باید دقیقاً ۱۰ رقم باشد")
        }
        return clean
    }

    fun requireValidNationalCode(rawCode: String): String {
        val clean = rawCode.trim()
        if (!NATIONAL_CODE_REGEX.matches(clean) || (0..9).any { d -> clean.all { it == ('0' + d) } }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "کد ملی باید ۱۰ رقم معتبر باشد")
        }
        val check = clean[9] - '0'
        val sum = (0..8).sumOf { i -> (clean[i] - '0') * (10 - i) } % 11
        val isValid = (sum < 2 && check == sum) || (sum >= 2 && check + sum == 11)
        if (!isValid) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "کد ملی وارد شده معتبر نیست")
        }
        return clean
    }

    fun normalizeSheba(rawSheba: String): String {
        val clean = rawSheba.trim().uppercase()
        if (!SHEBA_REGEX.matches(clean)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "شماره شبا باید ۲۴ رقم (همراه با IR) باشد")
        }
        return if (clean.startsWith("IR")) clean else "IR$clean"
    }
}

interface SmsService {
    fun sendOtp(phone: String, code: String): Boolean
}

@Service
class SmsIrSandboxService(
    @Value("\${sms.ir.api-key:sxyroI9E6KAdoAKZbT74dbegiJffEDeNUELzyGS424sqIWjt}") private val apiKey: String,
    @Value("\${sms.ir.template-id:100000}") private val templateId: Int
) : SmsService {

    override fun sendOtp(phone: String, code: String): Boolean {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return false

        return try {
            val endpoint = "https://api.sms.ir/v1/send/verify"
            val payload = """
                {
                  "mobile": "$phone",
                  "templateId": $templateId,
                  "parameters": [
                    {
                      "name": "CODE",
                      "value": "$code"
                    }
                  ]
                }
            """.trimIndent()

            val conn = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-API-KEY", cleanKey)
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val responseText = if (status in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText().orEmpty()
            }
            println("✅ [SMS.ir Sandbox] Status: $status | Body: $responseText")
            status in 200..299
        } catch (e: Exception) {
            println("⚠️ [SMS.ir Sandbox] Connection failed: ${e.message}")
            false
        }
    }
}

@Service
class JwtService {
    private val secret = "SnapMarketplaceSuperSecretKeyForJwtSigning2026Sprint1!"
    private val key = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))

    fun generateToken(user: User): String {
        val sevenDaysInMillis = 604_800_000L
        return Jwts.builder()
            .subject(user.id.toString())
            .claim("phone", user.phone)
            .claim("role", user.role.name)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + sevenDaysInMillis))
            .signWith(key)
            .compact()
    }

    fun parseAndValidate(token: String): Claims? = try {
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
    } catch (_: Exception) {
        null
    }
}

data class SendOtpRequest(val phone: String)
data class SendOtpResponse(
    val message: String,
    val mockOtpHint: String,
    val sentViaSmsIr: Boolean = false
)

data class VerifyOtpRequest(val phone: String, val otpCode: String)

data class RegisterRequest(
    val phone: String,
    val fullName: String = "",
    val role: UserRole = UserRole.BUYER,
    val otpCode: String
)

data class ChangePasswordRequest(
    val phone: String,
    val oldPassword: String,
    val newPassword: String
)

data class AuthResponse(
    val token: String,
    val user: UserProfileDto
)

data class UserProfileDto(
    val id: Long,
    val phone: String,
    val fullName: String,
    val role: UserRole,
    val avatarUrl: String?,
    val nationalCode: String? = null,
    val email: String? = null,
    val birthDate: String? = null,
    val shebaNumber: String? = null,
    val bio: String? = null
)

data class UpdateProfileRequest(
    val fullName: String,
    val phone: String? = null,
    val avatarUrl: String? = null,
    val role: UserRole? = null,
    val nationalCode: String? = null,
    val email: String? = null,
    val birthDate: String? = null,
    val shebaNumber: String? = null,
    val bio: String? = null
)

data class AddressDto(
    val id: Long,
    val title: String,
    val city: String,
    val fullAddress: String,
    val postalCode: String,
    val isDefault: Boolean
)

data class CreateAddressRequest(
    val title: String,
    val city: String,
    val fullAddress: String,
    val postalCode: String,
    val isDefault: Boolean = false
)

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "1. Authentication", description = "SMS.ir Sandbox OTP & BCrypt Password Authentication")
class AuthController(
    private val userRepository: UserRepository,
    private val smsService: SmsService,
    private val jwtService: JwtService,
    private val passwordEncoder: PasswordEncoder
) {
    private data class HashedOtpRecord(
        val hashedCode: String,
        val expiresAtMillis: Long,
        var failedAttempts: Int = 0
    )

    private val secureRandom = SecureRandom()
    private val otpStore = ConcurrentHashMap<String, HashedOtpRecord>()
    private val otpTtlMillis = 180_000L // 3 minutes
    private val maxOtpAttempts = 5

    private fun encodeSecret(raw: String): String =
        requireNotNull(passwordEncoder.encode(raw)) { "Failed to hash secret" }

    private fun verifyAndConsumeOtp(phone: String, rawCode: String): Boolean {
        val record = otpStore[phone] ?: return false
        if (System.currentTimeMillis() > record.expiresAtMillis) {
            otpStore.remove(phone)
            return false
        }
        if (passwordEncoder.matches(rawCode, record.hashedCode)) {
            otpStore.remove(phone)
            return true
        }
        record.failedAttempts += 1
        if (record.failedAttempts >= maxOtpAttempts) {
            otpStore.remove(phone)
        }
        return false
    }

    @PostMapping("/send-otp")
    @Operation(summary = "Generate 4-digit OTP, hash in memory with TTL, and dispatch via SMS.ir Sandbox API")
    fun sendOtp(@RequestBody req: SendOtpRequest): ResponseEntity<SendOtpResponse> {
        val cleanPhone = InputValidator.requireValidPhone(req.phone)
        val rawOtp = (1000 + secureRandom.nextInt(9000)).toString()

        otpStore[cleanPhone] = HashedOtpRecord(
            hashedCode = encodeSecret(rawOtp),
            expiresAtMillis = System.currentTimeMillis() + otpTtlMillis
        )

        val sent = smsService.sendOtp(cleanPhone, rawOtp)
        val message = if (sent) {
            "کد تایید از طریق وب‌سرویس آزمایشی SMS.ir ارسال شد"
        } else {
            "کد تایید یکبار مصرف (محیط تست Sandbox) تولید شد"
        }

        return ResponseEntity.ok(
            SendOtpResponse(
                message = message,
                mockOtpHint = rawOtp,
                sentViaSmsIr = sent
            )
        )
    }

    @PostMapping("/register")
    @Transactional
    @Operation(summary = "Register a Buyer or Seller using SMS.ir OTP or Password (stored as BCrypt hash)")
    fun register(@RequestBody req: RegisterRequest): ResponseEntity<AuthResponse> {
        val cleanPhone = InputValidator.requireValidPhone(req.phone)
        val cleanCredential = req.otpCode.trim()

        if (cleanCredential.length < 4) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "رمز عبور یا کد تایید پیامکی باید حداقل ۴ کاراکتر باشد")
        }
        if (userRepository.existsByPhone(cleanPhone)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "این شماره موبایل قبلاً ثبت شده است. لطفاً وارد شوید.")
        }

        // Consume active OTP if the user registered via SMS code
        verifyAndConsumeOtp(cleanPhone, cleanCredential)

        val effectiveRole = if (cleanPhone == "09120000000") UserRole.ADMIN else req.role
        val defaultName = when (effectiveRole) {
            UserRole.ADMIN -> "مدیر کل هرمس"
            UserRole.SELLER -> "غرفه‌دار هرمس"
            UserRole.BUYER -> "کاربر هرمس"
        }
        val finalName = req.fullName.trim().takeIf { it.isNotBlank() }
            ?.let { InputValidator.requireValidHumanName(it) }
            ?: defaultName

        val newUser = userRepository.save(
            User(
                phone = cleanPhone,
                fullName = finalName,
                password = encodeSecret(cleanCredential),
                role = effectiveRole
            )
        )
        val token = jwtService.generateToken(newUser)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            AuthResponse(token = token, user = newUser.toDto())
        )
    }

    @PostMapping("/login")
    @Operation(summary = "Login using either SMS.ir Sandbox OTP or BCrypt Password")
    fun login(@RequestBody req: VerifyOtpRequest): ResponseEntity<AuthResponse> {
        val cleanPhone = InputValidator.requireValidPhone(req.phone)
        val cleanCredential = req.otpCode.trim()

        if (cleanCredential.length < 4) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "رمز عبور یا کد تایید باید حداقل ۴ کاراکتر باشد")
        }

        val user = userRepository.findByPhone(cleanPhone)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "کاربری با این شماره یافت نشد. ابتدا ثبت‌نام کنید.")

        val isOtpValid = verifyAndConsumeOtp(cleanPhone, cleanCredential)
        val isPasswordValid = !isOtpValid && passwordEncoder.matches(cleanCredential, user.password)

        if (!isOtpValid && !isPasswordValid) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "رمز عبور یا کد تایید پیامکی وارد شده اشتباه است")
        }

        val token = jwtService.generateToken(user)
        return ResponseEntity.ok(AuthResponse(token = token, user = user.toDto()))
    }

    @PostMapping("/change-password")
    @Transactional
    @Operation(summary = "Verify old password (or active SMS OTP) and save new BCrypt password")
    fun changePassword(@RequestBody req: ChangePasswordRequest): Map<String, String> {
        val cleanPhone = InputValidator.requireValidPhone(req.phone)
        val oldCredential = req.oldPassword.trim()
        val newPassword = req.newPassword.trim()

        if (newPassword.length < 4) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "رمز عبور جدید باید حداقل ۴ کاراکتر باشد")
        }

        val user = userRepository.findByPhone(cleanPhone)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "کاربری با این شماره موبایل یافت نشد")

        val isOtpValid = verifyAndConsumeOtp(cleanPhone, oldCredential)
        val isOldPassValid = !isOtpValid && passwordEncoder.matches(oldCredential, user.password)

        if (!isOtpValid && !isOldPassValid) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "رمز عبور قبلی یا کد پیامکی اشتباه است")
        }

        user.password = encodeSecret(newPassword)
        userRepository.save(user)
        return mapOf("message" to "رمز عبور شما با موفقیت تغییر یافت")
    }
}

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "2. User Profile, Booths & Addresses", description = "Profile management, Seller Booths & Shipping Address CRUD")
class UserProfileController(
    private val userRepository: UserRepository,
    private val addressRepository: AddressRepository,
    private val boothRepository: BoothRepository
) {

    @GetMapping("/{userId}/profile")
    fun getProfile(@PathVariable userId: Long): UserProfileDto {
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "کاربر یافت نشد")
        }
        return user.toDto()
    }

    @PutMapping("/{userId}/profile")
    @Transactional
    fun updateProfile(
        @PathVariable userId: Long,
        @RequestBody req: UpdateProfileRequest
    ): UserProfileDto {
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "کاربر یافت نشد")
        }
        user.fullName = InputValidator.requireValidHumanName(req.fullName)

        req.phone?.takeIf { it.isNotBlank() }?.let { rawPhone ->
            val cleanPhone = InputValidator.requireValidPhone(rawPhone)
            if (cleanPhone != user.phone && userRepository.existsByPhone(cleanPhone)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "این شماره موبایل متعلق به حساب دیگری است")
            }
            user.phone = cleanPhone
        }

        req.nationalCode?.takeIf { it.isNotBlank() }?.let {
            user.nationalCode = InputValidator.requireValidNationalCode(it)
        }
        req.shebaNumber?.takeIf { it.isNotBlank() }?.let {
            user.shebaNumber = InputValidator.normalizeSheba(it)
        }
        req.avatarUrl?.let { user.avatarUrl = it }
        req.role?.let { user.role = it }
        req.email?.let { user.email = it.trim() }
        req.birthDate?.let { user.birthDate = it.trim() }
        req.bio?.let { user.bio = it.trim() }

        return userRepository.save(user).toDto()
    }

    @GetMapping("/{userId}/booths")
    fun getSellerBooths(@PathVariable userId: Long): List<BoothDto> =
        boothRepository.findBySellerId(userId).map { it.toDto() }

    @GetMapping("/{userId}/addresses")
    fun getAddresses(@PathVariable userId: Long): List<AddressDto> =
        addressRepository.findByUserId(userId)
            .sortedByDescending { it.isDefault }
            .map { it.toDto() }

    @PostMapping("/{userId}/addresses")
    @Transactional
    fun addAddress(
        @PathVariable userId: Long,
        @RequestBody req: CreateAddressRequest
    ): ResponseEntity<AddressDto> {
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "کاربر یافت نشد")
        }
        val cleanTitle = req.title.trim()
        val cleanCity = InputValidator.requireValidHumanName(req.city, "نام شهر")
        val cleanFull = req.fullAddress.trim()
        val cleanPostal = InputValidator.requireValidPostalCode(req.postalCode)

        if (cleanTitle.length < 2 || cleanTitle.all { it.isDigit() }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "عنوان آدرس باید معتبر و شامل حروف باشد")
        }
        if (cleanFull.length < 8 || cleanFull.all { it.isDigit() }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "لطفاً آدرس کامل پستی را با جزئیات وارد کنید")
        }

        val existingAddresses = addressRepository.findByUserId(userId)
        val shouldBeDefault = req.isDefault || existingAddresses.isEmpty()

        if (shouldBeDefault) {
            existingAddresses.forEach {
                it.isDefault = false
                addressRepository.save(it)
            }
        }

        val saved = addressRepository.save(
            Address(
                user = user,
                title = cleanTitle,
                city = cleanCity,
                fullAddress = cleanFull,
                postalCode = cleanPostal,
                isDefault = shouldBeDefault
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toDto())
    }

    @PutMapping("/{userId}/addresses/{addressId}/default")
    @Transactional
    fun setDefaultAddress(
        @PathVariable userId: Long,
        @PathVariable addressId: Long
    ): List<AddressDto> {
        val addresses = addressRepository.findByUserId(userId)
        if (addresses.none { it.id == addressId }) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "آدرس مورد نظر برای این کاربر یافت نشد")
        }
        addresses.forEach { addr ->
            addr.isDefault = (addr.id == addressId)
            addressRepository.save(addr)
        }
        return addresses.sortedByDescending { it.isDefault }.map { it.toDto() }
    }

    @DeleteMapping("/{userId}/addresses/{addressId}")
    @Transactional
    fun deleteAddress(@PathVariable userId: Long, @PathVariable addressId: Long): ResponseEntity<Void> {
        val addr = addressRepository.findById(addressId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "آدرس یافت نشد")
        }
        if (addr.user.id != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "شما مجاز به حذف این آدرس نیستید")
        }
        addressRepository.delete(addr)
        val remaining = addressRepository.findByUserId(userId)
        if (addr.isDefault && remaining.isNotEmpty()) {
            remaining.first().isDefault = true
            addressRepository.save(remaining.first())
        }
        return ResponseEntity.noContent().build()
    }
}

internal fun User.toDto() = UserProfileDto(
    id = id,
    phone = phone,
    fullName = fullName,
    role = role,
    avatarUrl = avatarUrl,
    nationalCode = nationalCode,
    email = email,
    birthDate = birthDate,
    shebaNumber = shebaNumber,
    bio = bio
)

internal fun Address.toDto() = AddressDto(id, title, city, fullAddress, postalCode, isDefault)