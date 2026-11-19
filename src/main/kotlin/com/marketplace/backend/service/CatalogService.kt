package com.marketplace.backend.service

import com.marketplace.backend.domain.*
import com.marketplace.backend.repository.*
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class CategoryNode(
    val category: Category,
    val children: List<Category>
)

data class VitrineData(
    val mode: String,
    val welcomeMessage: String,
    val banners: List<Banner>,
    val categoryTree: List<CategoryNode>,
    val hotDeals: List<Product>,
    val recommendedProducts: List<Product>
)

data class ProductSearchFilter(
    val query: String?,
    val categoryId: Long?,
    val minPrice: BigDecimal?,
    val maxPrice: BigDecimal?,
    val onlyHotDeals: Boolean?,
    val saleType: SaleType?,
    val warehouseOrigin: WarehouseOrigin?,
    val page: Int = 0,
    val size: Int = 50
)

@Service
class CatalogService(
    private val bannerRepository: BannerRepository,
    private val categoryRepository: CategoryRepository,
    private val productRepository: ProductRepository,
    private val userRepository: UserRepository,
    private val wishlistItemRepository: WishlistItemRepository
) {
    @Transactional(readOnly = true)
    fun getCategoryTree(): List<CategoryNode> {
        val all = categoryRepository.findAll()
        val childrenByParentId = all.filter { it.parent != null }.groupBy { it.parent!!.id }
        return all.filter { it.parent == null }.map { root ->
            CategoryNode(category = root, children = childrenByParentId[root.id].orEmpty())
        }
    }

    @Transactional(readOnly = true)
    fun getGuestVitrine(): VitrineData {
        val topPage = PageRequest.of(0, 12)
        return VitrineData(
            mode = "GUEST",
            welcomeMessage = "هرمس",
            banners = bannerRepository.findByIsActiveTrue(),
            categoryTree = getCategoryTree(),
            hotDeals = productRepository.findByIsHotDealTrueAndIsActiveTrue(topPage).preloadTiers(),
            recommendedProducts = productRepository.findByIsActiveTrueOrderByCreatedAtDesc(topPage).preloadTiers()
        )
    }

    @Transactional(readOnly = true)
    fun getPersonalizedVitrine(userId: Long, actor: AuthenticatedActor): VitrineData {
        if (actor.userId != userId && !actor.isAdmin()) {
            throw ForbiddenException("شما مجاز به مشاهده ویترین شخصی کاربر دیگر نیستید")
        }
        val user = userRepository.findById(userId).orElseThrow { NotFoundException("کاربر یافت نشد") }
        val topPage = PageRequest.of(0, 24)
        val wishedCategories = wishlistItemRepository.findByUserIdAndProductIsActiveTrue(userId)
            .map { it.product.category.id }
            .toSet()

        val recentProducts = productRepository.findByIsActiveTrueOrderByCreatedAtDesc(topPage).preloadTiers()
        val tailored = if (wishedCategories.isNotEmpty()) {
            recentProducts.sortedByDescending { wishedCategories.contains(it.category.id) }
        } else {
            recentProducts
        }

        return VitrineData(
            mode = "PERSONALIZED",
            welcomeMessage = "سلام ${user.fullName} عزیز",
            banners = bannerRepository.findByIsActiveTrue(),
            categoryTree = getCategoryTree(),
            hotDeals = productRepository.findByIsHotDealTrueAndIsActiveTrue(topPage).preloadTiers(),
            recommendedProducts = tailored
        )
    }

    @Transactional(readOnly = true)
    fun getActiveBanners(): List<Banner> = bannerRepository.findByIsActiveTrue()

    @Transactional
    fun createBanner(title: String?, imageUrl: String, targetUrl: String?): Banner {
        val cleanTitle = title?.trim().orEmpty()
        val cleanImage = imageUrl.trim()
        if (cleanTitle.isBlank() && cleanImage.isBlank()) {
            throw BadRequestException("تصویر یا متن بنر الزامی است")
        }
        return bannerRepository.save(
            Banner(title = cleanTitle, imageUrl = cleanImage, targetUrl = targetUrl?.trim(), isActive = true)
        )
    }

    @Transactional
    fun deleteBanner(bannerId: Long) {
        if (bannerRepository.existsById(bannerId)) {
            bannerRepository.deleteById(bannerId)
        }
    }

    @Transactional(readOnly = true)
    fun getProductsByCategory(categoryId: Long): List<Product> {
        val subIds = categoryRepository.findByParentId(categoryId).map { it.id }
        val targetIds = subIds + categoryId
        return productRepository.findByCategoryIdInAndIsActiveTrue(targetIds).preloadTiers()
    }

    @Transactional(readOnly = true)
    fun searchProducts(filter: ProductSearchFilter): Page<Product> {
        val targetCategoryIds = filter.categoryId?.let { catId ->
            categoryRepository.findByParentId(catId).map { it.id } + catId
        }

        val spec = Specification<Product> { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            predicates.add(cb.isTrue(root.get("isActive")))

            filter.query?.trim()?.takeIf { it.isNotBlank() }?.let { q ->
                val pattern = "%${q.lowercase()}%"
                val categoryJoin = root.join<Product, Category>("category")
                predicates.add(
                    cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern),
                        cb.like(cb.lower(categoryJoin.get("name")), pattern)
                    )
                )
            }

            if (targetCategoryIds != null) {
                predicates.add(root.get<Category>("category").get<Long>("id").`in`(targetCategoryIds))
            }

            filter.minPrice?.let { min ->
                predicates.add(
                    cb.greaterThanOrEqualTo(
                        cb.coalesce(root.get<BigDecimal>("discountPrice"), root.get("price")),
                        min
                    )
                )
            }

            filter.maxPrice?.let { max ->
                predicates.add(
                    cb.lessThanOrEqualTo(
                        cb.coalesce(root.get<BigDecimal>("discountPrice"), root.get("price")),
                        max
                    )
                )
            }

            if (filter.onlyHotDeals == true) {
                predicates.add(cb.isTrue(root.get("isHotDeal")))
            }

            filter.saleType?.let { type ->
                predicates.add(cb.equal(root.get<SaleType>("saleType"), type))
            }

            when (filter.warehouseOrigin) {
                WarehouseOrigin.IRAN -> predicates.add(cb.greaterThan(root.get("iranStockQuantity"), 0))
                WarehouseOrigin.CHINA -> predicates.add(cb.greaterThan(root.get("chinaStockQuantity"), 0))
                null -> {}
            }

            cb.and(*predicates.toTypedArray())
        }

        val safePage = filter.page.coerceAtLeast(0)
        val safeSize = filter.size.coerceIn(1, 100)
        val pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        val pageResult = productRepository.findAll(spec, pageable)
        pageResult.content.preloadTiers()
        return pageResult
    }
}