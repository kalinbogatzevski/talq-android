package talq.app.ui.chat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.unit.dp
import talq.app.files.Staging
import talq.app.theme.TalqTheme

// The ways a file reaches the tray other than the pickers: dropped onto the chat, pasted (a hardware
// keyboard, the box's own menu, the attach sheet) and handed over by the on-screen keyboard (GIFs,
// stickers, its clipboard). Only content:// items of other apps (Staging.isAcceptable). The message box
// stays the field it is: these sit around it.

/** Files on their way in. [mime]: what a single one is, for a provider that does not say; else empty. */
internal class IncomingFiles(val uris: List<Uri>, val mime: String = "")

// ---- clipboard -------------------------------------------------------------------------------

/**
 * The clipboard may hold a file. Asks for its description only, so Android does not tell the user that
 * the clipboard was read.
 */
internal fun clipboardMayHoldFiles(context: Context): Boolean =
    try {
        mayHoldFiles(clipboard(context)?.primaryClipDescription)
    } catch (_: RuntimeException) {
        false
    }

/** The files on the clipboard, read on the user's paste only. Null when it holds none (a text is the box's own paste). */
internal fun clipboardFiles(context: Context): IncomingFiles? {
    if (!clipboardMayHoldFiles(context)) return null
    return try {
        // An item with a text of its own is a text (a rich copy names its source document too).
        filesIn(context, clipboard(context)?.primaryClip, textToo = false)
    } catch (_: RuntimeException) {
        null
    }
}

private fun clipboard(context: Context): ClipboardManager? =
    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

private fun mayHoldFiles(about: ClipDescription?): Boolean {
    if (about == null) return false
    return (0 until about.mimeTypeCount).any { isFileType(about.getMimeType(it).orEmpty()) }
}

/** Not a plain text: a type of a file, or the mark of a list of links to files. */
private fun isFileType(type: String): Boolean = !type.startsWith("text/") || type == ClipDescription.MIMETYPE_TEXT_URILIST

private fun filesIn(context: Context, clip: ClipData?, textToo: Boolean): IncomingFiles? {
    if (clip == null) return null
    val uris = (0 until clip.itemCount).map { clip.getItemAt(it) }
        .filter { textToo || it.text == null }
        .mapNotNull { it.uri }
        .filter { Staging.isAcceptable(context, it) }
        .distinct()
    if (uris.isEmpty()) return null
    return IncomingFiles(uris, if (uris.size == 1) concreteType(clip.description) else "")
}

/** The one thing described, as a full type (a text only when nothing else is named); empty when it is not said. */
private fun concreteType(about: ClipDescription?): String {
    if (about == null) return ""
    val types = (0 until about.mimeTypeCount).map { about.getMimeType(it).orEmpty() }
        .filter { it != ClipDescription.MIMETYPE_TEXT_URILIST && '/' in it && '*' !in it }
    return types.firstOrNull { !it.startsWith("text/") } ?: types.firstOrNull().orEmpty()
}

/** "Paste image" / "Paste file" in the box's own menu (touch and hold, right-click) while the clipboard holds one. */
@Composable
internal fun rememberPasteMenu(enabled: Boolean, onPaste: () -> Unit): Modifier {
    val context = LocalContext.current
    val on by rememberUpdatedState(enabled)
    val paste by rememberUpdatedState(onPaste)
    return remember(context) {
        Modifier.appendTextContextMenuComponents {
            if (on && clipboardMayHoldFiles(context)) {
                item(PasteFilesKey, if (clipboardHoldsImage(context)) "Paste image" else "Paste file") {
                    paste()
                    close()
                }
            }
        }
    }
}

private data object PasteFilesKey

private fun clipboardHoldsImage(context: Context): Boolean =
    try {
        clipboard(context)?.primaryClipDescription?.hasMimeType("image/*") == true
    } catch (_: RuntimeException) {
        false
    }

// ---- drag and drop ---------------------------------------------------------------------------

/** Where files can be dropped: [modifier] on the area, and whether a drag of files is over it now. */
internal class FileDrop(val modifier: Modifier, private val over: () -> Boolean) {
    val hovering: Boolean get() = over()
}

/**
 * Files dropped from another app (split screen, desktop mode, a file manager's window). [onFiles] puts
 * them in and says whether it took them; its `done` ends the permission Android gave for this drop.
 * A dragged text is let through: a text file says the same of itself, so what it is is known at the drop.
 */
@Composable
internal fun rememberFileDrop(enabled: Boolean, onFiles: (files: IncomingFiles, done: () -> Unit) -> Boolean): FileDrop {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val on by rememberUpdatedState(enabled)
    val take by rememberUpdatedState(onFiles)
    var hovering by remember { mutableStateOf(false) }
    val modifier = remember(context, activity) {
        val target = object : DragAndDropTarget {
            private var sayDrop = false

            override fun onStarted(event: DragAndDropEvent) {
                sayDrop = event.mimeTypes().any(::isFileType)
            }

            override fun onEntered(event: DragAndDropEvent) {
                hovering = sayDrop && on
            }

            override fun onExited(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val drag = event.toAndroidDragEvent()
                val files = filesIn(context, drag.clipData, textToo = true) ?: return false
                val permission = activity?.requestDragAndDropPermissions(drag)
                return take(files) { permission?.release() }
            }
        }
        Modifier.dragAndDropTarget(shouldStartDragAndDrop = { on }, target = target)
    }
    return remember(modifier) { FileDrop(modifier) { hovering } }
}

/** Over the messages while files are dragged across the chat. */
@Composable
internal fun DropHint(modifier: Modifier) {
    val colors = TalqTheme.colors
    Row(
        modifier = modifier.background(colors.bgPrimary.copy(alpha = 0.92f)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.AttachFile, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("Drop to attach", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
    }
}

// ---- the on-screen keyboard ------------------------------------------------------------------

/**
 * While [enabled], [content]'s text field tells the keyboard that it takes pictures (asked when the keyboard
 * turns to the field); [onFiles] gets what the keyboard hands over and says whether it took it. The field's
 * own connection does everything else, unchanged.
 */
@Composable
internal fun ReceiveFromKeyboard(
    enabled: Boolean,
    onFiles: (files: IncomingFiles, done: () -> Unit) -> Boolean,
    content: @Composable () -> Unit,
) {
    val on by rememberUpdatedState(enabled)
    val take by rememberUpdatedState(onFiles)
    // One for the life of the field: another one would start the keyboard's session anew.
    val interceptor = remember {
        object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(
                request: PlatformTextInputMethodRequest,
                nextHandler: PlatformTextInputSession,
            ): Nothing {
                nextHandler.startInputMethod(
                    object : PlatformTextInputMethodRequest {
                        override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                            val connection = request.createInputConnection(outAttributes)
                            if (on) outAttributes.contentMimeTypes = KEYBOARD_TYPES
                            return PictureConnection(connection) { files, done -> take(files, done) }
                        }
                    },
                )
            }
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

private class PictureConnection(
    connection: InputConnection,
    private val take: (files: IncomingFiles, done: () -> Unit) -> Boolean,
) : InputConnectionWrapper(connection, false) {
    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
        val granting = flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
        if (granting) {
            try {
                inputContentInfo.requestPermission()
            } catch (_: RuntimeException) {
                return false
            }
        }
        val files = IncomingFiles(listOf(inputContentInfo.contentUri), concreteType(inputContentInfo.description))
        return take(files) { if (granting) inputContentInfo.releasePermission() }
    }
}

/** Named one by one too: keyboards look for "image/gif" itself. */
private val KEYBOARD_TYPES = arrayOf("image/gif", "image/webp", "image/png", "image/jpeg", "image/heic", "image/*")
