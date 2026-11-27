package talq.core.org.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import talq.core.org.BRAND_FILE_SIZE
import talq.core.org.BRAND_MAGIC
import talq.core.org.aesGcmOpen
import talq.core.org.aesGcmSeal
import talq.core.org.brandAad
import talq.core.org.BrandPayload
import talq.core.org.CodeInput
import talq.core.org.Opened
import talq.core.org.THEME_GROUNDS
import talq.core.org.TRUSTED_BRAND_KEYS
import talq.core.org.accentFor
import talq.core.org.accentWarnings
import talq.core.org.deriveKeys
import talq.core.org.ed25519NewSeed
import talq.core.org.ed25519PublicKey
import talq.core.org.encodePayload
import talq.core.org.formatInviteCode
import talq.core.org.keyIdOf
import talq.core.org.newInviteSymbols
import talq.core.org.openBrandFile
import talq.core.org.parseBrandPayload
import talq.core.org.parseInviteCode
import talq.core.org.strongRandom
import talq.core.org.toHex
import talq.core.org.writeBrandFile
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.Normalizer
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * The owner's brand issuing tool (spec: "The issuing tool"). It runs ONLY on the owner's PC, in a
 * plain terminal, never in an AI session: it holds the signing key (passphrase-sealed) and writes
 * the invite codes into the owner's record file. It never prints a code.
 *
 *   shared\build\brandtool\brandtool.cmd keygen
 *   shared\build\brandtool\brandtool.cmd issue <brand folder>      (brand.json + logo.png)
 *   shared\build\brandtool\brandtool.cmd update <brand id>
 *   shared\build\brandtool\brandtool.cmd rotate <brand id>
 *   shared\build\brandtool\brandtool.cmd retire <file id>
 *   shared\build\brandtool\brandtool.cmd verify
 *   shared\build\brandtool\brandtool.cmd publish-dir <talq-brands checkout>
 *   (any command: --home <folder> instead of %USERPROFILE%\.talq-android)
 *
 * brand.json: {"name", "welcome"?, "server", "erp"?, "accent": "#RRGGBB", "theme"?}. The tool gives a
 * brand a random id at its first issue (written beside it as brand.id).
 */
internal class BrandHome(val root: Path) {
    val keyFile: Path = root.resolve("brand-signing").resolve("ed25519.key")
    val publicKeyFile: Path = root.resolve("brand-signing").resolve("ed25519.pub")
    val records: Path = root.resolve("brand-codes.txt")
    val sources: Path = root.resolve("brand-sources.txt")
    val staged: Path = root.resolve("brand-files")

    /** The owner's own public key as the app would trust it. */
    fun trusted(): Map<String, String> {
        if (!Files.exists(publicKeyFile)) return emptyMap()
        val (id, hex) = Files.readString(publicKeyFile).trim().split(' ').let { it[0] to it[1] }
        return mapOf(id to hex)
    }

    fun sourceOf(brandId: String): Path? = sourceMap()[brandId]

    fun sourceMap(): Map<String, Path> =
        if (!Files.exists(sources)) emptyMap()
        else Files.readAllLines(sources).mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to Paths.get(it[1]) } }.toMap()

    /** Remembers [dir] as [brandId]'s folder; a brand's folder is never re-pointed to another one. */
    fun registerSource(brandId: String, dir: Path) {
        sourceOf(brandId)?.let { known ->
            check(sameFolder(known, dir)) { "brand $brandId already has its folder ($known)" }
            return
        }
        val map = sourceMap() + (brandId to dir.toAbsolutePath())
        Files.createDirectories(root)
        Files.write(sources, map.entries.joinToString("") { "${it.key}\t${it.value}\n" }.encodeToByteArray())
    }
}

/** Whether two paths are the same folder (the real file when both exist, else the normalised paths). */
private fun sameFolder(a: Path, b: Path): Boolean =
    runCatching { Files.isSameFile(a, b) }.getOrDefault(a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize())

/**
 * Whether the app built from this checkout trusts the tool's key (TRUSTED_BRAND_KEYS): if not, every
 * phone would refuse the files `verify` calls fine. Checked by `verify` and after `keygen`.
 */
internal fun appTrustsHomeKey(home: BrandHome): Boolean {
    val own = home.trusted()
    return own.isNotEmpty() && own.all { (id, hex) -> TRUSTED_BRAND_KEYS[id] == hex }
}

internal data class Issued(val brandId: String, val fileId: String)

internal object BrandOps {
    private const val PUBLISHED_FILES = 32
    private const val LOGO_SIDE = 512
    private const val LOGO_MAX_BYTES = 96 * 1024
    private val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun keygen(home: BrandHome, passphrase: CharArray): Pair<String, String> {
        val seed = ed25519NewSeed()
        writeKeyFile(home.keyFile, seed, passphrase)
        val pub = ed25519PublicKey(seed)
        seed.fill(0)
        val id = keyIdOf(pub)
        Files.writeString(home.publicKeyFile, "$id ${pub.toHex()}\n")
        return id to pub.toHex()
    }

    fun issue(home: BrandHome, dir: Path, passphrase: CharArray, now: Long): Issued {
        val seed = seed(home, passphrase)
        val idFile = dir.resolve("brand.id")
        val brandId = if (Files.exists(idFile)) Files.readString(idFile).trim() else randomId()
        // A folder copied as a template carries the other brand's id: never merge two organisations.
        home.sourceOf(brandId)?.let { known ->
            check(sameFolder(known, dir)) {
                "this folder carries brand $brandId's brand.id (its folder is $known); remove brand.id to issue a new brand"
            }
        }
        val payload = payloadFrom(home, dir, brandId, now)
        val issued = writeNewCode(home, payload, seed)
        if (!Files.exists(idFile)) Files.writeString(idFile, brandId + "\n")
        home.registerSource(brandId, dir)
        seed.fill(0)
        return issued
    }

    /** New content for every code of [brandId]: a fresh nonce and a newer `issued` for each. */
    fun update(home: BrandHome, brandId: String, passphrase: CharArray, now: Long): List<String> {
        val seed = seed(home, passphrase)
        val dir = home.sourceOf(brandId) ?: error("unknown brand $brandId")
        val codes = activeRecords(home.records).filter { it.brandId == brandId }
        check(codes.isNotEmpty()) { "brand $brandId has no code in use" }
        val issued = maxOf(now, lastIssued(home, brandId) + 1)
        val payload = payloadFrom(home, dir, brandId, issued)
        for (r in codes) writeFile(home, payload, symbolsOf(r.code), seed)
        seed.fill(0)
        return codes.map { it.fileId }
    }

    /** A new code for [brandId], beside the ones in use. */
    fun rotate(home: BrandHome, brandId: String, passphrase: CharArray, now: Long): Issued {
        val seed = seed(home, passphrase)
        val dir = home.sourceOf(brandId) ?: error("unknown brand $brandId")
        val payload = payloadFrom(home, dir, brandId, maxOf(now, lastIssued(home, brandId)))
        val issued = writeNewCode(home, payload, seed)
        seed.fill(0)
        return issued
    }

    /** Takes a code out of use: its file leaves the staging folder (and, at the next publish, the host). */
    fun retire(home: BrandHome, fileId: String) {
        val r = activeRecords(home.records).firstOrNull { it.fileId == fileId } ?: error("no code in use has file $fileId")
        appendRetired(home.records, Instant.now().toString(), r.brandId, fileId)
        Files.deleteIfExists(home.staged.resolve("$fileId.bin"))
    }

    /** Every code in use, opened as the app opens it: file id -> it opens, is signed, and is that brand's. */
    fun verify(home: BrandHome, trusted: Map<String, String> = home.trusted()): Map<String, Boolean> =
        activeRecords(home.records).associate { r ->
            val file = home.staged.resolve("${r.fileId}.bin")
            val ok = Files.exists(file) &&
                (openBrandFile(Files.readAllBytes(file), deriveKeys(symbolsOf(r.code)), trusted) as? Opened.Ok)?.payload?.id == r.brandId
            r.fileId to ok
        }

    /**
     * Lays out `v1/` of a talq-brands checkout so that it says nothing about the organisations:
     * - always [PUBLISHED_FILES] files of one size, each starting with the same magic bytes;
     * - names stay from one publish to the next: a decoy keeps its name, a retired code's name stays
     *   as a decoy, and a new code's file takes the place of one decoy;
     * - every file gets new bytes at every publish (real ones sealed again under a fresh nonce, the
     *   code's key from the records; decoys new random bytes), so a changed brand does not stand out.
     * Also `.nojekyll` and a neutral README. Publishing (one orphan commit, force-pushed) is the
     * owner's step.
     */
    fun publishDir(home: BrandHome, checkout: Path) {
        val active = activeRecords(home.records)
        check(active.size <= PUBLISHED_FILES) { "more codes in use than published files" }
        val v1 = Files.createDirectories(checkout.resolve("v1"))
        val realNames = active.map { "${it.fileId}.bin" }.toSet()
        val present = Files.list(v1).use { s -> s.toList() }.map { it.fileName.toString() }.filter { it.endsWith(".bin") }
        val decoys = present.filter { it !in realNames }.toMutableList()
        // Fewer decoys are needed when codes were added: drop that many; more when codes were retired.
        while (decoys.size > PUBLISHED_FILES - active.size) Files.delete(v1.resolve(decoys.removeAt(decoys.size - 1)))
        while (decoys.size < PUBLISHED_FILES - active.size) decoys += strongRandom(16).toHex() + ".bin"
        for (r in active) {
            val keys = deriveKeys(symbolsOf(r.code))
            val file = Files.readAllBytes(home.staged.resolve("${r.fileId}.bin"))
            val envelope = aesGcmOpen(keys.aesKey, file.copyOfRange(4, 16), brandAad(keys.fileId), file.copyOfRange(16, file.size))
                ?: error("the staged file ${r.fileId} does not open with its code")
            val nonce = strongRandom(12)
            Files.write(v1.resolve("${r.fileId}.bin"), BRAND_MAGIC.encodeToByteArray() + nonce + aesGcmSeal(keys.aesKey, nonce, brandAad(keys.fileId), envelope))
        }
        for (name in decoys) {
            Files.write(v1.resolve(name), BRAND_MAGIC.encodeToByteArray() + strongRandom(BRAND_FILE_SIZE - BRAND_MAGIC.length))
        }
        Files.write(checkout.resolve(".nojekyll"), ByteArray(0))
        Files.writeString(checkout.resolve("README.md"), "Static files.\n")
    }

    /** One line per theme with the colour it will show, then any warning. */
    fun accentReport(accent: Int): List<String> =
        THEME_GROUNDS.map { g ->
            val s = accentFor(accent, g)
            "${g.name}: " + (s?.let { "#" + it.accent.toString(16).padStart(6, '0').uppercase() } ?: "keeps teal")
        } + accentWarnings(accent).map { "warning: $it" }

    // ------------------------------------------------------------------------------ inside

    private fun seed(home: BrandHome, passphrase: CharArray): ByteArray =
        readKeyFile(home.keyFile, passphrase) ?: error("the passphrase does not open the signing key")

    private fun randomId(): String = strongRandom(12).joinToString("") { ID_CHARS[(it.toInt() and 0xFF) % ID_CHARS.length].toString() }

    private fun symbolsOf(code: String): String = (parseInviteCode(code) as? CodeInput.Valid)?.symbols ?: error("a broken record")

    private fun writeNewCode(home: BrandHome, payload: BrandPayload, seed: ByteArray): Issued {
        val symbols = newInviteSymbols { strongRandom(it) }
        val fileId = writeFile(home, payload, symbols, seed)
        appendRecord(home.records, CodeRecord(Instant.now().toString(), payload.id, fileId, formatInviteCode(symbols)))
        return Issued(payload.id, fileId)
    }

    private fun writeFile(home: BrandHome, payload: BrandPayload, symbols: String, seed: ByteArray): String {
        val derived = deriveKeys(symbols)
        val file = writeBrandFile(payload, derived, seed, strongRandom(12))
        Files.createDirectories(home.staged)
        Files.write(home.staged.resolve(derived.fileIdHex + ".bin"), file)
        return derived.fileIdHex
    }

    private fun lastIssued(home: BrandHome, brandId: String): Long =
        activeRecords(home.records).filter { it.brandId == brandId }.mapNotNull { r ->
            val f = home.staged.resolve("${r.fileId}.bin")
            if (!Files.exists(f)) null
            else (openBrandFile(Files.readAllBytes(f), deriveKeys(symbolsOf(r.code)), home.trusted()) as? Opened.Ok)?.payload?.issued
        }.maxOrNull() ?: 0L

    /** The brand in [dir] as a payload, through every gate: the app's rules and the look-alike rules. No signing path skips it. */
    private fun payloadFrom(home: BrandHome, dir: Path, brandId: String, issued: Long): BrandPayload {
        val o = Json.parseToJsonElement(Files.readString(dir.resolve("brand.json"))) as? JsonObject ?: error("brand.json is not an object")
        fun text(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val accent = text("accent")?.takeIf { Regex("#[0-9A-Fa-f]{6}").matches(it) }?.substring(1)?.toInt(16)
            ?: error("brand.json needs \"accent\": \"#RRGGBB\"")
        val payload = BrandPayload(
            id = brandId, issued = issued,
            name = text("name") ?: error("brand.json needs \"name\""),
            welcome = text("welcome"),
            server = text("server") ?: error("brand.json needs \"server\""),
            erp = text("erp"), accent = accent, theme = text("theme"),
            logoPng = prepareLogo(dir.resolve("logo.png")),
        )
        // The app's own rules decide: a brand the phone would refuse is never written.
        checkNotNull(parseBrandPayload(encodePayload(payload))) {
            "brand.json breaks a rule (name at most 40, welcome at most 80, plain single-line text, https server and ERP " +
                "with an ASCII host, theme one of ember warm vivid paper system)"
        }
        check(singleScript(payload.name)) { "the name mixes writing systems (for example Latin and Cyrillic letters)" }
        refuseLookAlikes(home, payload)
        return payload
    }

    /** The logo as a 512 x 512 PNG (letterboxed, transparent), which also drops anything else the file carried. */
    private fun prepareLogo(path: Path): ByteArray {
        val src = ImageIO.read(path.toFile()) ?: error("logo.png is not a picture")
        val scale = minOf(LOGO_SIDE.toDouble() / src.width, LOGO_SIDE.toDouble() / src.height)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(LOGO_SIDE, LOGO_SIDE, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, (LOGO_SIDE - w) / 2, (LOGO_SIDE - h) / 2, w, h, null)
        g.dispose()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
        check(bytes.size <= LOGO_MAX_BYTES) { "the logo is ${bytes.size / 1024} KiB as a 512 px PNG; at most 96 KiB (use a simpler logo)" }
        return bytes
    }

    /** No brand may look like another (a name with the same skeleton) or use another's server. */
    private fun refuseLookAlikes(home: BrandHome, p: BrandPayload) {
        for ((id, dir) in home.sourceMap()) {
            if (id == p.id || !Files.exists(dir.resolve("brand.json"))) continue
            val other = Json.parseToJsonElement(Files.readString(dir.resolve("brand.json"))) as? JsonObject ?: continue
            val name = (other["name"] as? JsonPrimitive)?.content.orEmpty()
            val server = (other["server"] as? JsonPrimitive)?.content.orEmpty()
            check(skeleton(name) != skeleton(p.name)) { "the name looks like brand $id's" }
            check(hostOf(server) != hostOf(p.server)) { "brand $id already uses that server's host" }
            check(skeleton(hostOf(server)) != skeleton(hostOf(p.server))) { "the server's host looks like brand $id's" }
        }
    }

    /**
     * A name's look, for "does it look like another": NFKC, lower case, the Cyrillic and Greek letters
     * that look Latin mapped to those, then digits and pairs that pass for letters. A small fixed
     * table, not all of Unicode's confusables (TR39); together with [singleScript] and the owner being
     * the only issuer, it is a guard against mistakes and borrowed names, not a proof.
     */
    private fun skeleton(name: String): String {
        var s = Normalizer.normalize(name, Normalizer.Form.NFKC)
        // Capitals first: a Greek or Cyrillic capital that looks Latin may lower-case to another shape.
        s = s.map { CAPITALS_LOOK_LATIN[it] ?: it }.joinToString("").lowercase()
        s = s.map { LOOKS_LATIN[it] ?: it }.joinToString("")
        // Single look-alikes before the pairs, so "CIient" and "Client" meet in the same "cl".
        s = s.map { when (it) { '0' -> 'o'; '1', '|', 'i' -> 'l'; '5' -> 's'; '3' -> 'e'; '8' -> 'b'; else -> it } }.joinToString("")
        s = s.replace("rn", "m").replace("vv", "w").replace("cl", "d")
        return s.filter { it.isLetterOrDigit() }
    }

    /** Greek and Cyrillic capitals drawn like Latin ones (to the Latin lower-case letter). */
    private val CAPITALS_LOOK_LATIN: Map<Char, Char> = mapOf(
        'Α' to 'a', 'Β' to 'b', 'Ε' to 'e', 'Ζ' to 'z', 'Η' to 'h', 'Ι' to 'l', 'Κ' to 'k', 'Μ' to 'm', 'Ν' to 'n',
        'Ο' to 'o', 'Ρ' to 'p', 'Τ' to 't', 'Υ' to 'y', 'Χ' to 'x',
        'А' to 'a', 'В' to 'b', 'Е' to 'e', 'К' to 'k', 'М' to 'm', 'Н' to 'h', 'О' to 'o', 'Р' to 'p', 'С' to 'c',
        'Т' to 't', 'Х' to 'x', 'У' to 'y', 'І' to 'l', 'Ј' to 'j', 'Ѕ' to 's',
    )

    /** Lower-case Cyrillic and Greek letters drawn like Latin ones. */
    private val LOOKS_LATIN: Map<Char, Char> = mapOf(
        'а' to 'a', 'в' to 'b', 'е' to 'e', 'ё' to 'e', 'к' to 'k', 'м' to 'm', 'н' to 'h', 'о' to 'o', 'р' to 'p',
        'с' to 'c', 'т' to 't', 'у' to 'y', 'х' to 'x', 'і' to 'i', 'ј' to 'j', 'ѕ' to 's', 'ԁ' to 'd', 'ԛ' to 'q', 'ԝ' to 'w',
        'α' to 'a', 'β' to 'b', 'ε' to 'e', 'ι' to 'i', 'κ' to 'k', 'ν' to 'v', 'ο' to 'o', 'ρ' to 'p', 'τ' to 't',
        'υ' to 'u', 'χ' to 'x', 'γ' to 'y', 'ω' to 'w',
    )

    /** Whether every letter of [name] is of one writing system (shared marks and punctuation aside). */
    private fun singleScript(name: String): Boolean {
        val scripts = name.codePoints().toArray().map { Character.UnicodeScript.of(it) }
            .filter { it != Character.UnicodeScript.COMMON && it != Character.UnicodeScript.INHERITED }
            .toSet()
        return scripts.size <= 1
    }

    /** The host of a canonical server address: two brands on one host are refused, whatever the path or port. */
    private fun hostOf(server: String): String = server.removePrefix("https://").substringBefore('/').substringBefore(':').lowercase()

    // ------------------------------------------------------------------------------ the console

    @JvmStatic
    fun main(args: Array<String>) {
        val list = args.toMutableList()
        val homeAt = list.indexOf("--home").takeIf { it >= 0 }?.let { i -> list.removeAt(i); Paths.get(list.removeAt(i)) }
        val home = BrandHome(homeAt ?: Paths.get(System.getProperty("user.home"), ".talq-android"))
        val console = System.console() ?: run {
            println("Run brandtool.cmd in a terminal of its own (it asks for the passphrase without showing it).")
            exitProcess(2)
        }
        fun pass(prompt: String = "Passphrase: ") = console.readPassword(prompt) ?: exitProcess(2)
        val now = Instant.now().epochSecond
        try {
            when (list.firstOrNull()) {
                "keygen" -> {
                    val p1 = pass("New passphrase (at least 12 characters): ")
                    val p2 = pass("Again: ")
                    check(p1.contentEquals(p2)) { "the two passphrases differ" }
                    val (id, hex) = keygen(home, p1)
                    println("Signing key made: ${home.keyFile}")
                    println("Key id: $id")
                    println("Public key: $hex")
                    println("Put these two public values into shared/src/commonMain/kotlin/talq/core/org/OrgSigningKeys.kt.")
                    println("Back up the key file and the passphrase, apart from each other.")
                }
                "issue" -> {
                    val issued = issue(home, Paths.get(list[1]), pass(), now)
                    println("Brand ${issued.brandId}: file ${issued.fileId}. The code is in ${home.records}.")
                    report(home, issued.brandId)
                }
                "update" -> {
                    val files = update(home, list[1], pass(), now)
                    println("Brand ${list[1]}: ${files.size} file(s) written anew.")
                    report(home, list[1])
                }
                "rotate" -> {
                    val issued = rotate(home, list[1], pass(), now)
                    println("Brand ${issued.brandId}: new file ${issued.fileId}. The new code is in ${home.records}.")
                }
                "retire" -> {
                    retire(home, list[1])
                    println("File ${list[1]} retired; publish again to take it off the host.")
                }
                "verify" -> {
                    verify(home).forEach { (file, ok) -> println("$file: ${if (ok) "ok" else "DOES NOT VERIFY"}") }
                    if (!appTrustsHomeKey(home)) {
                        println("WARNING: the app built from this checkout does not trust this key: phones would refuse these files.")
                        println("Put the key id and public key into shared/src/commonMain/kotlin/talq/core/org/OrgSigningKeys.kt.")
                    }
                }
                "publish-dir" -> {
                    publishDir(home, Paths.get(list[1]))
                    println("Laid out ${list[1]}\\v1. Publish it as one orphan commit with a neutral message.")
                }
                else -> {
                    println("commands: keygen | issue <brand folder> | update <brand id> | rotate <brand id> | retire <file id> | verify | publish-dir <checkout>")
                    exitProcess(2)
                }
            }
        } catch (e: Exception) {
            println("Not done: ${e.message}")
            exitProcess(1)
        }
    }

    private fun report(home: BrandHome, brandId: String) {
        val dir = home.sourceOf(brandId) ?: return
        val o = Json.parseToJsonElement(Files.readString(dir.resolve("brand.json"))) as? JsonObject ?: return
        val accent = (o["accent"] as? JsonPrimitive)?.content?.substring(1)?.toIntOrNull(16) ?: return
        accentReport(accent).forEach { println("  $it") }
    }
}
