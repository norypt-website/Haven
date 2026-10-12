package com.norypt.haven.photos

/** Size checks done before any full decode (decompression-bomb guard). Pure, unit tested. */
object ImageBounds {
    const val MAX_BYTES: Long = 64L * 1024 * 1024
    const val MAX_PIXELS: Long = 100_000_000L

    fun check(width: Int, height: Int): Boolean = width > 0 && height > 0 && width.toLong() * height.toLong() <= MAX_PIXELS

    /** Largest power of two that keeps the longest side at least [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var s = 1
        val longest = maxOf(width, height)
        while (longest / (s * 2) >= maxSide) s *= 2
        return s
    }

    fun fit(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return Math.round(width * scale).toInt().coerceAtLeast(1) to Math.round(height * scale).toInt().coerceAtLeast(1)
    }
}
