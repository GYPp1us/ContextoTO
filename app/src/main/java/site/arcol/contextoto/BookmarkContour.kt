package site.arcol.contextoto

/** Adjacent horizontal bands retain the first/last line's missing corners. */
internal data class BookmarkBand(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun offset(x: Float = 0f, y: Float = 0f) = copy(left = left + x, right = right + x, top = top + y, bottom = bottom + y)
}

internal fun interpolateBookmarkBands(from: List<BookmarkBand>, to: List<BookmarkBand>, progress: Float): List<BookmarkBand> {
    if (from.isEmpty()) return to
    if (to.isEmpty()) return from
    val t = progress.coerceIn(0f, 1f)
    val fromTop = from.first().top; val toTop = to.first().top
    val fromHeight = (from.last().bottom - fromTop).coerceAtLeast(1f)
    val toHeight = (to.last().bottom - toTop).coerceAtLeast(1f)
    val knots = (listOf(0f, 1f) + from.map { (it.bottom - fromTop) / fromHeight } + to.map { (it.bottom - toTop) / toHeight })
        .sorted().fold(mutableListOf<Float>()) { result, value ->
            if (result.isEmpty() || value - result.last() > .0001f) result += value.coerceIn(0f, 1f)
            result
        }
    val top = fromTop + (toTop - fromTop) * t
    val height = fromHeight + (toHeight - fromHeight) * t
    return knots.zipWithNext { start, end ->
        val mid = (start + end) * .5f
        val a = from.firstOrNull { mid < (it.bottom - fromTop) / fromHeight } ?: from.last()
        val b = to.firstOrNull { mid < (it.bottom - toTop) / toHeight } ?: to.last()
        BookmarkBand(a.left + (b.left - a.left) * t, top + start * height,
            a.right + (b.right - a.right) * t, top + end * height)
    }
}
