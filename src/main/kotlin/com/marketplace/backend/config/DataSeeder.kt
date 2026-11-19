package com.marketplace.backend.config

import com.marketplace.backend.domain.Banner
import com.marketplace.backend.domain.Category
import com.marketplace.backend.domain.User
import com.marketplace.backend.domain.UserRole
import com.marketplace.backend.repository.*
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

@Component
class DataSeeder(
    private val appProperties: AppProperties,
    private val userRepository: UserRepository,
    private val categoryRepository: CategoryRepository,
    private val bannerRepository: BannerRepository,
    private val passwordEncoder: PasswordEncoder
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(vararg args: String) {
        seedAdminAccount()
        seedCategories()
        seedBanners()
        logger.info("Initial domain seeding verified successfully.")
    }

    private fun seedAdminAccount() {
        val adminPhone = appProperties.auth.adminPhone
        val encodedPass = requireNotNull(passwordEncoder.encode(appProperties.auth.adminBootstrapPassword))
        val existingAdmin = userRepository.findByPhone(adminPhone)

        if (existingAdmin == null) {
            userRepository.save(
                User(
                    phone = adminPhone,
                    fullName = "مدیر کل هرمس",
                    password = encodedPass,
                    role = UserRole.ADMIN,
                    email = "admin@hermes.ir"
                )
            )
        } else {
            var changed = false
            if (existingAdmin.role != UserRole.ADMIN) {
                existingAdmin.role = UserRole.ADMIN
                changed = true
            }
            if (!existingAdmin.password.startsWith("\$2")) {
                existingAdmin.password = encodedPass
                changed = true
            }
            if (changed) userRepository.save(existingAdmin)
        }
    }

    private fun seedCategories() {
        val hierarchy = mapOf(
            "موبایل" to listOf("گوشی‌های اپل", "گوشی‌های سامسونگ و شیائومی", "گوشی بر اساس قیمت", "کیفیت دوربین", "لوازم جانبی موبایل", "قاب گوشی"),
            "لپ‌تاپ و سیستم" to listOf("لپ‌تاپ گیمینگ", "مک‌بوک", "مانیتور و قطعات", "کیبورد و ماوس"),
            "صوتی و تصویری" to listOf("هدفون و هندزفری", "تلویزیون هوشمند", "اسپیکر بلوتوثی"),
            "ساعت و گجت" to listOf("ساعت هوشمند", "مچ‌بند ورزشی", "بند و گلس ساعت"),
            "لوازم خانگی" to listOf("قهوه‌ساز و اسپرسو", "سرخ‌کن بدون روغن", "جاروبرقی رباتیک")
        )

        val existingRoots = categoryRepository.findByParentIsNull().associateBy { it.name }
        hierarchy.forEach { (rootName, subNames) ->
            val root = existingRoots[rootName] ?: categoryRepository.save(Category(name = rootName))
            val existingSubs = categoryRepository.findByParentId(root.id).map { it.name }.toSet()
            subNames.filterNot { it in existingSubs }.forEach { subName ->
                categoryRepository.save(Category(name = subName, parent = root))
            }
        }
    }

    private fun seedBanners() {
        if (bannerRepository.count() == 0L) {
            bannerRepository.saveAll(
                listOf(
                    Banner(title = "جشنواره تخفیف‌های ویژه بازارگاه هرمس", imageUrl = "", targetUrl = "/categories/1"),
                    Banner(title = "خرید تکی (انبار ایران و چین) و عمده (MOQ) از تامین‌کنندگان", imageUrl = "", targetUrl = "/categories/2")
                )
            )
        }
    }
}

@Component
class RealtimeDatabaseFileSync(
    private val appProperties: AppProperties,
    private val userRepository: UserRepository,
    private val boothRepository: BoothRepository,
    private val productRepository: ProductRepository,
    private val orderRepository: OrderRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 3000)
    @Transactional(readOnly = true)
    fun syncSnapshotIfEnabled() {
        if (!appProperties.realtimeSnapshot.enabled) return
        try {
            val sb = StringBuilder()
            sb.appendLine("=== HERMES REAL-TIME DATABASE SNAPSHOT (Updated: ${LocalDateTime.now()}) ===")

            val users = userRepository.findAll()
            sb.appendLine("\n[TABLE: users (${users.size} rows)]")
            users.forEach { u -> sb.appendLine("ID=${u.id} | Phone=${u.phone} | Name=${u.fullName} | Role=${u.role}") }

            val booths = boothRepository.findAll()
            sb.appendLine("\n[TABLE: booths (${booths.size} rows)]")
            booths.forEach { b -> sb.appendLine("ID=${b.id} | Name=${b.name} | Type=${b.type} | Seller=${b.seller.phone}") }

            val products = productRepository.findAll()
            sb.appendLine("\n[TABLE: products (${products.size} rows)]")
            products.forEach { p ->
                sb.appendLine(
                    "ID=${p.id} | Active=${p.isActive} | Title=${p.title} | SaleType=${p.saleType} | MOQ=${p.minOrderQuantity} | " +
                            "Iran=${p.price} (Stock=${p.iranStockQuantity}) | China=${p.chinaPrice} (Stock=${p.chinaStockQuantity})"
                )
            }

            val orders = orderRepository.findAll()
            sb.appendLine("\n[TABLE: orders (${orders.size} rows)]")
            orders.forEach { o -> sb.appendLine("ID=${o.id} | User=${o.user.phone} | Total=${o.totalAmount} | Status=${o.status}") }

            File(appProperties.realtimeSnapshot.filePath).writeText(sb.toString(), StandardCharsets.UTF_8)
        } catch (ex: Exception) {
            logger.warn("Failed to write realtime database snapshot: {}", ex.message)
        }
    }
}