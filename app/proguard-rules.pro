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
