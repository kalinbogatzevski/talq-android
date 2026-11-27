package talq.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.PersistableBundle
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import talq.app.Brand
import talq.app.MainActivity
import talq.app.R
import talq.app.SessionState
import talq.app.nav.DeepLink
import talq.app.push.NotificationGate
import talq.app.ui.common.authorizedImage
import talq.app.ui.common.avatarFill
import talq.app.ui.common.avatarInitial
import talq.core.model.Room
import talq.core.repo.TalkSession
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The most recent conversations in the share sheet of other apps (Android 10 and later; the category in
 * res/xml/shortcuts.xml leads to ShareActivity), and under touch and hold on the app's icon.
 *
 * - Which: the newest [MAX_CHATS] that can be written to (`Room.takesPosts`), and are neither archived nor marked sensitive.
 * - When: after a conversation list that changed which ones these are, or the name or picture of one of
 *   them or of one on the home screen; not before the first list has loaded (a process started by a push
 *   must not empty the row). And when the app comes to the front ([lookAgain]): Android limits what an app
 *   in the background may write, so a refused one goes then.
 * - What Android holds is read, and only the difference is written: a new process writes nothing that is
 *   there already.
 * - One that is only too old for the row goes from the row (Android may keep it for a notification) once the
 *   one that takes its place is there; one that was left, archived, closed to writing or marked sensitive
 *   goes altogether.
 *   Signed out: all go, and so does anything another account left. One the user put on the home screen
 *   cannot be taken down by an app: while its conversation is not in the list or nobody is signed in it is
 *   switched off and shows the app's own name and icon ([blank]); it has the conversation's again after that.
 * - Pictures come from the app's picture cache alone: nothing is ever asked of the server from here. One
 *   the app has not shown yet is the initial (as in the list) until it is there.
 * - A message notification names the shortcut of its conversation where there is one ([heldId]).
 *
 * Everything that talks to Android or decodes a picture is off the main thread.
 */
class ChatShortcuts(context: Context) {
    private val appContext = context.applicationContext

    private data class Chat(val token: String, val name: String, val avatarVersion: String, val oneToOne: Boolean) {
        /** What [EXTRA_PICTURE] says when this picture is the one shown. */
        val picture: String get() = "v$avatarVersion"
    }

    /** [recent]: newest first. [open]: the tokens of all that can be written to. [listed]: every conversation, by token. */
    /** [sensitive]: marked so; those are not among [listed]. */
    private class Picked(val recent: List<Chat>, val open: Set<String>, val listed: Map<String, Chat>, val sensitive: Set<String>) {
        /** Another order alone is no change. */
        fun sameAs(other: Picked): Boolean =
            recent.toSet() == other.recent.toSet() && open == other.open && listed == other.listed && sensitive == other.sensitive
    }

    /** Counts up each time what Android holds is worth another look. */
    private val looks = MutableStateFlow(0)

    /** The app came to the front. */
    fun lookAgain() = looks.update { it + 1 }

    /** Runs for as long as the process lives. */
    suspend fun run(states: StateFlow<SessionState>) {
        states.collectLatest { state ->
            when (state) {
                is SessionState.SignedIn -> follow(state.session)
                // What was being written has ended by now: nothing of it comes after this.
                SessionState.SignedOut -> looks.collect { withContext(Dispatchers.IO) { removeAll() } }
                // Not known yet, or not reached: the account's shortcuts stay.
                SessionState.Loading, is SessionState.Unreachable -> Unit
            }
        }
    }

    private suspend fun follow(session: TalkSession) {
        combine(session.rooms.rooms, session.rooms.state) { rooms, load -> rooms.takeIf { load.loaded } }
            .filterNotNull()
            .map { pick(session, it) }
            .distinctUntilChanged { a, b -> a.sameAs(b) }
            .flowOn(Dispatchers.Default)
            .combine(looks) { picked, _ -> picked }
            .collectLatest { picked ->
                // The first wait lets a burst of changes (and the list's own pictures) settle.
                for (wait in WAITS_MS) {
                    delay(wait)
                    if (update(session, picked)) break
                }
            }
    }

    private fun pick(session: TalkSession, rooms: List<Room>): Picked {
        val now = System.currentTimeMillis() / 1000
        // A shortcut is a name and a picture outside the app: none of a conversation marked sensitive.
        val open = rooms.filter { !it.isArchived && !it.isSensitive && it.takesPosts(session.capabilities, now) }
        return Picked(
            recent = open.filter { it.displayName.isNotBlank() }.take(MAX_CHATS).map(::chatOf),
            open = open.mapTo(HashSet()) { it.token },
            listed = rooms.filterNot { it.isSensitive }.associate { it.token to chatOf(it) },
            sensitive = rooms.filter { it.isSensitive }.mapTo(HashSet()) { it.token },
        )
    }

    private fun chatOf(room: Room): Chat = Chat(room.token, room.displayName, room.avatarVersion, room.isOneToOne)

    /** Bring what Android holds to [picked]. False: something is missing (a picture, a refusal), worth another look. */
    private suspend fun update(session: TalkSession, picked: Picked): Boolean = withContext(Dispatchers.IO) {
        try {
            val account = NotificationGate.accountKey(session)
            val ids = picked.listed.keys.associateWith { idFor(appContext, account, it) }
            val listed = ids.values.toSet()
            val open = picked.open.mapNotNull { ids[it] }.toSet()
            val hidden = picked.sensitive.mapTo(HashSet()) { idFor(appContext, account, it) }
            // One below Android's limit: at the limit, before Android 11, an update of one drops another.
            val limit = ShortcutManagerCompat.getMaxShortcutCountPerActivity(appContext) - 1
            val recent = picked.recent.take(limit.coerceAtLeast(0))
            val wanted = recent.mapNotNull { ids[it.token] }.toSet()
            val have = ours()

            var complete = retire(have.values.filter { it.id !in listed && it.id !in hidden }, GONE_TEXT)
            if (!retire(have.values.filter { it.id in hidden }, PRIVATE_TEXT)) complete = false
            val closed = have.values.filter { it.id in listed && it.id !in open && (it.isDynamic || it.isCached) }
            if (closed.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(appContext, closed.map { it.id })
            val back = have.values.filter { it.isPinned && !it.isEnabled && it.id in listed }
            if (back.isNotEmpty()) ShortcutManagerCompat.enableShortcuts(appContext, back)

            // The last in the row first. Each stays until one has come in its place: a push may be refused.
            val older = have.values.filter { it.isDynamic && it.id in open && it.id !in wanted }
                .sortedByDescending { it.rank }.mapTo(ArrayList()) { it.id }
            var refused = false
            for ((rank, chat) in recent.withIndex()) {
                val id = ids[chat.token] ?: continue
                val shown = have[id]?.takeIf { it.isDynamic && it.isEnabled }?.extras
                val sameName = shown?.getString(EXTRA_NAME) == chat.name
                if (sameName && shown.getString(EXTRA_PICTURE) == chat.picture) continue
                val picture = cachedPicture(session, chat)
                if (picture == null) {
                    complete = false
                    // Only the picture is missing: what is shown meanwhile stays.
                    if (sameName) continue
                }
                if (!ShortcutManagerCompat.pushDynamicShortcut(appContext, shortcut(id, chat, rank, picture))) {
                    complete = false
                    refused = true
                } else if (older.isNotEmpty() && have[id]?.isDynamic != true) {
                    ShortcutManagerCompat.removeDynamicShortcuts(appContext, listOf(older.removeAt(0)))
                }
            }
            if (!refused && older.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(appContext, older)

            // On the home screen, and not in the row: they follow a new name or picture too.
            val changed = ArrayList<ShortcutInfoCompat>()
            for (chat in picked.listed.values) {
                val id = ids[chat.token] ?: continue
                val held = have[id]?.takeIf { it.isPinned && id !in wanted } ?: continue
                val sameName = held.extras?.getString(EXTRA_NAME) == chat.name
                if (chat.name.isBlank() || (sameName && held.extras?.getString(EXTRA_PICTURE) == chat.picture)) continue
                val picture = cachedPicture(session, chat)
                if (picture == null) {
                    complete = false
                    if (sameName) continue
                }
                changed.add(shortcut(id, chat, held.rank, picture))
            }
            if (changed.isNotEmpty() && !ShortcutManagerCompat.updateShortcuts(appContext, changed)) complete = false
            complete
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the conversations in the share sheet could not be updated (${e::class.simpleName})")
            false
        }
    }

    private fun shortcut(id: String, chat: Chat, rank: Int, picture: Bitmap?): ShortcutInfoCompat {
        val builder = ShortcutInfoCompat.Builder(appContext, id)
            .setShortLabel(chat.name)
            .setIcon(icon(chat.name, picture))
            // From the app's icon or the home screen it opens the conversation; the share sheet has its own target.
            .setIntent(DeepLink.toIntent(appContext, chat.token))
            .setCategories(setOf(CATEGORY))
            .setLongLived(true)
            .setLocusId(LocusIdCompat(id))
            .setRank(rank)
            .setExtras(
                PersistableBundle().apply {
                    putString(EXTRA_NAME, chat.name)
                    putString(EXTRA_PICTURE, if (picture != null) chat.picture else "")
                },
            )
        if (chat.oneToOne) builder.setPerson(Person.Builder().setName(chat.name).setKey(id).build())
        return builder.build()
    }

    /** What is left on the home screen of a conversation that is gone or not this phone's any more: nothing of it. */
    private fun blank(id: String): ShortcutInfoCompat {
        val name = Brand.appName(appContext)
        return ShortcutInfoCompat.Builder(appContext, id)
            .setShortLabel(name)
            .setIcon(IconCompat.createWithResource(appContext, R.mipmap.ic_launcher))
            .setIntent(Intent(appContext, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .setLongLived(true)
            // What an update leaves out stays as it was: the person, the extras.
            .setPerson(Person.Builder().setName(name).setKey(id).build())
            .setExtras(
                PersistableBundle().apply {
                    putString(EXTRA_NAME, "")
                    putString(EXTRA_PICTURE, "")
                },
            )
            .build()
    }

    /** Signed out. */
    private fun removeAll() {
        try {
            val all = ours().values
            ShortcutManagerCompat.removeAllDynamicShortcuts(appContext)
            retire(all, SIGNED_OUT_TEXT)
        } catch (e: Exception) {
            // The next start, still signed out, tries again.
            Log.w(TAG, "the conversations in the share sheet could not be removed (${e::class.simpleName})")
        }
    }

    /**
     * Take [gone] away for good; those on the home screen lose their name and picture, are switched off and
     * say [why]. False: Android refused to take a name and picture off (worth another look).
     */
    private fun retire(gone: Collection<ShortcutInfoCompat>, why: String): Boolean {
        if (gone.isEmpty()) return true
        ShortcutManagerCompat.removeLongLivedShortcuts(appContext, gone.map { it.id })
        val pinned = gone.filter { it.isPinned }
        // Also one that is off already: this may have been refused the last time.
        val named = pinned.filter { !it.extras?.getString(EXTRA_NAME).isNullOrEmpty() }
        val blanked = named.isEmpty() || ShortcutManagerCompat.updateShortcuts(appContext, named.map { blank(it.id) })
        val on = pinned.filter { it.isEnabled }
        if (on.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(appContext, on.map { it.id }, why)
        return blanked
    }

    /** What Android holds of this app's conversations: in the row, kept for notifications, on the home screen. */
    private fun ours(): Map<String, ShortcutInfoCompat> =
        ShortcutManagerCompat.getShortcuts(appContext, MATCH_ALL).filter { it.id.startsWith(PREFIX) }.associateBy { it.id }

    /** The avatar as the app loaded it (its dark or its light variant), from the cache; null when it is not there. */
    private suspend fun cachedPicture(session: TalkSession, chat: Chat): Bitmap? {
        val loader = SingletonImageLoader.get(appContext)
        val disk = loader.diskCache ?: return null
        for (dark in listOf(true, false)) {
            val url = session.client.roomAvatarUrl(chat.token, chat.avatarVersion, dark)
            // Kept under its URL. Looked up first: a miss in the request below would reach the network layer.
            val held = disk.openSnapshot(url) ?: continue
            held.close()
            val request = authorizedImage(appContext, url, session.client.authorizationHeader)
                .newBuilder()
                .size(PICTURE_PX)
                .allowHardware(false)
                .memoryCachePolicy(CachePolicy.READ_ONLY)
                .networkCachePolicy(CachePolicy.DISABLED)
                .build()
            // Nothing Android can decode (the default pictures of groups are SVG): the initial, as in the list.
            val result = loader.execute(request) as? SuccessResult ?: continue
            return result.image.toBitmap()
        }
        return null
    }

    /**
     * An adaptive icon: Android's mask shows the middle two thirds, whatever its shape, so the picture
     * (or the initial) has those and the avatar's colour the rest.
     */
    private fun icon(name: String, picture: Bitmap?): IconCompat {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(avatarFill(name).toArgb())
        if (picture != null) {
            val edge = (ICON_PX - PICTURE_PX) / 2f
            val middle = RectF(edge, edge, edge + PICTURE_PX, edge + PICTURE_PX)
            val side = minOf(picture.width, picture.height)
            val left = (picture.width - side) / 2
            val top = (picture.height - side) / 2
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(picture, Rect(left, top, left + side, top + side), middle, paint)
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                textSize = PICTURE_PX * 0.42f
            }
            val baseline = ICON_PX / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(avatarInitial(name), ICON_PX / 2f, baseline, paint)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    companion object {
        private const val TAG = "TalqShare"

        /** The share sheet's row has room for about this many. */
        private const val MAX_CHATS = 5

        /** As in res/xml/shortcuts.xml. */
        private const val CATEGORY = "talq.app.category.CHAT_SHARE_TARGET"
        private const val PREFIX = "chat."
        private const val HMAC = "HmacSHA256"
        private const val ID_BYTES = 18

        private const val EXTRA_NAME = "talq.name"
        private const val EXTRA_PICTURE = "talq.picture"

        private const val GONE_TEXT = "This conversation is gone"
        private const val PRIVATE_TEXT = "This conversation is private"
        private const val SIGNED_OUT_TEXT = "Sign in first"

        /** In the row, or kept by Android for a notification. */
        private const val ROW = ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED
        private const val MATCH_ALL = ROW or ShortcutManagerCompat.FLAG_MATCH_PINNED

        /** 108 dp, and the visible 72 dp of it, at three pixels to the dp. */
        private const val ICON_PX = 324
        private const val PICTURE_PX = 216

        /** Before the first look at a change, and before each further one while something is missing. */
        private val WAITS_MS = longArrayOf(2_500L, 20_000L, 120_000L, 600_000L)

        /**
         * The shortcut (and locus) id of conversation [token] for [account] (`NotificationGate.accountKey`).
         * Keyed with this install's secret: it says nothing about the conversation, and no other app can
         * build one (ShareActivity is exported, and a conversation's token may be known from its link).
         */
        fun idFor(context: Context, account: String, token: String): String {
            val mac = Mac.getInstance(HMAC)
            mac.init(SecretKeySpec(DeepLink.installSecret(context).toByteArray(), HMAC))
            val sum = mac.doFinal("$account|$token".toByteArray())
            return PREFIX + Base64.encodeToString(sum, 0, ID_BYTES, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        /**
         * The id for a notification of conversation [token], which makes it one of Android's conversations: null where
         * Android holds no shortcut of it that is on (it has the newest few). None is made here, and one that is on
         * the home screen alone is left to its icon.
         */
        fun heldId(context: Context, account: String, token: String): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || account.isEmpty() || token.isEmpty()) return null
            return try {
                val held = ShortcutManagerCompat.getShortcuts(context, ROW)
                if (held.isEmpty()) return null
                val id = idFor(context, account, token)
                id.takeIf { held.any { it.id == id && it.isEnabled && (it.isDynamic || it.isCached) } }
            } catch (e: Exception) {
                Log.w(TAG, "the conversations in the share sheet could not be read (${e::class.simpleName})")
                null
            }
        }

        /** The conversation of [rooms] that [shortcutId] stands for; null when it is none of them. */
        fun roomOf(context: Context, session: TalkSession, rooms: List<Room>, shortcutId: String): Room? {
            if (!shortcutId.startsWith(PREFIX)) return null
            val account = NotificationGate.accountKey(session)
            return rooms.firstOrNull { idFor(context, account, it.token) == shortcutId }
        }
    }
}
