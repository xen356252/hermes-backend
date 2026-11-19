package com.marketplace.backend.web

import com.marketplace.backend.domain.*
import com.marketplace.backend.service.CartItemView
import com.marketplace.backend.service.CartSummaryView
import com.marketplace.backend.service.CategoryNode
import com.marketplace.backend.service.OrderWithItems
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

// --- Common Wrappers ---
data class MessageResponse(val message: String)

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)

data class MediaUploadResponse(
    val filename: String,
    val url: String,
    val contentType: String,
    val sizeBytes: Long
)

// --- Auth & User DTOs ---
data class SendOtpRequest(@field:NotBlank(message = "شماره موبایل الزامی است") val phone: String)

data class SendOtpResponse(
    val message: String,
    val mockOtpHint: String?,
    val sentViaSmsIr: Boolean = false
)

data class VerifyOtpRequest(
    @field:NotBlank(message = "شماره موبایل الزامی است") val phone: String,
    @field:Schema(description = "رمز عبور یا کد تایید پیامکی (OTP)")
    @field:NotBlank(message = "رمز عبور یا کد تایید الزامی است") val otpCode: String
)

data class RegisterRequest(
    @field:NotBlank(message = "شماره موبایل الزامی است") val phone: String,
    val fullName: String = "",
    val role: UserRole = UserRole.BUYER,
    @field:Schema(description = "رمز عبور یا کد تایید پیامکی (OTP)")
    @field:NotBlank(message = "رمز عبور یا کد تایید الزامی است") val otpCode: String
)

data class ChangePasswordRequest(
    @field:NotBlank(message = "شماره موبایل الزامی است") val phone: String,
    @field:Schema(description = "رمز عبور فعلی یا کد تایید پیامکی (OTP)")
    @field:NotBlank(message = "رمز عبور فعلی یا کد تایید الزامی است") val currentCredential: String,
    @field:NotBlank(message = "رمز عبور جدید الزامی است") val newPassword: String
)

data class ChangePhoneRequest(
    @field:NotBlank(message = "شماره موبایل جدید الزامی است") val newPhone: String,
    @field:Schema(description = "کد تایید پیامکی (OTP) ارسال‌شده به شماره موبایل جدید")
    @field:NotBlank(message = "کد تایید پیامکی الزامی است") val otpCode: String
)

data class AuthResponse(val token: String, val user: UserProfileDto)

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

@Schema(description = "ارسال null یعنی بدون تغییر؛ ارسال رشته خالی (\"\") فیلد اختیاری را پاک می‌کند")
data class UpdateProfileRequest(
    @field:NotBlank(message = "نام و نام خانوادگی الزامی است") val fullName: String,
    val avatarUrl: String? = null,
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
    @field:NotBlank(message = "عنوان آدرس الزامی است") val title: String,
    @field:NotBlank(message = "نام شهر الزامی است") val city: String,
    @field:NotBlank(message = "آدرس کامل الزامی است") val fullAddress: String,
    @field:NotBlank(message = "کد پستی الزامی است") val postalCode: String,
    val isDefault: Boolean = false
)

// --- Marketplace DTOs ---
data class BannerDto(val id: Long, val title: String, val imageUrl: String, val targetUrl: String?)
data class CreateBannerRequest(val title: String? = null, val imageUrl: String = "", val targetUrl: String? = null)

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
    @field:Min(2) val minQuantity: Int,
    @field:Positive val unitPrice: BigDecimal
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
    val iranDeliveryTimeRange: String = Product.resolveDeliveryLabel(WarehouseOrigin.IRAN),
    val chinaDeliveryTimeRange: String = Product.resolveDeliveryLabel(WarehouseOrigin.CHINA),
    val iranStockQuantity: Int = 0,
    val chinaStockQuantity: Int = 0,
    val hasIranWarehouse: Boolean = false,
    val hasChinaWarehouse: Boolean = false,
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
    @field:Positive val sellerId: Long,
    @field:NotBlank(message = "نام غرفه الزامی است") val name: String,
    val description: String? = null,
    val type: BoothType = BoothType.RETAIL,
    val logoUrl: String? = null,
    val bannerUrl: String? = null
)

data class CreateProductRequest(
    @field:Positive val categoryId: Long,
    @field:NotBlank(message = "نام محصول الزامی است") val title: String,
    val description: String? = null,
    @field:NotNull @field:Positive(message = "قیمت باید مثبت باشد") val price: BigDecimal,
    val discountPrice: BigDecimal? = null,
    val chinaPrice: BigDecimal? = null,
    val saleType: SaleType = SaleType.SINGLE,
    @field:Min(1) val minOrderQuantity: Int = 1,
    val warehouseOrigin: WarehouseOrigin = WarehouseOrigin.IRAN,
    val iranStockQuantity: Int? = null,
    val chinaStockQuantity: Int? = null,
    val priceTiers: List<PriceTierDto> = emptyList(),
    @field:Min(0) val stockQuantity: Int = 10,
    val imageUrl: String? = null,
    val isHotDeal: Boolean = false
)

data class CartItemDto(
    val id: Long,
    val product: ProductDto,
    val quantity: Int,
    val selectedWarehouse: WarehouseOrigin = WarehouseOrigin.IRAN,
    val deliveryTimeRange: String = Product.resolveDeliveryLabel(WarehouseOrigin.IRAN),
    val appliedUnitPrice: BigDecimal,
    val lineTotal: BigDecimal
)

data class CartSummaryDto(
    val items: List<CartItemDto>,
    val totalPrice: BigDecimal
)

data class AddToCartRequest(
    @field:Positive val productId: Long,
    val quantity: Int = 1,
    val selectedWarehouse: WarehouseOrigin? = null
)

data class CheckoutRequest(@field:Positive val addressId: Long)

data class UpdateOrderStatusRequest(
    @field:NotNull(message = "وضعیت جدید سفارش الزامی است") val status: OrderStatus
)

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

// --- Pure Mappers ---
internal fun User.toDto() = UserProfileDto(
    id = id, phone = phone, fullName = fullName, role = role, avatarUrl = avatarUrl,
    nationalCode = nationalCode, email = email, birthDate = birthDate, shebaNumber = shebaNumber, bio = bio
)

internal fun Address.toDto() = AddressDto(id, title, city, fullAddress, postalCode, isDefault)
internal fun Banner.toDto() = BannerDto(id, title, imageUrl, targetUrl)
internal fun Category.toSimpleDto() = CategoryDto(id, name, iconUrl, parent?.id)
internal fun CategoryNode.toDto() = CategoryDto(
    id = category.id,
    name = category.name,
    iconUrl = category.iconUrl,
    parentId = null,
    subCategories = children.map { it.toSimpleDto() }
)

internal fun Booth.toDto() = BoothDto(
    id, seller.id, seller.fullName, name, description, type, logoUrl, bannerUrl, rating
)

internal fun Product.toDto() = ProductDto(
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
    iranDeliveryTimeRange = Product.resolveDeliveryLabel(WarehouseOrigin.IRAN),
    chinaDeliveryTimeRange = Product.resolveDeliveryLabel(WarehouseOrigin.CHINA),
    iranStockQuantity = iranStockQuantity,
    chinaStockQuantity = chinaStockQuantity,
    hasIranWarehouse = iranStockQuantity > 0,
    hasChinaWarehouse = chinaStockQuantity > 0,
    priceTiers = priceTiers.sortedBy { it.minQuantity }.map { PriceTierDto(it.minQuantity, it.unitPrice) },
    stockQuantity = stockQuantity,
    imageUrl = imageUrl,
    isHotDeal = isHotDeal,
    rating = rating
)

internal fun CartItemView.toDto() = CartItemDto(
    id = cartItem.id,
    product = cartItem.product.toDto(),
    quantity = cartItem.quantity,
    selectedWarehouse = cartItem.selectedWarehouse,
    deliveryTimeRange = Product.resolveDeliveryLabel(cartItem.selectedWarehouse),
    appliedUnitPrice = appliedUnitPrice,
    lineTotal = lineTotal
)

internal fun CartSummaryView.toDto() = CartSummaryDto(
    items = items.map { it.toDto() },
    totalPrice = totalPrice
)

internal fun OrderWithItems.toDto() = OrderDto(
    id = order.id,
    status = order.status,
    totalAmount = displayTotalAmount,
    shippingAddress = "${order.shippingAddress.city}، ${order.shippingAddress.fullAddress}",
    createdAt = order.createdAt.toString().substringBefore("T"),
    items = items.map {
        OrderItemDto(
            productId = it.product.id,
            productTitle = it.product.title,
            quantity = it.quantity,
            unitPrice = it.unitPrice,
            saleType = it.product.saleType,
            warehouseOrigin = it.selectedWarehouse,
            deliveryTimeRange = Product.resolveDeliveryLabel(it.selectedWarehouse)
        )
    }
)