package com.marketplace.backend.service

import com.marketplace.backend.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.concurrent.ThreadLocalRandom

interface SmsGateway {
    fun isConfigured(): Boolean
    fun dispatchOtpAsync(phone: String, code: String)
}

@Service
class SmsIrSandboxGateway(
    private val appProperties: AppProperties
) : SmsGateway {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val restClient: RestClient = RestClient.builder().build()

    private data class SmsParameter(val name: String, val value: String)
    private data class SmsIrVerifyPayload(
        val mobile: String,
        val templateId: Int,
        val parameters: List<SmsParameter>
    )

    override fun isConfigured(): Boolean = appProperties.sms.apiKey.isNotBlank()

    @Async("notificationExecutor")
    override fun dispatchOtpAsync(phone: String, code: String) {
        val apiKey = appProperties.sms.apiKey.trim()
        if (apiKey.isBlank()) {
            logger.info("SMS.ir API key not configured; skipping external SMS dispatch for phone ending in {}", phone.takeLast(4))
            return
        }

        val payload = SmsIrVerifyPayload(
            mobile = phone,
            templateId = appProperties.sms.templateId,
            parameters = listOf(SmsParameter("CODE", code))
        )

        val maxAttempts = appProperties.sms.maxRetries.coerceAtLeast(1)
        var lastError: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                restClient.post()
                    .uri(appProperties.sms.baseUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-API-KEY", apiKey)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity()

                logger.info("OTP dispatched via SMS.ir for phone ending in {} on attempt {}", phone.takeLast(4), attempt)
                return
            } catch (ex: Exception) {
                lastError = ex
                logger.warn(
                    "SMS.ir dispatch attempt {}/{} failed for phone ending in {}: {}",
                    attempt, maxAttempts, phone.takeLast(4), ex.message
                )
                if (attempt < maxAttempts) {
                    val backoffMs = (500L shl (attempt - 1)) + ThreadLocalRandom.current().nextLong(100, 350)
                    try {
                        Thread.sleep(backoffMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        logger.error(
            "DLQ_SMS_FAILURE: Exhausted {} retries sending OTP to phone ending in {}. Last error: {}",
            maxAttempts, phone.takeLast(4), lastError?.message
        )
    }
}