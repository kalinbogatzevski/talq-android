# TalQ for Android

A native Android client for Nextcloud Talk, written in Kotlin with Jetpack Compose. It is the
Android sibling of [TalQ for desktop](https://github.com/kalinbogatzevski/talq-desktop).
Topics (Talk's threads) are first-class: each topic is a tab inside its conversation, with its
own place, draft, unread count and notification level.

**Status: early (0.4.x).** Chat, topics, files and notifications work; calls are not there
yet. See `CHANGELOG.md` for what each version does.

## What it does

- **Sign in through your browser** (Nextcloud Login Flow v2). TalQ only stores an app
  password, encrypted with a key held in the Android Keystore, and revokes it on the server
  when you sign out.
- **Conversations** with unread counts, mention markers and the latest message; a presence
  dot (online, away, do not disturb) on one-to-one chats.
- **Topics** as a strip of tabs under the top bar, unread ones first; a "Next: topic" button
  when another topic has something new; new topics; per-topic notification level, rename and
  mark as read.
- **Messages**: formatted text, mentions, replies, reactions, image previews. A message that
  could not be sent stays visible with Retry.
- **Files**: send photos, videos and files (photos are shrunk and their location data removed
  unless you send the original); uploads carry on in the background; share into TalQ from
  other apps; open, save and share received files; videos and voice messages play inside the
  app; a "Shared files" view per conversation.
- **Notifications** through [UnifiedPush](https://unifiedpush.org/) (for example ntfy) and
  the server's web push. A topic or conversation set to "Never" stays silent. On Honor and
  Huawei home screens the app icon shows the unread count; other launchers show their usual
  notification dot.
- **Shift status (optional)**: if your organisation's ERP offers the pairing and shift-status
  endpoints that desktop TalQ uses, one-to-one avatars also show whether a colleague is on
  shift, on a break or off shift. You pair the phone once in your browser; the key is stored
  encrypted and only ever sent to that ERP, over https. With no ERP address set in Settings
  nothing about shifts is shown or sent.
- Light and dark themes that follow the system.

Android 8.0 (API 26) or newer.

## Build

Requires JDK 21 and the Android SDK with compileSdk 37.2.

    ./gradlew :app:assembleGenericDebug      # debug build
    ./gradlew :app:assembleGenericRelease    # shrunk release build, signed with your local debug key

The APK lands in `app/build/outputs/apk/generic/<debug|release>/TalQ-android-<version>.apk`.

The core can be exercised against a real Nextcloud server from a desktop JVM, no phone
needed: `./gradlew :shared:smoke` reads `~/.talq-test.env` with the server address, two test
accounts with app passwords and the token of a room both are in (the variable names are at
the top of `shared/src/jvmMain/kotlin/talq/core/smoke/SmokeMain.kt`). By default it makes no
lasting changes, but one check is a deliberate wrong-password login, which counts toward the
server's brute-force throttle. `--args=--write` also writes into that room.

## Branded builds

Besides the public app (`generic` flavor) the build can produce an organisation's own app
(`branded` flavor): its own name, colours for the launcher icon, logo, and a built-in
server address (its sign-in screen has no server field) and ERP address. It has its own application id, so it installs next to the public
app. Everything that names the brand comes from a private store,
`private/branding/<brand>/`, which is not part of this repository; the top of
`app/build.gradle.kts` describes its layout. Without it only the `generic` flavor is built.

## Layout

- `shared/` — Kotlin Multiplatform core: Talk API, models, sync, topics, uploads.
  `commonMain` has no Android imports, so an iOS target can be added later. The `jvm` target
  runs the core against a real server from a PC (`:shared:smoke`).
- `app/` — the Android app: Compose UI, storage, push, notifications, uploads.

## Licence

Apache-2.0, see `LICENSE`. Third-party components and their licenses are listed in
`THIRD-PARTY-NOTICES.md`. TalQ is an independent client, not affiliated with Nextcloud GmbH
(see `NOTICE`).
