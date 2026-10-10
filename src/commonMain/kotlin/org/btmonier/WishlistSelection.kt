package org.btmonier

/**
 * Shift+click range selection over the wishlist cards, in the order they are
 * drawn. An item can be drawn more than once (under several tags or formats),
 * so ranges are measured between drawn positions rather than between ids.
 */

/** Ids drawn between two positions inclusive, in either direction, deduplicated. */
fun selectionRange(renderOrder: List<Int>, anchorPos: Int, targetPos: Int): Set<Int> {
    if (renderOrder.isEmpty()) return emptySet()
    val last = renderOrder.lastIndex
    val from = minOf(anchorPos, targetPos).coerceIn(0, last)
    val to = maxOf(anchorPos, targetPos).coerceIn(0, last)
    return renderOrder.subList(from, to + 1).toSet()
}

/**
 * A shift-click from [anchorPos] to [targetPos]: the whole range takes the
 * anchor item's current state, so a range from a card just deselected
 * deselects. An anchor outside the drawn cards changes nothing.
 */
fun applyRange(selected: Set<Int>, renderOrder: List<Int>, anchorPos: Int, targetPos: Int): Set<Int> {
    val anchorId = renderOrder.getOrNull(anchorPos) ?: return selected
    val range = selectionRange(renderOrder, anchorPos, targetPos)
    return if (anchorId in selected) selected + range else selected - range
}
