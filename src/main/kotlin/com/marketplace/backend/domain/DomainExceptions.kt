package com.marketplace.backend.domain

sealed class DomainException(override val message: String) : RuntimeException(message)

class BadRequestException(message: String) : DomainException(message)
class UnauthorizedException(message: String) : DomainException(message)
class ForbiddenException(message: String) : DomainException(message)
class NotFoundException(message: String) : DomainException(message)
class ConflictException(message: String) : DomainException(message)