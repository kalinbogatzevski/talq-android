# TalQ for Android

A native Android client for Nextcloud Talk, written in Kotlin with Jetpack Compose. It is the
Android sibling of [TalQ for desktop](https://github.com/kalinbogatzevski/talq-desktop).
Topics (Talk's threads) are first-class: each topic is a tab inside its conversation, with its
own place, draft, unread count and notification level.

**Status: 0.10.x.** Chat, topics, folders, files, voice messages, notifications and one-to-one calls
work; group calls are not there yet. See `CHANGELOG.md` for what each version does.

**Download:** the APKs are on the [releases page](https://github.com/kalinbogatzevski/talq-android/releases):
`TalQ-android-<version>.apk`, or `TalQ-android-<version>-foss.apk` without the Google push
route. Each has a `.sha256` file next to it.

## What it does

Some features show only where your server has them.

- **Sign in through your browser** (Nextcloud Login Flow v2): the app never sees your password. The
  app password it gets is encrypted with a key held in the Android Keystore and revoked on the server
  when you sign out (if the server can be reached then).
- **Conversations**: unread counts with mentions marked, and a grey count for what is muted; a
  presence dot (online, away, do not disturb) on one-to-one chats; sort, filter and search the list;
  a menu for each chat (read or unread, favourite, notifications, archive, important, call, move to a
  folder, leave). Start one-to-one chats, groups and a Note to self.
- **Folders** for conversations, the same as in TalQ for desktop. Keeping them in sync, through one
  small file in your own Nextcloud Files, is off until you turn it on.
- **Opens at once, also offline**: the list as you last saw it and the newest messages of the chats
  you used last are kept encrypted on the phone, to read until the server answers.
- **Topics** as a line of tabs: "+", General, then the topics, those with unread messages first when you
  open the chat; a "Next: topic" button when another one has something new; notification level, rename
  and mark as read per topic.
- **Messages**: formatted text, replies, reactions, image previews, and a read mark on your own
  messages (delivered ○, read by everyone ◉); edit and delete; select several to copy, forward or
  delete; jump to a quoted message, however far back. Type @ to mention someone. "typing…" shows who
  is typing, where the server has Talk's high-performance backend; yours is shared only when your Talk
  settings say so.
- **Send silently or later**: touch and hold the send button; scheduled messages can be changed
  until they go.
- **Drafts** wait in their chat and topic. A message that could not be sent is looked for in the chat
  and tried again when the app is back online, or waits for Retry.
- **Members of a group**: who is in it, with presence and roles; moderators add people, groups and
  teams, remove them, and make or unmake moderators.
- **Your own status**, shown and set at the top of the list.
- **Voice messages**: touch and hold the microphone to record, let go to send.
- **Files** you pick, photograph, drop or paste wait above the message box until you send them; other
  apps can share into TalQ. Photos (JPEG, HEIC) and videos (MP4, MOV, 3GP, Matroska, WebM) lose their
  location and are made smaller where that helps, unless you send the original (`PRIVACY.md` says what
  goes as it is). Uploads carry on in the background; open, save and share received files; videos and
  voice messages play inside the app; a "Shared files" view per conversation.
- **Notifications** through your server's web push: on phones with Google Play services
  straight through them, with nothing else to install, or through a
  [UnifiedPush](https://unifiedpush.org/) app such as ntfy (Settings can switch; the F-Droid
  build uses UnifiedPush only). Direct messages and mentions have an alert of their own; reply or mark
  as read in the notification. A topic or conversation set to "Never" stays silent. On Honor and
  Huawei home screens the app icon shows the unread count; other launchers show their usual
  notification dot. Your latest chats are in Android's share sheet (Android 10 and later).
- **One-to-one calls**, audio and video, with TalQ for desktop and the Nextcloud web app: call
  from a one-to-one conversation, or answer a call that rings like a phone call (also over the
  lock screen); mute, camera on and off, front or back camera, earpiece, speaker, Bluetooth or
  headset; the call keeps running with the screen off or in another app. Calls need a
  Nextcloud with Talk's high-performance backend (an external signaling server); without one
  the call buttons do not show.
- **Shift status (optional)**: if your organisation's ERP offers the pairing and shift-status
  endpoints that desktop TalQ uses, one-to-one avatars also show whether a colleague is on
  shift, on a break or off shift. You pair the phone once in your browser; the key is stored
  encrypted and only ever sent to that ERP, over https. With no ERP address set in Settings
  nothing about shifts is shown or sent.
- **Tablets and foldables**: a window at least 600 dp wide and 480 dp tall shows the
  conversation list beside the open conversation, with a divider you can drag; folding, unfolding or
  resizing keeps the open conversation as it is. With a mouse, a right-click opens the menu that touch
  and hold does; on a keyboard, Enter sends and Shift+Enter starts a new line.
- **Diagnostics** in Settings: what the app can and cannot reach, and a report to share.
- Four themes, as on desktop (Ember, Warm, Vivid, Paper), or following the phone's light/dark setting.

Android 8.0 (API 26) or newer, on ARM phones and tablets.

## Build

Requires JDK 21 and the Android SDK with compileSdk 37.2.

    ./gradlew :app:assembleGenericGoogleDebug     # debug build
    ./gradlew :app:assembleGenericGoogleRelease   # shrunk release build (unsigned: sign it yourself)
    ./gradlew :app:assembleGenericFossRelease     # the same without the Google push route (F-Droid)

The APKs land in `app/build/outputs/apk/<variant>/<debug|release>/`:
`TalQ-android-<version>.apk` and `TalQ-android-<version>-foss.apk`. Release APKs are signed
only when `~/.talq-android/signing.properties` names a key (see the top of
`app/build.gradle.kts`).

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
A branded app can also learn about its updates from an update channel on its organisation's
server (a JSON manifest written by `scripts/publish-update.sh`, with a download link the app
opens in the browser); only the `branded` flavor contains that code. No flavor installs
anything itself.

## Layout

- `shared/` — Kotlin Multiplatform core: Talk API, models, sync, topics, uploads.
  `commonMain` has no Android imports, so an iOS target can be added later. The `jvm` target
  runs the core against a real server from a PC (`:shared:smoke`).
- `app/` — the Android app: Compose UI, storage, push, notifications, uploads.

## Privacy

TalQ collects nothing and runs no server of its own. `PRIVACY.md` says what stays on the phone, what
goes to your server, and what signing out deletes.

## Licence

Apache-2.0, see `LICENSE`. Third-party components and their licenses are listed in
`THIRD-PARTY-NOTICES.md`. TalQ is an independent client, not affiliated with Nextcloud GmbH
(see `NOTICE`).
