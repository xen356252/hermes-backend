package com.marketplace.backend.web

import com.marketplace.backend.domain.UnauthorizedException
import com.marketplace.backend.service.AuthenticatedActor

data class RequestContext(
    val requestId: String,
    val actor: AuthenticatedActor? = null
)

object RequestContextHolder {
    private val context = ThreadLocal<RequestContext>()

    fun set(ctx: RequestContext) = context.set(ctx)
    fun get(): RequestContext? = context.get()
    fun clear() = context.remove()

    fun requireActor(): AuthenticatedActor =
        context.get()?.actor ?: throw UnauthorizedException("برای انجام این عملیات ابتدا وارد حساب کاربری شوید")
}