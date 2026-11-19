package com.marketplace.backend.repository

import com.marketplace.backend.domain.*
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.*
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface UserRepository : JpaRepository<User, Long> {
    fun findByPhone(phone: String): User?
    fun existsByPhone(phone: String): Boolean
}

interface OtpVerificationRepository : JpaRepository<OtpVerification, Long> {
    fun findByPhone(phone: String): OtpVerification?
    fun deleteByPhone(phone: String)

    @Modifying
    @Query("DELETE FROM OtpVerification o WHERE o.expiresAt < :now")
    fun deleteAllExpired(@Param("now") now: LocalDateTime): Int
}

interface AddressRepository : JpaRepository<Address, Long> {
    fun findByUserIdOrderByIsDefaultDescIdDesc(userId: Long): List<Address>
    fun findByIdAndUserId(id: Long, userId: Long): Address?
}

interface CategoryRepository : JpaRepository<Category, Long> {
    fun findByParentIsNull(): List<Category>
    fun findByParentId(parentId: Long): List<Category>
}

interface BoothRepository : JpaRepository<Booth, Long> {
    @EntityGraph(attributePaths = ["seller"])
    override fun findAll(): List<Booth>

    @EntityGraph(attributePaths = ["seller"])
    fun findBySellerId(sellerId: Long): List<Booth>

    @EntityGraph(attributePaths = ["seller"])
    fun findByType(type: BoothType): List<Booth>

    @EntityGraph(attributePaths = ["seller"])
    fun findWithSellerById(id: Long): Booth?

    @EntityGraph(attributePaths = ["seller"])
    fun findByIdAndSellerId(id: Long, sellerId: Long): Booth?
}

interface ProductRepository : JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    @EntityGraph(attributePaths = ["booth", "category"])
    override fun findAll(spec: Specification<Product>, pageable: Pageable): Page<Product>

    @EntityGraph(attributePaths = ["booth", "category"])
    fun findByIsHotDealTrueAndIsActiveTrue(pageable: Pageable): List<Product>

    @EntityGraph(attributePaths = ["booth", "category"])
    fun findByIsActiveTrueOrderByCreatedAtDesc(pageable: Pageable): List<Product>

    @EntityGraph(attributePaths = ["booth", "category"])
    fun findByBoothIdAndIsActiveTrue(boothId: Long): List<Product>

    @EntityGraph(attributePaths = ["booth", "category"])
    fun findByCategoryIdInAndIsActiveTrue(categoryIds: Collection<Long>): List<Product>

    @EntityGraph(attributePaths = ["booth", "booth.seller", "category"])
    fun findByIdAndIsActiveTrue(id: Long): Product?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id IN :ids AND p.isActive = true ORDER BY p.id ASC")
    fun findAllActiveByIdInForUpdate(@Param("ids") ids: Collection<Long>): List<Product>
}

interface BannerRepository : JpaRepository<Banner, Long> {
    fun findByIsActiveTrue(): List<Banner>
}

interface CartItemRepository : JpaRepository<CartItem, Long> {
    @EntityGraph(attributePaths = ["product", "product.booth", "product.category"])
    fun findByUserId(userId: Long): List<CartItem>

    @Query("SELECT c FROM CartItem c WHERE c.user.id = :userId")
    fun findRawByUserId(@Param("userId") userId: Long): List<CartItem>

    fun findByUserIdAndProductIdAndSelectedWarehouse(
        userId: Long,
        productId: Long,
        selectedWarehouse: WarehouseOrigin
    ): CartItem?

    fun findByUserIdAndProductId(userId: Long, productId: Long): List<CartItem>

    fun deleteByUserId(userId: Long)
    fun deleteByProductId(productId: Long)
}

interface WishlistItemRepository : JpaRepository<WishlistItem, Long> {
    @EntityGraph(attributePaths = ["product", "product.booth", "product.category"])
    fun findByUserIdAndProductIsActiveTrue(userId: Long): List<WishlistItem>

    fun findByUserIdAndProductId(userId: Long, productId: Long): WishlistItem?
    fun deleteByProductId(productId: Long)
}

interface OrderRepository : JpaRepository<Order, Long> {
    @EntityGraph(attributePaths = ["shippingAddress"])
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<Order>

    @EntityGraph(attributePaths = ["user", "shippingAddress"])
    fun findWithDetailsById(id: Long): Order?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Order?

    @EntityGraph(attributePaths = ["shippingAddress"])
    @Query(
        """
        SELECT o FROM Order o
        WHERE EXISTS (
            SELECT 1 FROM OrderItem oi
            WHERE oi.order = o AND oi.product.booth.id = :boothId
        )
        ORDER BY o.createdAt DESC
        """
    )
    fun findByBoothIdOrderByCreatedAtDesc(@Param("boothId") boothId: Long): List<Order>
}

interface OrderItemRepository : JpaRepository<OrderItem, Long> {
    @EntityGraph(attributePaths = ["product", "product.booth", "product.booth.seller"])
    fun findByOrderId(orderId: Long): List<OrderItem>

    @EntityGraph(attributePaths = ["product", "product.booth"])
    fun findByOrderIdIn(orderIds: Collection<Long>): List<OrderItem>
}