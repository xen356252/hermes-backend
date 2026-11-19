package com.marketplace.backend.web

import com.marketplace.backend.domain.*
import jakarta.persistence.OptimisticLockException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import java.time.Instant

data class ApiErrorResponse(
    val timestamp: String = Instant.now().toString(),
    val status: Int,
    val error: String,
    val message: String,
    val requestId: String? = RequestContextHolder.get()?.requestId
)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun handleDomainException(ex: DomainException): ResponseEntity<ApiErrorResponse> {
        val status = when (ex) {
            is BadRequestException -> HttpStatus.BAD_REQUEST
            is UnauthorizedException -> HttpStatus.UNAUTHORIZED
            is ForbiddenException -> HttpStatus.FORBIDDEN
            is NotFoundException -> HttpStatus.NOT_FOUND
            is ConflictException -> HttpStatus.CONFLICT
        }
        return ResponseEntity.status(status).body(
            ApiErrorResponse(status = status.value(), error = status.name, message = ex.message)
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(ex: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
        val msg = ex.bindingResult.fieldErrors
            .firstOrNull()?.defaultMessage ?: "ورودی ارسال‌شده نامعتبر است"
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(status = 400, error = "VALIDATION_ERROR", message = msg)
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(status = 400, error = "MALFORMED_JSON", message = "ساختار JSON ارسال‌شده معتبر نیست")
        )

    @ExceptionHandler(
        MissingServletRequestParameterException::class,
        MissingServletRequestPartException::class,
        MethodArgumentTypeMismatchException::class
    )
    fun handleBadRequestParameters(ex: Exception): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(status = 400, error = "BAD_REQUEST", message = "پارامتر یا فایل ارسال‌شده معتبر نیست")
        )

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
            ApiErrorResponse(status = 405, error = "METHOD_NOT_ALLOWED", message = "متد HTTP استفاده‌شده برای این مسیر مجاز نیست")
        )

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(ex: HttpMediaTypeNotSupportedException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(
            ApiErrorResponse(status = 415, error = "UNSUPPORTED_MEDIA_TYPE", message = "فرمت Content-Type درخواست پشتیبانی نمی‌شود")
        )

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSize(ex: MaxUploadSizeExceededException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.valueOf(413)).body(
            ApiErrorResponse(status = 413, error = "PAYLOAD_TOO_LARGE", message = "حجم فایل ارسالی بیش از حد مجاز (۵ مگابایت) است")
        )

    @ExceptionHandler(OptimisticLockException::class, ObjectOptimisticLockingFailureException::class)
    fun handleConcurrencyConflict(ex: Exception): ResponseEntity<ApiErrorResponse> {
        logger.warn("Optimistic lock conflict: {}", ex.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(status = 409, error = "CONFLICT", message = "تداخل هم‌زمانی رخ داد؛ لطفاً دوباره تلاش کنید")
        )
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Database integrity constraint violated: {}", ex.mostSpecificCause.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(status = 409, error = "DATA_CONFLICT", message = "عملیات با قوانین یکپارچگی داده‌ها تداخل دارد")
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(ex: Exception): ResponseEntity<ApiErrorResponse> {
        logger.error("Unhandled server exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(status = 500, error = "INTERNAL_SERVER_ERROR", message = "خطای غیرمنتظره در سرور رخ داد")
        )
    }
}