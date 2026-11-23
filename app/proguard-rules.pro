# R8 rules for the shrunk release build. The libraries (Compose, Ktor, Coil, Media3,
# WorkManager, DataStore, UnifiedPush, kotlinx.serialization) ship their own rules; this file
# only adds what this app needs on top. Keep it small: no blanket -keep of whole packages.

# Readable stack traces in logs: file names and line numbers survive (decode the rest with
# the variant's mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Error texts shown to the user name the exception class (e::class.simpleName in TalkError
# and ErpClient), e.g. "no answer (HttpRequestTimeoutException)". Keep those names readable.
-keepnames class * extends java.lang.Throwable

# libwebrtc (io.github.webrtc-sdk:android, calls) ships no rules of its own. Its native code
# calls back into Java members that nothing in Java calls (observers, the RTCConfiguration
# getters) and finds its classes by name, so R8 must leave the package exactly as it is. The one
# blanket keep in this file: without it a release build crashes at the first call, while a
# debug build works.
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-dontwarn org.webrtc.**
# Generated per app by Chromium's JNI tool; not in the AAR. Only JniZero.setJniClassLoader
# names it, which WebRTC never calls (a debug build, unshrunk, resolves it just as lazily).
-dontwarn org.jni_zero.JniZeroJni
