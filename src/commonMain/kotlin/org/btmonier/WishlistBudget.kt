package org.btmonier

import kotlin.random.Random

/**
 * Random selection of wishlist items that fit a budget, for deciding what to
 * buy this month without picking favourites.
 */

/** What a roll aims for once the budget is set. */
enum class BudgetGoal(val slug: String, val label: String) {
    /** Exactly the number of items asked for. */
    COUNT("count", "Set number of items"),
    /** As much of the budget spent as possible, however many items that takes. */
    FILL_BUDGET("fill", "Spend the most"),
    /** As many items as the budget covers. */
    MOST_ITEMS("most", "Most items");

    companion object {
        fun fromSlug(slug: String?): BudgetGoal? = entries.firstOrNull { it.slug == slug }
    }
}

/** Cent-level slack so a total that is exactly the budget is not rejected. */
private const val EPSILON = 1e-6

/** Random shuffles tried before falling back to the repair pass. */
private const val SHUFFLE_ATTEMPTS = 200

private val WishlistItem.price: Double
    get() = currentPrice ?: 0.0

/**
 * The outcome of one roll. When [requestedCount] is set, [picks] holds fewer
 * items only when that many could not fit; [minimumBudgetForCount] is then the
 * smallest budget that would have worked, or null when there are not even that
 * many priced items to choose from. When [requestedCount] is null, the roll
 * spends as much of the budget as it can with no target count.
 */
data class BudgetPickResult(
    val picks: List<WishlistItem>,
    val total: Double,
    val leftover: Double,
    val requestedCount: Int?,
    val eligibleCount: Int,
    val minimumBudgetForCount: Double? = null
) {
    /** True when the roll met what was asked for. */
    val isComplete: Boolean
        get() = when (requestedCount) {
            null -> picks.isNotEmpty() || eligibleCount == 0
            else -> picks.size == requestedCount && requestedCount > 0
        }
}

/**
 * Randomly choose items from [candidates] whose prices together stay within
 * [budget]. Items without a price are ignored. Pass a seeded [random] for a
 * repeatable result.
 *
 * When [count] is set, exactly that many items are picked when possible. When
 * [count] is null, as many items as fit are picked and the roll favors
 * spending as much of the budget as it can.
 *
 * When a fixed [count] cannot fit, the result holds as many as do, cheapest
 * first, so the caller can say how close the budget came.
 */
fun pickWithinBudget(
    candidates: List<WishlistItem>,
    count: Int?,
    budget: Double,
    random: Random = Random.Default
): BudgetPickResult {
    val eligible = candidates.filter { it.price > 0.0 }
    val wanted = count?.coerceAtLeast(0)

    fun result(picks: List<WishlistItem>, minimum: Double? = null): BudgetPickResult {
        val total = roundToCents(picks.sumOf { it.price })
        return BudgetPickResult(
            picks = picks,
            total = total,
            leftover = roundToCents(budget - total),
            requestedCount = wanted,
            eligibleCount = eligible.size,
            minimumBudgetForCount = minimum
        )
    }

    if (eligible.isEmpty() || budget <= 0.0) return result(emptyList())
    if (wanted == 0) return result(emptyList())

    if (wanted == null) {
        val picks = pickToMaximizeBudget(eligible, budget, random)
        val minimum = if (picks.isEmpty()) roundToCents(eligible.minOf { it.price }) else null
        return result(picks, minimum = minimum)
    }

    // The cheapest `wanted` items are the only combination worth testing: if
    // they do not fit, no other set of that size will either.
    val cheapestFirst = eligible.sortedBy { it.price }
    val cheapestSet = cheapestFirst.take(wanted)
    val cheapestTotal = roundToCents(cheapestSet.sumOf { it.price })

    if (cheapestSet.size < wanted || cheapestTotal > budget + EPSILON) {
        val fitting = greedyFill(cheapestFirst, wanted, budget)
        val minimum = if (cheapestSet.size < wanted) null else cheapestTotal
        return result(fitting, minimum = minimum)
    }

    repeat(SHUFFLE_ATTEMPTS) {
        val picks = greedyFill(eligible.shuffled(random), wanted, budget)
        if (picks.size == wanted) return result(picks)
    }

    // A budget that only a handful of combinations satisfy can defeat every
    // shuffle, so trade the priciest pick down until the set fits.
    return result(repairToFit(eligible, wanted, budget, random) ?: cheapestSet)
}

/**
 * Randomly choose the largest number of items from [candidates] that [budget]
 * can cover, so it buys as many physical units as possible. The count is set
 * by the cheapest items, but which items make up the set is random among every
 * set of that size that fits, so rolling again gives a different selection.
 *
 * The result's [BudgetPickResult.requestedCount] is that largest count. When
 * not even one item fits, it is null and [BudgetPickResult.minimumBudgetForCount]
 * is the cheapest item's price, the same as a roll with no count.
 */
fun pickMostWithinBudget(
    candidates: List<WishlistItem>,
    budget: Double,
    random: Random = Random.Default
): BudgetPickResult {
    val most = maxItemsWithinBudget(candidates, budget)
    return pickWithinBudget(candidates, if (most == 0) null else most, budget, random)
}

/** How many priced items [budget] can cover at most: the cheapest ones, taken in order. */
fun maxItemsWithinBudget(candidates: List<WishlistItem>, budget: Double): Int {
    var spent = 0.0
    var count = 0
    for (price in candidates.map { it.price }.filter { it > 0.0 }.sorted()) {
        if (spent + price > budget + EPSILON) break
        spent += price
        count++
    }
    return count
}

/** Pick as many items as fit while spending as much of [budget] as possible. */
private fun pickToMaximizeBudget(
    eligible: List<WishlistItem>,
    budget: Double,
    random: Random
): List<WishlistItem> {
    val cheapestPrice = eligible.minOf { it.price }
    if (cheapestPrice > budget + EPSILON) return emptyList()

    var best = greedyFill(eligible.sortedByDescending { it.price }, Int.MAX_VALUE, budget)
    var bestTotal = best.sumOf { it.price }

    repeat(SHUFFLE_ATTEMPTS) {
        val picks = greedyFill(eligible.shuffled(random), Int.MAX_VALUE, budget)
        val total = picks.sumOf { it.price }
        if (total > bestTotal + EPSILON) {
            best = picks
            bestTotal = total
        }
    }

    return best
}

/** Take items in the order given, skipping any that would break the budget. */
private fun greedyFill(order: List<WishlistItem>, wanted: Int, budget: Double): List<WishlistItem> {
    val picks = mutableListOf<WishlistItem>()
    var spent = 0.0
    for (item in order) {
        if (picks.size == wanted) break
        if (spent + item.price <= budget + EPSILON) {
            picks += item
            spent += item.price
        }
    }
    return picks
}

/**
 * Start from a random draw of [wanted] items and repeatedly swap its most
 * expensive pick for a random cheaper one, which strictly lowers the total, so
 * a feasible budget is always reached.
 */
private fun repairToFit(
    eligible: List<WishlistItem>,
    wanted: Int,
    budget: Double,
    random: Random
): List<WishlistItem>? {
    val shuffled = eligible.shuffled(random)
    val picks = shuffled.take(wanted).toMutableList()
    val rest = shuffled.drop(wanted).toMutableList()
    var total = picks.sumOf { it.price }

    while (total > budget + EPSILON) {
        val worstIndex = picks.indices.maxBy { picks[it].price }
        val worst = picks[worstIndex]
        val cheaper = rest.filter { it.price < worst.price }
        if (cheaper.isEmpty()) return null
        val replacement = cheaper.random(random)
        rest.remove(replacement)
        rest += worst
        picks[worstIndex] = replacement
        total += replacement.price - worst.price
    }
    return picks
}
