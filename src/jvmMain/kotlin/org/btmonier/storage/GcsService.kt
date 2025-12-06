package org.btmonier.storage

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Service for interacting with Google Cloud Storage.
 * Provides signed URL generation for private bucket objects.
 */
class GcsService private constructor(
    private val storage: Storage,
    private val bucketName: String,
    private val signedUrlDurationMinutes: Long
) {
    private val logger = LoggerFactory.getLogger(GcsService::class.java)

    companion object {
        private const val CONFIG_FILE = "gcs.properties"
        private const val DEFAULT_DURATION_MINUTES = 60L

        /**
         * Create a GcsService instance from configuration file.
         * Returns null if configuration is missing or invalid.
         */
        fun createFromConfig(): GcsService? {
            val logger = LoggerFactory.getLogger(GcsService::class.java)
            val configFile = File(CONFIG_FILE)

            if (!configFile.exists()) {
                logger.info("GCS configuration file not found: $CONFIG_FILE. GCS integration disabled.")
                return null
            }

            val props = Properties()
            try {
                configFile.inputStream().use { props.load(it) }
            } catch (e: Exception) {
                logger.error("Failed to load GCS configuration: ${e.message}")
                return null
            }

            val bucketName = props.getProperty("gcs.bucket")
            if (bucketName.isNullOrBlank()) {
                logger.error("GCS bucket name not configured in $CONFIG_FILE")
                return null
            }

            val credentialsPath = System.getenv(
                props.getProperty("gcs.credentials.path")
            )
            if (credentialsPath.isNullOrBlank()) {
                logger.error("GCS credentials path not configured in $CONFIG_FILE")
                return null
            }

            val credentialsFile = File(credentialsPath)
            if (!credentialsFile.exists()) {
                logger.error("GCS credentials file not found: $credentialsPath")
                return null
            }

            val durationMinutes = props.getProperty("gcs.signed.url.duration.minutes")
                ?.toLongOrNull() ?: DEFAULT_DURATION_MINUTES

            return try {
                val credentials = FileInputStream(credentialsFile).use { stream ->
                    GoogleCredentials.fromStream(stream)
                }

                val storage = StorageOptions.newBuilder()
                    .setCredentials(credentials)
                    .build()
                    .service

                logger.info("GCS service initialized for bucket: $bucketName")
                GcsService(storage, bucketName, durationMinutes)
            } catch (e: Exception) {
                logger.error("Failed to initialize GCS service: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Check if a URL/path is a GCS path that needs signing.
     * Recognizes formats:
     * - gs://bucket/path/to/object
     * - bucket/path/to/object (when bucket matches configured bucket)
     * - path/to/object (relative paths assumed to be in configured bucket)
     */
    fun isGcsPath(url: String): Boolean {
        if (url.isBlank()) return false
        
        // Already an HTTP(S) URL - not a GCS path
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return false
        }
        
        // Explicit GCS URL
        if (url.startsWith("gs://")) {
            return true
        }
        
        // Assume relative paths or bucket/path format are GCS paths
        // if they look like object paths (contain at least one path component)
        return url.contains("/") || url.contains(".")
    }

    /**
     * Extract the object path from a GCS URL or path.
     * Handles:
     * - gs://bucket/path/to/object -> path/to/object
     * - bucket/path/to/object -> path/to/object (if bucket matches)
     * - path/to/object -> path/to/object (relative path)
     */
    private fun extractObjectPath(url: String): String {
        // Handle gs:// URLs
        if (url.startsWith("gs://")) {
            val pathWithoutScheme = url.removePrefix("gs://")
            val slashIndex = pathWithoutScheme.indexOf('/')
            return if (slashIndex >= 0) {
                pathWithoutScheme.substring(slashIndex + 1)
            } else {
                pathWithoutScheme
            }
        }

        // Handle bucket/path format
        if (url.startsWith("$bucketName/")) {
            return url.removePrefix("$bucketName/")
        }

        // Assume it's already a relative object path
        return url
    }

    /**
     * Generate a signed URL for a GCS object.
     * The URL will be valid for the configured duration.
     *
     * @param objectPath The path to the object (can be gs:// URL, bucket/path, or relative path)
     * @return Signed URL string, or the original path if signing fails
     */
    fun generateSignedUrl(objectPath: String): String {
        if (!isGcsPath(objectPath)) {
            return objectPath
        }

        return try {
            val cleanPath = extractObjectPath(objectPath)
            val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, cleanPath)).build()

            val signedUrl: URL = storage.signUrl(
                blobInfo,
                signedUrlDurationMinutes,
                TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature()
            )

            logger.debug("Generated signed URL for: $cleanPath")
            signedUrl.toString()
        } catch (e: Exception) {
            logger.error("Failed to generate signed URL for: $objectPath - ${e.message}")
            objectPath // Return original path as fallback
        }
    }

    /**
     * Transform a URL to a signed URL if it's a GCS path.
     * Non-GCS URLs (regular HTTP URLs) are returned unchanged.
     *
     * @param url The URL to potentially transform
     * @return Signed URL if GCS path, original URL otherwise
     */
    fun transformUrl(url: String): String {
        return if (isGcsPath(url)) {
            generateSignedUrl(url)
        } else {
            url
        }
    }

    /**
     * Check if a URL is a signed GCS URL that we generated.
     * Signed URLs look like: https://storage.googleapis.com/bucket/path?X-Goog-Algorithm=...
     */
    fun isSignedGcsUrl(url: String): Boolean {
        return url.startsWith("https://storage.googleapis.com/") && 
               url.contains("X-Goog-")
    }

    /**
     * Extract the original storable path from a signed GCS URL.
     * Converts https://storage.googleapis.com/bucket-name/path/to/object?... back to path/to/object
     *
     * @param url The URL to clean (can be a signed URL, regular URL, or GCS path)
     * @return The storable path (without query parameters and bucket prefix)
     */
    fun cleanUrlForStorage(url: String): String {
        if (!isSignedGcsUrl(url)) {
            return url
        }

        // Remove query parameters
        val urlWithoutParams = url.substringBefore("?")
        
        // Extract path from https://storage.googleapis.com/bucket-name/path/to/object
        val prefix = "https://storage.googleapis.com/$bucketName/"
        return if (urlWithoutParams.startsWith(prefix)) {
            urlWithoutParams.removePrefix(prefix)
        } else {
            // Fallback: just strip query params
            urlWithoutParams
        }
    }
}

