package com.marketplace.backend.service

import com.marketplace.backend.domain.*
import com.marketplace.backend.repository.AddressRepository
import com.marketplace.backend.repository.BoothRepository
import com.marketplace.backend.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ProfileUpdateCommand(
    val fullName: String,
    val avatarUrl: String?,
    val nationalCode: String?,
    val email: String?,
    val birthDate: String?,
    val shebaNumber: String?,
    val bio: String?
)

data class CreateAddressCommand(
    val title: String,
    val city: String,
    val fullAddress: String,
    val postalCode: String,
    val isDefault: Boolean
)

@Service
class UserService(
    private val userRepository: UserRepository,
    private val addressRepository: AddressRepository,
    private val boothRepository: BoothRepository,
    private val authService: AuthService,
    private val jwtService: JwtService
) {
    private fun requireSelfOrAdmin(targetUserId: Long, actor: AuthenticatedActor) {
        if (actor.userId != targetUserId && !actor.isAdmin()) {
            throw ForbiddenException("شما مجاز به دسترسی به اطلاعات این حساب کاربری نیستید")
        }
    }

    /**
     * Optional field update semantics:
     * - null: omitted in payload -> keep existing value
     * - blank (""): explicitly clear field to null
     * - non-blank: validate and store trimmed value
     */
    private fun resolveOptionalField(
        incoming: String?,
        current: String?,
        validator: (String) -> String = { it }
    ): String? = when {
        incoming == null -> current
        incoming.isBlank() -> null
        else -> validator(incoming.trim())
    }

    @Transactional(readOnly = true)
    fun getProfile(userId: Long, actor: AuthenticatedActor): User {
        requireSelfOrAdmin(userId, actor)
        return userRepository.findById(userId).orElseThrow { NotFoundException("کاربر یافت نشد") }
    }

    @Transactional
    fun updateProfile(userId: Long, cmd: ProfileUpdateCommand, actor: AuthenticatedActor): User {
        requireSelfOrAdmin(userId, actor)
        val user = userRepository.findById(userId).orElseThrow { NotFoundException("کاربر یافت نشد") }

        user.fullName = InputValidator.requireValidHumanName(cmd.fullName)
        user.nationalCode = resolveOptionalField(cmd.nationalCode, user.nationalCode, InputValidator::requireValidNationalCode)
        user.shebaNumber = resolveOptionalField(cmd.shebaNumber, user.shebaNumber, InputValidator::normalizeSheba)
        user.email = resolveOptionalField(cmd.email, user.email, InputValidator::requireValidEmail)
        user.birthDate = resolveOptionalField(cmd.birthDate, user.birthDate, InputValidator::requireValidBirthDate)
        user.avatarUrl = resolveOptionalField(cmd.avatarUrl, user.avatarUrl)
        user.bio = resolveOptionalField(cmd.bio, user.bio)

        return userRepository.save(user)
    }

    @Transactional(noRollbackFor = [UnauthorizedException::class])
    fun changePhone(userId: Long, rawNewPhone: String, rawOtpCode: String, actor: AuthenticatedActor): AuthResult {
        requireSelfOrAdmin(userId, actor)
        val user = userRepository.findById(userId).orElseThrow { NotFoundException("کاربر یافت نشد") }
        val cleanNewPhone = InputValidator.requireValidPhone(rawNewPhone)
        val cleanOtp = rawOtpCode.trim()

        if (cleanNewPhone == user.phone) {
            throw BadRequestException("شماره موبایل جدید نمی‌تواند با شماره فعلی یکسان باشد")
        }
        if (userRepository.existsByPhone(cleanNewPhone)) {
            throw ConflictException("این شماره موبایل متعلق به حساب دیگری است")
        }
        if (!authService.verifyAndConsumeOtp(cleanNewPhone, cleanOtp)) {
            throw UnauthorizedException("کد تایید پیامکی برای شماره موبایل جدید نامعتبر یا منقضی شده است")
        }

        user.phone = cleanNewPhone
        val saved = userRepository.save(user)
        return AuthResult(token = jwtService.generateToken(saved), user = saved)
    }

    @Transactional(readOnly = true)
    fun getSellerBooths(userId: Long, actor: AuthenticatedActor): List<Booth> {
        requireSelfOrAdmin(userId, actor)
        return boothRepository.findBySellerId(userId)
    }

    @Transactional(readOnly = true)
    fun getAddresses(userId: Long, actor: AuthenticatedActor): List<Address> {
        requireSelfOrAdmin(userId, actor)
        return addressRepository.findByUserIdOrderByIsDefaultDescIdDesc(userId)
    }

    @Transactional
    fun addAddress(userId: Long, cmd: CreateAddressCommand, actor: AuthenticatedActor): Address {
        requireSelfOrAdmin(userId, actor)
        val user = userRepository.findById(userId).orElseThrow { NotFoundException("کاربر یافت نشد") }

        val cleanTitle = cmd.title.trim()
        val cleanCity = InputValidator.requireValidHumanName(cmd.city, "نام شهر")
        val cleanFull = cmd.fullAddress.trim()
        val cleanPostal = InputValidator.requireValidPostalCode(cmd.postalCode)

        if (cleanTitle.length < 2 || cleanTitle.all { it.isDigit() }) {
            throw BadRequestException("عنوان آدرس باید معتبر و شامل حروف باشد")
        }
        if (cleanFull.length < 8 || cleanFull.all { it.isDigit() }) {
            throw BadRequestException("لطفاً آدرس کامل پستی را با جزئیات وارد کنید")
        }

        val existing = addressRepository.findByUserIdOrderByIsDefaultDescIdDesc(userId)
        val shouldBeDefault = cmd.isDefault || existing.isEmpty()

        if (shouldBeDefault) {
            existing.forEach { it.isDefault = false }
            addressRepository.saveAll(existing)
        }

        return addressRepository.save(
            Address(
                user = user,
                title = cleanTitle,
                city = cleanCity,
                fullAddress = cleanFull,
                postalCode = cleanPostal,
                isDefault = shouldBeDefault
            )
        )
    }

    @Transactional
    fun setDefaultAddress(userId: Long, addressId: Long, actor: AuthenticatedActor): List<Address> {
        requireSelfOrAdmin(userId, actor)
        addressRepository.findByIdAndUserId(addressId, userId)
            ?: throw NotFoundException("آدرس مورد نظر برای این کاربر یافت نشد")

        val addresses = addressRepository.findByUserIdOrderByIsDefaultDescIdDesc(userId)
        addresses.forEach { it.isDefault = (it.id == addressId) }
        addressRepository.saveAll(addresses)
        return addresses.sortedByDescending { it.isDefault }
    }

    @Transactional
    fun deleteAddress(userId: Long, addressId: Long, actor: AuthenticatedActor) {
        requireSelfOrAdmin(userId, actor)
        val address = addressRepository.findByIdAndUserId(addressId, userId)
            ?: throw NotFoundException("آدرس یافت نشد")

        val wasDefault = address.isDefault
        addressRepository.delete(address)

        if (wasDefault) {
            val remaining = addressRepository.findByUserIdOrderByIsDefaultDescIdDesc(userId)
            remaining.firstOrNull()?.let {
                it.isDefault = true
                addressRepository.save(it)
            }
        }
    }
}