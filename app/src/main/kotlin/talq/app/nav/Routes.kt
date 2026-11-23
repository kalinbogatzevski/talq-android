package talq.app.nav

import kotlinx.serialization.Serializable
import talq.app.ui.room.RoomTab

/** Server URL + browser sign-in. Shown while nobody is signed in. */
@Serializable
data object LoginRoute

/** All conversations. The start destination when signed in. */
@Serializable
data object ChatListRoute

@Serializable
data object SettingsRoute

/** Settings > Diagnostics: connection checks and the report for support. */
@Serializable
data object DiagnosticsRoute

/**
 * An open conversation: one back-stack entry, whatever tab is showing inside it.
 *
 * - [tab]: a [RoomTab] key (-1 whole chat, 0 General, >0 a topic), or [REMEMBERED] to open on
 *   the tab the user last had open there (General when there is none).
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
