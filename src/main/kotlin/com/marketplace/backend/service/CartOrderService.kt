package com.marketplace.backend.service

import com.marketplace.backend.domain.*
import com.marketplace.backend.repository.*
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class CartItemView(
    val cartItem: CartItem,
    val appliedUnitPrice: BigDecimal,
    val lineTotal: BigDecimal
)

data class CartSummaryView(
    val items: List<CartItemView>,
    val totalPrice: BigDecimal
)

data class OrderWithItems(
    val order: Order,
    val items: List<OrderItem>,
    val displayTotalAmount: BigDecimal = order.totalAmount
)

@Service
class CartOrderService(
    private val userRepository: UserRepository,
    private val productRepository: ProductRepository,
    private val boothRepository: BoothRepository,
    private val addressRepository: AddressRepository,
    private val cartItemRepository: CartItemRepository,
    private val wishlistItemRepository: WishlistItemRepository,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val entityManager: EntityManager
) {
    private fun requireSelfOrAdmin(targetUserId: Long, actor: AuthenticatedActor) {
        if (actor.userId != targetUserId && !actor.isAdmin()) {
            throw ForbiddenException("شما مجاز به دسترسی به سبد خرید یا سفارش‌های این کاربر نیستید")
        }
    }

    @Transactional(readOnly = true)
    fun getCart(userId: Long, actor: AuthenticatedActor): CartSummaryView {
        requireSelfOrAdmin(userId, actor)
        return buildCartSummary(userId)
    }

    @Transactional
    fun addToCart(
        userId: Long,
        productId: Long,
        quantityDelta: Int,
        requestedWarehouse: WarehouseOrigin?,
        actor: AuthenticatedActor
    ): CartSummaryView {
        requireSelfOrAdmin(userId, actor)
        val user = userRepository.findById(userId).orElseThrow { NotFoundException("کاربر یافت نشد") }
        val product = productRepository.findByIdAndIsActiveTrue(productId)
            ?: throw NotFoundException("محصول یافت نشد")

        val chosenWarehouse = requestedWarehouse ?: product.defaultAvailableWarehouse()
        val exactWarehouseStock = product.availableStockFor(chosenWarehouse)
        val minRequired = if (product.saleType == SaleType.MOQ) product.minOrderQuantity.coerceAtLeast(2) else 1

        val existing = cartItemRepository.findByUserIdAndProductIdAndSelectedWarehouse(
            userId, productId, chosenWarehouse
        )

        if (existing != null) {
            val newQty = existing.quantity + quantityDelta
            if (newQty <= 0 || (quantityDelta < 0 && newQty < minRequired)) {
                cartItemRepository.delete(existing)
            } else {
                val enforcedQty = newQty.coerceAtLeast(minRequired)
                if (enforcedQty > exactWarehouseStock) {
                    throw BadRequestException(
                        "تعداد درخواستی بیشتر از موجودی ${Product.warehouseLabel(chosenWarehouse)} ($exactWarehouseStock عدد) است"
                    )
                }
                existing.quantity = enforcedQty
                cartItemRepository.save(existing)
            }
        } else if (quantityDelta > 0) {
            val initialQty = quantityDelta.coerceAtLeast(minRequired)
            if (exactWarehouseStock < initialQty) {
                throw BadRequestException(
                    "موجودی ${Product.warehouseLabel(chosenWarehouse)} ($exactWarehouseStock عدد) برای حداقل سفارش ($minRequired عدد) کافی نیست"
                )
            }
            cartItemRepository.save(
                CartItem(
                    user = user,
                    product = product,
                    quantity = initialQty,
                    selectedWarehouse = chosenWarehouse
                )
            )
        }

        return buildCartSummary(userId)
    }

    @Transactional
    fun removeFromCart(
        userId: Long,
        productId: Long,
        selectedWarehouse: WarehouseOrigin?,
        actor: AuthenticatedActor
    ): CartSummaryView {
        requireSelfOrAdmin(userId, actor)
        if (selectedWarehouse != null) {
            cartItemRepository.findByUserIdAndProductIdAndSelectedWarehouse(userId, productId, selectedWarehouse)
                ?.let { cartItemRepository.delete(it) }
        } else {
            val items = cartItemRepository.findByUserIdAndProductId(userId, productId)
            if (items.isNotEmpty()) cartItemRepository.deleteAll(items)
        }
        return buildCartSummary(userId)
    }

    @Transactional(readOnly = true)
    fun getWishlist(userId: Long, actor: AuthenticatedActor): List<Product> {
        requireSelfOrAdmin(userId, actor)
        return wishlistItemRepository.findByUserIdAndProductIsActiveTrue(userId).map { it.product }.preloadTiers()
    }

    @Transactional
    fun addToWishlist(userId: Long, productId: Long, actor: AuthenticatedActor): List<Product> {
        requireSelfOrAdmin(userId, actor)
        val user = userRepository.findById(userId).orElseThrow { NotFoundException("کاربر یافت نشد") }
        val product = productRepository.findByIdAndIsActiveTrue(productId)
            ?: throw NotFoundException("محصول یافت نشد")

        if (wishlistItemRepository.findByUserIdAndProductId(userId, productId) == null) {
            wishlistItemRepository.save(WishlistItem(user = user, product = product))
        }
        return wishlistItemRepository.findByUserIdAndProductIsActiveTrue(userId).map { it.product }.preloadTiers()
    }

    @Transactional
    fun removeFromWishlist(userId: Long, productId: Long, actor: AuthenticatedActor): List<Product> {
        requireSelfOrAdmin(userId, actor)
        wishlistItemRepository.findByUserIdAndProductId(userId, productId)?.let {
            wishlistItemRepository.delete(it)
        }
        return wishlistItemRepository.findByUserIdAndProductIsActiveTrue(userId).map { it.product }.preloadTiers()
    }

    @Transactional
    fun checkout(userId: Long, addressId: Long, actor: AuthenticatedActor): OrderWithItems {
        requireSelfOrAdmin(userId, actor)
        val user = userRepository.findById(userId).orElseThrow { NotFoundException("کاربر یافت نشد") }

        val address = addressRepository.findByIdAndUserId(addressId, userId)
            ?: throw NotFoundException("آدرس ارسال متعلق به این حساب یافت نشد")

        val cartItems = cartItemRepository.findRawByUserId(userId)
        if (cartItems.isEmpty()) {
            throw BadRequestException("سبد خرید شما خالی است")
        }

        // Acquire PESSIMISTIC_WRITE locks in deterministic ascending ID order to prevent deadlocks & overselling
        val distinctProductIds = cartItems.map { it.product.id }.distinct().sorted()
        val lockedProductsById = productRepository.findAllActiveByIdInForUpdate(distinctProductIds)
            .preloadTiers()
            .associateBy { it.id }

        if (lockedProductsById.size != distinctProductIds.size) {
            throw ConflictException("یکی از محصولات سبد خرید شما دیگر فعال نیست")
        }

        var totalAmount = BigDecimal.ZERO
        val pendingOrderLines = mutableListOf<Triple<Product, CartItem, BigDecimal>>()

        for (item in cartItems) {
            val lockedProduct = lockedProductsById.getValue(item.product.id)
            val minReq = if (lockedProduct.saleType == SaleType.MOQ) lockedProduct.minOrderQuantity else 1
            if (item.quantity < minReq) {
                throw BadRequestException(
                    "حداقل تعداد سفارش عمده (MOQ) برای محصول «${lockedProduct.title}» برابر با $minReq عدد است"
                )
            }

            lockedProduct.deductStock(item.selectedWarehouse, item.quantity)
            val unitPrice = lockedProduct.resolveUnitPrice(item.quantity, item.selectedWarehouse)
            totalAmount = totalAmount.add(unitPrice.multiply(BigDecimal(item.quantity)))
            pendingOrderLines.add(Triple(lockedProduct, item, unitPrice))
        }

        productRepository.saveAll(lockedProductsById.values)

        val savedOrder = orderRepository.save(
            Order(user = user, shippingAddress = address, totalAmount = totalAmount, status = OrderStatus.PENDING)
        )

        val savedOrderItems = orderItemRepository.saveAll(
            pendingOrderLines.map { (product, cartItem, unitPrice) ->
                OrderItem(
                    order = savedOrder,
                    product = product,
                    quantity = cartItem.quantity,
                    unitPrice = unitPrice,
                    selectedWarehouse = cartItem.selectedWarehouse
                )
            }
        )

        cartItemRepository.deleteByUserId(userId)
        return OrderWithItems(order = savedOrder, items = savedOrderItems)
    }

    @Transactional(readOnly = true)
    fun getOrders(userId: Long, actor: AuthenticatedActor): List<OrderWithItems> {
        requireSelfOrAdmin(userId, actor)
        val orders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
        if (orders.isEmpty()) return emptyList()

        val itemsByOrderId = orderItemRepository.findByOrderIdIn(orders.map { it.id })
            .groupBy { it.order.id }

        return orders.map { order ->
            OrderWithItems(order = order, items = itemsByOrderId[order.id].orEmpty())
        }
    }

    @Transactional(readOnly = true)
    fun getOrderById(orderId: Long, actor: AuthenticatedActor): OrderWithItems {
        val order = orderRepository.findWithDetailsById(orderId)
            ?: throw NotFoundException("سفارش یافت نشد")
        val items = orderItemRepository.findByOrderId(orderId)

        val isOwner = order.user.id == actor.userId
        val isInvolvedSeller = items.any { it.product.booth.seller.id == actor.userId }
        if (!isOwner && !isInvolvedSeller && !actor.isAdmin()) {
            throw ForbiddenException("شما مجاز به مشاهده جزئیات این سفارش نیستید")
        }

        return OrderWithItems(order = order, items = items)
    }

    @Transactional(readOnly = true)
    fun getBoothOrders(boothId: Long, actor: AuthenticatedActor): List<OrderWithItems> {
        val booth = boothRepository.findWithSellerById(boothId)
            ?: throw NotFoundException("غرفه یافت نشد")
        if (booth.seller.id != actor.userId && !actor.isAdmin()) {
            throw ForbiddenException("شما مجاز به مشاهده سفارش‌های این غرفه نیستید")
        }

        val orders = orderRepository.findByBoothIdOrderByCreatedAtDesc(boothId)
        if (orders.isEmpty()) return emptyList()

        // Scope both line items and displayed total to this booth only
        val itemsByOrderId = orderItemRepository.findByOrderIdIn(orders.map { it.id })
            .filter { it.product.booth.id == boothId }
            .groupBy { it.order.id }

        return orders.map { order ->
            val boothItems = itemsByOrderId[order.id].orEmpty()
            val boothSubtotal = boothItems.fold(BigDecimal.ZERO) { acc, item ->
                acc.add(item.unitPrice.multiply(BigDecimal(item.quantity)))
            }
            OrderWithItems(order = order, items = boothItems, displayTotalAmount = boothSubtotal)
        }
    }

    @Transactional
    fun payOrder(orderId: Long, actor: AuthenticatedActor): OrderWithItems {
        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw NotFoundException("سفارش یافت نشد")

        if (order.user.id != actor.userId && !actor.isAdmin()) {
            throw ForbiddenException("شما مجاز به پرداخت این سفارش نیستید")
        }
        if (order.status != OrderStatus.PENDING) {
            throw ConflictException("فقط سفارش‌های در انتظار پرداخت (PENDING) قابل پرداخت هستند")
        }

        order.status = OrderStatus.PAID
        val saved = orderRepository.save(order)
        // Initialize lazy address inside transaction (open-in-view = false)
        saved.shippingAddress.fullAddress
        val items = orderItemRepository.findByOrderId(orderId)
        return OrderWithItems(order = saved, items = items)
    }

    @Transactional
    fun updateOrderStatus(orderId: Long, targetStatus: OrderStatus, actor: AuthenticatedActor): OrderWithItems {
        // Lock Order row first to serialize concurrent status transitions & prevent double restocking
        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw NotFoundException("سفارش یافت نشد")
        order.shippingAddress.fullAddress

        val items = orderItemRepository.findByOrderId(orderId)

        val isOwner = order.user.id == actor.userId
        val isInvolvedSeller = items.any { it.product.booth.seller.id == actor.userId }
        val isAdmin = actor.isAdmin()

        if (!isOwner && !isInvolvedSeller && !isAdmin) {
            throw ForbiddenException("شما مجاز به تغییر وضعیت این سفارش نیستید")
        }

        val currentStatus = order.status
        if (currentStatus == targetStatus) {
            return OrderWithItems(order = order, items = items)
        }

        val allowed = when (currentStatus to targetStatus) {
            OrderStatus.PENDING to OrderStatus.PAID -> isOwner || isAdmin
            OrderStatus.PENDING to OrderStatus.CANCELLED -> isOwner || isInvolvedSeller || isAdmin
            OrderStatus.PAID to OrderStatus.CANCELLED -> isOwner || isInvolvedSeller || isAdmin
            OrderStatus.PAID to OrderStatus.SHIPPED -> isInvolvedSeller || isAdmin
            OrderStatus.SHIPPED to OrderStatus.DELIVERED -> isInvolvedSeller || isAdmin
            else -> false
        }

        if (!allowed) {
            throw BadRequestException("تغییر وضعیت سفارش از $currentStatus به $targetStatus مجاز نیست یا دسترسی آن را ندارید")
        }

        // Refresh & lock each Product row in ascending ID order so L1 cache never holds stale stock counts
        if (targetStatus == OrderStatus.CANCELLED) {
            val uniqueProducts = items.map { it.product }.distinctBy { it.id }.sortedBy { it.id }
            uniqueProducts.forEach { entityManager.refresh(it, LockModeType.PESSIMISTIC_WRITE) }
            items.forEach { line ->
                line.product.restoreStock(line.selectedWarehouse, line.quantity)
            }
            productRepository.saveAll(uniqueProducts)
        }

        order.status = targetStatus
        val saved = orderRepository.save(order)
        return OrderWithItems(order = saved, items = items)
    }

    private fun buildCartSummary(userId: Long): CartSummaryView {
        val activeItems = cartItemRepository.findByUserId(userId)
            .filter { it.product.isActive }
            .map { item ->
                item.product.preloadTiers()
                val unitPrice = item.product.resolveUnitPrice(item.quantity, item.selectedWarehouse)
                CartItemView(
                    cartItem = item,
                    appliedUnitPrice = unitPrice,
                    lineTotal = unitPrice.multiply(BigDecimal(item.quantity))
                )
            }
        val total = activeItems.fold(BigDecimal.ZERO) { acc, item -> acc.add(item.lineTotal) }
        return CartSummaryView(items = activeItems, totalPrice = total)
    }
}