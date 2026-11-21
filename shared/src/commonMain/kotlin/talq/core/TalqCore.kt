package talq.core

object TalqCore {
    const val NAME = "TalQ"
    const val VERSION = "0.1.0"

    /** Sent on every API request. The browser-style prefix keeps server-side UA sniffing calm. */
    const val USER_AGENT = "Mozilla/5.0 (Android) TalQ-Android/$VERSION"

    /**
     * Sent on the Login Flow v2 request only: the server stores this string as the name of the
     * app password, so it is what the user sees under "Devices & sessions".
     */
    const val LOGIN_DEVICE_NAME = "TalQ for Android"
}
