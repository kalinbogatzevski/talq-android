package talq.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A text kept for the next run of the app, as the bytes of a file: zipped, sealed with the Keystore key [alias]
 * ([KeystoreSeal]), and capped both ways. Not the main thread. Nothing of it goes to the log: an error may quote
 * what was read.
 */
internal class SealedText(alias: String, private val maxBytes: Long, private val maxPlainBytes: Int) {
    private val seal = KeystoreSeal(alias)

    /** Null when that is more than is kept. */
    fun close(text: String): ByteArray? {
        val plain = text.encodeToByteArray()
        if (plain.size > maxPlainBytes) return null
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        return seal.seal(zipped).takeIf { it.size <= maxBytes }
    }

    /** Throws for anything [close] did not make under this key. */
    fun open(sealed: ByteArray): String {
        check(sealed.size <= maxBytes) { "too large" }
        val out = ByteArrayOutputStream()
        GZIPInputStream(seal.open(sealed).inputStream()).use { it.copyTo(out, maxPlainBytes) }
        return out.toByteArray().decodeToString()
    }

    private fun InputStream.copyTo(out: ByteArrayOutputStream, max: Int) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) return
            check(out.size() + n <= max) { "too large" }
            out.write(buffer, 0, n)
        }
    }
}

/** [bytes] in place of what is in [file], whole or not at all: to the disk first ([tempOf]), then in its place. */
internal fun writeWhole(file: File, bytes: ByteArray): Boolean {
    val temp = tempOf(file)
    FileOutputStream(temp).use {
        it.write(bytes)
        it.fd.sync()
    }
    return temp.renameTo(file)
}

internal fun tempOf(file: File): File = File(file.path + ".tmp")
