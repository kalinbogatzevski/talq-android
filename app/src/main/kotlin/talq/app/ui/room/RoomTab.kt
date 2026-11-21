package talq.app.ui.room

import talq.core.repo.ChatScope

/**
 * One tab of an open conversation: everything, the messages in no topic, or one topic.
 * [key] is the tab's stable number (route argument, storage value, state-holder key).
 */
sealed interface RoomTab {
    val key: Long

    /** The whole chat, topics included. Also the only view of a conversation without topics. */
    data object All : RoomTab {
        override val key: Long get() = KEY_ALL
    }

    /** Messages outside any topic. */
    data object General : RoomTab {
        override val key: Long get() = KEY_GENERAL
    }

    data class Topic(val id: Long) : RoomTab {
        override val key: Long get() = id
    }

    val scope: ChatScope
        get() = when (this) {
            All -> ChatScope.All
            General -> ChatScope.General
            is Topic -> ChatScope.Topic(id)
        }

    /** The unread bucket: a topic id, 0 for General, null for the whole chat. */
    val bucket: Long?
        get() = when (this) {
            All -> null
            General -> 0L
            is Topic -> id
        }

    companion object {
        const val KEY_ALL = -1L
        const val KEY_GENERAL = 0L

        /** The tab with this [key]; null for anything that is not a tab (e.g. "no tab given"). */
        fun fromKey(key: Long?): RoomTab? = when {
            key == null -> null
            key == KEY_ALL -> All
            key == KEY_GENERAL -> General
            key > 0 -> Topic(key)
            else -> null
        }
    }
}
