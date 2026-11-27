package talq.core.model

import talq.core.folders.folderNameCollator
import talq.core.net.int

/*
 * The people of a group or public conversation: who may see and change the list, what may be done with
 * whom, and the order they are shown in. Ported from TalQ for desktop's ConversationInfoDialog.cpp
 * (`refreshParticipants`, `populateParticipants`, `onRemoveMember`) with the guards of the server's routes
 * (Talk's RoomController: `getParticipants`, `addParticipantToRoom`, `removeAttendeeFromRoom`,
 * `promoteModerator`, `demoteModerator`; ParticipantService): what the server would refuse is not offered.
 *
 * Differences from desktop: a group or a team is never offered a promotion (they are stored with the
 * participant type of a user), nor is a phone number or somebody from another server (as Talk's own web
 * client); making somebody an owner (Talk 25) is not offered, on desktop neither.
 */

/** Bit of a conversation's `attributes` (Talk 25): the list of people is for its moderators only. */
private const val ATTRIBUTE_CHANNEL = 8

/** `objectType` of a breakout room: it takes people, no groups and teams. */
private const val OBJECT_BREAKOUT_ROOM = "room"

/** `objectType` of a video verification: nobody can be added. */
private const val OBJECT_VIDEO_VERIFICATION = "share:password"

/** `objectType` of a file's conversation: it stays when its last member leaves. */
private const val OBJECT_FILE = "file"

/** The account of the chat bridge: the server takes no change to it. */
private const val BRIDGE_BOT = "bridge-bot"

/** Who can become a moderator (Talk's web client offers no other kind). */
private val PROMOTABLE_ACTORS = setOf("users", "guests", "emails")
private val PROMOTABLE_TYPES = setOf(ParticipantType.USER, ParticipantType.GUEST, ParticipantType.USER_SELF_JOINED)

/** The conversation has a list of members to open: group and public ones of the user's own server. */
fun hasMemberList(room: Room): Boolean =
    (room.type == RoomType.GROUP || room.type == RoomType.PUBLIC) && !room.isFederated

/** The server shows this conversation's list to moderators only (desktop's `isChannelRoom`). */
fun membersForModeratorsOnly(room: Room): Boolean = room.raw.int("attributes") and ATTRIBUTE_CHANNEL != 0

/**
 * The server gives the user this conversation's list: moderators always, anybody else neither a channel's nor
 * one behind a lobby (the rule of [Room.takesPosts]).
 */
fun canReadMembers(room: Room, nowSeconds: Long): Boolean {
    if (room.isModerator || room.participantType == ParticipantType.GUEST_MODERATOR) return true
    if (membersForModeratorsOnly(room)) return false
    val inLobby = room.lobbyState != 0 && room.lobbyTimer !in 1..nowSeconds
    return !inLobby || room.permissions and RoomPermission.LOBBY_IGNORE != 0
}

/**
 * When the user leaves [room], the server deletes it: of [members] only they count, as it counts them (Talk's
 * `RoomController::removeSelfFromRoomLogic`, `ParticipantService::getNumberOfUsers`). Guests and who only
 * followed the public link do not count, nor do groups and teams (their people are in the list themselves). Of
 * a conversation on another server only our copy would go: never said of one.
 */
fun leavingDeletes(room: Room, members: List<RoomParticipant>, isMe: (RoomParticipant) -> Boolean): Boolean {
    if (room.isFederated || room.objectType == OBJECT_FILE) return false
    val counted = members.filter { !it.isCollective && it.participantType in ParticipantType.OWNER..ParticipantType.USER }
    return counted.size == 1 && isMe(counted.single())
}

/** "Add people" is offered in [room] to a moderator. */
fun takesMembers(room: Room): Boolean = room.objectType != OBJECT_VIDEO_VERIFICATION

/** Only people can be added to [room], no groups and teams. */
fun takesOnlyPeople(room: Room): Boolean = room.objectType == OBJECT_BREAKOUT_ROOM

/** What a moderator may do with somebody in the list. */
enum class MemberAction { Promote, Demote, Remove }

/** [action] on [person]. */
data class MemberChange(val action: MemberAction, val person: RoomParticipant)

/**
 * What the menu of [p] holds; empty = no menu. Nothing for who is no [moderator], on oneself ([isMe]) and
 * on an owner; a group or team can only be removed.
 */
fun memberActions(p: RoomParticipant, isMe: Boolean, moderator: Boolean): List<MemberAction> {
    if (!moderator || isMe || p.attendeeId == 0L || p.participantType == ParticipantType.OWNER) return emptyList()
    if (p.actorType == "users" && p.actorId == BRIDGE_BOT) return emptyList()
    val level = when {
        p.isCollective -> null
        p.isModerator -> MemberAction.Demote
        p.participantType in PROMOTABLE_TYPES && p.actorType in PROMOTABLE_ACTORS -> MemberAction.Promote
        else -> null
    }
    return listOfNotNull(level, MemberAction.Remove)
}

/** Owners, moderators, people, groups and teams, guests. */
private fun memberRank(p: RoomParticipant): Int = when {
    p.isCollective -> 3
    p.participantType == ParticipantType.OWNER -> 0
    p.isModerator -> 1
    p.isGuest -> 4
    else -> 2
}

/** [list] in the order it is shown: by what they are, inside that by name. */
fun sortMembers(list: List<RoomParticipant>): List<RoomParticipant> {
    val names = folderNameCollator()
    return list.sortedWith(
        Comparator<RoomParticipant> { a, b ->
            val byRank = memberRank(a).compareTo(memberRank(b))
            val byName = if (byRank != 0) byRank else names.compare(a.name, b.name)
            if (byName != 0) byName else a.key.compareTo(b.key)
        },
    )
}

/**
 * [list] right after [change] succeeded, until it is read again: so the old menu cannot start it twice. (A
 * removed group's members leave with the next reading.)
 */
fun afterMemberChange(list: List<RoomParticipant>, change: MemberChange): List<RoomParticipant> {
    val id = change.person.attendeeId
    return when (change.action) {
        MemberAction.Remove -> list.filterNot { it.attendeeId == id }
        MemberAction.Promote -> sortMembers(
            list.map {
                when {
                    it.attendeeId != id -> it
                    it.participantType == ParticipantType.GUEST -> it.copy(participantType = ParticipantType.GUEST_MODERATOR)
                    else -> it.copy(participantType = ParticipantType.MODERATOR)
                }
            },
        )
        MemberAction.Demote -> sortMembers(
            list.map {
                when {
                    it.attendeeId != id -> it
                    it.participantType == ParticipantType.GUEST_MODERATOR -> it.copy(participantType = ParticipantType.GUEST)
                    else -> it.copy(participantType = ParticipantType.USER)
                }
            },
        )
    }
}

/**
 * [person], a search answer, is in [members]: not offered again. (Whoever only followed the public link
 * can still be added for good, so the server lists them too.)
 */
fun isMember(person: PersonMatch, members: List<RoomParticipant>): Boolean = members.any {
    it.actorType == person.source && it.actorId == person.id && it.participantType != ParticipantType.USER_SELF_JOINED
}
