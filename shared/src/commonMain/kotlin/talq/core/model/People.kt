package talq.core.model

/**
 * One answer of the people search for a new conversation (TalQ for desktop's `NcUser`): [id] is
 * what goes on the wire, [label] the name to show, [source] where it came from: [USERS], [GROUPS]
 * or [CIRCLES]. Talk wants that source back when the id is added to a conversation.
 */
data class PersonMatch(val id: String, val label: String, val source: String) {
    val isUser: Boolean get() = source == USERS

    /** The name to show; the id when the server sent none. */
    val name: String get() = label.ifEmpty { id }

    /** Unique in one answer: a group and a user may share an id. */
    val key: String get() = "$source:$id"

    companion object {
        const val USERS = "users"
        const val GROUPS = "groups"
        const val CIRCLES = "circles"
        val SOURCES = setOf(USERS, GROUPS, CIRCLES)
    }
}

/** TalQ for desktop asks nothing for fewer characters (NewChatDialog::runSearch). */
const val PEOPLE_SEARCH_MIN_CHARS = 2

/** TalQ for desktop's pause after the last key before it asks (NewChatDialog's debounce timer). */
const val PEOPLE_SEARCH_DEBOUNCE_MILLIS = 250L
