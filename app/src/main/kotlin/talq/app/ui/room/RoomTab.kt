package talq.app.ui.room

import talq.core.repo.ChatScope

/**
 * One tab of an open conversation: the messages in no topic, one topic, or (only in a conversation
 * without topics) everything. [key] is the tab's stable number (route argument, storage value,
 * state-holder key).
 */
sealed interface RoomTab {
    val key: Long

    /**
     * The whole chat: the only view of a conversation WITHOUT topics. One with topics has no such tab, as
     * on TalQ for desktop, where the first chip shows only what is in no topic. Nothing can ask for it
     * ([fromKey]); the room host shows it while there are no topics.
     */
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

    /**
     * The message box of this tab, a `ComposerDrafts` key. General and the whole chat share ONE box, the
     * room's own: a conversation that gets its first topic, or loses its last, goes on with what was typed
     * there, its reply, an open edit and a send later on its way, and there is nothing to move or to combine.
     */
    val boxKey: Long
        get() = if (this == All) KEY_GENERAL else key

    companion object {
        /** The whole chat's number: its state holders, and what versions up to 0.9 stored for a room left on it. */
        const val KEY_ALL = -1L
        const val KEY_GENERAL = 0L

        /**
         * Not a tab: the topic being started in a room. Its key in `ComposerDrafts`, and in `RoomRoute`
         * for a room opened on it ([fromKey] knows no tab of this number).
         */
        const val KEY_NEW_TOPIC = -2L

        /**
         * The tab with this [key]; null for anything that is not a tab (e.g. "no tab given"). Every saved,
         * remembered or requested tab comes through here, and this is the ONE place where the whole chat
         * ([KEY_ALL]) becomes General: a room with topics has no other, and one without shows its whole chat
         * whatever tab is asked for.
         */
        fun fromKey(key: Long?): RoomTab? = when {
            key == null -> null
            key == KEY_ALL || key == KEY_GENERAL -> General
            key > 0 -> Topic(key)
            else -> null
        }
    }
}
