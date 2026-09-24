package com.norypt.haven.crypto

import java.nio.ByteBuffer

/**
 * Best-effort zeroisation helpers.
 *
 * The JVM may copy or move heap arrays (GC compaction) and immutable Strings cannot be wiped at
 * all, so wiping is a hardening measure, not a guarantee. Haven keeps secrets in ByteArray /
 * direct ByteBuffer form and wipes them as soon as they are no longer needed.
 */
public object Wipe {
    public fun bytes(array: ByteArray?) {
        if (array != null) java.util.Arrays.fill(array, 0.toByte())
    }

    public fun buffer(buffer: ByteBuffer?) {
        if (buffer == null) return
        val b = buffer.duplicate()
        b.clear()
        while (b.hasRemaining()) b.put(0.toByte())
    }

    public fun chars(array: CharArray?) {
        if (array != null) java.util.Arrays.fill(array, '\u0000')
    }
}

/** Wipes the array after [block] completes, even on exception. */
public inline fun <T> ByteArray.useAndWipe(block: (ByteArray) -> T): T {
    try {
        return block(this)
    } finally {
        Wipe.bytes(this)
    }
}
