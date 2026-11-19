package com.marketplace.backend.web

import com.marketplace.backend.domain.BoothType
import com.marketplace.backend.domain.SaleType
import com.marketplace.backend.domain.WarehouseOrigin
import com.marketplace.backend.service.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "1. Authentication")
class AuthController(private val authService: AuthService) {

    @PostMapping("/send-otp")
    @Operation(summary = "Generate stateless hashed OTP and offload dispatch via async SMS.ir worker")
    fun sendOtp(@Valid @RequestBody req: SendOtpRequest): ResponseEntity<SendOtpResponse> {
        val result = authService.sendOtp(req.phone)
        return ResponseEntity.ok(
            SendOtpResponse(
                message = result.message,
                mockOtpHint = result.sandboxOtpHint,
                sentViaSmsIr = result.queuedForSmsIr
            )
        )
    }

    @PostMapping("/register")
    @Operation(summary = "Register Buyer or Seller account")
    fun register(@Valid @RequestBody req: RegisterRequest): ResponseEntity<AuthResponse> {
        val result = authService.register(req.phone, req.fullName, req.role, req.otpCode)
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse(result.token, result.user.toDto()))
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate with OTP or BCrypt password")
    fun login(@Valid @RequestBody req: VerifyOtpRequest): ResponseEntity<AuthResponse> {
        val result = authService.login(req.phone, req.otpCode)
        return ResponseEntity.ok(AuthResponse(result.token, result.user.toDto()))
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change password using current password or active SMS OTP")
    fun changePassword(@Valid @RequestBody req: ChangePasswordRequest): ResponseEntity<MessageResponse> {
        authService.changePassword(req.phone, req.currentCredential, req.newPassword)
        return ResponseEntity.ok(MessageResponse("رمز عبور شما با موفقیت تغییر یافت"))
    }
}

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "2. User Profile, Booths & Addresses")
class UserProfileController(private val userService: UserService) {

    @GetMapping("/{userId}/profile")
    fun getProfile(@PathVariable userId: Long): UserProfileDto =
        userService.getProfile(userId, RequestContextHolder.requireActor()).toDto()

    @PutMapping("/{userId}/profile")
    @Operation(summary = "Update profile fields (send empty string to clear optional fields, null to keep existing)")
    fun updateProfile(
        @PathVariable userId: Long,
        @Valid @RequestBody req: UpdateProfileRequest
    ): UserProfileDto = userService.updateProfile(
        userId = userId,
        cmd = ProfileUpdateCommand(
            fullName = req.fullName,
            avatarUrl = req.avatarUrl,
            nationalCode = req.nationalCode,
            email = req.email,
            birthDate = req.birthDate,
            shebaNumber = req.shebaNumber,
            bio = req.bio
        ),
        actor = RequestContextHolder.requireActor()
    ).toDto()

    @PostMapping("/{userId}/change-phone")
    @Operation(summary = "Verify OTP sent to new phone number, update phone, and issue a refreshed JWT")
    fun changePhone(
        @PathVariable userId: Long,
        @Valid @RequestBody req: ChangePhoneRequest
    ): ResponseEntity<AuthResponse> {
        val result = userService.changePhone(userId, req.newPhone, req.otpCode, RequestContextHolder.requireActor())
        return ResponseEntity.ok(AuthResponse(result.token, result.user.toDto()))
    }

    @GetMapping("/{userId}/booths")
    fun getSellerBooths(@PathVariable userId: Long): List<BoothDto> =
        userService.getSellerBooths(userId, RequestContextHolder.requireActor()).map { it.toDto() }

    @GetMapping("/{userId}/addresses")
    fun getAddresses(@PathVariable userId: Long): List<AddressDto> =
        userService.getAddresses(userId, RequestContextHolder.requireActor()).map { it.toDto() }

    @PostMapping("/{userId}/addresses")
    fun addAddress(
        @PathVariable userId: Long,
        @Valid @RequestBody req: CreateAddressRequest
    ): ResponseEntity<AddressDto> {
        val saved = userService.addAddress(
            userId = userId,
            cmd = CreateAddressCommand(req.title, req.city, req.fullAddress, req.postalCode, req.isDefault),
            actor = RequestContextHolder.requireActor()
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toDto())
    }

    @PutMapping("/{userId}/addresses/{addressId}/default")
    fun setDefaultAddress(@PathVariable userId: Long, @PathVariable addressId: Long): List<AddressDto> =
        userService.setDefaultAddress(userId, addressId, RequestContextHolder.requireActor()).map { it.toDto() }

    @DeleteMapping("/{userId}/addresses/{addressId}")
    fun deleteAddress(@PathVariable userId: Long, @PathVariable addressId: Long): ResponseEntity<Void> {
        userService.deleteAddress(userId, addressId, RequestContextHolder.requireActor())
        return ResponseEntity.noContent().build()
    }
}

@RestController
@RequestMapping("/api/v1/vitrine")
@Tag(name = "3. Home Vitrine")
class VitrineController(private val catalogService: CatalogService) {

    @GetMapping("/guest")
    fun getGuestVitrine(): VitrineResponse = catalogService.getGuestVitrine().let { data ->
        VitrineResponse(
            mode = data.mode,
            welcomeMessage = data.welcomeMessage,
            banners = data.banners.map { it.toDto() },
            rootCategories = data.categoryTree.map { it.toDto() },
            hotDeals = data.hotDeals.map { it.toDto() },
            recommendedProducts = data.recommendedProducts.map { it.toDto() }
        )
    }

    @GetMapping("/personalized/{userId}")
    fun getPersonalizedVitrine(@PathVariable userId: Long): VitrineResponse =
        catalogService.getPersonalizedVitrine(userId, RequestContextHolder.requireActor()).let { data ->
            VitrineResponse(
                mode = data.mode,
                welcomeMessage = data.welcomeMessage,
                banners = data.banners.map { it.toDto() },
                rootCategories = data.categoryTree.map { it.toDto() },
                hotDeals = data.hotDeals.map { it.toDto() },
                recommendedProducts = data.recommendedProducts.map { it.toDto() }
            )
        }
}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "4. Categories, Banners & Search")
class CatalogController(private val catalogService: CatalogService) {

    @GetMapping("/banners")
    fun getBanners(): List<BannerDto> = catalogService.getActiveBanners().map { it.toDto() }

    @PostMapping("/banners")
    fun createBanner(@Valid @RequestBody req: CreateBannerRequest): ResponseEntity<BannerDto> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(catalogService.createBanner(req.title, req.imageUrl, req.targetUrl).toDto())

    @DeleteMapping("/banners/{bannerId}")
    fun deleteBanner(@PathVariable bannerId: Long): ResponseEntity<Void> {
        catalogService.deleteBanner(bannerId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/categories")
    fun getCategories(): List<CategoryDto> = catalogService.getCategoryTree().map { it.toDto() }

    @GetMapping("/categories/{categoryId}/products")
    fun getProductsByCategory(@PathVariable categoryId: Long): List<ProductDto> =
        catalogService.getProductsByCategory(categoryId).map { it.toDto() }

    @GetMapping("/products/search")
    @Operation(summary = "Paginated product search with filters")
    fun searchProducts(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false) minPrice: BigDecimal?,
        @RequestParam(required = false) maxPrice: BigDecimal?,
        @RequestParam(required = false) onlyHotDeals: Boolean?,
        @RequestParam(required = false) saleType: SaleType?,
        @RequestParam(required = false) warehouseOrigin: WarehouseOrigin?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): PageResponse<ProductDto> {
        val result = catalogService.searchProducts(
            ProductSearchFilter(q, categoryId, minPrice, maxPrice, onlyHotDeals, saleType, warehouseOrigin, page, size)
        )
        return PageResponse(
            items = result.content.map { it.toDto() },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            hasNext = result.hasNext(),
            hasPrevious = result.hasPrevious()
        )
    }
}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "5. Booths & Products")
class BoothProductController(private val boothProductService: BoothProductService) {

    @GetMapping("/booths")
    fun getAllBooths(@RequestParam(required = false) type: BoothType?): List<BoothDto> =
        boothProductService.getAllBooths(type).map { it.toDto() }

    @GetMapping("/booths/{boothId}")
    fun getBooth(@PathVariable boothId: Long): BoothDto =
        boothProductService.getBooth(boothId).toDto()

    @GetMapping("/booths/{boothId}/products")
    fun getBoothProducts(@PathVariable boothId: Long): List<ProductDto> =
        boothProductService.getBoothProducts(boothId).map { it.toDto() }

    @PostMapping("/booths")
    fun createBooth(@Valid @RequestBody req: CreateBoothRequest): ResponseEntity<BoothDto> {
        val saved = boothProductService.createBooth(
            CreateBoothCommand(req.sellerId, req.name, req.description, req.type, req.logoUrl, req.bannerUrl),
            RequestContextHolder.requireActor()
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toDto())
    }

    @PostMapping("/booths/{boothId}/products")
    fun addProductToBooth(
        @PathVariable boothId: Long,
        @Valid @RequestBody req: CreateProductRequest
    ): ResponseEntity<ProductDto> {
        val saved = boothProductService.addProductToBooth(
            boothId = boothId,
            cmd = req.toCommand(),
            actor = RequestContextHolder.requireActor()
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toDto())
    }

    @PutMapping("/products/{productId}")
    fun updateProduct(
        @PathVariable productId: Long,
        @Valid @RequestBody req: CreateProductRequest
    ): ProductDto = boothProductService.updateProduct(
        productId = productId,
        cmd = req.toCommand(),
        actor = RequestContextHolder.requireActor()
    ).toDto()

    @GetMapping("/products/{productId}")
    fun getProductDetails(@PathVariable productId: Long): ProductDto =
        boothProductService.getProductDetails(productId).toDto()

    @DeleteMapping("/products/{productId}")
    fun deleteProduct(@PathVariable productId: Long): ResponseEntity<Void> {
        boothProductService.softDeleteProduct(productId, RequestContextHolder.requireActor())
        return ResponseEntity.noContent().build()
    }

    private fun CreateProductRequest.toCommand() = UpsertProductCommand(
        categoryId = categoryId,
        title = title,
        description = description,
        price = price,
        discountPrice = discountPrice,
        chinaPrice = chinaPrice,
        saleType = saleType,
        minOrderQuantity = minOrderQuantity,
        warehouseOrigin = warehouseOrigin,
        iranStockQuantity = iranStockQuantity,
        chinaStockQuantity = chinaStockQuantity,
        stockQuantity = stockQuantity,
        priceTiers = priceTiers.map { it.minQuantity to it.unitPrice },
        imageUrl = imageUrl,
        isHotDeal = isHotDeal
    )
}

@RestController
@RequestMapping("/api/v1/users/{userId}")
@Tag(name = "6. Cart, Wishlist & Checkout")
class CartOrderController(private val cartOrderService: CartOrderService) {

    @GetMapping("/cart")
    fun getCart(@PathVariable userId: Long): CartSummaryDto =
        cartOrderService.getCart(userId, RequestContextHolder.requireActor()).toDto()

    @PostMapping("/cart")
    fun addToCart(
        @PathVariable userId: Long,
        @Valid @RequestBody req: AddToCartRequest
    ): CartSummaryDto = cartOrderService.addToCart(
        userId = userId,
        productId = req.productId,
        quantityDelta = req.quantity,
        requestedWarehouse = req.selectedWarehouse,
        actor = RequestContextHolder.requireActor()
    ).toDto()

    @DeleteMapping("/cart/{productId}")
    fun removeFromCart(
        @PathVariable userId: Long,
        @PathVariable productId: Long,
        @RequestParam(required = false) selectedWarehouse: WarehouseOrigin?
    ): CartSummaryDto = cartOrderService.removeFromCart(
        userId = userId,
        productId = productId,
        selectedWarehouse = selectedWarehouse,
        actor = RequestContextHolder.requireActor()
    ).toDto()

    @GetMapping("/wishlist")
    fun getWishlist(@PathVariable userId: Long): List<ProductDto> =
        cartOrderService.getWishlist(userId, RequestContextHolder.requireActor()).map { it.toDto() }

    @PostMapping("/wishlist/{productId}")
    fun addToWishlist(@PathVariable userId: Long, @PathVariable productId: Long): List<ProductDto> =
        cartOrderService.addToWishlist(userId, productId, RequestContextHolder.requireActor()).map { it.toDto() }

    @DeleteMapping("/wishlist/{productId}")
    fun removeFromWishlist(@PathVariable userId: Long, @PathVariable productId: Long): List<ProductDto> =
        cartOrderService.removeFromWishlist(userId, productId, RequestContextHolder.requireActor()).map { it.toDto() }

    @PostMapping("/orders/checkout")
    @Operation(summary = "Create PENDING order and atomically reserve warehouse stock")
    fun checkout(
        @PathVariable userId: Long,
        @Valid @RequestBody req: CheckoutRequest
    ): ResponseEntity<OrderDto> {
        val orderWithItems = cartOrderService.checkout(userId, req.addressId, RequestContextHolder.requireActor())
        return ResponseEntity.status(HttpStatus.CREATED).body(orderWithItems.toDto())
    }

    @GetMapping("/orders")
    fun getOrders(@PathVariable userId: Long): List<OrderDto> =
        cartOrderService.getOrders(userId, RequestContextHolder.requireActor()).map { it.toDto() }
}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "7. Order Lifecycle Management")
class OrderLifecycleController(private val cartOrderService: CartOrderService) {

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Get single order details (Buyer owner, involved Seller, or Admin)")
    fun getOrder(@PathVariable orderId: Long): OrderDto =
        cartOrderService.getOrderById(orderId, RequestContextHolder.requireActor()).toDto()

    @GetMapping("/booths/{boothId}/orders")
    @Operation(summary = "List incoming orders for a seller's booth")
    fun getBoothOrders(@PathVariable boothId: Long): List<OrderDto> =
        cartOrderService.getBoothOrders(boothId, RequestContextHolder.requireActor()).map { it.toDto() }

    @PostMapping("/orders/{orderId}/pay")
    @Operation(summary = "Confirm payment for a PENDING order (transitions PENDING -> PAID)")
    fun payOrder(@PathVariable orderId: Long): OrderDto =
        cartOrderService.payOrder(orderId, RequestContextHolder.requireActor()).toDto()

    @PatchMapping("/orders/{orderId}/status")
    @Operation(summary = "Advance order status (SHIPPED, DELIVERED) or CANCEL (automatically restores warehouse stock)")
    fun updateOrderStatus(
        @PathVariable orderId: Long,
        @Valid @RequestBody req: UpdateOrderStatusRequest
    ): OrderDto = cartOrderService.updateOrderStatus(
        orderId = orderId,
        targetStatus = req.status,
        actor = RequestContextHolder.requireActor()
    ).toDto()
}

@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "8. Media Upload & Serving")
class MediaController(private val mediaStorageService: MediaStorageService) {

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(summary = "Upload JPEG, PNG, or WEBP image (max 5MB) and receive a reusable URL")
    fun uploadImage(@RequestPart("file") file: MultipartFile): ResponseEntity<MediaUploadResponse> {
        val stored = mediaStorageService.storeImage(file.bytes)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            MediaUploadResponse(
                filename = stored.filename,
                url = stored.url,
                contentType = stored.contentType,
                sizeBytes = stored.sizeBytes
            )
        )
    }

    @GetMapping("/{filename}")
    @Operation(summary = "Serve uploaded image by generated UUID filename")
    fun serveImage(@PathVariable filename: String): ResponseEntity<ByteArray> {
        val loaded = mediaStorageService.loadImage(filename)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(loaded.contentType))
            .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
            .body(loaded.bytes)
    }
}