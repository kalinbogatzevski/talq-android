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
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
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
