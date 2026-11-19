package com.marketplace.backend.service

import com.marketplace.backend.config.AppProperties
import com.marketplace.backend.domain.BadRequestException
import com.marketplace.backend.domain.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

data class DetectedImageType(
    val extension: String,
    val mimeType: String
)

data class StoredMedia(
    val filename: String,
    val url: String,
    val contentType: String,
    val sizeBytes: Long
)

data class LoadedMedia(
    val bytes: ByteArray,
    val contentType: String
)

object ImageFormatDetector {
    fun detect(bytes: ByteArray): DetectedImageType? {
        if (bytes.size < 12) return null

        // JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return DetectedImageType("jpg", "image/jpeg")
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) {
            return DetectedImageType("png", "image/png")
        }

        // WEBP: "RIFF" .... "WEBP"
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return DetectedImageType("webp", "image/webp")
        }

        return null
    }
}

interface MediaStorageService {
    fun storeImage(bytes: ByteArray): StoredMedia
    fun loadImage(filename: String): LoadedMedia
}

@Service
class LocalMediaStorageService(
    private val appProperties: AppProperties
) : MediaStorageService {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val safeFilenameRegex = Regex("^[a-f0-9-]{36}\\.(jpg|png|webp)$")
    private val uploadRoot: Path = Paths.get(appProperties.media.uploadDir).toAbsolutePath().normalize()

    override fun storeImage(bytes: ByteArray): StoredMedia {
        if (bytes.isEmpty()) {
            throw BadRequestException("فایل تصویر ارسال‌شده خالی است")
        }
        if (bytes.size.toLong() > appProperties.media.maxFileSizeBytes) {
            val maxMb = appProperties.media.maxFileSizeBytes / (1024 * 1024)
            throw BadRequestException("حجم تصویر نمی‌تواند بیشتر از $maxMb مگابایت باشد")
        }

        val detected = ImageFormatDetector.detect(bytes)
            ?: throw BadRequestException("فرمت فایل مجاز نیست؛ فقط تصاویر JPEG، PNG و WEBP پشتیبانی می‌شوند")

        Files.createDirectories(uploadRoot)
        val filename = "${UUID.randomUUID()}.${detected.extension}"
        val targetPath = uploadRoot.resolve(filename).normalize()

        if (!targetPath.startsWith(uploadRoot)) {
            throw BadRequestException("مسیر ذخیره‌سازی نامعتبر است")
        }

        Files.write(targetPath, bytes)
        logger.info("Stored media file {} ({} bytes)", filename, bytes.size)

        return StoredMedia(
            filename = filename,
            url = "/api/v1/media/$filename",
            contentType = detected.mimeType,
            sizeBytes = bytes.size.toLong()
        )
    }

    override fun loadImage(filename: String): LoadedMedia {
        val cleanName = filename.trim().lowercase()
        if (!safeFilenameRegex.matches(cleanName)) {
            throw NotFoundException("تصویر یافت نشد")
        }

        val filePath = uploadRoot.resolve(cleanName).normalize()
        if (!filePath.startsWith(uploadRoot) || !Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw NotFoundException("تصویر یافت نشد")
        }

        val contentType = when {
            cleanName.endsWith(".png") -> "image/png"
            cleanName.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }

        return LoadedMedia(bytes = Files.readAllBytes(filePath), contentType = contentType)
    }
}