package talq.core.files

import io.ktor.http.encodeURLPathPart

/** File name rules shared by every upload path. */
object FileNames {
    /**
     * The name a file is uploaded under: safe as one WebDAV path segment. Characters that break
     * a path or a URL are removed, ".." cannot survive, and an empty result becomes "upload".
     */
    fun sanitize(name: String): String {
        var out = name.substringAfterLast('/').substringAfterLast('\\')
        out = out.filterNot { it in "/\\?#%" || it.code < 0x20 }
        out = out.replace("..", "_").trim()
        return out.ifEmpty { "upload" }
    }

    /** "a.png", 2 -> "a (2).png". A leading dot (".profile") is not an extension. */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + " ($n)" + name.substring(dot) else "$name ($n)"
    }

    /** ".png" for "a.png", empty when there is none (or it is not a plain short extension). */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return ""
        val ext = name.substring(dot + 1)
        return if (ext.length in 1..10 && ext.all { it.isLetterOrDigit() }) ".$ext" else ""
    }

    /**
     * [names] made distinct from each other, in order, the first of a kind kept as it is:
     * "a.png", "a.png" -> "a.png", "a (2).png". Compared without case. Without this two files
     * of the same name in one batch would be stored at the same path, the second replacing
     * the first.
     */
    fun unique(names: List<String>): List<String> {
        val used = HashSet<String>()
        return names.map { name ->
            var candidate = name
            var n = 1
            while (!used.add(candidate.lowercase())) candidate = numbered(name, ++n)
            candidate
        }
    }

    /**
     * A path relative to the user's root, percent-encoded segment by segment for WebDAV.
     * Paths come from message parameters, i.e. from other people, so "." / ".." segments and
     * segments with a backslash are dropped. A path can never climb out of the user's files
     * folder and point an authenticated request at some other endpoint of the server.
     */
    fun encodeDavPath(path: String): String =
        path.trim('/').split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it }
            .joinToString("/") { it.encodeURLPathPart() }
}
