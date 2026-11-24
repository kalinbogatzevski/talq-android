import com.android.build.api.variant.impl.VariantOutputImpl
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---- The branded build -------------------------------------------------------------------
// The "branded" flavor takes everything that names the brand from a PRIVATE, gitignored store:
// private/branding/<brand>/brand.properties (applicationId, appName, shortName, serverUrl,
// erpBaseUrl, apkName) and private/branding/<brand>/res (a resource overlay: launcher and
// status bar icons, icon_background, brand_logo, and app_name in values/). <brand> is the
// gradle property talq.brand, else the only folder in private/branding. Nothing of the brand
// is in git. Without the store the branded variants are switched off and generic builds as ever.
val brandingRoot: File = rootProject.file("private/branding")
val brandRequested: String = (findProperty("talq.brand") as String?)?.trim().orEmpty()
val brandDir: File? =
    if (brandRequested.isNotEmpty()) brandingRoot.resolve(brandRequested).takeIf { it.isDirectory }
    else brandingRoot.listFiles { file -> file.isDirectory }?.singleOrNull()
val brandProps = Properties().apply {
    brandDir?.resolve("brand.properties")?.takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use { load(it) }
}
fun brandValue(key: String): String = brandProps.getProperty(key)?.trim().orEmpty()

/** Null when the store is complete, else what is wrong with it (never a value from it). */
val brandProblem: String? = run {
    val folders = brandingRoot.listFiles { file -> file.isDirectory }.orEmpty()
    when {
        brandRequested.isNotEmpty() && brandDir == null ->
            "private/branding/$brandRequested (from -Ptalq.brand) does not exist."
        brandDir == null && folders.isEmpty() ->
            "there is no brand store. It belongs in private/branding/<brand>/ (brand.properties + res/); the folder is gitignored and has to be copied in by hand."
        brandDir == null ->
            "private/branding holds ${folders.size} brands; name one with -Ptalq.brand=<folder>."
        !brandDir.resolve("brand.properties").isFile -> "brand.properties is missing in the brand store."
        else -> {
            val missing = listOf("applicationId", "appName", "serverUrl", "erpBaseUrl", "apkName")
                .filter { brandValue(it).isEmpty() }
            // The visible name is the store's app_name string; appName has to say the same.
            val appNameString = Regex("<string\\s+name=\"app_name\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            val named = brandDir.resolve("res/values").listFiles().orEmpty()
                .filter { it.isFile && it.extension == "xml" }
                .firstNotNullOfOrNull { appNameString.find(it.readText())?.groupValues?.get(1) }
                ?.replace("\\'", "'")?.replace("&amp;", "&")?.trim()
            // The login screen draws brand_logo as a picture; main's placeholder is not one.
            val logo = brandDir.resolve("res").listFiles { dir -> dir.isDirectory && dir.name.startsWith("drawable") }.orEmpty()
                .any { dir -> dir.listFiles().orEmpty().any { it.nameWithoutExtension == "brand_logo" && it.extension in setOf("png", "webp", "jpg") } }
            when {
                missing.isNotEmpty() -> "brand.properties lacks: ${missing.joinToString()}."
                named == null -> "the brand store's res/values has no app_name string."
                named != brandValue("appName") -> "app_name in the brand store's res/values differs from appName in brand.properties."
                !logo -> "the brand store has no brand_logo picture (png, webp or jpg in a res/drawable* folder)."
                else -> null
            }
        }
    }
}
if (brandProblem != null) {
    val wanted = gradle.startParameter.taskNames.filter { it.contains("branded", ignoreCase = true) }
    if (wanted.isNotEmpty()) {
        throw GradleException("Cannot run ${wanted.joinToString()}: the branded build is not available, $brandProblem")
    }
    logger.warn("TalQ: branded variants are switched off, $brandProblem Building generic only.")
}

// ---- Release signing ----------------------------------------------------------------------
// Release APKs are signed with release keys whose location and passwords come from properties
// files OUTSIDE the repository (storeFile, relative to the file's folder or absolute;
// storePassword; keyAlias; keyPassword):
//   generic:  -Ptalq.signing=<file>,      else ~/.talq-android/signing.properties
//   branded:  -Ptalq.brandSigning=<file>, else ~/.talq-android/<brand folder>/signing.properties
// Without its file a generic release APK is left UNSIGNED (for F-Droid or whoever rebuilds it
// to sign or to compare), and a branded one is signed with the local debug key. Debug builds are
// always signed with the debug key. Android only updates an app from an APK signed with the
// same key.
val talqHome = File(System.getProperty("user.home"), ".talq-android")
fun propertyFile(name: String): File? = (findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }?.let { file(it) }

/** The signing settings in [f], or null when there is no such file. */
fun signingProps(f: File?): Properties? {
    if (f == null || !f.isFile) return null
    val p = Properties().apply { f.reader(Charsets.UTF_8).use { load(it) } }
    val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").filter { p.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) throw GradleException("$f lacks ${missing.joinToString()}.")
    return p
}
val genericSigningFile: File = propertyFile("talq.signing") ?: talqHome.resolve("signing.properties")
val brandedSigningFile: File? = propertyFile("talq.brandSigning") ?: brandDir?.let { talqHome.resolve(it.name).resolve("signing.properties") }
val releaseKeys: Map<String, Pair<File, Properties>> = buildMap {
    signingProps(genericSigningFile)?.let { put("genericRelease", genericSigningFile to it) }
    signingProps(brandedSigningFile)?.let { put("brandedRelease", brandedSigningFile!! to it) }
}

/** A Java string literal for a BuildConfig field. */
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "talq.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "io.github.kalinbogatzevski.talq"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "0.9.2"
        // Calls bring libwebrtc's native library (12 MB for arm64, 7 MB for 32-bit ARM, more
        // for x86). Phones and tablets are ARM; x86 builds would only serve emulators and a few
        // Chromebooks, at three times the size.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Native libraries compressed in the APK: a much smaller download, unpacked once on install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        for ((name, key) in releaseKeys) {
            val (file, props) = key
            create(name) {
                storeFile = file.parentFile.resolve(props.getProperty("storeFile").trim())
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias").trim()
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    // Two dimensions:
    //   brand: generic (the public app) or branded (an organisation's own app, see above).
    //   push:  google, where push can also come through Google Play services with no extra app
    //          (UnifiedPush's embedded FCM distributor: no Firebase SDK, no Google account), or
    //          foss, push only through a UnifiedPush distributor app such as ntfy. F-Droid builds
    //          genericFoss; GitHub, Google Play and the branded app use the google variant.
    flavorDimensions += listOf("brand", "push")
    productFlavors {
        // The public app.
        create("generic") {
            dimension = "brand"
            // Release builds only: the debug build type keeps its own debug key. Without the
            // release key a generic release APK is left unsigned, for whoever rebuilds it
            // (F-Droid) to sign or to compare with the published one.
            signingConfig = if ("genericRelease" in releaseKeys) signingConfigs.getByName("genericRelease") else null
            buildConfigField("boolean", "IS_BRANDED", "false")
            buildConfigField("String", "BRAND_SERVER_URL", quoted(""))
            buildConfigField("String", "BRAND_ERP_BASE_URL", quoted(""))
            buildConfigField("String", "BRAND_SHORT_NAME", quoted(""))
        }
        // The organisation's own app: its own id, so it installs next to the generic one.
        create("branded") {
            dimension = "brand"
            signingConfig = signingConfigs.getByName(if ("brandedRelease" in releaseKeys) "brandedRelease" else "debug")
            // The fallback id is never built: without the store these variants are switched off.
            applicationId = brandValue("applicationId").ifEmpty { "talq.branded.unconfigured" }
            buildConfigField("boolean", "IS_BRANDED", "true")
            buildConfigField("String", "BRAND_SERVER_URL", quoted(brandValue("serverUrl")))
            buildConfigField("String", "BRAND_ERP_BASE_URL", quoted(brandValue("erpBaseUrl")))
            buildConfigField("String", "BRAND_SHORT_NAME", quoted(brandValue("shortName")))
            // Its own update channel (optional; without it the app does not look for updates):
            // the manifest's https address on the organisation's server and, for a password-
            // protected public share, the share token and its password.
            buildConfigField("String", "BRAND_UPDATE_MANIFEST_URL", quoted(brandValue("updateManifestUrl")))
            buildConfigField("String", "BRAND_UPDATE_SHARE_TOKEN", quoted(brandValue("updateShareToken")))
            buildConfigField("String", "BRAND_UPDATE_SHARE_PASSWORD", quoted(brandValue("updateSharePassword")))
        }
        create("google") {
            dimension = "push"
            buildConfigField("boolean", "GOOGLE_PUSH", "true")
        }
        create("foss") {
            dimension = "push"
            buildConfigField("boolean", "GOOGLE_PUSH", "false")
        }
    }
    // Flavor resources win over main: the store's res/ replaces the icons and app_name.
    if (brandProblem == null && brandDir != null) {
        sourceSets.getByName("branded").res.srcDir(brandDir.resolve("res"))
    }

    buildTypes {
        // The build to hand out: shrunk (R8 drops unused code and resources, which takes the
        // APK from about 32 MB to under 4 MB) and not debuggable, so it also runs faster.
        // Signed per brand (see "Release signing" above): each with its own release key when
        // there is one; without it generic stays unsigned and branded takes the local debug key
        // (AGP creates one on first use). Android only updates an installed app from an APK
        // signed with the same key, so a self-built APK cannot update one built elsewhere:
        // uninstall that first (which signs you out).
        // mapping.txt (app/build/outputs/mapping/<variant>/) decodes its stack traces: keep it
        // with every APK you hand out.
        release {
            // No git revision stamped into the APK: the same source then builds the same bytes
            // from any checkout (a clone at a tag, a worktree, F-Droid's build server).
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // The dependency list AGP would put into the APK is encrypted for Google Play only, and
    // F-Droid's scanner rejects such a blob. App bundles (Play) keep it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
}

androidComponents {
    beforeVariants(selector().withFlavor("brand" to "branded")) { variant ->
        // The branded app always has the Google route; it is never built for F-Droid.
        variant.enable = brandProblem == null && variant.productFlavors.none { it.second == "foss" }
    }
    // APK names: TalQ-android-<version>.apk (generic, google), TalQ-android-<version>-foss.apk
    // (generic, foss), <apkName>-<version>.apk (branded). Each variant has its own output
    // folder, so debug and release share the name.
    onVariants { variant ->
        val flavors = variant.productFlavors.map { it.second }
        val stem = if ("branded" in flavors) brandValue("apkName") else "TalQ-android"
        val suffix = if ("foss" in flavors) "-foss" else ""
        variant.outputs.forEach { output ->
            val named = output as? VariantOutputImpl
                ?: throw GradleException("The APK naming in app/build.gradle.kts needs updating for this Android Gradle Plugin.")
            named.outputFileName.set(output.versionName.map { "$stem-$it$suffix.apk" })
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.unifiedpush.connector)
    // Calls: libwebrtc (media) and core-telecom (system call integration).
    implementation(libs.webrtc.android)
    implementation(libs.core.telecom)
    implementation(libs.androidx.window)
    // Push through Google Play services without an extra app: google variants only.
    "googleImplementation"(libs.unifiedpush.embedded.fcm)
    implementation(libs.work.runtime.ktx)
    implementation(libs.exifinterface)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    debugImplementation(libs.compose.ui.tooling)
}
