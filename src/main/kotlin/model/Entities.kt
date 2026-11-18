package com.marketplace.backend.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

enum class UserRole {
    BUYER, SELLER, ADMIN
}

enum class BoothType {
    RETAIL,
    SUPPLIER,
    PRODUCTION
}

enum class SaleType {
    SINGLE,
    MOQ
}

enum class WarehouseOrigin {
    IRAN,
    CHINA
}

enum class OrderStatus {
    PENDING, PAID, SHIPPED, DELIVERED, CANCELLED
}

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true, length = 15)
    var phone: String,

    @Column(nullable = false, length = 120)
    var fullName: String,

    @Column(nullable = false, length = 120)
    var password: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: UserRole = UserRole.BUYER,

    @Column(columnDefinition = "TEXT")
    var avatarUrl: String? = null,

    @Column(length = 10)
    var nationalCode: String? = null,

    @Column(length = 120)
    var email: String? = null,

    @Column(length = 20)
    var birthDate: String? = null,

    @Column(length = 34)
    var shebaNumber: String? = null,

    @Column(columnDefinition = "TEXT")
    var bio: String? = null,

    val createdAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "addresses")
class Address(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false, length = 80)
    var title: String,

    @Column(nullable = false, length = 80)
    var city: String,

    @Column(nullable = false, length = 500)
    var fullAddress: String,

    @Column(nullable = false, length = 20)
    var postalCode: String,

    var isDefault: Boolean = false
)

@Entity
@Table(name = "categories")
class Category(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(columnDefinition = "TEXT")
    var iconUrl: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: Category? = null
)

@Entity
@Table(name = "booths")
class Booth(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    val seller: User,

    @Column(nullable = false, length = 150)
    var name: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: BoothType = BoothType.RETAIL,

    @Column(columnDefinition = "TEXT")
    var logoUrl: String? = null,

    @Column(columnDefinition = "TEXT")
    var bannerUrl: String? = null,

    var rating: Double = 5.0,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "products")
class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booth_id", nullable = false)
    val booth: Booth,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    var category: Category,

    @Column(nullable = false, length = 300)
    var title: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    // Base / Iran Warehouse Price
    @Column(nullable = false, precision = 18, scale = 0)
    var price: BigDecimal,

    // Optional Discounted Price for Iran Warehouse
    @Column(precision = 18, scale = 0)
    var discountPrice: BigDecimal? = null,

    // Optional Lower Price for China Warehouse (50-90 working days)
    @Column(precision = 18, scale = 0)
    var chinaPrice: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var saleType: SaleType = SaleType.SINGLE,

    @Column(nullable = false)
    var minOrderQuantity: Int = 1,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var warehouseOrigin: WarehouseOrigin = WarehouseOrigin.IRAN,

    @Column(nullable = false, length = 120)
    var deliveryTimeRange: String = resolveDeliveryLabel(WarehouseOrigin.IRAN),

    // Separate inventory for Iran Warehouse vs. China Warehouse (ChayaMarket model)
    @Column(nullable = false)
    var iranStockQuantity: Int = 10,

    @Column(nullable = false)
    var chinaStockQuantity: Int = 0,

    // Total combined stock (kept synchronized for backward compatibility)
    @Column(nullable = false)
    var stockQuantity: Int = 10,

    @Column(columnDefinition = "TEXT")
    var imageUrl: String? = null,

    var isHotDeal: Boolean = false,

    var rating: Double = 5.0,

    @OneToMany(mappedBy = "product", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    val priceTiers: MutableList<PriceTier> = mutableListOf(),

    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    fun syncTotalStock() {
        stockQuantity = (iranStockQuantity.coerceAtLeast(0) + chinaStockQuantity.coerceAtLeast(0))
    }

    fun availableStockFor(warehouse: WarehouseOrigin): Int = when (warehouse) {
        WarehouseOrigin.IRAN -> iranStockQuantity
        WarehouseOrigin.CHINA -> chinaStockQuantity
    }

    fun deductStock(warehouse: WarehouseOrigin, quantity: Int) {
        when (warehouse) {
            WarehouseOrigin.IRAN -> iranStockQuantity = (iranStockQuantity - quantity).coerceAtLeast(0)
            WarehouseOrigin.CHINA -> chinaStockQuantity = (chinaStockQuantity - quantity).coerceAtLeast(0)
        }
        syncTotalStock()
    }

    fun defaultAvailableWarehouse(): WarehouseOrigin = when {
        warehouseOrigin == WarehouseOrigin.CHINA && chinaStockQuantity > 0 -> WarehouseOrigin.CHINA
        iranStockQuantity > 0 -> WarehouseOrigin.IRAN
        chinaStockQuantity > 0 -> WarehouseOrigin.CHINA
        else -> warehouseOrigin
    }

    fun effectivePriceForWarehouse(warehouse: WarehouseOrigin = defaultAvailableWarehouse()): BigDecimal = when (warehouse) {
        WarehouseOrigin.CHINA -> chinaPrice?.takeIf { it > BigDecimal.ZERO }
            ?: discountPrice?.takeIf { it > BigDecimal.ZERO }
            ?: price
        WarehouseOrigin.IRAN -> discountPrice?.takeIf { it > BigDecimal.ZERO } ?: price
    }

    fun resolveUnitPrice(quantity: Int, warehouse: WarehouseOrigin = defaultAvailableWarehouse()): BigDecimal {
        if (saleType == SaleType.MOQ && priceTiers.isNotEmpty()) {
            val matchedTier = priceTiers
                .sortedBy { it.minQuantity }
                .lastOrNull { quantity >= it.minQuantity }
            if (matchedTier != null) return matchedTier.unitPrice
        }
        return effectivePriceForWarehouse(warehouse)
    }

    fun replacePriceTiers(newTiers: List<Pair<Int, BigDecimal>>) {
        priceTiers.clear()
        if (saleType == SaleType.MOQ) {
            newTiers
                .filter { (minQty, unitPrice) -> minQty >= minOrderQuantity && unitPrice > BigDecimal.ZERO }
                .sortedBy { it.first }
                .distinctBy { it.first }
                .forEach { (minQty, unitPrice) ->
                    priceTiers.add(PriceTier(product = this, minQuantity = minQty, unitPrice = unitPrice))
                }
        }
    }

    companion object {
        fun resolveDeliveryLabel(origin: WarehouseOrigin): String = when (origin) {
            WarehouseOrigin.IRAN -> "آماده ارسال از انبار ایران (۱ تا ۳ روز کاری)"
            WarehouseOrigin.CHINA -> "ارسال مستقیم از انبار چین به ایران (۵۰ تا ۹۰ روز کاری - قیمت اقتصادی)"
        }
    }
}

@Entity
@Table(name = "product_price_tiers")
class PriceTier(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    val product: Product,

    @Column(nullable = false)
    var minQuantity: Int,

    @Column(nullable = false, precision = 18, scale = 0)
    var unitPrice: BigDecimal
)

@Entity
@Table(name = "banners")
class Banner(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 300)
    var title: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var imageUrl: String,

    @Column(columnDefinition = "TEXT")
    var targetUrl: String? = null,
    var isActive: Boolean = true
)

@Entity
@Table(
    name = "cart_items",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "product_id"])]
)
class CartItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    val product: Product,

    @Column(nullable = false)
    var quantity: Int = 1,

    @Enumerated(EnumType.STRING)
    @Column(name = "selected_warehouse", nullable = false, length = 20)
    var selectedWarehouse: WarehouseOrigin = WarehouseOrigin.IRAN
)

@Entity
@Table(
    name = "wishlist_items",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "product_id"])]
)
class WishlistItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    val product: Product,

    val addedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "orders")
class Order(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "address_id", nullable = false)
    val shippingAddress: Address,

    @Column(nullable = false, precision = 18, scale = 0)
    var totalAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OrderStatus = OrderStatus.PAID,

    val createdAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "order_items")
class OrderItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    val order: Order,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    val product: Product,

    @Column(nullable = false)
    val quantity: Int,

    @Column(nullable = false, precision = 18, scale = 0)
    val unitPrice: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "selected_warehouse", nullable = false, length = 20)
    val selectedWarehouse: WarehouseOrigin = WarehouseOrigin.IRAN
)