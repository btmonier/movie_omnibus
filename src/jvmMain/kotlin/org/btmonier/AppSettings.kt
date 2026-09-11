package org.btmonier

import java.io.File
import java.util.Properties

/**
 * Small runtime settings that are not database credentials. Each value is
 * resolved as environment variable, then `database.properties`, then default.
 */
object AppSettings {
    private val props: Properties? by lazy {
        val file = File("database.properties")
        if (file.exists()) Properties().apply { file.inputStream().use { load(it) } } else null
    }

    /**
     * Sales tax rate applied to purchases when none is given. Defaults to Iowa's
     * 6%. Set `DEFAULT_TAX_RATE=0.07` or `tax.rate=0.07` to change it.
     */
    val defaultTaxRate: Double by lazy {
        (System.getenv("DEFAULT_TAX_RATE") ?: props?.getProperty("tax.rate"))
            ?.trim()
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 && it < 1.0 }
            ?: DEFAULT_TAX_RATE
    }

    /**
     * Hours between automatic wishlist price refreshes. `0` disables the
     * background job. Set `WISHLIST_PRICE_REFRESH_HOURS` or
     * `wishlist.priceRefreshHours`.
     */
    val wishlistPriceRefreshHours: Double by lazy {
        (System.getenv("WISHLIST_PRICE_REFRESH_HOURS") ?: props?.getProperty("wishlist.priceRefreshHours"))
            ?.trim()
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 }
            ?: 12.0
    }

    /**
     * Items checked more recently than this many hours ago are skipped by the
     * automatic refresh.
     */
    val wishlistPriceMinAgeHours: Double by lazy {
        (System.getenv("WISHLIST_PRICE_MIN_AGE_HOURS") ?: props?.getProperty("wishlist.priceMinAgeHours"))
            ?.trim()
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 }
            ?: 6.0
    }

    /**
     * How recently an item must have been checked for "Refresh all prices" to
     * skip it. Lower than [wishlistPriceMinAgeHours] because pressing the
     * button means wanting fresh numbers; `0` re-checks everything. Set
     * `WISHLIST_PRICE_MANUAL_MIN_AGE_HOURS` or
     * `wishlist.priceManualMinAgeHours`.
     */
    val wishlistPriceManualMinAgeHours: Double by lazy {
        (System.getenv("WISHLIST_PRICE_MANUAL_MIN_AGE_HOURS") ?: props?.getProperty("wishlist.priceManualMinAgeHours"))
            ?.trim()
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0.0 }
            ?: 1.0
    }

    /**
     * How many blu-ray.com pages a refresh pass fetches at once. Dial down to
     * 1 to go back to one request at a time. Set
     * `WISHLIST_PRICE_CONCURRENCY` or `wishlist.priceConcurrency`.
     */
    val wishlistPriceConcurrency: Int by lazy {
        (System.getenv("WISHLIST_PRICE_CONCURRENCY") ?: props?.getProperty("wishlist.priceConcurrency"))
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it in 1..16 }
            ?: 4
    }
}
