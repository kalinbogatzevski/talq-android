package talq.core.smoke

import talq.core.org.BrandPayload
import talq.core.org.tool.appTrustsHomeKey
import java.nio.file.attribute.AclFileAttributeView
import kotlin.io.encoding.Base64
import talq.core.org.hideInviteCodes
import talq.core.org.BRAND_MAGIC
import talq.core.typing.clientHello
import talq.core.typing.clientHelloStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import talq.core.org.orgFlowAllowed
import talq.core.org.sameServer
import talq.core.org.tool.BrandHome
import talq.core.org.tool.BrandOps
import talq.core.org.tool.activeRecords
import talq.core.org.tool.readKeyFile
import talq.core.org.tool.writeKeyFile
import java.nio.file.Files
import java.nio.file.Path
import talq.core.org.THEME_GROUNDS
import talq.core.org.accentFor
import talq.core.org.accentWarnings
import talq.core.org.contrastRatio
import talq.core.org.readableOn
import talq.core.org.CodeInput
import talq.core.org.Opened
import talq.core.org.RefreshOutcome
import talq.core.org.acceptsRefresh
import talq.core.org.canonicalOrgServer
import talq.core.org.encodePayload
import talq.core.org.openBrandFile
import talq.core.org.orgErp
import talq.core.org.parseBrandPayload
import talq.core.org.pngSize
import talq.core.org.refreshOutcome
import talq.core.org.toHex
import talq.core.org.writeBrandFile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import talq.core.org.aesGcmOpen
import talq.core.org.aesGcmSeal
import talq.core.org.deriveKeys
import talq.core.org.ed25519NewSeed
import talq.core.org.ed25519PublicKey
import talq.core.org.ed25519Sign
import talq.core.org.ed25519Verify
import talq.core.org.keyIdOf
import talq.core.org.checkSymbol
import talq.core.org.formatInviteCode
import talq.core.org.newInviteSymbols
import talq.core.org.parseInviteCode
import kotlin.system.exitProcess

/**
 * Offline checks of organisation mode's security core (talq.core.org): the invite code, the key
 * derivation, the brand file and its signature, the payload rules, the refresh rule, the colour
 * rules and the owner's issuing tool. No network and nothing written outside a temp folder; every
 * key used here is a throwaway one made on the spot:
 *
 *   ./gradlew :shared:orgCheck
 *
 * Prints one [ok]/[FAIL] line per check and exits non-zero on any failure.
 */
object OrgCheck {
    private var failures = 0
    private var passes = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            testInviteCode()
            testDerivation()
            testBrandFile()
            testColours()
            testTool()
            testLogin()
            testHello()
            if (failures == 0) 0 else 1
        } catch (e: Throwable) {
            println("ORG CHECK ABORTED: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            2
        }
        println(if (code == 0) "\nORG CHECK OK ($passes checks)" else "\nORG CHECK FAILED ($failures of ${passes + failures} checks failed)")
        exitProcess(code)
    }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) passes++ else failures++
        println("  [${if (ok) "ok" else "FAIL"}] $name${if (detail.isNotEmpty()) "  -- $detail" else ""}")
    }

    // ---------------------------------------------------------------------------------- the code

    private fun testInviteCode() {
        println("invite code")
        check("code: check symbol", checkSymbol("0123456789ABCDEF") == '1')
        check("code: check symbol 2", checkSymbol("7ZK3QM9VX2HT4RWP") == '5')
        check("code: format", formatInviteCode("0123456789ABCDEF") == "0123-4567-89AB-CDEF-1")
        check("code: forgiving", parseInviteCode(" o123-4567-89ab-cdef-l ") == CodeInput.Valid("0123456789ABCDEF"))
        check("code: bad check", parseInviteCode("0123-4567-89AB-CDEF-2") == CodeInput.BadCheck)
        check("code: short", parseInviteCode("0123-4567") == CodeInput.Incomplete)
        check("code: U refused", parseInviteCode("U123-4567-89AB-CDEF-1") == CodeInput.BadSymbol)
        check("code: too long", parseInviteCode("0123-4567-89AB-CDEF-1X") == CodeInput.BadSymbol)
        check("code: pasted with a line break", parseInviteCode("0123-4567-89AB-CDEF-1\r\n") == CodeInput.Valid("0123456789ABCDEF"))
        check("code: non-breaking spaces", parseInviteCode("0123\u00A04567\u00A089AB\u00A0CDEF\u00A01") == CodeInput.Valid("0123456789ABCDEF"))
        check("code: typographic dashes", parseInviteCode("0123\u20134567\u201089AB\u2212CDEF\u20141") == CodeInput.Valid("0123456789ABCDEF"))
        // B: diagnostics hide real codes only
        check("hide: dashed code", hideInviteCodes("code 0123-4567-89AB-CDEF-1 here") == "code <hidden> here")
        check("hide: plain lower case", hideInviteCodes("x 0123456789abcdef1 y") == "x <hidden> y")
        check("hide: forgiving form", hideInviteCodes("o123-4567-89ab-cdef-l") == "<hidden>")
        check("hide: an exception name stays", hideInviteCodes("refused (SecurityException)") == "refused (SecurityException)")
        // A mistyped check symbol still carries the 16 real symbols: hidden too.
        check("hide: a wrong check symbol is hidden too", hideInviteCodes("0123-4567-89AB-CDEF-2") == "<hidden>")
        check("hide: spaced code", hideInviteCodes("code 0123 4567 89AB CDEF 1 end") == "code <hidden> end")
        check("hide: typographic dashes", hideInviteCodes("0123\u20134567\u201089AB\u2212CDEF\u20141") == "<hidden>")
        check("hide: no-break spaces", hideInviteCodes("x 0123\u00A04567\u00A089AB\u00A0CDEF\u00A01 y") == "x <hidden> y")
        check("hide: runs of separators", hideInviteCodes("0123 - 4567 - 89AB - CDEF - 1") == "<hidden>")
        check("hide: a word with U stays", hideInviteCodes("IllegalArgumentEx UnsupportedOperat") == "IllegalArgumentEx UnsupportedOperat")
        check("code: 80 bits", newInviteSymbols { ByteArray(it) { 0xFF.toByte() } } == "ZZZZZZZZZZZZZZZZ")
        check("code: zero", newInviteSymbols { ByteArray(it) } == "0000000000000000")
        check("code: asks for 10 bytes", run { var n = 0; newInviteSymbols { n = it; ByteArray(it) }; n == 10 })
    }

    // ---------------------------------------------------------------------------- derivation

    private fun testDerivation() {
        println("derivation and crypto")
        val d = deriveKeys("0123456789ABCDEF")
        check("derive: file id", d.fileIdHex == "e77a05f7c873010f5c431bd445aaba76", d.fileIdHex)
        check("derive: key", d.aesKey.hex() == "f4e9e92bed1b94529f46cad27db20da3d576502f8a8ef834b6ca588773b8ff2a")
        check("derive: other code differs", deriveKeys("7ZK3QM9VX2HT4RWP").fileIdHex != d.fileIdHex)
        val seed = ed25519NewSeed()
        val pub = ed25519PublicKey(seed)
        val msg = "x".encodeToByteArray()
        val sig = ed25519Sign(seed, msg)
        check("ed25519: sizes", seed.size == 32 && pub.size == 32 && sig.size == 64)
        check("ed25519: verifies", ed25519Verify(pub, sig, msg))
        check("ed25519: other message", !ed25519Verify(pub, sig, "y".encodeToByteArray()))
        check("ed25519: short key", !ed25519Verify(pub.copyOf(31), sig, msg))
        check("ed25519: other key", !ed25519Verify(ed25519PublicKey(ed25519NewSeed()), sig, msg))
        check("ed25519: flipped signature", !ed25519Verify(pub, sig.copyOf().also { it[5] = (it[5] + 1).toByte() }, msg))
        check("key id: 8 hex", keyIdOf(pub).matches(Regex("[0-9a-f]{8}")))
        val nonce = ByteArray(12)
        val sealed = aesGcmSeal(d.aesKey, nonce, "a".encodeToByteArray(), msg)
        check("gcm: opens", aesGcmOpen(d.aesKey, nonce, "a".encodeToByteArray(), sealed)?.contentEquals(msg) == true)
        check("gcm: wrong aad", aesGcmOpen(d.aesKey, nonce, "b".encodeToByteArray(), sealed) == null)
        check("gcm: wrong key", aesGcmOpen(deriveKeys("7ZK3QM9VX2HT4RWP").aesKey, nonce, "a".encodeToByteArray(), sealed) == null)
        check("gcm: truncated", aesGcmOpen(d.aesKey, nonce, "a".encodeToByteArray(), sealed.copyOf(sealed.size - 1)) == null)
    }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // ---------------------------------------------------------------------------- brand file

    private fun png(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private val sample by lazy {
        BrandPayload(
            id = "demo0001", issued = 200, name = "Demo Organisation", welcome = "Welcome back",
            server = "https://cloud.example.com", erp = "https://erp.example.com", accent = 0x0A3D91,
            theme = "vivid", logoPng = png(16, 16),
        )
    }

    private fun testBrandFile() {
        println("brand file and payload")
        val d = deriveKeys("0123456789ABCDEF")
        val d2 = deriveKeys("7ZK3QM9VX2HT4RWP")
        val seed = ed25519NewSeed()
        val pub = ed25519PublicKey(seed)
        val trusted = mapOf(keyIdOf(pub) to pub.toHex())
        var n = 0
        fun nonce() = ByteArray(12).also { it[0] = (++n).toByte() }
        fun ok(p: BrandPayload) = openBrandFile(writeBrandFile(p, d, seed, nonce()), d, trusted) as Opened.Ok

        val file = writeBrandFile(sample, d, seed, nonce())
        check("file: fixed size", file.size == writeBrandFile(sample.copy(name = "A much longer organisation name", welcome = null, erp = null), d, seed, nonce()).size)
        check("file: fixed size, bigger logo", file.size == writeBrandFile(sample.copy(logoPng = png(512, 512)), d, seed, nonce()).size)
        val opened = openBrandFile(file, d, trusted)
        check("file: opens", opened is Opened.Ok && opened.payload.name == "Demo Organisation" && opened.payload.server == "https://cloud.example.com")
        check("file: other code", openBrandFile(file, d2, trusted) == Opened.Bad)
        check("file: unknown key", openBrandFile(file, d, emptyMap()) == Opened.Bad)
        check("file: other signer", openBrandFile(writeBrandFile(sample, d, ed25519NewSeed(), nonce()), d, trusted) == Opened.Bad)
        check("file: flipped byte", openBrandFile(file.copyOf().also { it[40] = (it[40] + 1).toByte() }, d, trusted) == Opened.Bad)
        check("file: truncated", openBrandFile(file.copyOf(file.size - 1), d, trusted) == Opened.Bad)
        // The same signed envelope, encrypted under another code: it decrypts, but it was signed for the first id.
        val envelope = (opened as Opened.Ok).envelope
        val aad2 = "TQB1".encodeToByteArray() + d2.fileId
        val n2 = nonce()
        val resealed = "TQB1".encodeToByteArray() + n2 + aesGcmSeal(d2.aesKey, n2, aad2, envelope)
        check("file: signed for another id", openBrandFile(resealed, d2, trusted) == Opened.Bad)
        // The envelope opened, one payload byte changed, the original signature and trusted key id kept,
        // sealed again under the same code: the signature must refuse it.
        val env = envelope.decodeToString()
        val payloadB64 = env.substringAfter("\"payload\":\"").substringBefore('"')
        val payloadBytes = Base64.Default.decode(payloadB64)
        val tampered = payloadBytes.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }
        val tamperedEnv = env.replace(payloadB64, Base64.Default.encode(tampered)).encodeToByteArray()
        val n3 = nonce()
        val aad1 = "TQB1".encodeToByteArray() + d.fileId
        check("file: tampered payload refused", openBrandFile("TQB1".encodeToByteArray() + n3 + aesGcmSeal(d.aesKey, n3, aad1, tamperedEnv), d, trusted) == Opened.Bad)
        // The same signature under another TRUSTED key id: refused too.
        val other = ed25519PublicKey(ed25519NewSeed())
        val twoKeys = trusted + (keyIdOf(other) to other.toHex())
        val swappedEnv = env.replace("\"key\":\"${keyIdOf(pub)}\"", "\"key\":\"${keyIdOf(other)}\"").encodeToByteArray()
        val n4 = nonce()
        check("file: another trusted key id refused", openBrandFile("TQB1".encodeToByteArray() + n4 + aesGcmSeal(d.aesKey, n4, aad1, swappedEnv), d, twoKeys) == Opened.Bad)
        check("file: the untouched envelope still opens with both keys trusted",
            openBrandFile("TQB1".encodeToByteArray() + n4 + aesGcmSeal(d.aesKey, n4, aad1, envelope), d, twoKeys) is Opened.Ok)

        check("server: ok", canonicalOrgServer("https://cloud.example.com/nc") == "https://cloud.example.com/nc")
        check("server: port", canonicalOrgServer("https://cloud.example.com:8443") == "https://cloud.example.com:8443")
        check("server: user part", canonicalOrgServer("https://good.example@evil.example") == null)
        check("server: http", canonicalOrgServer("http://cloud.example.com") == null)
        check("server: idn", canonicalOrgServer("https://xn--clod-8na.example.com") == null)
        check("server: ip", canonicalOrgServer("https://192.0.2.1") == null)
        check("server: ipv6", canonicalOrgServer("https://[2001:db8::1]") == null)
        check("server: localhost", canonicalOrgServer("https://localhost") == null)
        check("server: .local", canonicalOrgServer("https://nas.local") == null)
        check("server: upper case", canonicalOrgServer("https://Cloud.example.com") == null)
        check("server: trailing slash", canonicalOrgServer("https://cloud.example.com/") == null)
        check("server: query", canonicalOrgServer("https://cloud.example.com/?a=b") == null)
        check("server: backslash", canonicalOrgServer("https://cloud.example.com" + '\\' + "@evil.example") == null)
        check("erp: ok", orgErp("https://erp.example.com") == "https://erp.example.com")
        check("erp: loopback http", orgErp("http://localhost:8080") == null)
        check("erp: ip", orgErp("https://10.0.0.1") == null)

        check("payload: round trip", parseBrandPayload(encodePayload(sample))?.let { it.name == sample.name && it.accent == sample.accent && it.logoPng.contentEquals(sample.logoPng) } == true)
        check("payload: padded size", encodePayload(sample).size == 163_840)
        check("payload: bidi name", parseBrandPayload(encodePayload(sample.copy(name = "A" + '‮' + "b"))) == null)
        check("payload: line break", parseBrandPayload(encodePayload(sample.copy(welcome = "a" + '\n' + "b"))) == null)
        check("payload: long name", parseBrandPayload(encodePayload(sample.copy(name = "x".repeat(41)))) == null)
        check("payload: bad id", parseBrandPayload(encodePayload(sample.copy(id = "Demo"))) == null)
        check("payload: bad theme", parseBrandPayload(encodePayload(sample.copy(theme = "neon"))) == null)
        check("payload: http server", parseBrandPayload(encodePayload(sample.copy(server = "http://cloud.example.com"))) == null)
        check("payload: big logo", parseBrandPayload(encodePayload(sample.copy(logoPng = png(1025, 8)))) == null)
        check("payload: not png", parseBrandPayload(encodePayload(sample.copy(logoPng = ByteArray(40)))) == null)
        check("payload: unpadded", parseBrandPayload("{}".encodeToByteArray()) == null)
        check("png: size", pngSize(png(7, 9)) == (7 to 9))

        val stored = ok(sample)
        check("refresh: older refused", !acceptsRefresh(200, stored.payloadBytes, ok(sample.copy(issued = 100))))
        check("refresh: same bytes ok", acceptsRefresh(200, stored.payloadBytes, ok(sample)))
        check("refresh: same issued other bytes refused", !acceptsRefresh(200, stored.payloadBytes, ok(sample.copy(name = "Other"))))
        check("refresh: older kept", refreshOutcome(stored.payload, stored.payloadBytes, ok(sample.copy(issued = 100))) == RefreshOutcome.Keep)
        check("refresh: newer replaces", refreshOutcome(stored.payload, stored.payloadBytes, ok(sample.copy(issued = 300, name = "Renamed"))) == RefreshOutcome.Replace)
        check("refresh: address change is held", refreshOutcome(stored.payload, stored.payloadBytes, ok(sample.copy(issued = 300, server = "https://new.example.com"))) is RefreshOutcome.Hold)
        check("refresh: erp change is held", refreshOutcome(stored.payload, stored.payloadBytes, ok(sample.copy(issued = 300, erp = null))) is RefreshOutcome.Hold)
        // A change held at issued 400: an older genuine file (300) can neither replace nor re-point it.
        check("refresh: older than held kept", refreshOutcome(stored.payload, stored.payloadBytes, ok(sample.copy(issued = 300, server = "https://old.example.com")), heldIssued = 400) == RefreshOutcome.Keep)
        check("refresh: older than held, same addresses, kept", refreshOutcome(stored.payload, stored.payloadBytes, ok(sample.copy(issued = 300, name = "Older")), heldIssued = 400) == RefreshOutcome.Keep)
        check("refresh: newer than held replaces", refreshOutcome(stored.payload, stored.payloadBytes, ok(sample.copy(issued = 500, name = "Newest")), heldIssued = 400) == RefreshOutcome.Replace)
    }

    // ---------------------------------------------------------------------------- colours

    private fun testColours() {
        println("colours")
        check("grounds: four themes", THEME_GROUNDS.map { it.name } == listOf("ember", "warm", "vivid", "paper"))
        for (brand in listOf(0x0A3D91, 0xE30613, 0x00A651, 0xFFD400, 0x111111, 0xFFFFFF)) {
            THEME_GROUNDS.forEach { g ->
                val s = accentFor(brand, g)
                val tag = "accent ${brand.toString(16)} ${g.name}"
                check("$tag: found", s != null)
                if (s == null) return@forEach
                check("$tag: 3:1 on ground", contrastRatio(s.accent, g.bgPrimary) >= 3.0)
                check("$tag: ink 4.5:1", maxOf(contrastRatio(s.accent, g.controlInk), contrastRatio(s.accent, g.textPrimary)) >= 4.5)
                check("$tag: text 4.5:1", contrastRatio(s.accentText, g.bgSelected) >= 4.5)
                check("$tag: soft ink 4.5:1", contrastRatio(s.accentSoftInk, s.accentSoft) >= 4.5)
                check("$tag: own bubble readable", contrastRatio(g.textPrimary, s.bgMessageOwn) >= 4.5)
            }
        }
        THEME_GROUNDS.forEach { g ->
            check("accent ${g.name}: own teal unchanged", accentFor(g.accent, g)?.accent == g.accent)
        }
        check("readableOn: passing colour kept", readableOn(0xFFFFFF, 0x000000, 4.5) == 0xFFFFFF)
        check("readableOn: dark on dark lightened", contrastRatio(readableOn(0x0A3D91, 0x271D12, 4.5), 0x271D12) >= 4.5)
        check("warnings: a danger-like red warns", accentWarnings(0xFF8A60).isNotEmpty())
        check("warnings: a blue does not", accentWarnings(0x0A3D91).isEmpty())
    }

    // ---------------------------------------------------------------------------- the tool

    private fun testTool() {
        println("issuing tool (throwaway key, temp folder)")
        val root = Files.createTempDirectory("orgcheck")
        try {
            val pass = "correct horse battery staple".toCharArray()
            val seed = ed25519NewSeed()
            val kf = root.resolve("k.key")
            writeKeyFile(kf, seed, pass)
            check("tool: key round trip", readKeyFile(kf, pass)?.contentEquals(seed) == true)
            check("tool: wrong passphrase", readKeyFile(kf, "a wrong passphrase".toCharArray()) == null)

            val home = BrandHome(root.resolve("home"))
            val (keyId, pubHex) = BrandOps.keygen(home, pass)
            check("tool: keygen id", keyId.matches(Regex("[0-9a-f]{8}")) && pubHex.length == 64)
            check("tool: keygen refuses a second key", runCatching { BrandOps.keygen(home, pass) }.isFailure)
            val trusted = mapOf(keyId to pubHex)

            fun brandDir(name: String, server: String): Path {
                val dir = Files.createDirectories(root.resolve("src").resolve(name.filter { it.isLetterOrDigit() }))
                Files.writeString(
                    dir.resolve("brand.json"),
                    """{"name":"$name","welcome":"Welcome","server":"$server","erp":"https://erp.example.com","accent":"#0A3D91","theme":"vivid"}""",
                )
                Files.write(dir.resolve("logo.png"), png(300, 200))
                return dir
            }

            val first = BrandOps.issue(home, brandDir("Demo Organisation", "https://cloud.example.com"), pass, now = 1_000)
            val active = activeRecords(home.records)
            check("tool: one record", active.size == 1 && active[0].fileId == first.fileId)
            val code = (parseInviteCode(active[0].code) as? CodeInput.Valid)?.symbols
            check("tool: record holds a valid code", code != null && deriveKeys(code).fileIdHex == first.fileId)
            check("tool: issued file verifies", BrandOps.verify(home, trusted) == mapOf(first.fileId to true))

            val before = openBrandFile(Files.readAllBytes(home.staged.resolve(first.fileId + ".bin")), deriveKeys(code!!), trusted) as Opened.Ok
            BrandOps.update(home, first.brandId, pass, now = 1_000)
            val after = openBrandFile(Files.readAllBytes(home.staged.resolve(first.fileId + ".bin")), deriveKeys(code), trusted)
            check("tool: update is newer", after is Opened.Ok && acceptsRefresh(before.payload.issued, before.payloadBytes, after))
            check("tool: logo re-encoded to 512", (after as? Opened.Ok)?.payload?.logoPng?.let { pngSize(it) } == (512 to 512))

            val second = BrandOps.rotate(home, first.brandId, pass, now = 2_000)
            check("tool: rotate adds a code", activeRecords(home.records).size == 2 && second.fileId != first.fileId)
            check("tool: both verify", BrandOps.verify(home, trusted).values.all { it } && BrandOps.verify(home, trusted).size == 2)

            val checkout = Files.createDirectories(root.resolve("talq-brands"))
            BrandOps.publishDir(home, checkout)
            fun published() = Files.list(checkout.resolve("v1")).use { s -> s.toList() }
            check("tool: 32 files", published().size == 32)
            check("tool: one size", published().map { Files.size(it) }.toSet().size == 1)
            check("tool: .nojekyll", Files.exists(checkout.resolve(".nojekyll")))
            check("tool: real files published", published().map { it.fileName.toString() }.containsAll(listOf(first.fileId + ".bin", second.fileId + ".bin")))
            check("tool: every file starts alike", published().all { Files.readAllBytes(it).copyOf(4).contentEquals(BRAND_MAGIC.encodeToByteArray()) })
            val namesBefore = published().map { it.fileName.toString() }.toSet()
            val bytesBefore = published().associate { it.fileName.toString() to Files.readAllBytes(it) }
            BrandOps.publishDir(home, checkout)
            check("tool: republish keeps every name", published().map { it.fileName.toString() }.toSet() == namesBefore)
            check("tool: republish changes every file's bytes", published().all { !Files.readAllBytes(it).contentEquals(bytesBefore[it.fileName.toString()]) })
            check("tool: republished real file still opens",
                openBrandFile(Files.readAllBytes(checkout.resolve("v1").resolve(first.fileId + ".bin")), deriveKeys(code), trusted) is Opened.Ok)

            BrandOps.retire(home, first.fileId)
            check("tool: retire", activeRecords(home.records).map { it.fileId } == listOf(second.fileId))
            BrandOps.publishDir(home, checkout)
            check("tool: still 32 after retire", published().size == 32)
            check("tool: a retired name stays, as a decoy", published().map { it.fileName.toString() }.toSet() == namesBefore)
            check("tool: the retired file no longer opens",
                openBrandFile(Files.readAllBytes(checkout.resolve("v1").resolve(first.fileId + ".bin")), deriveKeys(code), trusted) == Opened.Bad)

            check("tool: confusable name refused", runCatching {
                BrandOps.issue(home, brandDir("Dem0 0rganisation", "https://other.example.com"), pass, now = 3_000)
            }.isFailure)
            check("tool: same server refused", runCatching {
                BrandOps.issue(home, brandDir("Another One", "https://cloud.example.com"), pass, now = 3_000)
            }.isFailure)
            check("tool: mixed-script name refused", runCatching {
                BrandOps.issue(home, brandDir("Dem\u043E Mixed", "https://mixed.example.com"), pass, now = 3_000)
            }.isFailure)
            BrandOps.issue(home, brandDir("Peace Co", "https://peace.example.com"), pass, now = 3_000)
            check("tool: cyrillic look-alike refused", runCatching {
                BrandOps.issue(home, brandDir("\u0420\u0435\u0430\u0441\u0435 \u0421\u043E", "https://peace2.example.com"), pass, now = 3_000)
            }.isFailure)
            check("tool: same host other path refused", runCatching {
                BrandOps.issue(home, brandDir("Path Org", "https://cloud.example.com/other"), pass, now = 3_000)
            }.isFailure)
            // A rotated code goes through the same gate: a brand whose source now imitates another is refused.
            val impostor = BrandOps.issue(home, brandDir("Fresh Org", "https://fresh.example.com"), pass, now = 3_000)
            Files.writeString(
                home.sourceOf(impostor.brandId)!!.resolve("brand.json"),
                """{"name":"Demo 0rganisation","server":"https://fresh.example.com","accent":"#0A3D91"}""",
            )
            check("tool: rotate checks look-alikes", runCatching { BrandOps.rotate(home, impostor.brandId, pass, now = 4_000) }.isFailure)
            // A brand folder copied as a template carries the first brand's id: refused, the first brand untouched.
            val copied = Files.createDirectories(root.resolve("src").resolve("Copied"))
            Files.writeString(copied.resolve("brand.json"), """{"name":"Copied Org","server":"https://copied.example.com","accent":"#0A3D91"}""")
            Files.write(copied.resolve("logo.png"), png(300, 200))
            Files.copy(home.sourceOf(first.brandId)!!.resolve("brand.id"), copied.resolve("brand.id"))
            val sourceBefore = home.sourceOf(first.brandId)
            val copyRefusal = runCatching { BrandOps.issue(home, copied, pass, now = 3_500) }.exceptionOrNull()?.message.orEmpty()
            check("tool: a copied brand.id is refused", "brand.id" in copyRefusal, copyRefusal)
            check("tool: the first brand's folder is kept", home.sourceOf(first.brandId) == sourceBefore)
            BrandOps.issue(home, brandDir("Clean Co", "https://cloud.acme.com"), pass, now = 3_600)
            check("tool: capital I for l refused", runCatching {
                BrandOps.issue(home, brandDir("CIean Co", "https://clean2.example.com"), pass, now = 3_600)
            }.isFailure)
            BrandOps.issue(home, brandDir("NIKE", "https://nike.example.com"), pass, now = 3_600)
            check("tool: Greek capitals refused", runCatching {
                BrandOps.issue(home, brandDir("\u039D\u0399\u039A\u0395", "https://greek.example.com"), pass, now = 3_600)
            }.isFailure)
            check("tool: look-alike host refused", runCatching {
                BrandOps.issue(home, brandDir("Acme Other", "https://cloud.acrne.com"), pass, now = 3_600)
            }.isFailure)
            check("tool: the app here does not trust the tool's key", !appTrustsHomeKey(home))
            val acl = Files.getFileAttributeView(home.records, AclFileAttributeView::class.java)
            check("tool: the record file is the owner's only", acl == null || (acl.acl.size == 1 && acl.acl[0].principal() == acl.owner))
            check("tool: wrong passphrase refused", runCatching {
                BrandOps.issue(home, brandDir("Third Org", "https://third.example.com"), "a wrong passphrase".toCharArray(), now = 3_000)
            }.isFailure)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------------- sign-in

    private fun testLogin() {
        println("sign-in on the fixed server")
        val fixed = "https://cloud.example.com"
        check("flow: same server", orgFlowAllowed(fixed, "https://cloud.example.com/login/v2/flow/abc", "https://cloud.example.com/login/v2/poll"))
        check("flow: other host", !orgFlowAllowed(fixed, "https://evil.example/login/v2/flow/abc", "https://cloud.example.com/login/v2/poll"))
        check("flow: other poll host", !orgFlowAllowed(fixed, "https://cloud.example.com/login/v2/flow/abc", "https://evil.example/poll"))
        check("flow: http", !orgFlowAllowed(fixed, "http://cloud.example.com/login/v2/flow/abc", "https://cloud.example.com/login/v2/poll"))
        check("flow: other port", !orgFlowAllowed(fixed, "https://cloud.example.com:8443/login", "https://cloud.example.com/poll"))
        check("flow: explicit 443", orgFlowAllowed(fixed, "https://cloud.example.com:443/login", "https://cloud.example.com/poll"))
        check("flow: user part", !orgFlowAllowed(fixed, "https://cloud.example.com@evil.example/login", "https://cloud.example.com/poll"))
        check("flow: suffix host", !orgFlowAllowed(fixed, "https://cloud.example.com.evil.example/login", "https://cloud.example.com/poll"))
        check("server: same, case and slash", sameServer(fixed, "https://Cloud.example.com/"))
        check("server: other path", !sameServer(fixed, "https://cloud.example.com/nc"))
        check("server: http", !sameServer(fixed, "http://cloud.example.com"))
        check("server: other host", !sameServer(fixed, "https://cloud2.example.com"))
    }

    // ---------------------------------------------------------------------------- TalQ for desktop's hello

    private fun testHello() {
        println("hello to TalQ for desktop")
        val o = Json.parseToJsonElement(clientHello("0.11.0", "u1")).jsonObject
        val msg = o["message"]!!.jsonObject
        val data = msg["data"]!!.jsonObject
        check("hello: a room message", o["type"]?.jsonPrimitive?.content == "message" && msg["recipient"]!!.jsonObject["type"]?.jsonPrimitive?.content == "room")
        check("hello: talq.client", data["type"]?.jsonPrimitive?.content == "talq.client")
        check("hello: TalQ Android", data["client"]?.jsonPrimitive?.content == "TalQ Android")
        check("hello: version", data["version"]?.jsonPrimitive?.content == "0.11.0")
        check("hello: user id", data["userid"]?.jsonPrimitive?.content == "u1")
        val dc = Json.parseToJsonElement(clientHelloStatus("0.11.0")).jsonObject
        check("hello: data channel", dc["type"]?.jsonPrimitive?.content == "talq.client" && dc["client"]?.jsonPrimitive?.content == "TalQ Android" && dc["version"]?.jsonPrimitive?.content == "0.11.0")
    }
}
