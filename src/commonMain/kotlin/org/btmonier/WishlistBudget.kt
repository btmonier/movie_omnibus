package org.btmonier

import kotlin.random.Random

/**
 * Random selection of wishlist items that fit a budget, for deciding what to
 * buy this month without picking favourites.
 */

/** Cent-level slack so a total that is exactly the budget is not rejected. */
private const val EPSILON = 1e-6

/** Random shuffles tried before falling back to the repair pass. */
private const val SHUFFLE_ATTEMPTS = 200

private val WishlistItem.price: Double
    get() = currentPrice ?: 0.0

/**
 * The outcome of one roll. [picks] holds fewer than [requestedCount] items only
 * when that many could not fit; [minimumBudgetForCount] is then the smallest
 * budget that would have worked, or null when there are not even that many
 * priced items to choose from.
 */
data class BudgetPickResult(
    val picks: List<WishlistItem>,
    val total: Double,
    val leftover: Double,
    val requestedCount: Int,
    val eligibleCount: Int,
    val minimumBudgetForCount: Double? = null
) {
    /** True when the roll produced the number of items that was asked for. */
    val isComplete: Boolean
        get() = picks.size == requestedCount && requestedCount > 0
}

/**
 * Randomly choose [count] of [candidates] whose prices together stay within
 * [budget]. Items without a price are ignored. Pass a seeded [random] for a
 * repeatable result.
 *
 * When [count] items cannot fit, the result holds as many as do, cheapest
 * first, so the caller can say how close the budget came.
 */
fun pickWithinBudget(
    candidates: List<WishlistItem>,
    count: Int,
    budget: Double,
    random: Random = Random.Default
): BudgetPickResult {
    val eligible = candidates.filter { it.price > 0.0 }
    val wanted = count.coerceAtLeast(0)

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

    if (wanted == 0 || eligible.isEmpty() || budget <= 0.0) return result(emptyList())

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
