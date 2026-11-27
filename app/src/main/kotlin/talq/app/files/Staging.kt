package talq.app.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import talq.core.files.ByteReader
import talq.core.files.ByteSource
import talq.core.files.FileNames

/**
 * A file copied into the app's cache, ready to upload. [path] is the app's own copy. [toClean]: by its content
 * ([Mp4Metadata.needsCleaning]), whatever its type and name say, it loses its location before it goes.
 */
@Serializable
data class StagedFile(val path: String, val name: String, val mimeType: String, val size: Long, val toClean: Boolean = false)

/**
 * A photo that was to be made smaller (and lose its location) could not be, and cannot be
 * cleaned either: it is not sent rather than sent as it is.
 */
class NotPreparedException(message: String) : IOException(message)

/** The phone has no room left for a copy. */
class NoRoomException : IOException("no room left on the phone")

/** A cache file as upload bytes. A retry opens it again from the start. */
class FileByteSource(private val file: File) : ByteSource {
    override val size: Long = file.length()

    override suspend fun open(): ByteReader = withContext(Dispatchers.IO) {
        val input = FileInputStream(file)
        object : ByteReader {
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                withContext(Dispatchers.IO) { input.read(buffer, offset, length) }

            override fun close() {
                runCatching { input.close() }
            }
        }
    }
}

/** "Send original" for photos and videos: off unless the user turned it on; remembered on this device. */
class SendPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("talq_files", Context.MODE_PRIVATE)

    var sendOriginal: Boolean
        get() = prefs.getBoolean(KEY_ORIGINAL, false)
        set(value) = prefs.edit().putBoolean(KEY_ORIGINAL, value).apply()

    private companion object {
        const val KEY_ORIGINAL = "send_original"
    }
}

/**
 * Everything that leaves the phone is first copied into `cache/outgoing/<random>/<name>`:
 * the sending app's permission to its URI ends with the screen that received it, an upload
 * may be retried much later, and a photo is shrunk on the way in.
 */
object Staging {
    /** The FileProvider authority (see the manifest and `res/xml/file_paths.xml`). */
    fun authority(context: Context): String = context.packageName + ".files"

    fun outgoingDir(context: Context): File = File(context.cacheDir, "outgoing")

    private fun cameraDir(context: Context): File = File(context.cacheDir, "camera")

    /**
     * Remove copies nothing refers to any more (a share that was abandoned, a process that
     * died between copying and queueing): every outgoing folder that holds none of [keep] and
     * every camera file, when older than a few minutes / an hour. Run once at start. In the folders
     * that stay, the half written smaller video of a process that died before [startedAt] goes.
     */
    fun sweep(context: Context, keep: Set<String>, startedAt: Long) {
        val now = System.currentTimeMillis()
        val kept = keep.mapNotNull { runCatching { File(it).canonicalFile.parentFile }.getOrNull() }.toSet()
        outgoingDir(context).listFiles()?.forEach { dir ->
            val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical !in kept && now - dir.lastModified() > OUTGOING_KEEP_MILLIS) dir.deleteRecursively()
            if (canonical in kept) VideoShrink.dropUnfinished(dir, startedAt)
        }
        cameraDir(context).listFiles()?.forEach { if (now - it.lastModified() > CAMERA_KEEP_MILLIS) it.delete() }
    }

    /** Sign-out: every copy goes, the camera's included. */
    fun clearAll(context: Context) {
        outgoingDir(context).deleteRecursively()
        cameraDir(context).deleteRecursively()
    }

    /** A new empty file for the camera to write into, and the URI the camera app is given. */
    fun newCameraFile(context: Context): Pair<File, Uri> {
        val dir = cameraDir(context).apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "photo-$stamp.jpg")
        file.createNewFile()
        return file to FileProvider.getUriForFile(context, authority(context), file)
    }

    /** A camera file of ours, and nothing else: the path comes back through saved state. */
    fun isCameraFile(context: Context, file: File): Boolean =
        runCatching { file.canonicalFile.parentFile == cameraDir(context).canonicalFile }.getOrDefault(false)

    /**
     * May this URI be read as something to send? Only `content://` (never a file path), and
     * never our own provider: another app must not be able to make TalQ send its own files.
     */
    fun isAcceptable(context: Context, uri: Uri): Boolean =
        uri.scheme == "content" && !uri.authority.isNullOrEmpty() &&
            uri.authority?.substringAfterLast('@') != authority(context)

    /**
     * Copy what [uri] points to into the cache. A photo is shrunk unless [sendOriginal]. [hint]: its type
     * as the clipboard, the drag or the keyboard names it, for a provider that does not say.
     * Throws [IOException] when it cannot be read.
     */
    suspend fun stage(context: Context, uri: Uri, sendOriginal: Boolean, hint: String = ""): StagedFile = withContext(Dispatchers.IO) {
        if (!isAcceptable(context, uri)) throw IOException("not a content URI")
        val resolver = context.contentResolver
        val given = resolver.getType(uri).orEmpty().ifEmpty { hint }
        val name = displayName(context, uri, given)
        // The content decides whether this is a photo, not the type the provider reports: a
        // file manager may call a JPEG "application/octet-stream", and it must still lose its
        // location unless Send original is on. Otherwise the reported type, then the name's ending.
        val sniffed = runCatching { resolver.openInputStream(uri)?.use { sniffPhotoMime(it) } }.getOrNull()
        val generic = given.isEmpty() || given == "application/octet-stream" || given == "*/*"
        val mime = sniffed ?: if (!generic) given else
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileNames.extension(name).removePrefix(".").lowercase()).orEmpty()
        stageFrom(context, name, mime, sendOriginal) {
            resolver.openInputStream(uri) ?: throw IOException("the file could not be opened")
        }
    }

    /**
     * The same for a photo the camera just wrote into our cache, as it is. It is moved: that takes no room,
     * and it is the only copy of the picture (it stays where it is when it can be neither moved nor copied).
     */
    suspend fun stageCamera(context: Context, file: File): StagedFile = withContext(Dispatchers.IO) {
        if (!isCameraFile(context, file) || file.length() == 0L) throw IOException("no photo was taken")
        val dir = newFolder(context)
        val target = File(dir, file.name)
        if (file.renameTo(target)) return@withContext StagedFile(target.path, target.name, CAMERA_MIME, target.length())
        dir.delete()
        stageFrom(context, file.name, CAMERA_MIME, sendOriginal = true) { FileInputStream(file) }.also { file.delete() }
    }

    /** A photo that goes out smaller and without its location, unless it is sent as the original. */
    fun isPhoto(mimeType: String): Boolean = mimeType.lowercase() in SHRINKABLE

    /**
     * The smaller copy of the photo [file] (a copy made with `sendOriginal`), beside it in a folder of its
     * own. Throws [IOException] when it cannot be made.
     */
    suspend fun smaller(context: Context, file: StagedFile): StagedFile = withContext(Dispatchers.IO) {
        stageFrom(context, file.name, file.mimeType, sendOriginal = false) { FileInputStream(file.path) }
    }

    /**
     * "image/jpeg" or "image/heic" when the first bytes say the stream is such a photo, else null.
     * JPEG starts FF D8 FF; HEIC/HEIF is an ISO box file with an "ftyp" box and a HEIF brand.
     */
    private fun sniffPhotoMime(input: InputStream): String? {
        val head = ByteArray(12)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n <= 0) break
            read += n
        }
        if (read >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (read >= 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" &&
            String(head, 8, 4, Charsets.US_ASCII) in HEIF_BRANDS
        ) {
            return "image/heic"
        }
        return null
    }

    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1")

    /** Remove a staged file and its folder (its own; a video's smaller copy is in there too). */
    fun delete(context: Context, path: String) {
        val file = File(path)
        val root = runCatching { outgoingDir(context).canonicalFile }.getOrNull() ?: return
        val parent = runCatching { file.canonicalFile.parentFile }.getOrNull() ?: return
        // Only ever inside our own outgoing folder.
        if (parent.parentFile != root) return
        parent.deleteRecursively()
    }

    private fun newFolder(context: Context): File {
        val dir = File(outgoingDir(context), UUID.randomUUID().toString())
        if (!dir.mkdirs()) throw NoRoomException()
        return dir
    }

    private suspend fun stageFrom(
        context: Context,
        name: String,
        mime: String,
        sendOriginal: Boolean,
        open: () -> InputStream,
    ): StagedFile {
        val dir = newFolder(context)
        try {
            if (!sendOriginal && mime.lowercase() in SHRINKABLE) {
                val target = File(dir, jpegName(name))
                val shrunk = try {
                    shrink(open, target)
                } catch (e: OutOfMemoryError) {
                    false
                } catch (e: RuntimeException) {
                    false
                }
                if (shrunk) return StagedFile(target.path, target.name, "image/jpeg", target.length())
                target.delete()
                // Not decodable here (no codec for it, too large for the memory, damaged). It
                // must still not go out with its location: a JPEG is copied and cleaned, any
                // other photo is not sent.
                if (!isJpeg(mime)) throw NotPreparedException("the photo could not be made smaller")
                val copy = File(dir, name)
                copy(open, copy)
                try {
                    stripMetadata(copy)
                } catch (e: Exception) {
                    throw NotPreparedException("the photo's details could not be removed")
                }
                return StagedFile(copy.path, name, mime, copy.length())
            }
            val target = File(dir, name)
            copy(open, target)
            // Asked of the copy: it is what will be sent.
            val toClean = Mp4Metadata.needsCleaning(target)
            return StagedFile(target.path, name, mime.ifEmpty { "application/octet-stream" }, target.length(), toClean)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw if (e is SecurityException) IOException("no permission to read the file", e) else e
        }
    }

    /** Copy, giving up when the phone runs out of room (a provider may stream without end). */
    private suspend fun copy(open: () -> InputStream, target: File): Unit = coroutineScope {
        open().use { input ->
            // A read that hangs (a provider without a network) does not see that the file was given up: closing ends it.
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { input.close() }
                }
            }
            try {
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (unchecked.addAndGet(n.toLong()) >= SPACE_CHECK_BYTES) {
                            // Left as it is when there is no room: the next file looks again at once.
                            if (target.parentFile!!.usableSpace < SPACE_RESERVE_BYTES) throw NoRoomException()
                            unchecked.set(0L)
                        }
                        output.write(buffer, 0, n)
                        written += n
                        if (written > MAX_BYTES) throw IOException("the file is too large")
                    }
                }
                ensureActive()
            } catch (e: Exception) {
                // Given up (the stream was closed under the read), not failed.
                ensureActive()
                throw e
            } finally {
                closer.cancel()
            }
        }
    }

    /** What was copied since the room on the phone was last looked at, all files together: many small ones fill it too. */
    private val unchecked = AtomicLong()

    private fun isJpeg(mime: String): Boolean = mime.lowercase().let { it == "image/jpeg" || it == "image/jpg" }

    /** Take the location and what identifies the camera and its owner out of a JPEG, in place. */
    private fun stripMetadata(file: File) {
        val exif = ExifInterface(file)
        for (tag in PRIVATE_TAGS) exif.setAttribute(tag, null)
        exif.saveAttributes()
    }

    private fun displayName(context: Context, uri: Uri, mime: String): String {
        val given = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (e: RuntimeException) {
            null
        }
        var name = FileNames.sanitize(given.orEmpty().ifBlank { "file" }).take(120)
        if (FileNames.extension(name).isEmpty()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
        }
        return name
    }

    private fun jpegName(name: String): String {
        val ext = FileNames.extension(name)
        val lower = ext.lowercase()
        return if (lower == ".jpg" || lower == ".jpeg") name else name.removeSuffix(ext) + ".jpg"
    }

    /**
     * Re-encode a photo: long edge at most [MAX_EDGE], upright (the EXIF rotation is applied to
     * the pixels), JPEG quality [JPEG_QUALITY]. The new file carries none of the original's
     * metadata (no location, no camera details). False when the image could not be decoded.
     */
    private fun shrink(open: () -> InputStream, target: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        // Decode at the smallest power-of-two size that is still at least the wanted size.
        while (longEdge / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false

        val exif = runCatching { open().use { ExifInterface(it) } }.getOrNull()
        val matrix = Matrix()
        val decodedLong = maxOf(decoded.width, decoded.height)
        if (decodedLong > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / decodedLong
            matrix.postScale(scale, scale)
        }
        if (exif != null) {
            if (exif.isFlipped) matrix.postScale(-1f, 1f)
            val degrees = exif.rotationDegrees
            if (degrees != 0) matrix.postRotate(degrees.toFloat())
        }
        val upright = if (matrix.isIdentity) decoded else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
        try {
            return target.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            upright.recycle()
        }
    }

    /** Photos. Not PNG (drawings, transparency), not GIF (animation). */
    private val SHRINKABLE = setOf("image/jpeg", "image/jpg", "image/heic", "image/heif")
    private const val CAMERA_MIME = "image/jpeg"
    private const val OUTGOING_KEEP_MILLIS = 10 * 60 * 1000L
    private const val CAMERA_KEEP_MILLIS = 60 * 60 * 1000L
    private const val MAX_BYTES = 8L * 1024 * 1024 * 1024
    private const val SPACE_CHECK_BYTES = 8L * 1024 * 1024
    private const val SPACE_RESERVE_BYTES = 200L * 1024 * 1024

    private val PRIVATE_TAGS = listOf(
        ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_XMP,
    )
    private const val MAX_EDGE = 2048
    private const val JPEG_QUALITY = 82
}
