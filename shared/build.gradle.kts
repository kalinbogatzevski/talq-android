// Shared core: Talk API, models, sync and topic logic. Pure Kotlin (no Android
// imports in commonMain) so an iOS target can be added later. The jvm() target
// lets the core be exercised against a real Nextcloud server from a desktop JVM, no phone needed.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "talq.core"
        compileSdk = 37
        minSdk = 26
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.tink)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.tink)
        }
    }
}

// Runs the core against the Nextcloud test server named in ~/.talq-test.env (see SmokeMain.kt).
//   ./gradlew :shared:smoke                 no lasting changes
//   ./gradlew :shared:smoke --args=--write  also writes into the test room (see SmokeMain.kt)
tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Exercise talq.core against the test server named in ~/.talq-test.env."
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("talq.core.smoke.SmokeMain")
}

// Offline checks of the conversation-folder model, its file format and folder sync (merge, policy,
// and the cycle and probe against an in-memory server; see FolderCheck.kt): no network, no env
// file, nothing written. Exits non-zero on any failed check.
//   ./gradlew :shared:folderCheck
tasks.register<JavaExec>("folderCheck") {
    group = "verification"
    description = "Check the folder model, codec and sync against the desktop test vectors (offline)."
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("talq.core.smoke.FolderCheck")
}

// Organisation mode's security core: invite codes, key derivation, brand files, signatures, payload
// rules, colour rules and the issuing tool, with throwaway keys (offline, nothing kept).
//   ./gradlew :shared:orgCheck
tasks.register<JavaExec>("orgCheck") {
    group = "verification"
    description = "Check organisation mode's codes, brand files, signatures and colour rules (offline)."
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("talq.core.smoke.OrgCheck")
}

// The owner's brand issuing tool (talq.core.org.tool.BrandOps), as a script the owner runs in a
// plain terminal of his own, so it can ask for the signing key's passphrase without showing it.
// Never run it in an AI session: it holds the signing key and writes the invite codes.
//   ./gradlew :shared:brandToolScript   then   shared\build\brandtool\brandtool.cmd <command>
tasks.register("brandToolScript") {
    group = "distribution"
    description = "Write shared/build/brandtool/brandtool.cmd for the owner's brand issuing tool."
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    val script = layout.buildDirectory.file("brandtool/brandtool.cmd")
    outputs.file(script)
    doLast {
        val classpath = files(main.output.allOutputs, main.runtimeDependencyFiles).files.joinToString(";") { it.absolutePath }
        val java = File(System.getProperty("java.home"), "bin/java.exe").absolutePath
        script.get().asFile.apply {
            parentFile.mkdirs()
            writeText("@echo off\r\n\"$java\" -cp \"$classpath\" talq.core.org.tool.BrandOps %*\r\n")
        }
    }
}
