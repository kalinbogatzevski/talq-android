package talq.app.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import talq.app.ui.common.userMessage
import talq.core.TalkError
import talq.core.files.ByteSink
import talq.core.files.FileNames
import talq.core.model.RichObject
import talq.core.repo.TalkSession

/** Something to tell the user about a received file; [offerSave] adds a "Save" action to it. */
data class FileNotice(val text: String, val offerSave: RichObject? = null)

/**
 * Files received in chats: downloaded inside the app into `cache/shared/<id>-<etag>/<name>`
 * (the only download folder the FileProvider exposes), then opened in another app, shared, or
 * saved. A file that is already there is not downloaded again; a changed file has another etag
 * and so another folder. Call from the main thread.
 *
 * The bytes come through `TalkFiles.download`, which sends the account's header to the
 * account's own server only.
 */
class ReceivedFiles(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: () -> TalkSession?,
) {
    private val _active = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Downloads in flight: file id -> fraction 0..1, or -1 while the size is unknown. */
    val active: StateFlow<Map<String, Float>> = _active.asStateFlow()

    /** Shown (once) by whichever screen is in front. */
    val notice = MutableStateFlow<FileNotice?>(null)

    private val jobs = HashMap<String, Deferred<File>>()

    /** API 26-28 have no storage-permission-free Downloads: there the user picks the place. */
    val savesThroughPicker: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    init {
        scope.launch(Dispatchers.IO) { trim() }
    }

    private fun root(): File = File(context.cacheDir, "shared")

    private fun target(file: RichObject): File {
        val tag = file.etag.ifEmpty { file.size.toString() }.filter { it.isLetterOrDigit() }.take(40)
        val id = file.id.filter { it.isLetterOrDigit() }.take(24).ifEmpty { "0" }
        return File(File(root(), "$id-$tag"), FileNames.sanitize(file.name.ifBlank { "file" }).take(120))
    }

    fun mimeOf(file: RichObject): String = file.mimetype.ifBlank {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase()).orEmpty()
    }.ifBlank { "application/octet-stream" }

    /** Download (or find in the cache) and open in a suitable app. */
    fun open(file: RichObject) = act(file) { view(file, it) }

    /** Download and hand to the Android share sheet. */
    fun share(file: RichObject) = act(file) { send(file, it) }

    /** Download and save: images into Pictures, everything else into Downloads (Android 10+). */
    fun save(file: RichObject) = act(file) { local ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val where = export(file, local)
            notice.value = FileNotice("Saved to $where.")
        }
    }

    /** Download and write into the document the user created with the system picker. */
    fun saveTo(file: RichObject, destination: Uri) = act(file) { local ->
        withContext(Dispatchers.IO) {
            val out = context.contentResolver.openOutputStream(destination) ?: throw IOException("no output stream")
            out.use { stream -> local.inputStream().use { it.copyTo(stream) } }
        }
        notice.value = FileNotice("Saved.")
    }

    fun cancel(fileId: String) {
        jobs[fileId]?.cancel()
    }

    /** Signing out: nothing of the account's files stays on the phone. */
    fun clearAll() {
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        _active.value = emptyMap()
        notice.value = null
        scope.launch(Dispatchers.IO) { root().deleteRecursively() }
    }

    private fun act(file: RichObject, then: suspend (File) -> Unit) {
        scope.launch {
            try {
                then(fetch(file).await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: TalkError) {
                notice.value = FileNotice("Couldn't get ${label(file)}. ${e.userMessage()}")
            } catch (e: Exception) {
                // Its text may hold the file's name.
                Log.w(TAG, "file action failed (${e::class.simpleName})")
                notice.value = FileNotice("That didn't work for ${label(file)}.")
            }
        }
    }

    private fun label(file: RichObject) = file.name.ifBlank { "the file" }

    private fun fetch(file: RichObject): Deferred<File> {
        jobs[file.id]?.let { return it }
        // Lazy, so the job is registered before its body (and its `finally`) can run.
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                download(file)
            } finally {
                jobs.remove(file.id)
                _active.update { it - file.id }
            }
        }
        jobs[file.id] = job
        job.start()
        return job
    }

    private suspend fun download(file: RichObject): File {
        val session = session() ?: throw IllegalStateException("not signed in")
        val out = target(file)
        if (withContext(Dispatchers.IO) { out.isFile && out.length() > 0 }) return out
        _active.update { it + (file.id to -1f) }
        val part = File(out.parentFile, out.name + ".part")
        try {
            val stream = withContext(Dispatchers.IO) {
                out.parentFile?.mkdirs()
                FileOutputStream(part)
            }
            try {
                val sink = ByteSink { buffer, offset, length -> withContext(Dispatchers.IO) { stream.write(buffer, offset, length) } }
                var shown = -1
                session.files.download(file, sink) { received, total ->
                    val known = if (total > 0) total else file.size
                    val percent = if (known > 0) (received * 100 / known).toInt().coerceIn(0, 100) else -1
                    if (percent != shown) {
                        shown = percent
                        _active.update { it + (file.id to if (percent < 0) -1f else percent / 100f) }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { stream.close() } }
            }
            withContext(Dispatchers.IO) {
                if (!part.renameTo(out)) throw IOException("could not move the download into place")
                out.parentFile?.setLastModified(System.currentTimeMillis())
            }
            return out
        } catch (e: IOException) {
            withContext(NonCancellable + Dispatchers.IO) { part.delete() }
            throw TalkError.LocalFile(e, "download")
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { part.delete() }
            throw e
        }
    }

    private fun uriOf(local: File): Uri = FileProvider.getUriForFile(context, Staging.authority(context), local)

    private fun view(file: RichObject, local: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriOf(local), mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            notice.value = FileNotice("No app on this phone can open ${label(file)}.", offerSave = file)
        }
    }

    private fun send(file: RichObject, local: File) {
        val uri = uriOf(local)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeOf(file))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        val chooser = Intent.createChooser(send, null)
            // Not back into this app: its share target refuses its own files anyway.
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, ShareActivity::class.java)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** Writes [local] into the shared media store; answers the folder's name for the notice. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun export(file: RichObject, local: File): String = withContext(Dispatchers.IO) {
        if (file.isImage) {
            try {
                insert(file, local, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_PICTURES + "/TalQ")
                return@withContext "Pictures"
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                // An image type the gallery does not take: it still fits in Downloads.
                Log.w(TAG, "not accepted as a picture (${e::class.simpleName})")
            }
        }
        insert(file, local, MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_DOWNLOADS)
        "Downloads"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insert(file: RichObject, local: File, collection: Uri, folder: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, local.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(file))
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("the media store refused the file")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("no output stream")
            out.use { stream -> local.inputStream().use { it.copyTo(stream) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Old downloads go: anything not touched for two weeks, and half-written leftovers. */
    private fun trim() {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        root().listFiles()?.forEach { dir ->
            if (dir.lastModified() < cutoff) dir.deleteRecursively()
            else dir.listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
        }
    }

    private companion object {
        const val TAG = "TalqFiles"
        const val KEEP_MS = 14L * 24 * 60 * 60 * 1000
    }
}
