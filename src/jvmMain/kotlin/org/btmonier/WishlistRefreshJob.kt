package org.btmonier

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * A "refresh all prices" pass running in the background so the client can show
 * how far along it is.
 *
 * A bulk refresh takes a page fetch per source, spread out on purpose to stay
 * polite, which puts it well past the point where one HTTP request can hold
 * the answer. So the request starts a pass and returns, and progress is read
 * back until the pass reports itself done.
 *
 * Only one pass runs at a time: a second "refresh all" while one is in flight
 * would check the same pages twice, so asking again just returns the pass
 * already running.
 */
object WishlistRefreshJob {
    private val log = LoggerFactory.getLogger(WishlistRefreshJob::class.java)

    private val current = AtomicReference<Progress?>(null)

    /**
     * How far one pass has got. [total] is pages rather than items, because a
     * page is what takes the time; [checkedItems] counts the things the user
     * asked about. [lastSource] names the store whose page came back most
     * recently, which is the one detail that makes the count feel alive.
     */
    data class Progress(
        val jobId: Long,
        val total: Int,
        val completed: Int = 0,
        val checkedItems: Int = 0,
        val failed: Int = 0,
        val priceChanges: Int = 0,
        val skipped: Int = 0,
        val lastSource: String? = null,
        val done: Boolean = false,
        val errors: List<String> = emptyList()
    )

    /** The pass currently running, or the last one to finish. */
    fun progress(): Progress? = current.get()

    /**
     * Start a pass, unless one is already running. Returns the progress of
     * whichever pass is now in flight, paired with whether it was already
     * going before this call.
     */
    suspend fun start(
        scope: CoroutineScope,
        service: WishlistPriceService,
        dao: org.btmonier.database.WishlistDao,
        force: Boolean
    ): Pair<Progress, Boolean> {
        current.get()?.takeIf { !it.done }?.let { return it to true }

        val minAge = if (force) null else AppSettings.wishlistPriceManualMinAgeHours.takeIf { it > 0.0 }

        // Asked twice on purpose: what is due now gives the page count to show
        // progress against, and what is eligible regardless of age gives how
        // many were left alone for having just been checked. Counted in items
        // rather than pages, because an item tracked at three stores was still
        // only one thing the user asked about.
        val due = dao.itemsDueForRefresh(minAgeHours = minAge)
        val eligible = dao.itemsDueForRefresh(minAgeHours = null).map { it.itemId }.distinct().size

        val started = Progress(
            jobId = System.currentTimeMillis(),
            total = due.size,
            skipped = (eligible - due.map { it.itemId }.distinct().size).coerceAtLeast(0)
        )
        current.set(started)

        if (due.isEmpty()) {
            current.set(started.copy(done = true))
            return current.get()!! to false
        }

        scope.launch { run(service, minAge) }
        return started to false
    }

    private suspend fun run(service: WishlistPriceService, minAgeHours: Double?) {
        val checkedItems = mutableSetOf<Int>()
        try {
            service.refreshDue(minAgeHours = minAgeHours, onEach = { result ->
                checkedItems += result.itemId
                current.updateAndGet { progress ->
                    progress?.copy(
                        completed = progress.completed + 1,
                        checkedItems = checkedItems.size,
                        failed = progress.failed + if (result.succeeded) 0 else 1,
                        priceChanges = progress.priceChanges + result.observationsAdded,
                        lastSource = result.sourceLabel,
                        errors = if (result.succeeded) progress.errors
                        else progress.errors + "#${result.itemId} ${result.sourceLabel}: ${result.error}"
                    )
                }
            })
        } catch (e: Exception) {
            log.warn("Bulk wishlist price refresh failed: ${e.message}")
            current.updateAndGet { progress ->
                progress?.copy(errors = progress.errors + (e.message ?: "Refresh failed"))
            }
        } finally {
            current.updateAndGet { it?.copy(done = true) }
        }
    }
}
