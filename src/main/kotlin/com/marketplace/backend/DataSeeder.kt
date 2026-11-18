package com.marketplace.backend.config

import com.marketplace.backend.model.*
import com.marketplace.backend.repository.*
import org.springframework.boot.CommandLineRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class DataSeeder(
    private val jdbcTemplate: JdbcTemplate,
    private val userRepository: UserRepository,
    private val categoryRepository: CategoryRepository,
    private val bannerRepository: BannerRepository,
    private val passwordEncoder: PasswordEncoder
) : CommandLineRunner {

    @Transactional
    override fun run(vararg args: String) {
        val defaultAdminHash = requireNotNull(passwordEncoder.encode("1234"))

        // Idempotent schema upgrades for PostgreSQL columns
        listOf(
            "ALTER TABLE products ALTER COLUMN image_url TYPE TEXT",
            "ALTER TABLE products ALTER COLUMN description TYPE TEXT",
            "ALTER TABLE products ALTER COLUMN title TYPE VARCHAR(300)",
            "ALTER TABLE products ADD COLUMN IF NOT EXISTS sale_type VARCHAR(20) DEFAULT 'SINGLE' NOT NULL",
            "ALTER TABLE products ADD COLUMN IF NOT EXISTS min_order_quantity INTEGER DEFAULT 1 NOT NULL",
            "ALTER TABLE products ADD COLUMN IF NOT EXISTS warehouse_origin VARCHAR(20) DEFAULT 'IRAN' NOT NULL",
            "ALTER TABLE products ADD COLUMN IF NOT EXISTS china_price NUMERIC(18,0)",
            "ALTER TABLE products ADD COLUMN IF NOT EXISTS delivery_time_range VARCHAR(120) DEFAULT 'آماده ارسال از انبار ایران (۱ تا ۳ روز کاری)' NOT NULL",
            "ALTER TABLE products ADD COLUMN IF NOT EXISTS iran_stock_quantity INTEGER DEFAULT 10 NOT NULL",
            "ALTER TABLE products ADD COLUMN IF NOT EXISTS china_stock_quantity INTEGER DEFAULT 0 NOT NULL",
            "ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS selected_warehouse VARCHAR(20) DEFAULT 'IRAN' NOT NULL",
            "ALTER TABLE order_items ADD COLUMN IF NOT EXISTS selected_warehouse VARCHAR(20) DEFAULT 'IRAN' NOT NULL",
            "ALTER TABLE banners ALTER COLUMN image_url TYPE TEXT",
            "ALTER TABLE banners ALTER COLUMN title TYPE TEXT",
            "ALTER TABLE banners ALTER COLUMN target_url TYPE TEXT",
            "ALTER TABLE booths ALTER COLUMN logo_url TYPE TEXT",
            "ALTER TABLE booths ALTER COLUMN banner_url TYPE TEXT",
            "ALTER TABLE booths ALTER COLUMN description TYPE TEXT",
            "ALTER TABLE categories ALTER COLUMN icon_url TYPE TEXT",
            "ALTER TABLE users ALTER COLUMN avatar_url TYPE TEXT",
            "ALTER TABLE users ALTER COLUMN bio TYPE TEXT",
            "UPDATE users SET password = '$defaultAdminHash' WHERE password IS NULL OR password = ''"
        ).forEach { sql ->
            try {
                jdbcTemplate.execute(sql)
            } catch (_: Exception) {}
        }

        // Ensure Admin account exists and its password is always BCrypt-hashed
        val existingAdmin = userRepository.findByPhone("09120000000")
        if (existingAdmin == null) {
            userRepository.save(
                User(
                    phone = "09120000000",
                    fullName = "مدیر کل هرمس",
                    password = defaultAdminHash,
                    role = UserRole.ADMIN,
                    email = "admin@hermes.ir"
                )
            )
        } else {
            existingAdmin.role = UserRole.ADMIN
            existingAdmin.fullName = "مدیر کل هرمس"
            if (!existingAdmin.password.startsWith("\$2")) {
                existingAdmin.password = defaultAdminHash
            }
            userRepository.save(existingAdmin)
        }

        val categoryHierarchy = mapOf(
            "موبایل" to listOf(
                "گوشی‌های اپل",
                "گوشی‌های سامسونگ و شیائومی",
                "گوشی بر اساس قیمت",
                "کیفیت دوربین",
                "لوازم جانبی موبایل",
                "قاب گوشی"
            ),
            "لپ‌تاپ و سیستم" to listOf(
                "لپ‌تاپ گیمینگ",
                "مک‌بوک",
                "مانیتور و قطعات",
                "کیبورد و ماوس"
            ),
            "صوتی و تصویری" to listOf(
                "هدفون و هندزفری",
                "تلویزیون هوشمند",
                "اسپیکر بلوتوثی"
            ),
            "ساعت و گجت" to listOf(
                "ساعت هوشمند",
                "مچ‌بند ورزشی",
                "بند و گلس ساعت"
            ),
            "لوازم خانگی" to listOf(
                "قهوه‌ساز و اسپرسو",
                "سرخ‌کن بدون روغن",
                "جاروبرقی رباتیک"
            )
        )

        val existingRoots = categoryRepository.findByParentIsNull().associateBy { it.name }
        categoryHierarchy.forEach { (rootName, subNames) ->
            val root = existingRoots[rootName] ?: categoryRepository.save(Category(name = rootName))
            val existingSubs = categoryRepository.findByParentId(root.id).map { it.name }.toSet()
            subNames.filterNot { it in existingSubs }.forEach { subName ->
                categoryRepository.save(Category(name = subName, parent = root))
            }
        }

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