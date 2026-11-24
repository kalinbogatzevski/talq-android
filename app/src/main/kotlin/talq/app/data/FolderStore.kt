package talq.app.data

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import talq.core.folders.ApplyResult
import talq.core.folders.DecodeStatus
import talq.core.folders.FolderChange
import talq.core.folders.FolderLoadOutcome
import talq.core.folders.FolderLocal
import talq.core.folders.FolderResult
import talq.core.folders.FolderState
import talq.core.folders.LocalSnapshot
import talq.core.folders.MAX_ASSIGNMENTS
import talq.core.folders.MAX_FOLDERS
import talq.core.folders.MAX_FOLDER_FILE_BYTES
import talq.core.folders.MAX_SIDECAR_BYTES
import talq.core.folders.SidecarDecode
import talq.core.folders.SidecarStatus
import talq.core.folders.assignToken
import talq.core.folders.createFolder as coreCreateFolder
import talq.core.folders.decodeFolders
import talq.core.folders.decodeSidecar
import talq.core.folders.deleteFolder as coreDeleteFolder
import talq.core.folders.encodeFolders
import talq.core.folders.encodeSidecar
import talq.core.folders.isScopeKey
import talq.core.folders.moveFolder as coreMoveFolder
import talq.core.folders.removesFoldersOrFilings
import talq.core.folders.renameFolder as coreRenameFolder
import talq.core.folders.sanitiseFolderState
import talq.core.folders.setFolderCollapsed
import talq.core.folders.setOtherCollapsed as coreSetOtherCollapsed
import talq.core.folders.sortFoldersByName as coreSortFoldersByName
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Conversation folders on this device: one [FolderState] per account, in one small JSON file,
 * ported from TalQ for desktop's FolderStore (src/core/FolderStore.h/.cpp) and the file half of its
 * FolderSyncMeta. The data rules are the shared core's (talq.core.folders); this class owns the
 * state, the files and the hooks. It never talks to a server. The sync (talq.app.folders) reads its
 * facts and hands merges back through [applyMerged].
 *
 * MAIN THREAD ONLY. Nothing is locked. Every write happens before the call returns (no debounce, no
 * background write): the process can be killed at any moment after the app leaves the screen, and a
 * folder whose write had not happened yet would be lost. The file is small (a few kB; the largest
 * state this app can hold is about 1.2 MB), so the read at [attach] is synchronous as well, and the
 * conversation list never draws without its folders.
 *
 * Files, in `filesDir/folders/` (kept out of backups by data_extraction_rules.xml):
 *  - `<scopeKey>.json`: the folders (the same v1 format as the shared file and desktop's local file);
 *  - `<scopeKey>.json.pre-sync`: ONE rolling copy of that file, taken before a sync apply that
 *    removes a folder or a filing (best effort; nothing reads it back: it is for recovery by hand);
 *  - `<scopeKey>.json.corrupt-<yyyyMMddHHmmss>[-n]`: a file that could not be read, set aside;
 *  - `<scopeKey>.sync.json`: the sync's sidecar (the base of the last sync and the sha of the file
 *    this app last wrote), and `<scopeKey>.probed`: the server passed the capability probe.
 * They stay at sign-out: the layout comes back at the next sign-in to the same account, as on
 * desktop. The scope key is accountScopeKey(server, user id), so accounts never share a file.
 *
 * Every write goes to a temporary file in the same folder, is synced to the disk and then renamed
 * over the target, so a reader finds the old file or the whole new one, never a part. A failed write
 * never deletes the target.
 *
 * WHAT IT NEVER DOES
 *  - attach to an account it cannot name: a key that is not 16 lowercase hex is refused, and the
 *    previous account is detached first all the same;
 *  - overwrite a file it did not understand: a damaged file is RENAMED out of the way; a file of a
 *    newer format is held read-only and never written; a damaged file that cannot be renamed makes
 *    the store read-only;
 *  - write while read-only, from [flush], [detach] or anywhere else;
 *  - touch the disk for a mutation that changed nothing;
 *  - take a merge it cannot vouch for ([applyMerged] refuses another account, a generation that has
 *    moved on, a state over the caps or one that would need repairing);
 *  - count a merge from another device as an edit made here: only the user's own changes are
 *    [localEditCount] and [localEdited], which is what keeps two devices from bouncing each other's
 *    changes back and forth;
 *  - prune filings against the conversation list (a chat not listed now keeps its folder).
 */
class FolderStore(private val root: File) {

    /** Why the store is read-only. The two causes have different remedies, so the cause is kept. */
    enum class ReadOnlyReason {
        None,
        /** The file was written by a newer version. */
        NewerFormat,
        /** A damaged file that could not be renamed out of the way. */
        CouldNotSetAside,
    }

    /** What the screens need besides the state. */
    data class Status(
        val attached: Boolean = false,
        val readOnly: ReadOnlyReason = ReadOnlyReason.None,
    )

    private val _state = MutableStateFlow(FolderState())

    /** The folders of the attached account; the EMPTY state while detached. */
    val state: StateFlow<FolderState> = _state.asStateFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _localEdited = MutableSharedFlow<Unit>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** After every USER mutation that changed the state; never for attach, detach, a save or a sync apply. */
    val localEdited: SharedFlow<Unit> = _localEdited.asSharedFlow()

    /**
     * Called, synchronously, after EVERY successful write of the folders file (a user edit, a sync
     * apply, [forceSave], a [flush] retry, and the flush of an account being left) with the scope
     * and the hex SHA-1 of the exact bytes written. The sync keeps its sidecar current with it. A
     * listener only RECORDS: it must not attach, detach or mutate this store.
     */
    var onSaved: ((scopeKey: String, sha1Hex: String) -> Unit)? = null

    /** Called, synchronously, after every user mutation that changed the state (as [localEdited]). */
    var onLocalEdit: (() -> Unit)? = null

    var scopeKey: String = ""
        private set
    var isAttached: Boolean = false
        private set
    var readOnlyReason: ReadOnlyReason = ReadOnlyReason.None
        private set
    val isReadOnly: Boolean get() = readOnlyReason != ReadOnlyReason.None

    /** A write failed (or never happened) and memory is ahead of the disk; [flush] or the next change retries. */
    var isDirty: Boolean = false
        private set

    /** How [attach] found the file. Detached while no account is attached. */
    var loadOutcome: FolderLoadOutcome = FolderLoadOutcome.Detached
        private set

    /**
     * Hex SHA-1 of the bytes [attach] read, a zero-byte file included (the hash of empty input).
     * Empty only when there was no file (Missing) and while detached. Fixed for the whole attach.
     */
    var loadedSha: String = ""
        private set

    /**
     * Bumped by every change of the state (a user edit or a sync apply, also when the write then
     * failed), by every successful attach and by the detach of an attached store. It never goes
     * back, so a number remembered across a network call names one state of one attach.
     */
    var generation: Long = 0
        private set

    /** User mutations that changed the state since the last attach (a sync apply does not count). */
    var localEditCount: Int = 0
        private set

    private var path: File? = null

    // ---------------------------------------------------------------------------------- attach

    /**
     * Load `<scopeKey>.json`. A previous account is flushed and detached first, also when this key is
     * refused. Returns false (and stays detached) for a key that is not a scope key and for a file
     * that exists but cannot be read (never guess, never overwrite). Otherwise true:
     *  - no file: an empty state (nothing is written until the first change);
     *  - a damaged file: renamed to `.corrupt-<stamp>`, an empty state; if it cannot be renamed, an
     *    empty state, read-only;
     *  - a newer format: an empty state, read-only, the file untouched.
     */
    fun attach(key: String): Boolean {
        detachSilently()
        if (!isScopeKey(key)) {
            Log.w(TAG, "refusing to attach to an invalid scope key")
            return false
        }
        return load(key)
    }

    /** Flush, then forget the account. The files stay; the layout comes back at the next sign-in. */
    fun detach() {
        detachSilently()
    }

    private fun detachSilently() {
        if (!isAttached) return
        flush()   // may call onSaved for the account being left: scopeKey is still set here
        isAttached = false
        readOnlyReason = ReadOnlyReason.None
        isDirty = false
        scopeKey = ""
        path = null
        loadOutcome = FolderLoadOutcome.Detached
        loadedSha = ""
        localEditCount = 0
        generation++   // whatever a cycle remembered about this attach is stale now
        _state.value = FolderState()
        _status.value = Status()
    }

    private fun load(key: String): Boolean {
        val file = fileFor(key)
        var loaded = FolderState()
        var reason = ReadOnlyReason.None
        var outcome = FolderLoadOutcome.Missing
        var sha = ""
        if (file.exists()) {
            // One byte past the cap tells "too big" without reading an arbitrary file whole.
            val bytes = readBounded(file, MAX_FOLDER_FILE_BYTES)
            if (bytes == null) {
                // There, but unreadable: whether it is damaged cannot be told, and starting empty
                // would let the next save replace it. Stay detached.
                Log.w(TAG, "the folders file cannot be read; folders stay off")
                return false
            }
            // Hashed as read, before decoding. A zero-byte file is a file: the hash of empty input.
            sha = sha1Hex(bytes)
            val d = decodeFolders(bytes)
            when (d.status) {
                DecodeStatus.Ok -> {
                    loaded = d.state
                    outcome = if (d.repaired) FolderLoadOutcome.Repaired else FolderLoadOutcome.Ok
                    if (d.repaired) Log.w(TAG, "unusable entries were dropped while loading the folders file")
                }
                DecodeStatus.FutureVersion -> {
                    // Written by a newer version (the app was downgraded). Keep the file exactly as it
                    // is, show no folders, refuse every change.
                    reason = ReadOnlyReason.NewerFormat
                    outcome = FolderLoadOutcome.ReadOnlyLoad
                    Log.w(TAG, "the folders file is a newer format; folders are read-only in this version")
                }
                DecodeStatus.Corrupt -> {
                    if (setAside(file)) {
                        outcome = FolderLoadOutcome.SetAside
                        Log.w(TAG, "the folders file is damaged; it was renamed and folders start empty")
                    } else {
                        reason = ReadOnlyReason.CouldNotSetAside
                        outcome = FolderLoadOutcome.ReadOnlyLoad
                        Log.w(TAG, "the folders file is damaged and could not be renamed; folders are read-only")
                    }
                }
            }
        }
        scopeKey = key
        path = file
        readOnlyReason = reason
        isDirty = false
        isAttached = true
        loadOutcome = outcome
        loadedSha = sha
        localEditCount = 0
        generation++   // a new attach: anything remembered about an earlier one is stale
        _state.value = loaded
        _status.value = Status(attached = true, readOnly = reason)
        return true
    }

    /** `<name>.corrupt-<yyyyMMddHHmmss>`, with -2, -3, ... on a clash; an earlier set-aside file is never replaced. */
    private fun setAside(file: File): Boolean {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        var target = File(file.path + ".corrupt-" + stamp)
        var n = 2
        while (target.exists() && n < 1000) {
            target = File(file.path + ".corrupt-" + stamp + "-" + n)
            n++
        }
        if (target.exists()) return false
        return try {
            file.renameTo(target)
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------------------- writing

    private fun save(): Boolean {
        val file = path ?: return false
        if (!isAttached || isReadOnly) return false   // a read-only store never reaches the disk
        // An empty state is written too: a missing file would load as Missing at the next start, and
        // "delete my last folder" would come back from the other devices.
        val bytes = encodeFolders(_state.value)
        if (!writeAtomically(file, bytes)) {
            Log.w(TAG, "the folders file could not be written; folders are kept in memory and saved on the next change")
            return false
        }
        isDirty = false
        // The ONE place every successful write passes: the hash of the exact bytes just written.
        val sha = sha1Hex(bytes)
        val scope = scopeKey
        onSaved?.invoke(scope, sha)
        return true
    }

    /** Write now if the last write failed. A no-op when clean, detached or read-only. */
    fun flush() {
        if (isAttached && !isReadOnly && isDirty) save()
    }

    /**
     * Write the state once even when nothing is pending: a file that loaded Repaired or SetAside is
     * never rewritten by a mutation that changes nothing. False when detached, read-only or when the
     * write fails (a failure leaves [isDirty] as it was). Changes neither [generation] nor
     * [localEditCount]; never counts as an edit.
     */
    fun forceSave(): Boolean {
        if (!isAttached || isReadOnly) return false
        return save()
    }

    // ---------------------------------------------------------------------------------- user edits

    /**
     * One user mutation. NotAttached / ReadOnly are the store's own refusals; everything else is the
     * core's. Ok: the state is replaced and written (also when the write failed: memory did change,
     * and [isDirty] stays set), then [onLocalEdit] and [localEdited].
     */
    private fun edit(change: (FolderState) -> FolderChange): FolderChange {
        val current = _state.value
        if (!isAttached) return FolderChange(FolderResult.NotAttached, current)
        if (isReadOnly) return FolderChange(FolderResult.ReadOnly, current)
        val c = change(current)
        if (c.result == FolderResult.Ok) {
            generation++       // the state changed, whether or not the write succeeds
            localEditCount++   // a user mutation: the only caller that counts
            isDirty = true
            _state.value = c.state
            save()
            onLocalEdit?.invoke()
            _localEdited.tryEmit(Unit)
        }
        return c
    }

    /** [FolderChange.folderId] is the new folder's id when the result is Ok. */
    fun createFolder(name: String): FolderChange = edit { coreCreateFolder(it, name, Random.nextLong()) }

    fun renameFolder(id: String, name: String): FolderResult = edit { coreRenameFolder(it, id, name) }.result

    fun deleteFolder(id: String): FolderResult = edit { coreDeleteFolder(it, id) }.result

    fun moveFolder(id: String, delta: Int): FolderResult = edit { coreMoveFolder(it, id, delta) }.result

    fun sortFoldersByName(): FolderResult = edit { coreSortFoldersByName(it) }.result

    /** [folderId] empty unfiles. */
    fun assign(token: String, folderId: String): FolderResult = edit { assignToken(it, token, folderId) }.result

    fun setCollapsed(id: String, collapsed: Boolean): FolderResult = edit { setFolderCollapsed(it, id, collapsed) }.result

    fun setOtherCollapsed(collapsed: Boolean): FolderResult = edit { coreSetOtherCollapsed(it, collapsed) }.result

    // ---------------------------------------------------------------------------------- sync apply

    /**
     * Replace the whole state with [merged], computed by the sync from a snapshot taken at
     * [expectedGeneration] for [key]. Refusals, in this order, touch nothing: NotAttached; another
     * account is Stale; ReadOnly; a moved generation is Stale (the user edited while the network call
     * was out); more than MAX_FOLDERS folders or MAX_ASSIGNMENTS filings, or a state
     * sanitiseFolderState would change, is Invalid; an equal state is Unchanged. Otherwise, when the
     * merge removes a folder or a filing, the file on disk is first copied to `.pre-sync` (best
     * effort), then the state is replaced and written. Never an edit: no [localEdited], no count.
     * AppliedNotSaved leaves the store dirty, and the caller must not write the sidecar then.
     */
    fun applyMerged(key: String, expectedGeneration: Long, merged: FolderState): ApplyResult {
        if (!isAttached) return ApplyResult.NotAttached
        if (key != scopeKey) return ApplyResult.Stale
        if (isReadOnly) return ApplyResult.ReadOnly
        if (generation != expectedGeneration) return ApplyResult.Stale
        if (merged.folders.size > MAX_FOLDERS || merged.assign.size > MAX_ASSIGNMENTS) return ApplyResult.Invalid
        if (sanitiseFolderState(merged).changed) return ApplyResult.Invalid
        val current = _state.value
        if (current == merged) return ApplyResult.Unchanged
        // Decided against the state as it is NOW, and copied before anything is written.
        if (removesFoldersOrFilings(current, merged)) makePreSyncCopy()
        generation++
        isDirty = true
        _state.value = merged
        return if (save()) ApplyResult.Applied else ApplyResult.AppliedNotSaved
    }

    /**
     * The core's view of this store for one account. [isAttached][FolderLocal.isAttached] means
     * "still attached to [key]": a cycle that outlives its account sends nothing more.
     */
    fun localFor(key: String): FolderLocal = object : FolderLocal {
        override fun isAttached(): Boolean = this@FolderStore.isAttached && scopeKey == key

        override fun snapshot(): LocalSnapshot = LocalSnapshot(generation, _state.value)

        override suspend fun applyMerged(expectedGeneration: Long, merged: FolderState): ApplyResult =
            this@FolderStore.applyMerged(key, expectedGeneration, merged)
    }

    /**
     * ONE rolling copy of the FILE (the disk, not memory: after a failed write memory is ahead of
     * it, and the disk is what the apply replaces). No file, an empty or oversized one, an unreadable
     * one or a copy that cannot be written: no copy (an empty copy would replace an older good one),
     * and the apply goes ahead.
     */
    private fun makePreSyncCopy() {
        val file = path ?: return
        if (!file.exists()) return
        val bytes = readBounded(file, MAX_FOLDER_FILE_BYTES)
        if (bytes == null || bytes.size > MAX_FOLDER_FILE_BYTES) {
            Log.w(TAG, "no .pre-sync copy: the folders file is unreadable or larger than any of ours")
            return
        }
        if (bytes.isEmpty()) {
            Log.w(TAG, "no new .pre-sync copy: the folders file is empty")
            return
        }
        if (!writeAtomically(File(file.path + ".pre-sync"), bytes)) {
            Log.w(TAG, "the .pre-sync copy could not be written; applying without one")
        }
    }

    // ---------------------------------------------------------------------------------- sync files

    /**
     * The sidecar of [key]. No file: Absent. A file that cannot be read, or is over
     * MAX_SIDECAR_BYTES: Unreadable. Anything else is decodeSidecar's verdict. Only Ok carries a
     * sidecar; every other status carries the EMPTY one.
     */
    fun readSidecar(key: String): SidecarDecode {
        if (!isScopeKey(key)) return SidecarDecode(SidecarStatus.Absent)
        val file = File(root, key + SIDECAR_SUFFIX)
        if (!file.exists()) return SidecarDecode(SidecarStatus.Absent)
        val bytes = readBounded(file, MAX_SIDECAR_BYTES) ?: return SidecarDecode(SidecarStatus.Unreadable)
        return decodeSidecar(bytes)   // judges the size too: cap + 1 bytes is over
    }

    /** Atomic; true only when the new sidecar is on disk. [localShaHex] is empty or 40 lowercase hex. */
    fun writeSidecar(key: String, base: FolderState, localShaHex: String): Boolean {
        if (!isScopeKey(key)) return false
        return writeAtomically(File(root, key + SIDECAR_SUFFIX), encodeSidecar(base, localShaHex))
    }

    /**
     * True ONLY for the JSON object {"v": 1, "probedOk": true}: those two keys, the number 1, the
     * boolean true. Anything else (absent, unreadable, too big, another shape) means "not probed",
     * which costs one probe: the safe direction.
     */
    fun probeMarkerPresent(key: String): Boolean {
        if (!isScopeKey(key)) return false
        val file = File(root, key + PROBED_SUFFIX)
        if (!file.exists()) return false
        val bytes = readBounded(file, MAX_PROBE_MARKER_BYTES) ?: return false
        if (bytes.size > MAX_PROBE_MARKER_BYTES) return false
        val element = try {
            STRICT_JSON.parseToJsonElement(bytes.decodeToString())
        } catch (e: Exception) {
            return false
        }
        if (element !is JsonObject || element.size != 2) return false
        val v = element["v"] as? JsonPrimitive ?: return false
        val ok = element["probedOk"] as? JsonPrimitive ?: return false
        // Not strings: "1" and "true" in quotes are not the number and the boolean.
        return !v.isString && v.content.toDoubleOrNull() == 1.0 && !ok.isString && ok.content == "true"
    }

    /** Atomic; true only when the marker is on disk. */
    fun writeProbeMarker(key: String): Boolean {
        if (!isScopeKey(key)) return false
        return writeAtomically(File(root, key + PROBED_SUFFIX), PROBE_MARKER_BYTES)
    }

    /** True when the marker is gone afterwards, also when there never was one. */
    fun removeProbeMarker(key: String): Boolean {
        if (!isScopeKey(key)) return false
        val file = File(root, key + PROBED_SUFFIX)
        if (!file.exists()) return true
        try {
            file.delete()
        } catch (e: Exception) {
            // judged by what is there now
        }
        if (!file.exists()) return true
        Log.w(TAG, "the probe marker could not be removed")
        return false
    }

    // ---------------------------------------------------------------------------------- files

    /** The key passed isScopeKey (16 lowercase hex), so it cannot carry a separator or "..". */
    private fun fileFor(key: String) = File(root, "$key.json")

    /**
     * A temporary file in the same folder, synced to the disk, then renamed over [target] (rename
     * replaces a file in one step). True only when the new file is in place; on any failure the old
     * file is untouched and the temporary file is removed.
     */
    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val dir = target.parentFile ?: return false
        try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                Log.w(TAG, "the folders directory cannot be created")
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "the folders directory cannot be created", e)
            return false
        }
        val tmp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "a folders file could not be written", e)
            tmp.delete()
            return false
        }
        val renamed = try {
            tmp.renameTo(target)
        } catch (e: Exception) {
            false
        }
        if (!renamed) {
            Log.w(TAG, "a folders file could not be replaced")
            tmp.delete()
            return false
        }
        return true
    }

    private companion object {
        const val TAG = "FolderStore"
        const val SIDECAR_SUFFIX = ".sync.json"
        const val PROBED_SUFFIX = ".probed"

        /** The marker is about 30 bytes; this much of it is ever read (one byte more tells "too big"). */
        const val MAX_PROBE_MARKER_BYTES = 4096

        /** The marker as desktop writes it: keys sorted, four spaces, a final newline. */
        val PROBE_MARKER_BYTES: ByteArray = "{\n    \"probedOk\": true,\n    \"v\": 1\n}\n".encodeToByteArray()

        /** Default settings: strict (not the Talk client's lenient reader). */
        val STRICT_JSON: Json = Json

        /** At most [cap] + 1 bytes of [file]; null when it cannot be opened or read. */
        fun readBounded(file: File, cap: Int): ByteArray? = try {
            FileInputStream(file).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (total <= cap) {
                    val n = input.read(buf, 0, minOf(buf.size, cap + 1 - total))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }

        /** Lowercase hex SHA-1, the form the sidecar stores (a fingerprint, not a security measure). */
        fun sha1Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
            val out = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return out.toString()
        }

        const val HEX = "0123456789abcdef"
    }
}
