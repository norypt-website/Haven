package com.norypt.haven.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Fills [buffer] as far as the stream allows and returns the number of bytes read. */
internal fun readUpTo(input: InputStream, buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = input.read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return total
}

/** Forwards writes but turns close() into flush(), so an inner stream cannot close the file. */
internal class NonClosingOutputStream(private val out: OutputStream) : OutputStream() {
    override fun write(b: Int): Unit = out.write(b)
    override fun write(b: ByteArray, off: Int, len: Int): Unit = out.write(b, off, len)
    override fun flush(): Unit = out.flush()
    override fun close(): Unit = out.flush()
}

internal class CountingOutputStream(private val out: OutputStream) : OutputStream() {
    var count: Long = 0
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun flush(): Unit = out.flush()
    override fun close(): Unit = out.close()
}

/** Wraps an I/O failure of the file being read so it can be told apart from decryption failures. */
internal class SourceReadException(val source: IOException) : IOException(source)

/**
 * Hands out everything except the last [tailSize] bytes of [source]; those become available
 * through [tail] once the source is exhausted. Records how many bytes were delivered and whether
 * the source itself failed, which the reader uses to classify decryption failures.
 */
internal class TailHoldingInputStream(
    private val source: InputStream,
    private val tailSize: Int,
) : InputStream() {
    private val buffer = ByteArray(tailSize + CHUNK)
    private var start = 0
    private var end = 0
    private var sourceExhausted = false

    var delivered: Long = 0
        private set

    var sourceError: IOException? = null
        private set

    /** True once the source hit end-of-file and every byte ahead of the tail has been handed out. */
    val isExhausted: Boolean
        get() = sourceExhausted && end - start <= tailSize

    /** The reserved tail, or null when the source ended before [tailSize] bytes were seen. */
    fun tail(): ByteArray? {
        check(sourceExhausted) { "tail requested before the source was exhausted" }
        return if (end - start < tailSize) null else buffer.copyOfRange(end - tailSize, end)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        fill()
        val deliverable = end - start - tailSize
        if (deliverable <= 0) return -1
        val n = minOf(len, deliverable)
        System.arraycopy(buffer, start, b, off, n)
        start += n
        delivered += n
        return n
    }

    override fun available(): Int = maxOf(0, end - start - tailSize)

    override fun close(): Unit = source.close()

    private fun fill() {
        while (!sourceExhausted && end - start <= tailSize) {
            if (start > 0) {
                System.arraycopy(buffer, start, buffer, 0, end - start)
                end -= start
                start = 0
            }
            val n = try {
                source.read(buffer, end, buffer.size - end)
            } catch (e: IOException) {
                sourceError = e
                throw SourceReadException(e)
            }
            if (n < 0) sourceExhausted = true else end += n
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
