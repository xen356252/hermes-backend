package com.marketplace.backend.service

import com.marketplace.backend.domain.BadRequestException

object InputValidator {
    private val IRAN_PHONE_REGEX = Regex("^09\\d{9}$")
    private val HUMAN_NAME_REGEX = Regex("^[\\u0600-\\u06FFa-zA-Z\\s\\u200C]{2,80}$")
    private val POSTAL_CODE_REGEX = Regex("^\\d{10}$")
    private val NATIONAL_CODE_REGEX = Regex("^\\d{10}$")
    private val SHEBA_REGEX = Regex("^(IR)?\\d{24}$")
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val BIRTH_DATE_REGEX = Regex("^\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}$")

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
            throw BadRequestException("شماره موبایل باید ۱۱ رقم و با 09 شروع شود")
        }
        return phone
    }

    fun requireValidHumanName(rawName: String, fieldLabel: String = "نام و نام خانوادگی"): String {
        val clean = rawName.trim()
        if (!HUMAN_NAME_REGEX.matches(clean)) {
            throw BadRequestException("$fieldLabel فقط باید شامل حروف فارسی یا انگلیسی باشد")
        }
        return clean
    }

    fun requireValidPostalCode(rawPostal: String): String {
        val clean = rawPostal.trim()
        if (!POSTAL_CODE_REGEX.matches(clean)) {
            throw BadRequestException("کد پستی باید دقیقاً ۱۰ رقم باشد")
        }
        return clean
    }

    fun requireValidNationalCode(rawCode: String): String {
        val clean = rawCode.trim()
        if (!NATIONAL_CODE_REGEX.matches(clean) || (0..9).any { d -> clean.all { it == ('0' + d) } }) {
            throw BadRequestException("کد ملی باید ۱۰ رقم معتبر باشد")
        }
        val check = clean[9] - '0'
        val sum = (0..8).sumOf { i -> (clean[i] - '0') * (10 - i) } % 11
        val isValid = (sum < 2 && check == sum) || (sum >= 2 && check + sum == 11)
        if (!isValid) {
            throw BadRequestException("کد ملی وارد شده معتبر نیست")
        }
        return clean
    }

    fun normalizeSheba(rawSheba: String): String {
        val clean = rawSheba.trim().uppercase()
        if (!SHEBA_REGEX.matches(clean)) {
            throw BadRequestException("شماره شبا باید ۲۴ رقم (همراه با IR) باشد")
        }
        return if (clean.startsWith("IR")) clean else "IR$clean"
    }

    fun requireValidEmail(rawEmail: String): String {
        val clean = rawEmail.trim()
        if (!EMAIL_REGEX.matches(clean)) {
            throw BadRequestException("فرمت ایمیل وارد شده صحیح نیست")
        }
        return clean
    }

    fun requireValidBirthDate(rawBirthDate: String): String {
        val clean = rawBirthDate.trim()
        if (!BIRTH_DATE_REGEX.matches(clean)) {
            throw BadRequestException("فرمت تاریخ تولد باید به شکل YYYY/MM/DD باشد")
        }
        return clean
    }
}