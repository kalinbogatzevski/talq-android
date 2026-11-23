package talq.core

import kotlin.concurrent.Volatile

object TalqCore {
    const val NAME = "TalQ"

    /**
     * The app's own version (its versionName), handed in by the app before it creates any
     * client ([setAppVersion]); the JVM smoke run keeps "dev".
     */
    @Volatile
    var version: String = "dev"
        private set

    fun setAppVersion(appVersion: String) {
        if (appVersion.isNotBlank()) version = appVersion
    }

    /** Sent on every API request. The browser-style prefix keeps server-side UA sniffing calm. */
    val userAgent: String get() = "Mozilla/5.0 (Android) TalQ-Android/$version"

    /**
     * Sent on the Login Flow v2 request only: the server stores this string as the name of the
     * app password, so it is what the user sees under "Devices & sessions".
     */
    const val LOGIN_DEVICE_NAME = "TalQ for Android"
}
