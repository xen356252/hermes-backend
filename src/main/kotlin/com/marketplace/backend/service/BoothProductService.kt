package com.marketplace.backend.service

import com.marketplace.backend.domain.*
import com.marketplace.backend.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class CreateBoothCommand(
    val sellerId: Long,
    val name: String,
    val description: String?,
    val type: BoothType,
    val logoUrl: String?,
    val bannerUrl: String?
)

data class UpsertProductCommand(
    val categoryId: Long,
    val title: String,
    val description: String?,
    val price: BigDecimal,
    val discountPrice: BigDecimal?,
    val chinaPrice: BigDecimal?,
    val saleType: SaleType,
    val minOrderQuantity: Int,
    val warehouseOrigin: WarehouseOrigin,
    val iranStockQuantity: Int?,
    val chinaStockQuantity: Int?,
    val stockQuantity: Int,
    val priceTiers: List<Pair<Int, BigDecimal>>,
    val imageUrl: String?,
    val isHotDeal: Boolean
)

@Service
class BoothProductService(
    private val boothRepository: BoothRepository,
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
    private val categoryRepository: CategoryRepository,
    private val cartItemRepository: CartItemRepository,
    private val wishlistItemRepository: WishlistItemRepository
) {
    @Transactional(readOnly = true)
    fun getAllBooths(type: BoothType?): List<Booth> =
        if (type != null) boothRepository.findByType(type) else boothRepository.findAll()

    @Transactional(readOnly = true)
    fun getBooth(boothId: Long): Booth =
        boothRepository.findWithSellerById(boothId) ?: throw NotFoundException("غرفه یافت نشد")

    @Transactional(readOnly = true)
    fun getBoothProducts(boothId: Long): List<Product> =
        productRepository.findByBoothIdAndIsActiveTrue(boothId).preloadTiers()

    @Transactional(readOnly = true)
    fun getProductDetails(productId: Long): Product =
        productRepository.findByIdAndIsActiveTrue(productId)?.preloadTiers()
            ?: throw NotFoundException("محصول یافت نشد")

    @Transactional
    fun createBooth(cmd: CreateBoothCommand, actor: AuthenticatedActor): Booth {
        if (actor.userId != cmd.sellerId && !actor.isAdmin()) {
            throw ForbiddenException("شما مجاز به ایجاد غرفه برای کاربر دیگری نیستید")
        }
        val cleanName = cmd.name.trim()
        if (cleanName.length < 3) {
            throw BadRequestException("نام غرفه باید حداقل ۳ کاراکتر باشد")
        }
        val seller = userRepository.findById(cmd.sellerId).orElseThrow { NotFoundException("فروشنده یافت نشد") }
        if (seller.role == UserRole.BUYER) {
            seller.role = UserRole.SELLER
            userRepository.save(seller)
        }
        return boothRepository.save(
            Booth(
                seller = seller,
                name = cleanName,
                description = cmd.description?.trim(),
                type = cmd.type,
                logoUrl = cmd.logoUrl?.trim(),
                bannerUrl = cmd.bannerUrl?.trim()
            )
        )
    }

    @Transactional
    fun addProductToBooth(boothId: Long, cmd: UpsertProductCommand, actor: AuthenticatedActor): Product {
        validateProductInput(cmd)

        val booth = if (actor.isAdmin()) {
            boothRepository.findWithSellerById(boothId) ?: throw NotFoundException("غرفه یافت نشد")
        } else {
            boothRepository.findByIdAndSellerId(boothId, actor.userId)
                ?: throw ForbiddenException("غرفه یافت نشد یا متعلق به شما نیست")
        }

        val category = categoryRepository.findById(cmd.categoryId)
            .orElseThrow { NotFoundException("دسته‌بندی انتخاب‌شده یافت نشد") }

        if ((cmd.saleType == SaleType.MOQ || cmd.chinaPrice != null || (cmd.chinaStockQuantity ?: 0) > 0) &&
            booth.type == BoothType.RETAIL
        ) {
            booth.type = BoothType.SUPPLIER
            boothRepository.save(booth)
        }

        val product = Product(
            booth = booth,
            category = category,
            title = cmd.title.trim(),
            price = cmd.price
        )
        applyProductAttributes(product, cmd)
        return productRepository.save(product).preloadTiers()
    }

    @Transactional
    fun updateProduct(productId: Long, cmd: UpsertProductCommand, actor: AuthenticatedActor): Product {
        validateProductInput(cmd)

        val product = productRepository.findByIdAndIsActiveTrue(productId)
            ?: throw NotFoundException("محصول یافت نشد")

        if (product.booth.seller.id != actor.userId && !actor.isAdmin()) {
            throw ForbiddenException("شما مجاز به ویرایش محصول این غرفه نیستید")
        }

        val category = categoryRepository.findById(cmd.categoryId)
            .orElseThrow { NotFoundException("دسته‌بندی انتخاب‌شده یافت نشد") }

        product.category = category
        applyProductAttributes(product, cmd)
        return productRepository.save(product).preloadTiers()
    }

    @Transactional
    fun softDeleteProduct(productId: Long, actor: AuthenticatedActor) {
        val product = productRepository.findByIdAndIsActiveTrue(productId)
            ?: throw NotFoundException("محصول یافت نشد")

        if (product.booth.seller.id != actor.userId && !actor.isAdmin()) {
            throw ForbiddenException("شما مجاز به حذف این محصول نیستید")
        }

        cartItemRepository.deleteByProductId(productId)
        wishlistItemRepository.deleteByProductId(productId)
        product.isActive = false
        productRepository.save(product)
    }

    private fun validateProductInput(cmd: UpsertProductCommand) {
        if (cmd.title.trim().length < 2) {
            throw BadRequestException("نام محصول باید حداقل ۲ کاراکتر باشد")
        }
        if (cmd.price <= BigDecimal.ZERO) {
            throw BadRequestException("قیمت محصول باید بزرگتر از صفر باشد")
        }
        if (cmd.discountPrice != null && (cmd.discountPrice <= BigDecimal.ZERO || cmd.discountPrice >= cmd.price)) {
            throw BadRequestException("قیمت تخفیف باید مثبت و کمتر از قیمت اصلی باشد")
        }
        if (cmd.chinaPrice != null && cmd.chinaPrice <= BigDecimal.ZERO) {
            throw BadRequestException("قیمت انبار چین باید بزرگتر از صفر باشد")
        }
        if (cmd.iranStockQuantity != null && cmd.iranStockQuantity < 0) {
            throw BadRequestException("موجودی انبار ایران نمی‌تواند منفی باشد")
        }
        if (cmd.chinaStockQuantity != null && cmd.chinaStockQuantity < 0) {
            throw BadRequestException("موجودی انبار چین نمی‌تواند منفی باشد")
        }
    }

    private fun applyProductAttributes(product: Product, cmd: UpsertProductCommand) {
        val effectiveMinQty = if (cmd.saleType == SaleType.MOQ) cmd.minOrderQuantity.coerceAtLeast(2) else 1

        val resolvedIranStock: Int
        val resolvedChinaStock: Int

        if (cmd.iranStockQuantity != null || cmd.chinaStockQuantity != null) {
            resolvedIranStock = cmd.iranStockQuantity ?: 0
            resolvedChinaStock = cmd.chinaStockQuantity ?: 0
        } else {
            val baseStock = cmd.stockQuantity.coerceAtLeast(0)
            if (cmd.warehouseOrigin == WarehouseOrigin.CHINA) {
                resolvedIranStock = 0
                resolvedChinaStock = baseStock
            } else {
                resolvedIranStock = baseStock
                resolvedChinaStock = if (cmd.chinaPrice != null) baseStock else 0
            }
        }

        if (cmd.saleType == SaleType.MOQ && (resolvedIranStock + resolvedChinaStock) < effectiveMinQty) {
            throw BadRequestException("مجموع موجودی انبارها نمی‌تواند کمتر از حداقل تعداد سفارش عمده ($effectiveMinQty) باشد")
        }

        val primaryOrigin = when {
            cmd.warehouseOrigin == WarehouseOrigin.CHINA && resolvedChinaStock > 0 -> WarehouseOrigin.CHINA
            resolvedIranStock > 0 -> WarehouseOrigin.IRAN
            resolvedChinaStock > 0 -> WarehouseOrigin.CHINA
            else -> cmd.warehouseOrigin
        }

        product.title = cmd.title.trim()
        product.description = cmd.description?.trim()
        product.price = cmd.price
        product.discountPrice = cmd.discountPrice
        product.chinaPrice = cmd.chinaPrice
        product.saleType = cmd.saleType
        product.minOrderQuantity = effectiveMinQty
        product.warehouseOrigin = primaryOrigin
        product.deliveryTimeRange = Product.resolveDeliveryLabel(primaryOrigin)
        product.iranStockQuantity = resolvedIranStock
        product.chinaStockQuantity = resolvedChinaStock
        product.syncTotalStock()

        if (!cmd.imageUrl.isNullOrBlank()) {
            product.imageUrl = cmd.imageUrl.trim()
        }
        product.isHotDeal = cmd.isHotDeal
        product.replacePriceTiers(cmd.priceTiers)
    }
}