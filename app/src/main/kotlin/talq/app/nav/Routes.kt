package talq.app.nav

import kotlinx.serialization.Serializable
import talq.app.ui.room.RoomTab

/** Server URL + browser sign-in. Shown while nobody is signed in. */
@Serializable
data object LoginRoute

/** All conversations. The start destination when signed in. */
@Serializable
data object ChatListRoute

/** [notifications]: opened by the list's "Notifications aren't working" Fix, scrolled to that section. */
@Serializable
data class SettingsRoute(val notifications: Boolean = false)

/** Settings > Diagnostics: connection checks and the report for support. */
@Serializable
data object DiagnosticsRoute

/**
 * An open conversation: one back-stack entry, whatever tab is showing inside it.
 *
 * - [tab]: a [RoomTab] key (0 General, >0 a topic; -1, the whole chat, is General), [RoomTab.KEY_NEW_TOPIC]
 *   for the topic being started there, or [REMEMBERED] to open on the tab the user last had open there
 *   (General when there is none). A room without topics shows its whole chat whatever this says.
 * - [messageId]: the message to scroll to in that tab (from a notification).
 *
 * Build it through `NavHostController.openRoom`, the one way into a conversation.
 */
@Serializable
data class RoomRoute(val token: String, val tab: Long = REMEMBERED, val messageId: Long? = null) {
    companion object {
        const val REMEMBERED = Long.MIN_VALUE
    }
}

/** "Shared files" of a conversation: its media, files and voice messages. */
@Serializable
data class SharedFilesRoute(val token: String)
