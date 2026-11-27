package talq.core.org.tool

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * The owner's record of every code issued, outside every repository: one line per event,
 * `date<TAB>brand id<TAB>file id<TAB>code` for a code issued, `date<TAB>brand id<TAB>file id<TAB>RETIRED`
 * for one taken out of use. Lines are only ever appended. The file holds the codes themselves, so
 * it is readable by its owner only, and nothing prints from it.
 */
internal data class CodeRecord(val date: String, val brandId: String, val fileId: String, val code: String) {
    override fun toString(): String = "CodeRecord($date, $brandId, $fileId)"
}

private const val RETIRED = "RETIRED"

internal fun readRecords(path: Path): List<CodeRecord> {
    if (!Files.exists(path)) return emptyList()
    return Files.readAllLines(path).mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size == 4) CodeRecord(parts[0], parts[1], parts[2], parts[3]) else null
    }
}

/** The codes in use: issued and not retired, by file id. */
internal fun activeRecords(path: Path): List<CodeRecord> {
    val all = readRecords(path)
    val retired = all.filter { it.code == RETIRED }.map { it.fileId }.toSet()
    return all.filter { it.code != RETIRED && it.fileId !in retired }
}

internal fun appendRecord(path: Path, record: CodeRecord) {
    Files.createDirectories(path.parent)
    // Made empty and closed to others BEFORE any code is written, and checked again at every write.
    if (!Files.exists(path)) Files.createFile(path)
    restrictToOwner(path)
    Files.write(
        path, (listOf(record.date, record.brandId, record.fileId, record.code).joinToString("\t") + "\n").encodeToByteArray(),
        StandardOpenOption.APPEND,
    )
}

internal fun appendRetired(path: Path, date: String, brandId: String, fileId: String) =
    appendRecord(path, CodeRecord(date, brandId, fileId, RETIRED))

/** Readable and writable by the file's owner only (an ACL on Windows, permissions elsewhere). */
internal fun restrictToOwner(path: Path) {
    Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.let {
        it.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        return
    }
    val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
    val entry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(acl.owner)
        .setPermissions(AclEntryPermission.values().toSet())
        .build()
    acl.acl = listOf(entry)
}
