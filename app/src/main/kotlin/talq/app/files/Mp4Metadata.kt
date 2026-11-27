package talq.app.files

import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Takes the location and the camera's details out of a movie file (ISO base media: MP4, MOV, 3GP, M4A), in
 * place. Such a file is boxes in boxes, and of the kinds a writer may put beside the picture and the sound a
 * player needs few. Those stay ([TOP], [MOVIE], [TRACK], [MEDIA], [MEDIA_INFO], [FRAGMENT], [TRACK_FRAGMENT]);
 * every other box there is named `free`, which players skip, and overwritten with zeros: `udta` and `meta`
 * (location, camera; `keys` and `ilst` are inside), `uuid` (XMP and makers' own) but the three of [NEEDED],
 * kinds that are not known, and what `free` and `skip` boxes hold. A track that is neither picture nor sound
 * (any but [SHOWN]: timed metadata and texts, which is where action cameras, drones and phones write places)
 * goes the same way, with its samples. Nothing moves, so the offsets into the picture and the sound stay right.
 *
 * - All or nothing: the whole file is read before anything is written. Anything in it that is not understood
 *   (bytes that are no box, a wrong size, more than [MAX_BOXES], not exactly one `moov`, a packed header,
 *   samples that are elsewhere or shared, anything between them) means that it is not clean and stays as it was.
 * - Interrupted halfway, it is finished the next time: a track's samples go while its tables still say where
 *   they are, and a box is named `free` before it is emptied, which every `free` box is, every time.
 * - Sound alone is left as it is, with its title, cover and chapters, unless it says where it was made
 *   ([isPlainSound]): then it is cleaned as a movie is, and they go too.
 * - Not looked into: the picture and the sound themselves, their sample tables (where the name of an encoder may
 *   stand) and the name of a track's handler (both may name the camera's maker), and what lies between the samples
 *   of a file in fragments (each fragment lists its own).
 */
internal object Mp4Metadata {
    private class Box(val type: String, val start: Long, val body: Long, val end: Long)

    private class Track(val handler: String, val box: Box, val tables: Box) {
        val kept = handler in SHOWN
    }

    private class Range(val from: Long, val to: Long)

    /** What goes, the samples first. */
    private class Found(val samples: List<Range>, val boxes: List<Box>)

    /** Something in the file is not understood. */
    private class NotClean : Exception()

    /**
     * True: [file] is a movie file that holds no such details (any more). False: it is none, or that cannot be
     * made sure; then it is as it was.
     */
    @Throws(IOException::class)
    fun blank(file: File): Boolean {
        // Opening it to write would make it.
        if (!file.isFile) throw FileNotFoundException("the copy is gone")
        return RandomAccessFile(file, "rw").use { f ->
            val found = try {
                Scan(f).read()
            } catch (e: NotClean) {
                null
            }
            if (found != null) {
                for (r in found.samples) zero(f, r.from, r.to)
                for (box in found.boxes) wipe(f, box)
            }
            found != null
        }
    }

    /** [file] has a movie file's boxes, in whatever state. */
    @Throws(IOException::class)
    fun isIso(file: File): Boolean = RandomAccessFile(file, "r").use { first(it, ISO) != null }

    /**
     * By its content, whatever its type and name say: it loses its details before it goes. A movie file, Matroska / WebM
     * with a picture, and sound alone that says where it was made. Not what has no `moov` (JPEG XL, a lone
     * segment), pictures in the same boxes (HEIC, AVIF, a camera's raw file), and any other sound ([isPlainSound]).
     */
    @Throws(IOException::class)
    fun needsCleaning(file: File): Boolean = RandomAccessFile(file, "r").use { f ->
        val head = ByteArray(minOf(f.length(), 12L).toInt())
        f.readFully(head)
        when {
            head.size < 12 -> false
            EBML.indices.all { head[it] == EBML[it] } -> !isMatroskaSound(f)
            String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" && String(head, 8, 4, Charsets.ISO_8859_1) in PICTURES -> false
            else -> first(f, MOOV)?.let { !isPlainSound(f, it) } ?: false
        }
    }

    /**
     * A movie file's boxes with sound alone in them (music, a book, a recording), with chapters that are text and a
     * cover at most, and nothing that says a place: it goes as it is, with its title. Judged by the kinds of box that hold
     * places; what cannot be read is none.
     */
    @Throws(IOException::class)
    fun isPlainSound(file: File): Boolean = RandomAccessFile(file, "r").use { f ->
        first(f, MOOV)?.let { isPlainSound(f, it) } ?: false
    }

    private fun isPlainSound(f: RandomAccessFile, moov: Box): Boolean = try {
        Scan(f).isPlainSound(moov, outermost(f, MAX_BOXES))
    } catch (e: NotClean) {
        false
    }

    /** The first of the outermost boxes whose type is one of [types], not looked for far. */
    private fun first(f: RandomAccessFile, types: Set<String>): Box? = outermost(f, MAX_FIRST).firstOrNull { it.type in types }

    /** The outermost boxes, [most] at most, as far as they go: one that is cut short is the last, and so is what is no box. */
    private fun outermost(f: RandomAccessFile, most: Int): Sequence<Box> = sequence {
        val head = ByteArray(HEADER.toInt())
        val length = f.length()
        var at = 0L
        var left = most
        while (left-- > 0 && length - at >= HEADER) {
            f.seek(at)
            f.readFully(head)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            var size = ByteBuffer.wrap(head).getInt(0).toLong() and 0xFFFFFFFFL
            var header = HEADER
            if (size == 1L && length - at >= LONG_HEADER) {
                size = f.readLong()
                header = LONG_HEADER
            }
            val whole = size >= header && size <= length - at
            yield(Box(type, at, at + header, if (whole) at + size else length))
            if (!whole) break
            at += size
        }
    }

    /** The same of the tracks a Matroska file lists before its first cluster. */
    private fun isMatroskaSound(f: RandomAccessFile): Boolean {
        val length = f.length()
        var at = 0L
        var left = MAX_FIRST
        var sound = false
        try {
            while (at < length && left-- > 0) {
                f.seek(at)
                val id = number(f, marked = true)
                val size = number(f, marked = false)
                if (id < 0 || size < 0) return false
                val body = f.filePointer
                if (id == CLUSTER) return sound
                if (id == TRACK_TYPE) {
                    val kind = f.readUnsignedByte()
                    if (size != 1L || kind == PICTURE_KIND || kind == MIXED_KIND) return false
                    if (kind == SOUND_KIND) sound = true
                }
                at = if (id in ENTERED) body else body + size
            }
        } catch (e: EOFException) {
            // Cut short.
        }
        return false
    }

    /** A number as Matroska writes it, its length in front; [marked]: with the bit that says it, as ids are named. -1: none. */
    private fun number(f: RandomAccessFile, marked: Boolean): Long {
        val first = f.readUnsignedByte()
        val more = first.countLeadingZeroBits() - 24
        if (more > 7) return -1L
        var n = (if (marked) first else first and (0x7F shr more)).toLong()
        repeat(more) { n = (n shl 8) or f.readUnsignedByte().toLong() }
        return n
    }

    // ---- what is in it -------------------------------------------------------------------

    private class Scan(private val f: RandomAccessFile) {
        private val length = f.length()
        private val head = ByteArray(HEADER.toInt())
        private var seen = 0
        private val going = ArrayList<Box>()
        private val tracks = ArrayList<Track>()
        private val media = ArrayList<Box>()
        private var fragments = false

        fun read(): Found {
            val top = boxes(0L, length)
            if (top.count { it.type == "moov" } != 1) throw NotClean()
            if (top.first().type == "ftyp" && text(top.first(), 0) in PICTURES) throw NotClean()
            for (box in top) {
                when (box.type) {
                    "moov" -> movie(box)
                    "moof" -> fragment(box)
                    "mdat" -> media.add(box)
                    else -> if (box.type !in TOP) drop(box)
                }
            }
            if (tracks.none { it.kept }) throw NotClean()
            return Found(samples(), going)
        }

        /** See [Mp4Metadata.isPlainSound]; [outer]: the file's outermost boxes. */
        fun isPlainSound(moov: Box, outer: Sequence<Box>): Boolean {
            val level = movie(moov)
            val sound = tracks.filter { it.handler == "soun" }
            if (sound.isEmpty()) return false
            val chapters = sound.flatMapTo(HashSet()) { chapters(it) }
            // The samples of one that goes on in fragments are not all counted here.
            val whole = level.none { it.type == "mvex" }
            return tracks.all {
                it.handler == "soun" || (it.handler in CHAPTER_HANDLERS && id(it) in chapters) || (whole && isCover(it))
            } &&
                !saysPlace(level.asSequence()) && !saysPlace(outer)
        }

        private fun movie(moov: Box): List<Box> {
            val level = inside(moov)
            for (box in level) {
                when (box.type) {
                    "trak" -> track(box)
                    // A packed header; a dash camera's list of places that lie among the samples, in no track.
                    "cmov", "gps " -> throw NotClean()
                    else -> if (box.type !in MOVIE) drop(box)
                }
            }
            return level
        }

        private fun track(trak: Box) {
            var handler = ""
            var tables: Box? = null
            val extra = ArrayList<Box>()
            for (box in boxes(trak.body, trak.end)) {
                if (box.type !in TRACK) extra.add(box)
                if (box.type != "mdia") continue
                for (inner in boxes(box.body, box.end)) {
                    if (inner.type !in MEDIA) extra.add(inner)
                    if (inner.type == "hdlr") {
                        if (handler.isNotEmpty()) throw NotClean()
                        handler = text(inner, 8)
                    }
                    if (inner.type != "minf") continue
                    for (last in boxes(inner.body, inner.end)) {
                        if (last.type !in MEDIA_INFO) extra.add(last)
                        if (last.type == "stbl") {
                            if (tables != null) throw NotClean()
                            tables = last
                        }
                    }
                }
            }
            val stbl = tables
            if (handler.isEmpty() || stbl == null) throw NotClean()
            val track = Track(handler, trak, stbl)
            tracks.add(track)
            if (track.kept) {
                for (box in extra) drop(box)
            } else {
                going.add(trak)
            }
        }

        private fun fragment(moof: Box) {
            fragments = true
            for (box in boxes(moof.body, moof.end)) {
                if (box.type !in FRAGMENT) drop(box)
                if (box.type != "traf") continue
                for (inner in boxes(box.body, box.end)) {
                    if (inner.type !in TRACK_FRAGMENT) drop(inner)
                }
            }
        }

        private fun drop(box: Box) {
            if (box.type != "uuid" || uuid(box) !in NEEDED) going.add(box)
        }

        // ---- sound that says a place -------------------------------------------------------

        /** The tracks that [track] names as its chapters: their titles, and their pictures. */
        private fun chapters(track: Track): List<Int> =
            inside(track.box).filter { it.type == "tref" }.flatMap { inside(it) }.filter { it.type == "chap" }.flatMap { box ->
                val ids = ByteBuffer.wrap(bytes(box, 0, size(box, MAX_WORDS)))
                List(ids.capacity() / 4) { ids.getInt(4 * it) }
            }

        /** The number [track] is named by; null: it has none. */
        private fun id(track: Track): Int? {
            val head = inside(track.box).firstOrNull { it.type == "tkhd" } ?: return null
            // Two dates come before it, of 32 bits or of 64.
            return ByteBuffer.wrap(bytes(head, if (bytes(head, 0, 1)[0].toInt() == 0) 12 else 20, 4)).getInt(0)
        }

        /** One picture, for the whole of the sound. */
        private fun isCover(track: Track): Boolean {
            val sizes = inside(track.tables).singleOrNull { it.type == "stsz" } ?: return false
            return track.handler == "vide" && ByteBuffer.wrap(bytes(sizes, 8, 4)).getInt(0) == 1
        }

        /** One of the boxes of [level], or of what describes the tracks among them, says a place, or may (XMP). */
        private fun saysPlace(level: Sequence<Box>): Boolean = level.any { box ->
            when (box.type) {
                "trak", "mdia", "minf" -> saysPlace(inside(box).asSequence())
                // The list of a QuickTime file ends with zeros.
                "udta" -> boxes(box.body, box.end, zerosEnd = true).any {
                    it.type in PLACES || (it.type == "meta" && metaSaysPlace(it)) || isXmp(it)
                }
                "meta" -> metaSaysPlace(box)
                else -> isXmp(box)
            }
        }

        private fun metaSaysPlace(meta: Box): Boolean {
            if (meta.end - meta.body < HEADER) return false
            // The standard's has a version in front of its boxes, QuickTime's has none.
            val from = meta.body + (if (text(meta, 4) == "hdlr") 0 else 4)
            return boxes(from, meta.end, zerosEnd = true).any { box ->
                when (box.type) {
                    "keys" -> namesPlace(box)
                    "ilst" -> boxes(box.body, box.end, zerosEnd = true).any { item ->
                        item.type in PLACES || (item.type == "----" && inside(item).any { it.type == "name" && namesPlace(it) })
                    }
                    "xml ", "bxml" -> true
                    else -> isXmp(box)
                }
            }
        }

        /** The names in [box] tell of a place (the values are elsewhere). */
        private fun namesPlace(box: Box): Boolean {
            val names = String(bytes(box, 0, size(box, MAX_WORDS)), Charsets.ISO_8859_1).lowercase()
            return PLACE_WORDS.any { it in names }
        }

        private fun isXmp(box: Box): Boolean = box.type == "uuid" && uuid(box) == XMP

        private fun inside(box: Box): List<Box> = boxes(box.body, box.end)

        /** How much [box] holds; one that holds more than [most] is not understood. */
        private fun size(box: Box, most: Int): Int {
            if (box.end - box.body > most) throw NotClean()
            return (box.end - box.body).toInt()
        }

        /** Where the samples of the tracks that go lie, when it is sure that nothing else lies there. */
        private fun samples(): List<Range> {
            // A fragment lists its own.
            if (fragments) {
                if (tracks.any { !it.kept }) throw NotClean()
                return emptyList()
            }
            val gone = tracks.filter { !it.kept }.flatMap { chunks(it.tables) }.sortedBy { it.from }
            val kept = tracks.filter { it.kept }.flatMap { chunks(it.tables) }.sortedBy { it.from }
            var next = 0
            var reached = 0L
            for (r in gone) {
                while (next < kept.size && kept[next].from < r.to) {
                    reached = maxOf(reached, kept[next].to)
                    next++
                }
                if (reached > r.from) throw NotClean()
            }
            cover((gone + kept).sortedBy { it.from })
            return gone
        }

        /**
         * Every sample ([all], in order) lies in an `mdat`, so in no box that goes, and what none of them covers
         * there is empty: a recorder may have left an earlier header in it, a dash camera its places.
         */
        private fun cover(all: List<Range>) {
            var next = 0
            for (box in media) {
                var at = box.body
                while (next < all.size && all[next].from < box.end) {
                    val r = all[next++]
                    if (r.from < box.body || r.to > box.end) throw NotClean()
                    empty(at, r.from)
                    at = maxOf(at, r.to)
                }
                empty(at, box.end)
            }
            if (next < all.size) throw NotClean()
        }

        private fun empty(from: Long, to: Long) {
            if (from >= to) return
            val buffer = ByteArray(minOf(to - from, 64L * 1024).toInt())
            var left = to - from
            f.seek(from)
            while (left > 0) {
                val n = minOf(left, buffer.size.toLong()).toInt()
                f.readFully(buffer, 0, n)
                for (i in 0 until n) if (buffer[i].toInt() != 0) throw NotClean()
                left -= n
            }
        }

        /** Where a track's samples lie, chunk by chunk. */
        private fun chunks(stbl: Box): List<Range> {
            val tables = boxes(stbl.body, stbl.end)
            val sizes = table(tables, "stsz") ?: throw NotClean()
            val perChunk = table(tables, "stsc") ?: throw NotClean()
            val near = table(tables, "stco")
            val far = table(tables, "co64")
            val offsets = near ?: far
            if (offsets == null || (near != null && far != null) || sizes.capacity() < 12) throw NotClean()
            val fixed = sizes.getInt(4)
            if (fixed < 0) throw NotClean()
            val samples = entries(sizes, 8, 12, if (fixed == 0) 4 else 0)
            val rules = entries(perChunk, 4, 8, 12)
            val chunks = entries(offsets, 4, 8, if (near != null) 4 else 8)
            val out = ArrayList<Range>()
            var sample = 0
            var rule = 0
            for (chunk in 0 until chunks) {
                // A rule counts the chunks from 1 and holds until the next one.
                while (rule + 1 < rules && perChunk.getInt(8 + 12 * (rule + 1)) <= chunk + 1) rule++
                val wanted = if (rule < rules) perChunk.getInt(12 + 12 * rule) else 0
                if (wanted < 0) throw NotClean()
                val here = minOf(wanted, samples - sample)
                var bytes = here.toLong() * fixed
                if (fixed == 0) {
                    for (i in sample until sample + here) bytes += unsigned(sizes.getInt(12 + 4 * i))
                }
                sample += here
                val from = if (near != null) unsigned(near.getInt(8 + 4 * chunk)) else offsets.getLong(8 + 8 * chunk)
                if (from < 0 || from > length || bytes > length - from) throw NotClean()
                if (bytes > 0) out.add(Range(from, from + bytes))
            }
            if (sample != samples) throw NotClean()
            return out
        }

        private fun table(tables: List<Box>, type: String): ByteBuffer? {
            val found = tables.filter { it.type == type }
            if (found.size > 1) throw NotClean()
            val box = found.firstOrNull() ?: return null
            if (box.end - box.body > MAX_TABLE) throw NotClean()
            return ByteBuffer.wrap(bytes(box, 0, (box.end - box.body).toInt()))
        }

        /** The number at [at], when the table really holds that many entries of [width] bytes from [first] on. */
        private fun entries(table: ByteBuffer, at: Int, first: Int, width: Int): Int {
            if (table.capacity() < first) throw NotClean()
            val count = table.getInt(at)
            if (count < 0 || count.toLong() * width > table.capacity() - first) throw NotClean()
            return count
        }

        private fun unsigned(number: Int): Long = number.toLong() and 0xFFFFFFFFL

        /** The boxes that fill [from] to [to], to the last byte; [zerosEnd]: zeros may follow the last of them. */
        private fun boxes(from: Long, to: Long, zerosEnd: Boolean = false): List<Box> {
            val out = ArrayList<Box>()
            var at = from
            while (at < to) {
                seen++
                if (seen > MAX_BOXES || (to - at < HEADER && !zerosEnd)) throw NotClean()
                if (to - at < HEADER) {
                    empty(at, to)
                    break
                }
                f.seek(at)
                f.readFully(head)
                var size = unsigned(ByteBuffer.wrap(head).getInt(0))
                var header = HEADER
                if (size == 0L && zerosEnd) {
                    empty(at, to)
                    break
                }
                if (size == 1L) {
                    // A 64-bit size follows the type.
                    if (to - at < LONG_HEADER) throw NotClean()
                    size = f.readLong()
                    header = LONG_HEADER
                } else if (size == 0L && to == length) {
                    // As far as the file goes.
                    size = to - at
                }
                if (size < header || size > to - at) throw NotClean()
                out.add(Box(String(head, 4, 4, Charsets.ISO_8859_1), at, at + header, at + size))
                at += size
            }
            return out
        }

        private fun text(box: Box, at: Int): String = String(bytes(box, at, 4), Charsets.ISO_8859_1)

        private fun uuid(box: Box): String = bytes(box, 0, 16).joinToString("") { "%02x".format(it) }

        private fun bytes(box: Box, at: Int, count: Int): ByteArray {
            if (box.end - box.body < at + count) throw NotClean()
            val out = ByteArray(count)
            f.seek(box.body + at)
            f.readFully(out)
            return out
        }
    }

    // ---- taking it out -------------------------------------------------------------------

    private fun wipe(f: RandomAccessFile, box: Box) {
        f.seek(box.start + 4)
        f.write(FREE)
        zero(f, box.body, box.end)
    }

    private fun zero(f: RandomAccessFile, from: Long, to: Long) {
        var left = to - from
        if (left <= 0) return
        val zeros = ByteArray(minOf(left, 64L * 1024).toInt())
        f.seek(from)
        while (left > 0) {
            val n = minOf(left, zeros.size.toLong()).toInt()
            f.write(zeros, 0, n)
            left -= n
        }
    }

    // ---- what stays ----------------------------------------------------------------------

    private val TOP = setOf("ftyp", "styp", "moov", "moof", "mdat", "imda", "mfra", "sidx", "ssix", "pdin", "prft")
    private val MOVIE = setOf("mvhd", "trak", "mvex", "iods", "pssh", "clip", "ctab")
    private val TRACK = setOf("tkhd", "edts", "tref", "trgr", "mdia", "tapt", "load", "clip", "matt", "txas", "imap", "senc")
    private val MEDIA = setOf("mdhd", "hdlr", "elng", "minf")
    private val MEDIA_INFO = setOf("vmhd", "smhd", "hmhd", "sthd", "nmhd", "gmhd", "hdlr", "dinf", "stbl")
    private val FRAGMENT = setOf("mfhd", "traf", "pssh")
    private val TRACK_FRAGMENT = setOf("tfhd", "tfdt", "trun", "sdtp", "sbgp", "sgpd", "subs", "saiz", "saio", "senc")

    /** Tracks that stay: picture, sound, the picture's depth, the timecode (it counts frames), closed captions. */
    private val SHOWN = setOf("vide", "soun", "auxv", "tmcd", "clcp")

    /** Spherical video (a 360° video would be shown flat), and PIFF's sample encryption and protection header. */
    private val NEEDED = setOf(
        "ffcc8263f8554a938814587a02521fdd",
        "a2394f525a9b4f14a2446c427c648df4",
        "d08a4f1810f34a82b6c832d8aba183d3",
    )

    // ---- where sound says a place --------------------------------------------------------

    /** In `udta` or `ilst`: where it was recorded (QuickTime's, 3GPP's), and XMP. */
    private val PLACES = setOf("\u00A9xyz", "loci", "XMP_")

    /** In the names of what a `meta` box says (QuickTime's keys of a location, or a name of somebody's own). */
    private val PLACE_WORDS = listOf("location", "iso6709", "gps", "xmp")
    private const val XMP = "be7acfcb97a942e89c71999491e3afac"

    // ---- telling files apart -------------------------------------------------------------

    private val ISO = setOf("ftyp", "styp", "moov", "moof", "mdat")
    private val MOOV = setOf("moov")

    /** Brands of pictures. */
    private val PICTURES = setOf(
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "mif2", "msf1", "avif", "avis",
        "avci", "avcs", "jpeg", "jpgs", "j2ki", "j2is", "crx ",
    )
    private val EBML = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    /** Matroska: a track's type, and what comes after the list of them. */
    private const val TRACK_TYPE = 0x83L
    private const val PICTURE_KIND = 1
    private const val SOUND_KIND = 2
    private const val MIXED_KIND = 3
    private const val CLUSTER = 0x1F43B675L

    /** What that list is in: the segment, the tracks, one track. */
    private val ENTERED = setOf(0x18538067L, 0x1654AE6BL, 0xAEL)
    private val FREE = "free".toByteArray(Charsets.ISO_8859_1)
    private val CHAPTER_HANDLERS = setOf("text", "sbtl", "subt")
    private const val HEADER = 8L
    private const val LONG_HEADER = 16L
    private const val MAX_BOXES = 200_000
    private const val MAX_TABLE = 32L * 1024 * 1024

    /** A list of names, or of a track's chapters, is not longer. */
    private const val MAX_WORDS = 64 * 1024

    /** How many boxes are looked at to tell a movie file. */
    private const val MAX_FIRST = 1_000
}
