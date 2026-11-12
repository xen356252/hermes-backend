package com.marketplace.backend.controller

import com.marketplace.backend.model.*
import com.marketplace.backend.repository.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

data class BannerDto(val id: Long, val title: String, val imageUrl: String, val targetUrl: String?)
data class CreateBannerRequest(val title: String? = null, val imageUrl: String, val targetUrl: String? = null)

data class CategoryDto(
    val id: Long,
    val name: String,
    val iconUrl: String?,
    val parentId: Long?,
    val subCategories: List<CategoryDto> = emptyList()
)

data class BoothDto(
    val id: Long,
    val sellerId: Long,
    val sellerName: String,
    val name: String,
    val description: String?,
    val type: BoothType,
    val logoUrl: String?,
    val bannerUrl: String?,
    val rating: Double
)

data class PriceTierDto(
    val minQuantity: Int,
    val unitPrice: BigDecimal
)

data class ProductDto(
    val id: Long,
    val boothId: Long,
    val boothName: String,
    val boothType: BoothType = BoothType.RETAIL,
    val categoryId: Long,
    val categoryName: String,
    val title: String,
    val description: String?,
    val price: BigDecimal,
    val discountPrice: BigDecimal?,
    val chinaPrice: BigDecimal? = null,
    val saleType: SaleType = SaleType.SINGLE,
    val minOrderQuantity: Int = 1,
    val warehouseOrigin: WarehouseOrigin = WarehouseOrigin.IRAN,
    val deliveryTimeRange: String = Product.resolveDeliveryLabel(WarehouseOrigin.IRAN),
    val priceTiers: List<PriceTierDto> = emptyList(),
    val stockQuantity: Int,
    val imageUrl: String?,
    val isHotDeal: Boolean,
    val rating: Double
)

data class VitrineResponse(
    val mode: String,
    val welcomeMessage: String,
    val banners: List<BannerDto>,
    val rootCategories: List<CategoryDto>,
    val hotDeals: List<ProductDto>,
    val recommendedProducts: List<ProductDto>
)

data class CreateBoothRequest(
    val sellerId: Long,
    val name: String,
    val description: String?,
    val type: BoothType = BoothType.RETAIL,
    val logoUrl: String? = null,
    val bannerUrl: String? = null
)

data class CreateProductRequest(
    val categoryId: Long,
    val title: String,
    val description: String?,
    val price: BigDecimal,
    val discountPrice: BigDecimal? = null,
    val chinaPrice: BigDecimal? = null,
    val saleType: SaleType = SaleType.SINGLE,
    val minOrderQuantity: Int = 1,
    val warehouseOrigin: WarehouseOrigin = WarehouseOrigin.IRAN,
    val priceTiers: List<PriceTierDto> = emptyList(),
    val stockQuantity: Int = 10,
    val imageUrl: String? = null,
    val isHotDeal: Boolean = false
)

data class CartItemDto(
    val id: Long,
    val product: ProductDto,
    val quantity: Int,
    val appliedUnitPrice: BigDecimal,
    val lineTotal: BigDecimal
)

data class CartSummaryDto(
    val items: List<CartItemDto>,
    val totalPrice: BigDecimal
)

data class AddToCartRequest(val productId: Long, val quantity: Int = 1)
data class CheckoutRequest(val addressId: Long)

data class OrderItemDto(
    val productId: Long,
    val productTitle: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val saleType: SaleType = SaleType.SINGLE,
    val warehouseOrigin: WarehouseOrigin = WarehouseOrigin.IRAN,
    val deliveryTimeRange: String = ""
)

data class OrderDto(
    val id: Long,
    val status: OrderStatus,
    val totalAmount: BigDecimal,
    val shippingAddress: String,
    val createdAt: String,
    val items: List<OrderItemDto>
)

/**
 * Builds the 2-level category tree in O(N) memory from a single query instead of N+1 queries.
 */
internal fun CategoryRepository.buildCategoryTree(): List<CategoryDto> {
    val all = findAll()
    val childrenByParentId = all.filter { it.parent != null }.groupBy { it.parent!!.id }
    return all.filter { it.parent == null }.map { root ->
        CategoryDto(
            id = root.id,
            name = root.name,
            iconUrl = root.iconUrl,
            parentId = null,
            subCategories = childrenByParentId[root.id].orEmpty().map { it.toSimpleDto() }
        )
    }
}

@RestController
@RequestMapping("/api/v1/vitrine")
@Tag(name = "3. Home Vitrine", description = "Guest Vitrine and Personalized User Feeds")
class VitrineController(
    private val bannerRepository: BannerRepository,
    private val categoryRepository: CategoryRepository,
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
    private val wishlistItemRepository: WishlistItemRepository
) {

    @GetMapping("/guest")
    @Operation(summary = "Get Guest Home Vitrine")
    fun getGuestVitrine(): VitrineResponse {
        val banners = bannerRepository.findByIsActiveTrue().map { it.toDto() }
        val rootCats = categoryRepository.buildCategoryTree()
        val hotDeals = productRepository.findByIsHotDealTrue().map { it.toDto() }
        val latest = productRepository.findAll().takeLast(12).map { it.toDto() }

        return VitrineResponse(
            mode = "GUEST",
            welcomeMessage = "هرمس",
            banners = banners,
            rootCategories = rootCats,
            hotDeals = hotDeals,
            recommendedProducts = latest
        )
    }

    @GetMapping("/personalized/{userId}")
    @Operation(summary = "Get Personalized Home Vitrine tailored to logged-in user")
    fun getPersonalizedVitrine(@PathVariable userId: Long): VitrineResponse {
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "کاربر یافت نشد")
        }
        val banners = bannerRepository.findByIsActiveTrue().map { it.toDto() }
        val rootCats = categoryRepository.buildCategoryTree()
        val hotDeals = productRepository.findByIsHotDealTrue().map { it.toDto() }

        val wishedCategories = wishlistItemRepository.findByUserId(userId)
            .map { it.product.category.id }
            .toSet()

        val allProducts = productRepository.findAll()
        val tailored = if (wishedCategories.isNotEmpty()) {
            allProducts.sortedByDescending { wishedCategories.contains(it.category.id) }
        } else {
            allProducts
        }.map { it.toDto() }

        return VitrineResponse(
            mode = "PERSONALIZED",
            welcomeMessage = "سلام ${user.fullName} عزیز",
            banners = banners,
            rootCategories = rootCats,
            hotDeals = hotDeals,
            recommendedProducts = tailored
        )
    }
}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "4. Categories, Banners & Search", description = "Categories, Banners, and Search (with MOQ & Warehouse Filters)")
class CategoryAndSearchController(
    private val categoryRepository: CategoryRepository,
    private val productRepository: ProductRepository,
    private val bannerRepository: BannerRepository
) {

    @GetMapping("/banners")
    fun getBanners(): List<BannerDto> = bannerRepository.findByIsActiveTrue().map { it.toDto() }

    @PostMapping("/banners")
    @Operation(summary = "Add a new Homepage Ad Banner")
    fun createBanner(@RequestBody req: CreateBannerRequest): ResponseEntity<BannerDto> {
        val cleanTitle = req.title?.trim().orEmpty()
        val cleanImage = req.imageUrl.trim()
        if (cleanTitle.isBlank() && cleanImage.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "تصویر یا متن بنر الزامی است")
        }
        val saved = bannerRepository.save(
            Banner(
                title = cleanTitle,
                imageUrl = cleanImage,
                targetUrl = req.targetUrl,
                isActive = true
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toDto())
    }

    @DeleteMapping("/banners/{bannerId}")
    @Operation(summary = "Delete an Ad Banner")
    fun deleteBanner(@PathVariable bannerId: Long): ResponseEntity<Void> {
        if (bannerRepository.existsById(bannerId)) {
            bannerRepository.deleteById(bannerId)
        }
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/categories")
    @Operation(summary = "Get full hierarchical category tree")
    fun getCategories(): List<CategoryDto> = categoryRepository.buildCategoryTree()

    @GetMapping("/categories/{categoryId}/products")
    @Operation(summary = "Get products inside a category or subcategory")
    fun getProductsByCategory(@PathVariable categoryId: Long): List<ProductDto> {
        val subIds = categoryRepository.findByParentId(categoryId).map { it.id }.toSet()
        val targetIds = subIds + categoryId
        return productRepository.findAll()
            .filter { targetIds.contains(it.category.id) || it.category.parent?.id == categoryId }
            .map { it.toDto() }
    }

    @GetMapping("/products/search")
    @Operation(summary = "Search and filter products by keyword, category, price, saleType (SINGLE/MOQ), or warehouseOrigin (IRAN/CHINA)")
    fun searchProducts(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false) minPrice: BigDecimal?,
        @RequestParam(required = false) maxPrice: BigDecimal?,
        @RequestParam(required = false) onlyHotDeals: Boolean?,
        @RequestParam(required = false) saleType: SaleType?,
        @RequestParam(required = false) warehouseOrigin: WarehouseOrigin?
    ): List<ProductDto> {
        return productRepository.findAll()
            .filter { p ->
                val matchesQuery = q.isNullOrBlank() ||
                        p.title.contains(q, ignoreCase = true) ||
                        p.category.name.contains(q, ignoreCase = true) ||
                        (p.description?.contains(q, ignoreCase = true) == true)
                val matchesCat = categoryId == null || p.category.id == categoryId || p.category.parent?.id == categoryId
                val effectivePrice = p.effectiveBasePrice()
                val matchesMin = minPrice == null || effectivePrice >= minPrice
                val matchesMax = maxPrice == null || effectivePrice <= maxPrice
                val matchesHot = onlyHotDeals != true || p.isHotDeal
                val matchesSaleType = saleType == null || p.saleType == saleType
                val matchesOrigin = warehouseOrigin == null || p.warehouseOrigin == warehouseOrigin

                matchesQuery && matchesCat && matchesMin && matchesMax && matchesHot && matchesSaleType && matchesOrigin
            }
            .map { it.toDto() }
    }
}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "5. Retail & Supplier Booths & Products", description = "Retail & MOQ Supplier Booths, Single (Iran/China) & MOQ Products CRUD")
class BoothAndProductController(
    private val boothRepository: BoothRepository,
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
    private val categoryRepository: CategoryRepository,
    private val cartItemRepository: CartItemRepository,
    private val wishlistItemRepository: WishlistItemRepository,
    private val orderItemRepository: OrderItemRepository
) {

    @GetMapping("/booths")
    fun getAllBooths(@RequestParam(required = false) type: BoothType?): List<BoothDto> {
        val list = if (type != null) boothRepository.findByType(type) else boothRepository.findAll()
        return list.map { it.toDto() }
    }

    @GetMapping("/booths/{boothId}")
    fun getBooth(@PathVariable boothId: Long): BoothDto {
        val booth = boothRepository.findById(boothId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "غرفه یافت نشد")
        }
        return booth.toDto()
    }

    @GetMapping("/booths/{boothId}/products")
    fun getBoothProducts(@PathVariable boothId: Long): List<ProductDto> =
        productRepository.findByBoothId(boothId).map { it.toDto() }

    @PostMapping("/booths")
    @Transactional
    @Operation(summary = "Create a RETAIL or SUPPLIER (MOQ) Booth")
    fun createBooth(@RequestBody req: CreateBoothRequest): ResponseEntity<BoothDto> {
        if (req.name.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "نام غرفه الزامی است")
        }
        val seller = userRepository.findById(req.sellerId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "فروشنده یافت نشد")
        }
        if (seller.role == UserRole.BUYER) {
            seller.role = UserRole.SELLER
            userRepository.save(seller)
        }
        val saved = boothRepository.save(
            Booth(
                seller = seller,
                name = req.name.trim(),
                description = req.description?.trim(),
                type = req.type,
                logoUrl = req.logoUrl,
                bannerUrl = req.bannerUrl
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toDto())
    }

    private fun applyProductAttributes(product: Product, req: CreateProductRequest) {
        val effectiveMinQty = if (req.saleType == SaleType.MOQ) req.minOrderQuantity.coerceAtLeast(2) else 1
        val validDiscount = req.discountPrice?.takeIf { it > BigDecimal.ZERO && it < req.price }
        val validChinaPrice = if (req.warehouseOrigin == WarehouseOrigin.CHINA) {
            req.chinaPrice?.takeIf { it > BigDecimal.ZERO && it < req.price } ?: validDiscount
        } else null

        product.title = req.title.trim()
        product.description = req.description?.trim()
        product.price = req.price
        product.discountPrice = validDiscount
        product.chinaPrice = validChinaPrice
        product.saleType = req.saleType
        product.minOrderQuantity = effectiveMinQty
        product.warehouseOrigin = req.warehouseOrigin
        product.deliveryTimeRange = Product.resolveDeliveryLabel(req.warehouseOrigin)
        product.stockQuantity = req.stockQuantity.coerceAtLeast(effectiveMinQty)
        if (!req.imageUrl.isNullOrBlank()) {
            product.imageUrl = req.imageUrl.trim()
        }
        product.isHotDeal = req.isHotDeal
        product.replacePriceTiers(req.priceTiers.map { it.minQuantity to it.unitPrice })
    }

    @PostMapping("/booths/{boothId}/products")
    @Transactional
    @Operation(summary = "Add a SINGLE (Iran Ready / China 50-90 Days) or MOQ Wholesale Product to a Booth")
    fun addProductToBooth(
        @PathVariable boothId: Long,
        @RequestBody req: CreateProductRequest
    ): ResponseEntity<ProductDto> {
        if (req.title.isBlank() || req.price <= BigDecimal.ZERO) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "نام محصول و قیمت معتبر الزامی است")
        }
        val booth = boothRepository.findById(boothId).orElseGet {
            boothRepository.findAll().firstOrNull()
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "غرفه یافت نشد")
        }
        val category = categoryRepository.findById(req.categoryId).orElseGet {
            categoryRepository.findAll().firstOrNull()
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "دسته‌بندی یافت نشد")
        }

        if (req.saleType == SaleType.MOQ && booth.type == BoothType.RETAIL) {
            booth.type = BoothType.SUPPLIER
            boothRepository.save(booth)
        }

        val product = Product(
            booth = booth,
            category = category,
            title = req.title.trim(),
            price = req.price
        )
        applyProductAttributes(product, req)

        val saved = productRepository.save(product)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toDto())
    }

    @PutMapping("/products/{productId}")
    @Transactional
    @Operation(summary = "Update an existing SINGLE or MOQ product")
    fun updateProduct(
        @PathVariable productId: Long,
        @RequestBody req: CreateProductRequest
    ): ProductDto {
        if (req.title.isBlank() || req.price <= BigDecimal.ZERO) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "نام محصول و قیمت معتبر الزامی است")
        }
        val product = productRepository.findById(productId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "محصول یافت نشد")
        }
        categoryRepository.findById(req.categoryId).ifPresent { product.category = it }
        applyProductAttributes(product, req)

        return productRepository.save(product).toDto()
    }

    @GetMapping("/products/{productId}")
    fun getProductDetails(@PathVariable productId: Long): ProductDto {
        val product = productRepository.findById(productId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "محصول یافت نشد")
        }
        return product.toDto()
    }

    @DeleteMapping("/products/{productId}")
    @Transactional
    @Operation(summary = "Delete a product safely across all relations (Admin / Seller)")
    fun deleteProduct(@PathVariable productId: Long): ResponseEntity<Void> {
        if (productRepository.existsById(productId)) {
            cartItemRepository.deleteByProductId(productId)
            wishlistItemRepository.deleteByProductId(productId)
            orderItemRepository.deleteByProductId(productId)
            productRepository.deleteById(productId)
        }
        return ResponseEntity.noContent().build()
    }
}

@RestController
@RequestMapping("/api/v1/users/{userId}")
@Tag(name = "6. Cart, Wishlist & Checkout", description = "Shopping cart (with MOQ & China/Iran pricing), wishlist, and order checkout")
class CartAndOrderController(
    private val userRepository: UserRepository,
    private val productRepository: ProductRepository,
    private val addressRepository: AddressRepository,
    private val cartItemRepository: CartItemRepository,
    private val wishlistItemRepository: WishlistItemRepository,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository
) {

    @GetMapping("/cart")
    fun getCart(@PathVariable userId: Long): CartSummaryDto {
        val items = cartItemRepository.findByUserId(userId).map { item ->
            val unitPrice = item.product.resolveUnitPrice(item.quantity)
            CartItemDto(
                id = item.id,
                product = item.product.toDto(),
                quantity = item.quantity,
                appliedUnitPrice = unitPrice,
                lineTotal = unitPrice.multiply(BigDecimal(item.quantity))
            )
        }
        val total = items.fold(BigDecimal.ZERO) { acc, item -> acc.add(item.lineTotal) }
        return CartSummaryDto(items = items, totalPrice = total)
    }

    @PostMapping("/cart")
    @Transactional
    fun addToCart(
        @PathVariable userId: Long,
        @RequestBody req: AddToCartRequest
    ): CartSummaryDto {
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "کاربر یافت نشد")
        }
        val product = productRepository.findById(req.productId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "محصول یافت نشد")
        }

        val minRequired = if (product.saleType == SaleType.MOQ) product.minOrderQuantity.coerceAtLeast(2) else 1
        val existing = cartItemRepository.findByUserIdAndProductId(userId, req.productId)

        if (existing != null) {
            val newQty = existing.quantity + req.quantity
            if (newQty <= 0 || (req.quantity < 0 && newQty < minRequired)) {
                cartItemRepository.delete(existing)
            } else {
                val enforcedQty = newQty.coerceAtLeast(minRequired)
                if (enforcedQty > product.stockQuantity) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "تعداد درخواستی بیشتر از موجودی انبار (${product.stockQuantity} عدد) است"
                    )
                }
                existing.quantity = enforcedQty
                cartItemRepository.save(existing)
            }
        } else if (req.quantity > 0) {
            val initialQty = req.quantity.coerceAtLeast(minRequired)
            if (product.stockQuantity < initialQty) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "موجودی انبار برای حداقل سفارش (${minRequired} عدد) کافی نیست"
                )
            }
            cartItemRepository.save(CartItem(user = user, product = product, quantity = initialQty))
        }
        return getCart(userId)
    }

    @DeleteMapping("/cart/{productId}")
    @Transactional
    fun removeFromCart(@PathVariable userId: Long, @PathVariable productId: Long): CartSummaryDto {
        cartItemRepository.findByUserIdAndProductId(userId, productId)?.let {
            cartItemRepository.delete(it)
        }
        return getCart(userId)
    }

    @GetMapping("/wishlist")
    fun getWishlist(@PathVariable userId: Long): List<ProductDto> =
        wishlistItemRepository.findByUserId(userId).map { it.product.toDto() }

    @PostMapping("/wishlist/{productId}")
    @Transactional
    fun addToWishlist(@PathVariable userId: Long, @PathVariable productId: Long): List<ProductDto> {
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "کاربر یافت نشد")
        }
        val product = productRepository.findById(productId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "محصول یافت نشد")
        }
        if (wishlistItemRepository.findByUserIdAndProductId(userId, productId) == null) {
            wishlistItemRepository.save(WishlistItem(user = user, product = product))
        }
        return getWishlist(userId)
    }

    @DeleteMapping("/wishlist/{productId}")
    @Transactional
    fun removeFromWishlist(@PathVariable userId: Long, @PathVariable productId: Long): List<ProductDto> {
        wishlistItemRepository.findByUserIdAndProductId(userId, productId)?.let {
            wishlistItemRepository.delete(it)
        }
        return getWishlist(userId)
    }

    @PostMapping("/orders/checkout")
    @Transactional
    fun checkout(
        @PathVariable userId: Long,
        @RequestBody req: CheckoutRequest
    ): ResponseEntity<OrderDto> {
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "کاربر یافت نشد")
        }
        val address = addressRepository.findById(req.addressId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "آدرس ارسال یافت نشد")
        }
        if (address.user.id != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "آدرس انتخاب شده متعلق به حساب شما نیست")
        }
        val cartItems = cartItemRepository.findByUserId(userId)
        if (cartItems.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "سبد خرید شما خالی است")
        }

        for (item in cartItems) {
            val minReq = if (item.product.saleType == SaleType.MOQ) item.product.minOrderQuantity else 1
            if (item.quantity < minReq) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "حداقل تعداد سفارش عمده (MOQ) برای محصول «${item.product.title}» برابر با $minReq عدد است"
                )
            }
            if (item.quantity <= 0 || item.quantity > item.product.stockQuantity) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "موجودی محصول «${item.product.title}» کافی نیست (موجودی: ${item.product.stockQuantity})"
                )
            }
            item.product.stockQuantity -= item.quantity
            productRepository.save(item.product)
        }

        val total = cartItems.fold(BigDecimal.ZERO) { acc, item ->
            val unitPrice = item.product.resolveUnitPrice(item.quantity)
            acc.add(unitPrice.multiply(BigDecimal(item.quantity)))
        }

        val savedOrder = orderRepository.save(
            Order(user = user, shippingAddress = address, totalAmount = total, status = OrderStatus.PAID)
        )

        val savedItems = cartItems.map { item ->
            val unitPrice = item.product.resolveUnitPrice(item.quantity)
            orderItemRepository.save(
                OrderItem(
                    order = savedOrder,
                    product = item.product,
                    quantity = item.quantity,
                    unitPrice = unitPrice
                )
            )
        }

        cartItemRepository.deleteByUserId(userId)

        return ResponseEntity.status(HttpStatus.CREATED).body(savedOrder.toDto(savedItems))
    }

    @GetMapping("/orders")
    fun getOrders(@PathVariable userId: Long): List<OrderDto> =
        orderRepository.findByUserIdOrderByCreatedAtDesc(userId).map { order ->
            val items = orderItemRepository.findByOrderId(order.id)
            order.toDto(items)
        }
}

internal fun Banner.toDto() = BannerDto(id, title, imageUrl, targetUrl)
internal fun Category.toSimpleDto() = CategoryDto(id, name, iconUrl, parent?.id)
internal fun Booth.toDto() = BoothDto(id, seller.id, seller.fullName, name, description, type, logoUrl, bannerUrl, rating)
internal fun Product.toDto(): ProductDto = ProductDto(
    id = id,
    boothId = booth.id,
    boothName = booth.name,
    boothType = booth.type,
    categoryId = category.id,
    categoryName = category.name,
    title = title,
    description = description,
    price = price,
    discountPrice = discountPrice,
    chinaPrice = chinaPrice,
    saleType = saleType,
    minOrderQuantity = minOrderQuantity,
    warehouseOrigin = warehouseOrigin,
    deliveryTimeRange = deliveryTimeRange,
    priceTiers = priceTiers.sortedBy { it.minQuantity }.map { PriceTierDto(it.minQuantity, it.unitPrice) },
    stockQuantity = stockQuantity,
    imageUrl = imageUrl,
    isHotDeal = isHotDeal,
    rating = rating
)

internal fun Order.toDto(items: List<OrderItem>) = OrderDto(
    id = id,
    status = status,
    totalAmount = totalAmount,
    shippingAddress = "${shippingAddress.city}، ${shippingAddress.fullAddress}",
    createdAt = createdAt.toString().substringBefore("T"),
    items = items.map {
        OrderItemDto(
            productId = it.product.id,
            productTitle = it.product.title,
            quantity = it.quantity,
            unitPrice = it.unitPrice,
            saleType = it.product.saleType,
            warehouseOrigin = it.product.warehouseOrigin,
            deliveryTimeRange = it.product.deliveryTimeRange
        )
    }
)