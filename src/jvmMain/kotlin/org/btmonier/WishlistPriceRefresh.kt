package org.btmonier

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.btmonier.database.DatabaseFactory
import org.btmonier.database.WishlistDao
import org.slf4j.LoggerFactory
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Periodic refresh of wishlist prices, run inside the web server.
 *
 * Every [AppSettings.wishlistPriceRefreshHours] (default 12) it re-scrapes the
 * items that have not been checked in the last
 * [AppSettings.wishlistPriceMinAgeHours]. A small random jitter is added to the
 * interval so restarts do not line refreshes up on the clock. Setting the
 * interval to 0 disables the job.
 */
object WishlistPriceRefresher {
    private val log = LoggerFactory.getLogger(WishlistPriceRefresher::class.java)

    /** Nobody is waiting on this pass, so it stays gentler than a manual one. */
    private const val BACKGROUND_CONCURRENCY = 2

    fun start(scope: CoroutineScope, service: WishlistPriceService): Job? {
        val intervalHours = AppSettings.wishlistPriceRefreshHours
        if (intervalHours <= 0.0) {
            log.info("Wishlist price refresh disabled (WISHLIST_PRICE_REFRESH_HOURS=0)")
            return null
        }

        log.info("Wishlist price refresh every ${intervalHours}h (items older than ${AppSettings.wishlistPriceMinAgeHours}h)")

        return scope.launch {
            // Let the server finish coming up before the first pass
            delay(2.minutes)
            while (isActive) {
                try {
                    val results = service.refreshDue(
                        minAgeHours = AppSettings.wishlistPriceMinAgeHours,
                        concurrency = BACKGROUND_CONCURRENCY
                    )
                    val failures = results.count { !it.succeeded }
                    val changes = results.sumOf { it.observationsAdded }
                    if (results.isNotEmpty()) {
                        log.info("Wishlist price refresh: ${results.size} checked, $changes price changes, $failures failures")
                    }
                } catch (e: Exception) {
                    log.warn("Wishlist price refresh failed: ${e.message}")
                }

                val jitterMinutes = Random.nextLong(0, 30)
                delay((intervalHours * 60).toLong().minutes + jitterMinutes.minutes)
            }
        }
    }
}

/**
 * CLI entry point for the `refreshWishlistPrices` Gradle task, so prices can
 * be refreshed from cron or Task Scheduler without the server running.
 */
class WishlistPriceRefreshCommand : CliktCommand(name = "refresh-wishlist-prices") {
    private val all by option("--all").flag().help("Include owned items, not just wanted and ordered ones")
    private val force by option("--force").flag().help("Ignore how recently each item was checked")
    private val limit by option("--limit").int().help("Only refresh the first N due items")
    private val dryRun by option("--dry-run").flag().help("List what would be refreshed without fetching anything")

    override fun run() = runBlocking {
        try {
            DatabaseFactory.init(skipReleaseMigration = true)
        } catch (e: Exception) {
            echo("Failed to initialize the database: ${e.message}", err = true)
            throw e
        }

        val dao = WishlistDao()
        val minAge = if (force) null else AppSettings.wishlistPriceMinAgeHours
        val due = WishlistPriceService.firstItems(dao.itemsDueForRefresh(minAge, includeAll = all), limit)
        val itemCount = due.map { it.itemId }.distinct().size

        if (due.isEmpty()) {
            echo("No wishlist items are due for a price check.")
            return@runBlocking
        }

        if (dryRun) {
            echo("$itemCount item(s), ${due.size} page(s), would be refreshed:")
            due.forEach { target -> echo("  #${target.itemId}  ${target.vendor ?: "blu-ray.com"}  ${target.url}") }
            return@runBlocking
        }

        echo("Refreshing $itemCount item(s) across ${due.size} page(s)...")
        val results = WishlistPriceService(dao).refreshDue(minAge, all, limit, onEach = { result ->
            val summary = when {
                !result.succeeded -> "FAILED: ${result.error}"
                result.observationsAdded == 0 -> "no change"
                else -> "${result.observationsAdded} new price(s)" +
                    (result.reportedPrice?.let { " - \$$it" } ?: "")
            }
            echo("  #${result.itemId}  ${result.sourceLabel}  $summary")
        })

        echo("Done: ${results.count { it.succeeded }} checked, ${results.count { !it.succeeded }} failed, " +
            "${results.sumOf { it.observationsAdded }} price change(s) recorded.")
    }
}

fun main(args: Array<String>) = WishlistPriceRefreshCommand().main(args)
