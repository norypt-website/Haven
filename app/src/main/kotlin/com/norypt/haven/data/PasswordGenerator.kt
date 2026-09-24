package com.norypt.haven.data

import java.security.SecureRandom

/**
 * Cryptographically secure password/passphrase generator (SecureRandom; rejection sampling so
 * every symbol is equally likely). The word list is bundled (no network).
 */
object PasswordGenerator {
    private val random = SecureRandom()

    data class Options(
        val length: Int = 20,
        val lower: Boolean = true,
        val upper: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val excludeAmbiguous: Boolean = true,
    )

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/"
    private const val AMBIGUOUS = "O0Il1|`'\""

    fun generate(options: Options): CharArray {
        val sets = buildList {
            if (options.lower) add(LOWER)
            if (options.upper) add(UPPER)
            if (options.digits) add(DIGITS)
            if (options.symbols) add(SYMBOLS)
        }.map { s -> if (options.excludeAmbiguous) s.filterNot { it in AMBIGUOUS } else s }
        require(sets.isNotEmpty()) { "select at least one character class" }
        val len = options.length.coerceIn(8, 128)
        val alphabet = sets.joinToString("")
        while (true) {
            val out = CharArray(len) { alphabet[nextIndex(alphabet.length)] }
            // Ensure every selected class appears at least once (retry otherwise).
            if (sets.all { set -> out.any { it in set } }) return out
        }
    }

    /** Diceware-style passphrase from the bundled EFF-style short list; entropy = words * log2(list size). */
    fun passphrase(words: Int, separator: String = " "): CharArray {
        val n = words.coerceIn(4, 12)
        val list = WordList.WORDS
        val parts = List(n) { list[nextIndex(list.size)] }
        return parts.joinToString(separator).toCharArray()
    }

    fun entropyBits(options: Options): Double {
        val size = buildList {
            if (options.lower) add(LOWER); if (options.upper) add(UPPER); if (options.digits) add(DIGITS); if (options.symbols) add(SYMBOLS)
        }.map { s -> if (options.excludeAmbiguous) s.filterNot { it in AMBIGUOUS } else s }.sumOf { it.length }
        if (size == 0) return 0.0
        return options.length * (Math.log(size.toDouble()) / Math.log(2.0))
    }

    fun passphraseEntropyBits(words: Int): Double = words * (Math.log(WordList.WORDS.size.toDouble()) / Math.log(2.0))

    private fun nextIndex(bound: Int): Int = random.nextInt(bound) // SecureRandom.nextInt(bound) is unbiased
}
