package com.marketplace.backend

import com.marketplace.backend.domain.*
import com.marketplace.backend.service.ImageFormatDetector
import com.marketplace.backend.service.InputValidator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HermesDomainAndSecurityTests {

    private fun sampleProduct(
        saleType: SaleType = SaleType.SINGLE,
        iranStock: Int = 5,
        chinaStock: Int = 50,
        moq: Int = 1
    ): Product {
        val seller = User(id = 1, phone = "09121111111", fullName = "فروشنده تست", password = "hash", role = UserRole.SELLER)
        val booth = Booth(id = 1, seller = seller, name = "غرفه تست", type = BoothType.SUPPLIER)
        val category = Category(id = 1, name = "موبایل")
        return Product(
            id = 10,
            booth = booth,
            category = category,
            title = "تیونر گیتار دیجیتال",
            price = BigDecimal("1000000"),
            discountPrice = BigDecimal("900000"),
            chinaPrice = BigDecimal("650000"),
            saleType = saleType,
            minOrderQuantity = moq,
            iranStockQuantity = iranStock,
            chinaStockQuantity = chinaStock
        ).apply { syncTotalStock() }
    }

    @Test
    fun `MOQ tiered pricing resolves highest matching bracket`() {
        val product = sampleProduct(saleType = SaleType.MOQ, iranStock = 0, chinaStock = 2000, moq = 10)
        product.replacePriceTiers(
            listOf(
                10 to BigDecimal("107000"),
                100 to BigDecimal("98000"),
                500 to BigDecimal("89000"),
                1000 to BigDecimal("80000")
            )
        )

        assertEquals(BigDecimal("107000"), product.resolveUnitPrice(25, WarehouseOrigin.CHINA))
        assertEquals(BigDecimal("98000"), product.resolveUnitPrice(150, WarehouseOrigin.CHINA))
        assertEquals(BigDecimal("89000"), product.resolveUnitPrice(500, WarehouseOrigin.CHINA))
        assertEquals(BigDecimal("80000"), product.resolveUnitPrice(1200, WarehouseOrigin.CHINA))
    }

    @Test
    fun `deductStock and restoreStock maintain exact per-warehouse inventory`() {
        val product = sampleProduct(iranStock = 0, chinaStock = 50)

        assertThrows<BadRequestException> {
            product.deductStock(WarehouseOrigin.IRAN, 1)
        }
        assertEquals(50, product.chinaStockQuantity)

        product.deductStock(WarehouseOrigin.CHINA, 10)
        assertEquals(40, product.chinaStockQuantity)
        assertEquals(40, product.stockQuantity)

        // Simulates order cancellation restock
        product.restoreStock(WarehouseOrigin.CHINA, 10)
        assertEquals(50, product.chinaStockQuantity)
        assertEquals(50, product.stockQuantity)
        assertEquals(0, product.iranStockQuantity)
    }

    @Test
    fun `InputValidator normalizes Persian digits and validates Iranian phone and national code`() {
        assertEquals("09123456789", InputValidator.requireValidPhone("۰۹۱۲۳۴۵۶۷۸۹"))
        assertEquals("09123456789", InputValidator.requireValidPhone("+98 912 345 6789"))
        assertEquals("0084575948", InputValidator.requireValidNationalCode("0084575948"))

        assertThrows<BadRequestException> { InputValidator.requireValidNationalCode("1111111111") }
        assertThrows<BadRequestException> { InputValidator.requireValidEmail("not-an-email") }
    }

    @Test
    fun `ImageFormatDetector validates real magic bytes and rejects disguised files`() {
        val fakeScript = "<script>alert(1)</script>".toByteArray()
        assertNull(ImageFormatDetector.detect(fakeScript))

        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(16)
        assertEquals("image/jpeg", ImageFormatDetector.detect(jpegHeader)?.mimeType)

        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        assertEquals("image/png", ImageFormatDetector.detect(pngHeader)?.mimeType)
    }
}