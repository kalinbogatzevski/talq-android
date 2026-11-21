package talq.core.files

/**
 * Where the bytes of a file to send come from. The platform implements it (a content URI, a
 * cache file); the core never touches the file system. [open] may be called more than once: a
 * retry reads the file again from the start.
 */
interface ByteSource {
    /** Exact length in bytes. Must be known: copy a stream of unknown length to a file first. */
    val size: Long

    suspend fun open(): ByteReader
}

interface ByteReader {
    /** Read up to [length] bytes into [buffer] at [offset]; returns the count, or -1 at the end. */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun close()
}

/**
 * Where downloaded bytes go. The caller opens it before the download and closes it afterwards
 * (also when the download throws: then whatever was written is incomplete and must be deleted).
 */
fun interface ByteSink {
    suspend fun write(buffer: ByteArray, offset: Int, length: Int)
}

/** A [ByteSource] over bytes already in memory. */
class MemorySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override suspend fun open(): ByteReader = object : ByteReader {
        private var at = 0

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= bytes.size) return -1
            val n = minOf(length, bytes.size - at)
            bytes.copyInto(buffer, offset, at, at + n)
            at += n
            return n
        }

        override fun close() {}
    }
}

/** A [ByteSink] that keeps everything in memory. For small things only (previews, tests). */
class MemorySink : ByteSink {
    private var data = ByteArray(8 * 1024)
    private var length = 0

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (this.length + length > data.size) data = data.copyOf(maxOf(data.size * 2, this.length + length))
        buffer.copyInto(data, this.length, offset, offset + length)
        this.length += length
    }

    fun toByteArray(): ByteArray = data.copyOf(length)
}
