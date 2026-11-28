# Privacy

TalQ for Android is a client for **your own Nextcloud server**. Its developer runs no server
for it and collects nothing: no analytics, no advertising, no trackers, no crash reports.

This text is about version 0.12.0.

## What stays on the phone

What the app keeps is in its own storage, which other apps cannot read. Where it says
**encrypted**, the app encrypts it itself, with keys held in the Android Keystore that never
leave the phone; the rest is kept as it is.

- **Your account**: your server's address and your login name, and the **app password** your server
  issues at sign-in (the app password encrypted). Your own password is typed in your browser; the app
  never sees it.
- **Your organisation**, only if you entered an organisation's invite code ("Use with an
  organisation"): its name, logo, colour, default theme and welcome line, its server and ERP
  addresses, and the key to its brand file (encrypted). The code itself is not kept. Signing out keeps
  it; "Use without an organisation" on the sign-in screen removes it.
- **Your conversation list** as you last saw it (encrypted), so that the app opens on it, also
  offline: your name, the names of up to 300 conversations, how many messages are unread, who wrote
  last and the beginning of that message (not of one that disappears, nor of a call). Kept until you
  sign out.
- **The newest messages of the conversations you used last** (encrypted), so that a chat opens on
  them, also offline: about a hundred for each conversation and for each of up to six of its topics,
  in 40 conversations and 4 MB at most. There is no time limit. They go when you sign out, when the
  conversation is no longer in your list or is marked sensitive, when your server no longer lets you
  read it, and when conversations used later need the room. Edits, deletions and a deleted history
  reach what is kept when the app next loads that conversation. A message that disappears is not shown
  after its time and is removed while the app is in use, or the next time the app reaches your server.
  Never kept: conversations marked sensitive, conversations on another server, notices of calls, and
  private replies that quote another conversation.
- **What has not been sent yet**: messages your server has not confirmed (also a reply typed in a
  notification), until they are confirmed or you delete them. Files you pick, photograph, drop, paste
  or share into the app, and voice messages you record, are copied to the app's cache and stay there
  until they are uploaded, you remove them, or their conversation is no longer in your list; their
  names and captions are listed beside them. A photo may wait there as you picked it beside its
  smaller copy; only the one you chose is uploaded. Text
  you are still typing is kept in memory only (Android may hold that of the open conversation while
  the app is in the background).
- **Files you open, share or save** are downloaded into the app's cache and removed when the app
  starts two weeks later or more. Pictures and avatars the app has shown are cached too. "Save" puts a
  copy into your Pictures or Downloads folder (on Android 8 and 9, where you choose).
- **Settings and small lists**: theme, sounds, sorting and the like, the push registration (its keys
  encrypted by the push library), which conversations are marked sensitive, the notification level of conversations and topics, the tab last open in a conversation, your
  folders (their names, their order and, by room code, which conversations are in them), and for each
  one-to-one conversation a number made from the other person's name, by which a notification is told
  apart.
- **What Android shows for the app**: notifications, with the message in them (for a conversation
  marked sensitive neither the message nor any name); a ringing call, with the caller's name, also
  over the lock screen; and the names and pictures of up to five recent conversations, never sensitive
  ones, in the share sheet of other apps (Android 10 and later) and under touch and hold on the app's
  icon. They are handed to Android on the phone, and your home screen app can see them.
- Nothing the app keeps for itself goes to cloud backup or device-to-device transfer (files you
  save to Pictures or Downloads are ordinary files there, like any other).
- The clipboard is read only when you paste. To offer "Paste", the app only asks Android what kind
  of thing the clipboard holds.

## Location in photos, videos and files you send

- **Photos** (JPEG and HEIC) are made smaller on the phone and saved anew without their location and
  camera details. If a JPEG cannot be made smaller, its location and what names the camera are
  removed. A photo with which neither works is not sent unless you turn on "Original".
- **Videos** (MP4, MOV, 3GP, Matroska, WebM) lose their location and what the camera wrote beside the
  picture and the sound, and tracks of other data (some cameras record the places there). One from
  which they cannot be removed is not sent unless you tap "Send original". A video that is not made
  smaller may keep the name its camera gave to its picture and sound formats, which can name the maker.
- **Sound files** in the MP4 family (M4A and the like) that name a place are cleaned in the same way.
- **Sent as they are**: all of these while "Original" is on, and the one file you tap "Send original"
  for; other kinds of pictures (PNG, GIF, WebP, AVIF, raw files of a camera); other kinds of
  videos that are not made smaller (AVI, MPEG-TS, FLV, WMV); other sound files; and all other files.
  What is in the picture or the sound itself is not looked at, nor what lies between the pieces of a
  video saved in fragments.

## Where data goes

- **Your Nextcloud server**: every request, signed with your app password. The app sends your
  sign-in to no other host, also when a download is redirected elsewhere. What you do in the app goes
  there: messages, files, voice messages and reactions, what you edit and delete, how far you have
  read, the names you type to start a conversation, to add members or after an "@", and what you
  change about a conversation, its members, its topics and its notifications. A scheduled message
  waits on the server, which sends it.
- **Your status** is read from the server; the app changes it only when you save it, and reading chats
  does not show you as online. While you are in a call your server may show that by itself.
- **Folders** stay on the phone unless you turn on "Keep my folders in sync" (with an organisation it
  starts on). Then one small hidden file, `.talq-folders.json`, in your own Nextcloud Files holds their
  names, their order, whether they are open and, by room code, which conversations are in them.
  Turning sync off or signing out leaves that file there. To find out whether your server takes it,
  turning sync on writes and deletes a small `.talq-folders.probe-…` file; if deleting it fails, it
  stays.
- **Your organisation's brand file**: when you enter an invite code or open an organisation's link, and
  every few days after that while an organisation is set, the app fetches one file from GitHub Pages
  (`kalinbogatzevski.github.io`). GitHub sees your phone's internet address and the file's name, which
  is made from the code but is not the code; the file is encrypted with a key only the code gives, and
  the app takes it only when the developer's signature on it checks out. Nothing else is sent there.
- **Typing**: while a conversation is open on your screen and your server offers it, the app joins the
  conversation on your Nextcloud and its signaling server, so that "typing…" can be shown; by that the
  others' apps can see that you have it open. That you started or stopped typing is sent to them,
  never the text, and only when your Talk settings on the server share it. That setting is read each
  time a conversation is joined, and again before anything is sent once the answer is ten minutes old.
  It ends when you leave the conversation or the app.
- **Which app you use**: in a conversation the app has open, and in a call, it tells everyone in that
  conversation (guests of a public one included) that it is TalQ Android and its version, also when
  typing is not shared. Other apps ignore it; TalQ for desktop shows it beside your name.
- **Push notifications**: your server sends them as Web Push, encrypted end to end
  (RFC 8291) with keys created on the phone, to the push service you use:
  - a UnifiedPush distributor app of your choice (for example ntfy) and its server, or
  - in the standard build, if you keep that route, Google Play services (Firebase Cloud
    Messaging). TalQ registers with it directly: no Firebase library is in the app, and no
    Google account information is used by TalQ. The standard build starts on this route where
    Google Play services are there, also when a push app is installed (Settings lets you switch);
    and if the push app you picked drops the app's subscription, it moves to this route by itself.

  The push service carries the encrypted message. It can see when a push arrives, how large it
  is, which server sent it to which phone, and a notification number and priority the server
  attaches; not what it says. The app then asks your server for the details.
- **Calls**: the call is set up through your Nextcloud server and its call backend (the
  signaling server and media server your Nextcloud is configured with). Your voice and, when
  the camera is on, your video go encrypted (DTLS-SRTP) to that media server, which passes them
  to the others in the call (in a group call, everyone in it); so does whether your microphone and
  camera are on and, while you talk, that you are speaking (worked out on the phone from your
  microphone's level; no sound is kept). Where a direct path is blocked they go through the TURN relay servers
  your Nextcloud names. To find a path, your phone's local and public internet addresses go to the call
  backend and to those servers. If your Nextcloud names no STUN server, the app uses Nextcloud's public
  one, `stun.nextcloud.com`, which then sees your phone's public internet address. The app keeps no recording and no call history of its own; where your server
  records calls only with consent, the app asks you before it joins one.
- **Microphone and camera**: the microphone is used during a call you placed or answered, while the
  call's notification is showing, and while you record a voice message: from your touch on the
  microphone button until you let go, tap again, discard it, or leave the conversation or the app. The
  camera is used during a call only, while you have it switched on. A photo to send is taken by the
  phone's own camera app.
- **In the background** the app finishes uploads, sends a reply you typed in a notification, fetches
  what a push announces and, on Honor and Huawei home screens, reloads the conversation list about
  twice an hour for the number on its icon. All of that is between the phone and your server.
- **Diagnostics** (in Settings): only while that screen is open, the app tests your server, its
  signaling server and the STUN and TURN servers your Nextcloud names. "Share report" hands a text
  file to the app you choose: the results; your server, its versions, and its signaling, STUN and TURN
  hosts; your account name; the phone's maker, model and Android version, and the Google Play services
  version; the network type, VPN, proxy and private DNS; battery and notification settings; when the
  last push came; the name the ERP gave a paired phone; whether an organisation is set (with its random
  id, never its code); and the app's recent log. Passwords, call credentials, the queries of addresses
  and anything shaped like an invite code are taken out of the log as well as a filter can; read the
  report before you share it.
- **Shift status (optional)**: only if an ERP address is set. Pairing sends the ERP the phone's
  model name, shown on its approval page. Once you approved the phone in your browser, the app
  sends, over https and only to that ERP, the Nextcloud user names
  of the people in your one-to-one conversations, with the key the ERP issued (kept encrypted
  like the app password, removed when you sign out or unpair). With no ERP address set nothing
  about shifts is sent anywhere.
- **Updates**: the public app asks nobody for updates. An app an organisation builds for itself may
  ask that organisation's own update channel whether there is a newer version.
- A link you tap opens in your browser (or the app it belongs to), and a file you open or share goes
  to the app you choose.

## Signing out

- The app asks your server to forget this phone's push subscription and revokes the app password. If
  the server cannot be reached at that moment, the sign-in screen says so: remove "TalQ for Android"
  under Security, Devices & sessions in your Nextcloud.
- It deletes from the phone: the account, the saved conversation list and messages, messages and files
  not sent yet, drafts, downloaded files, cached pictures, the app's notifications, the push
  registration with its keys, notification levels, remembered tabs, any ERP pairing, and the
  conversations in the share sheet and under the app's icon.
- It stays on the phone: your organisation, if you set one up (see above); the push library's note of
  your server's push key and the time of the last push; the last server address, and an ERP address you typed (with the server and
  user name it was typed for), to fill them in again; settings that belong to the phone (theme, sounds, sorting, "Original" and the like, the way
  push arrives); and your folders with their sync setting, which come back when you sign in to the same
  account. The encryption keys stay in the Android Keystore, with nothing left for them to open.
- It cannot take back: files you saved, or opened or shared with another app; a conversation you put
  on your home screen yourself (it loses its name and picture and is switched off); and what is on
  your server: messages, files, your status, the folders file, and scheduled messages, which are still
  sent.
- If your server no longer accepts the app password, for example because you removed the phone there,
  the app signs out by itself as soon as it finds out, in use or as it starts, and deletes the same.
  Android's own "Clear storage" for the app removes what the app keeps.
- Uninstalling the app, or clearing its storage, does not revoke the app password on your server:
  remove "TalQ for Android" under Security, Devices & sessions in your Nextcloud.

## Permissions

Asked for when they are first needed; chats work without them:

- **Notifications** (Android 13 and later), once after you sign in.
- **Microphone**: for your first call or your first voice message.
- **Camera**: for your first video call, when you switch the camera on in a call, or when you first
  take a photo to send (Android lets an app that makes video calls open the camera app only with it).
- **Names of Bluetooth devices** (Android 12 and later): at your first call, for the audio output list.

Given by Android without asking: internet, and whether there is a network; running uploads in the
background (foreground service, data sync) and keeping a call running there (foreground service for
phone calls, microphone and camera); managing its own calls with the phone's call system (Bluetooth
headsets, cars, and a regular phone call putting a TalQ call on hold); showing an incoming call over
the lock screen (Android 14 and later let you take that away); changing the audio output; keeping the
phone awake briefly while a notification is handled, and turning the screen off at your ear during a
call; setting the unread count on Honor and Huawei home screens. Added by libraries: restarting
scheduled background work after the phone restarts, and a permission of the app's own that keeps
messages from other apps away from its internal parts.

Photos and files come through the system's pickers, from other apps' share sheets, or by drop and
paste, so no storage permission is asked. The app never installs anything.

## Contact

Questions: open an issue at https://github.com/kalinbogatzevski/talq-android/issues.
