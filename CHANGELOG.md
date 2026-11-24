# Changelog

## 0.9.2

- **Conversation header**: the name gets the room; on a narrow screen Video call, Mute topic and
  All topics are in the ⋮ menu. The status line ends in "…" when it does not fit, and the tab
  name is no longer repeated under the name when the topic strip shows it.

## 0.9.1

- **Forward one message** straight from its touch-and-hold menu (no need to Select first).
- **Fixed**: on the light theme, unread numbers were dark on teal and hard to read; they are now
  light on a deeper teal.

## 0.9.0

- **Drafts stay in their chat**: text you started, and the message you reply to, wait in their
  conversation and topic until you send them, also when you go back or open another chat. The
  list shows "Draft:" with the text. Drafts are kept until you sign out or the app is closed.
- **Reply opens the keyboard**: swipe a message or tap Reply, and the cursor is in the box.
- **With a hardware keyboard**, Enter sends, Shift+Enter starts a new line, Alt+Enter sends
  silently and Esc cancels a reply.
- **Read up to where you stopped**: a chat counts what has been on screen, also halfway through a
  long backlog, and the unread counts drop as you scroll.
- **New messages arrive in view**: at the top of the conversation list, a chat that gets a message
  shows at the top instead of just above the screen.
- **Badges show who needs you**: red-orange with "@" when someone mentions you, amber for a mention
  of everyone; the numbers are easier to read on the light theme. Folder headers show only what is
  unread, open or closed.
- **Back online at once**: when the network returns, chats, the conversation list and files that
  failed for want of a connection carry on by themselves. Meanwhile one line under the header says
  "No connection", or "Connecting…" while the server does not answer.
- **A notification opens on its message**: the chat's newest messages load when the notification
  arrives, so tapping it shows them without waiting.
- **Notifications off?** The conversation list says so in one line, with Fix to turn them back on.
- **Photos open at once**: the full-screen picture starts from the copy the chat already shows and
  sharpens when the large one arrives; if that fails, one line offers Retry.
- **Calmer to read**: inline code is a faint tint at the size of the text around it, topic tabs lose
  their letter discs and a muted topic stays quiet, a topic name on the list leaves room for the
  message, and day and month names are in English like the rest of the app.
- **With a mouse**, a right-click opens the menu of a message, a chat or a folder, and rows light up
  under the pointer.
- **Edit and delete your own messages** from the touch-and-hold menu, while the server still allows
  it. Edit opens the text in the box under an "Editing" bar; X, Esc or Back cancels it and brings back
  what you were typing. On a hardware keyboard, Up in an empty box edits your last message.
- **Type @ to mention someone**: matching people of the conversation, and everyone where the server
  offers it, show above the box with their status. Tap one, or press Enter or Tab on a keyboard; the
  box shows the name, and the message notifies that person.

## 0.8.1

- **Your status is at the top of the conversation list**: your picture, name and status sit in
  the header, as in TalQ for desktop. Tap them to change your status. It is no longer in Settings.
- **Search your conversations**: the field under the header filters the list by name as you type,
  ignoring case and accents. Folders stay, shown open. The X or Back clears it.
- **Start a conversation** with the + in the header: find a person by name and tap them to chat, or
  pick "New group", name it and add people. "Note to self" is there too where the server offers it.
- **Fixed**: on a tablet, "Select a conversation" beside the list was black on the dark themes.

## 0.8.0

- **Set your own status**, the same one TalQ for desktop and Nextcloud on the web show. Settings
  now starts with a Status row: tap it to pick Online, Away, Do not disturb or Invisible (and Busy
  where the server offers it), set a message with an emoji or pick one of the server's, choose
  when it clears, or clear it. The phone only changes your status when you set it.
- **Send silently or later**: touch and hold the send button. "Send silently" notifies no one;
  "Send later" offers in 1 hour, in 3 hours, tomorrow 8:00 or 18:00, next Monday 9:00, or "Pick a
  time…". If the server refuses, your text stays in the box and the app says why.
- **Scheduled messages** in a conversation's menu lists what waits there: edit its text or time, or delete it.
- **Select several messages**: touch and hold a message, then "Select"; taps add more. The top bar
  has Copy, Forward and Cancel. Copy puts them on the clipboard in order, each under its author and
  time. Forward sends them in order to the conversation you pick: a text "Forwarded from" its
  author, a file shared again.
- **Voice messages**: with the box empty, the send button is a microphone. Touch and hold to record,
  let go to send, slide left to discard; or tap to record and tap again to send. A recording goes to
  the conversation and topic where it started, also when you leave first; under a second is dropped.

## 0.7.1

- **Update starts one download.** Tapping Update again while it downloads did start the download
  again (three taps, three downloads). The button now shows "Downloading…" and waits; once the
  download is done, Update opens Downloads, where you tap it to install.
- **Archived conversations are out of the list**, as in TalQ for desktop and Talk on the web (they
  used to sit at the end of the list, also inside folders). An "Archived" row at the end shows them.
- **Shorter texts in Settings**: one line each, for updates, folders, sounds, push, calls and the
  diagnostics report.

## 0.7.0

- **Folders for your conversations**, the same folders as in TalQ for desktop. Touch and hold a
  conversation for "Move to folder": pick a folder (the one it is in is checked), make a new one
  with "New folder…" (the conversation goes into it), or take it out with "Remove from folder". A
  conversation is in one folder at most.
- **The list in folders**: each folder is a header with its name and the number of conversations
  in it, in your order, and "Other" at the end holds the conversations in no folder. Tap a header
  to close or open the folder. A closed folder shows how many unread messages it holds: red-orange
  (with "@") when someone mentioned you, amber for another mention, the usual colour otherwise; a
  conversation set to Never adds nothing. Favourites stay on top inside their folder.
  A conversation opened from a notification, or open beside the list on a wide screen, opens its
  folder.
- **Touch and hold a folder's header** for Rename…, Move up, Move down, Sort folders A to Z and
  Delete folder (no question asked: its conversations go back to Other). New and renamed folders
  keep the list in name order until you move one by hand; after that a new folder goes at the end.
  Up to 50 folders; a name has up to 40 characters (a longer one is shortened, and the app says
  so), and two folders cannot have the same name, whatever the case. A name with characters the
  phone does not know yet (newer than its Unicode tables) is refused, so that TalQ for desktop
  reads it as it was typed.
- **Settings > Folders > Show folders**: draw the list in folders or flat, on this device. Making
  your first folder turns it on; turned off, the folders are kept and the list is flat as before.
- **Settings > Folders > Keep my folders in sync** (for each account, off until you turn it on):
  keeps your folders the same on this phone and on every other device you turn it on for,
  including TalQ for desktop. It is the same file desktop uses: one small hidden file,
  `.talq-folders.json`, in your own Nextcloud Files, with the folders' names, their order, which
  conversations are in them (by room code; no conversation names or messages) and whether they
  are open. Nothing about folders goes to the Talk server. Turning it on first checks that your
  server saves that file safely (with a tiny test file it normally deletes right away); if it does
  not, or cannot be reached, the switch goes back off and says why. Under the switch: when it last
  synced, or why it is paused, and "Sync now". Turning it off stops syncing and leaves the file in
  place. A folder deleted on one device is deleted on the others.
- **When it syncs**: when the app comes to the front (at most every two minutes), about ten
  seconds after you change your folders, when the app leaves the front with a change not yet synced,
  every ten minutes while it is in front, soon after the network comes back (after a failed try,
  or with a change not yet synced), and with "Sync now". Nothing runs in the background, so a
  change made on another device arrives the next time you open the app.
- Folders stay on the phone when you sign out (for each account) and come back when you sign in
  to the same account again.
- Not in this version: notices about sync (a change that came from another device, sync paused)
  appear only in Settings; there is no drag and drop into folders. The folder screens and the sync
  were built from TalQ for desktop's own rules but had not run on a phone, or against a server and
  TalQ for desktop together, when 0.7.0 was made.

## 0.6.5

- **Settings > Diagnostics** also tells where Google push comes from: Google Play services
  signed by Google, a replacement signed by someone else (such as microG, or what an emulator app
  like GBox brings), or none; and whether the app runs inside another app (such an emulator app
  keeps the apps it runs in its own folder; updates must then be installed from inside it, and it
  must be let run in the background). Both are in the shared report.

## 0.6.4

- **Builds with an update channel** (organisation builds, not the store or GitHub ones): "Update"
  now downloads the new version itself, through Android's own download manager, from the
  channel's password-protected share (its server allows no public link without a password, which
  the browser download of 0.6.2 and 0.6.3 needed). When the download is done, tap its notification
  (or open it in Downloads) and confirm to install; the app still installs nothing itself.

## 0.6.3

- **Settings > Diagnostics**: what the app can and cannot reach, each line with a coloured dot.
  The server (answer time, versions, the account), push (how it arrives, the last push, whether
  Android allows notifications and full-screen calls, whether battery saving holds the app back),
  the call signaling server (a real sign-in, closed at once) and every STUN and TURN address the
  server hands out (each asked for an address or a relay, as a call would), the network (Wi-Fi or
  mobile, VPN, metered, IPv6, proxy) and shift status. A red line that a setting fixes opens that
  setting. The checks run only on this screen, never in the background.
- **Share report**: everything on that screen plus the app's recent log as one text file, through
  the usual share sheet (into a chat, by email...). It leaves out passwords, keys, the signaling
  ticket and call credentials.
- **Settings > Sounds**: pick the message sound and the call ringtone: the phone's own, one of the
  app's tones (the same as TalQ for desktop: six message tones, seven ringtones), any sound from
  the phone, or silent. Picking one plays it. A new sound replaces that notification channel, so
  Android's own settings for it (importance, vibration) start again from the app's defaults.
- Builds with an update channel look for a new version every two hours while the app is in
  front, also when it stays open all day (0.6.2: twice a day).

## 0.6.2

- **The conversation that is already open stays as it was** when you tap a notification about
  it, share something into it or use "Show in chat" in its shared files: it comes back to the
  front, switches to the right topic and scrolls to the message, and the unsent text in every
  tab is kept (it used to open afresh and lose it).
- **Previews show messages as the chat does**: the chat list, quotes, the reply bar, the topic
  list and notifications no longer show markdown markers or backslashes (`**`, `\[`).
- **Settings, Calls** (Android 14 and later): says when Android does not let the app show
  incoming calls full screen (a call then rings as a notification only, also on the lock
  screen), with a button to the system page that allows it.
- The app tells the server its real version (it said 0.1.0).
- A conversation list reload that failed is no longer hidden by an older one that worked, and an
  old failure no longer shows over a list that was just reloaded.
- A branded build learns about its updates from its organisation's own update channel: it looks
  at most twice a day and offers a newer version at the top of the conversation list and in
  Settings. Update opens the download in the browser; you open the file and Android asks before
  installing it. The app itself installs nothing and asks for no permission to (an unknown app
  that installs other apps is blocked by Google Play Protect). The public app has none of this
  (its stores update it).
- Third-party notices: the code inside the WebRTC library (used for calls) is now listed as it is
  in the app, each part with its full license text, including the parts the webrtc-sdk build
  adds under the Apache License 2.0.

## 0.6.1

- **Tablets, unfolded phones and wide windows** (at least 600 dp wide and 480 dp tall) show the
  conversation list on the left and the open conversation on the right. The open conversation
  is highlighted in the list; with none open the right side says "Select a conversation".
  Shared files and Settings open on the right too. Smaller windows, and a phone turned
  sideways, keep the phone layout.
- **The divider between the two can be dragged** (the list from 280 to 560 dp wide; TalkBack
  users have "Make the list wider / narrower" on it). The width is kept on the device. On a
  foldable held half open like a book, the divider sits on the hinge.
- Folding, unfolding, turning the device or resizing the window switches between the layouts
  on the spot and keeps the open conversation, its tab and the unsent text.
- Beside the list, a conversation has no back arrow, and back leaves the app (the list is
  already on screen); back from Shared files or Settings returns to the conversation.
- In the wide layout, messages may be up to 480 dp wide (310 dp on a phone).
- A conversation you have just read no longer shows its unread count again for a few seconds
  when a list reload that was already on its way comes back.

## 0.6.0

- **One-to-one calls, audio and video.** A one-to-one conversation has a phone and a camera
  button at the top. Calls go through your Nextcloud's Talk high-performance backend (its
  signaling server and media server), the way TalQ for desktop and the Nextcloud web app call,
  so a call works with both, in either direction. On a server without that backend the buttons
  do not show.
- **Incoming calls ring like a phone call**: full screen, also over the lock screen, with the
  ringtone, Answer and Decline. The ringing stops when the caller gives up, when you answer on
  another device, or after 45 s, and then leaves a missed call with "Call back". Declining also
  takes the call off your other devices (the caller keeps ringing until they give up: Talk has
  no way to send a decline). A call in a group conversation does not ring; group calls are not
  in this version.
- **In a call**: the other person's video full screen (their picture when they have none), your
  own preview in a corner, mute, camera on and off, front or back camera, earpiece, speaker,
  Bluetooth or a headset, the duration, and a note when nothing arrives from the other side
  ("Can't hear them"). In an audio call the screen goes dark at your ear.
- **The call keeps running** with the screen off or in another app: the notification shows it,
  with Hang up, and the conversation list has a bar back to it. A regular phone call puts it on
  hold. A short loss of the network shows "Reconnecting…" and either comes back or ends the call.
- The microphone (and the camera for a video call) is asked for when you place or answer your
  first call; the camera can also be switched on later in a call.
- Placing a call from the lock screen (Call back on a missed call) asks to unlock first.
- The app is larger: calls bring WebRTC's native library. It is included for ARM phones and
  tablets only, so x86 emulators and x86 Chromebooks can no longer install TalQ.

## 0.5.7

- **Italic and bold around inline code show correctly again.** 0.5.6 drew
  ``*run `rm *.tmp` first*`` (a star inside the code) with its stars instead of in italic; the
  rule behind that is undone, so emphasis is drawn as in 0.5.5 again, with escapes still
  respected. (The 0.5.6 change to `*a *b*` goes with it.)
- **Link labels with code or links in them.** A `]` inside inline code no longer ends a link's
  label (`` [`a]`](https://…) `` is one link again), and a link written inside another link's
  label is drawn as a link in plain brackets instead of a link inside a link.

## 0.5.6

- **Escaped characters show without their backslash.** A message whose text was escaped
  (`\[approved\]`, `\*`, `\_` and the like: servers and bots do this so the text cannot turn
  into a link, bold or code) now shows `[approved]`, `*`, `_`, as the Talk web app does, and an
  escaped `*` or `_` no longer starts or ends bold or italic. Links carry no backslash either,
  in their text or their address: `https://example.com/\~user` opens
  `https://example.com/~user`, and `\[https://example.com\]` shows the brackets around the link.
- Two small formatting fixes on the way, again as the Talk web app draws them: in
  `[a] and [b](https://…)` only "b" is a link (the text in between was swallowed into it), and in
  `*a *b*` only "b" is italic.

## 0.5.5

- **A reaction no longer leaves a one-to-one conversation "1 unread".** In a one-to-one
  conversation Talk counts a reaction to your message as an unread entry (and shows it as the
  last message); reading the chat to the bottom now counts it as read too, also when the
  reaction arrives while the chat is open. A conversation stuck at "1" this way clears the next
  time you open it.

## 0.5.4

- **Mute a topic with one tap**: while a topic is open, the bell at the top mutes it (its
  notifications go to Never) or unmutes it again; a message offers Undo. Press and hold a
  topic for the finer choices (all messages, mentions only) as before.
- **You can see which topics are muted**: on the topic strip a muted topic shows a slashed
  bell after its title, and a topic set to mentions only shows "@", as in TalQ for desktop.

## 0.5.3

- **Read status on your own messages**, as in TalQ for desktop: after the time, ○ means
  delivered and ◉ (in the accent colour) means everyone in the conversation has read it. It
  follows live while the chat is open. Shown only when your server offers it and you share
  your own read status (a Talk setting): without that, Talk does not tell anyone how far the
  others have read, and every message shows ○.
- **The branded build says when the phone is not paired with the ERP**: a notice at the top
  of the conversation list ("Shift status is off"), with Pair (opens Settings) and Not now
  (hides it for a week). It also appears when the ERP no longer accepts the phone.

## 0.5.2

- Settings showed "Theme" twice: the old fixed line ("Follows the system") from before the
  theme choice existed is gone.

## 0.5.1

- **Themes.** Settings, Appearance, Theme: Ember, Warm and Vivid (dark) and Paper (light),
  the four of TalQ for desktop, or "Follow phone" (Vivid when the phone is dark, Paper when it
  is light), which stays the default. The choice applies at once, also on the share screen, and
  the status bar icons follow it. It belongs to the phone, not to the account: signing out
  keeps it.

## 0.5.0

- **Notifications without an extra app.** On a phone with Google Play services, notifications
  now come straight through them: there is nothing else to install. Your server still
  encrypts every notification end to end; Google carries it without being able to read it,
  and TalQ uses no Firebase library and no Google account information.
- **Settings shows how notifications are delivered** ("Delivered by") and can switch between
  Google Play services and a UnifiedPush app such as ntfy, when one is installed. A phone that
  already used ntfy keeps it, also after signing out and in again. If the push app you use is
  removed, notifications carry on through Google Play services by themselves.
- On Honor and Huawei phones Settings says how to keep notifications coming once the app is
  swiped away (App launch, Manage manually).
- **Two variants of the public app**: the standard one with the Google route, and one without
  it for F-Droid (`-foss`), which uses a UnifiedPush app only. Both have the same app id; one
  can replace the other only when both are signed with the same key.
- Signing out now also forgets which tab each conversation was last open on.
- A privacy policy (`PRIVACY.md`) and store descriptions.

## 0.4.3

- **A branded build is signed with its own release key** (its file sits next to the public
  app's, outside the repository; see app/build.gradle.kts). An earlier branded build was
  signed with a different key, so Android will not update it to this version: uninstall it
  first (you then sign in again). Nothing changes for the public app.

## 0.4.2

- **The first published build of the public app**, signed with the project's release key
  (certificate SHA-256 `44:E6:9A:2F:37:24:31:C0:DE:0B:08:C8:C2:9B:5F:65:44:B0:93:95:09:62:48:18:F5:E6:02:28:2E:AF:06:9B`).
  Test builds before it were signed with a different key, so Android will not update one of
  them to this version: uninstall it first (you then sign in again).
- **The branded build signs in to its own server only.** Its sign-in screen has no server
  address to type or pick: it always uses the address built into the app, also when the
  server names a different address of its own during the sign-in.
- **The branded build has its own launcher icon**: TalQ's Q in the organisation's colour
  instead of its bare logo, so it is not mistaken for the organisation's other apps. The
  notification icon is the Q as well.

## 0.4.1

- **A much smaller download.** The app is now built the way a finished app is: code and
  pictures it never uses are left out, so the APK is about 3.6 MB instead of 32 MB. It is no
  longer a debugging build either, so it should also run quicker. It updates an earlier TalQ
  signed with the same key in place and keeps your sign-in.

## 0.4.0

See at a glance whether a colleague is reachable, and whether they are working right now.

- **Presence dot.** In one-to-one conversations the avatar carries a small dot at the bottom
  right: green when the person is online, amber when away, red for "do not disturb" and
  "busy". No dot means offline. It is in the conversation list and at the top of the open
  conversation, and follows the server about every half minute while you look at it.
- **Who they are right now, in words.** At the top of a one-to-one conversation, under the
  name: "Online", "Away", "Do not disturb", "Busy" or "Offline", or the person's own status
  message with its icon when they set one. In a conversation with topics the name of the open
  tab moves one line down.
- **Shift light.** If your organisation has an ERP that knows the rosters, the avatar also
  carries a small rounded square at the bottom left: green for on shift, amber for on a
  break, grey for off shift (the avatar is then dimmed as well). The top of the conversation
  says it in words, before the presence. A colleague the ERP knows nothing about shows no
  square at all. The square and the dot answer different questions: the square is "are they
  at work", the dot is "is their app open".
- **Shift status needs a one-time pairing** (Settings, "Shift status", "Pair with ERP"): your
  browser opens the ERP's approval page, you approve this phone there, and the app continues
  by itself. No password is typed into the app. The key the ERP hands out is stored encrypted
  on the phone, is only ever sent to that ERP (never to your Nextcloud server), and is removed
  when you sign out or tap "Unpair". If the ERP stops accepting the phone, Settings says so
  and offers to pair again.
- Shift status is asked for the people in your conversation list, about every two minutes,
  and only while the app is open on the screen.
- **A branded build for an organisation.** Besides the public app there can now be an
  organisation's own build: its own name, icon and logo, its server already filled in on the
  sign-in screen and its ERP address built in. It is a separate app and installs next to the
  public one. In the public app the ERP address is typed in Settings ("ERP address"); as long
  as it is empty nothing about shifts is shown or sent.

## 0.3.1

- **A muted topic stays silent.** A topic you set to "Never" no longer shows any notification:
  no messages, no mentions, no replies and no reactions (the server still sent those for a
  muted topic). The same goes for a conversation set to "Never". A topic you switched back on
  inside a muted conversation still notifies.
- Setting a topic or a conversation to "Never" also removes its notifications that are
  already showing.
- **Unread number on the app icon.** The icon shows how many messages are unread, leaving
  out muted conversations and muted topics. It follows what you read here and on your other
  devices, and goes away when everything is read or you sign out. The number is shown on
  Honor and Huawei home screens; other phones show their usual dot or count from the
  notifications.
- When several messages of a conversation with a muted topic arrive, a notification may
  appear a moment later than before: TalQ first checks which topic the message is in.
  If that cannot be found out (no network at that moment), the notification is not shown at
  all in a conversation that has a muted topic: a muted topic must never fire. The message
  still counts on the icon when its topic is not muted.
- The icon number also catches up on its own about twice an hour (messages that send no
  notification, such as those in a group set to mentions only, and things you read on
  another device). Between those moments it is as of the last notification or the last time
  TalQ was open. A phone that restricts TalQ's background activity may do this later or not
  at all.
- Signing out while a notification was still being prepared no longer shows it afterwards
  or puts a number back on the icon.
- Calls, invitations and reminders are not affected by "Never".

## 0.3.0

Files: send them, open them, find them again.

- **Send files**: the paperclip next to the message field sends photos and videos from your
  gallery, a new photo from the camera, or any file. What you typed goes along as the
  caption. Files go into the topic you are in.
- **Smaller photos by default**: photos are shrunk before sending and their location data is
  removed. "Send original" in the attach sheet turns that off.
- **Uploads you can see**: each file shows its progress in the chat and can be cancelled. An
  upload that fails stays in the chat with Retry. Uploads carry on, with a notification, when
  you leave the app.
- **Share to TalQ**: "Share" in another app now offers TalQ. Pick the conversation and the
  topic, add a message, send.
- **Open files in the app**: tap a file and it downloads right on its card, then opens in a
  suitable app. If nothing on the phone can open it, TalQ says so and offers to save it.
- **Save and Share**: press and hold a message with a file to save it (pictures to Pictures,
  everything else to Downloads) or to pass it to another app. No storage permission is asked.
- **Pictures**: swipe sideways through the pictures of the chat or topic you are in. Pinch or
  double-tap to zoom. Save and Share are at the top.
- **Videos** play inside the app: tap the thumbnail. If a video cannot be played that way it
  is downloaded and opened in another app.
- **Voice messages and audio** play right in the chat, with play/pause and a bar you can drag.
  Only one plays at a time, and it stops when you leave the conversation.
- **Shared files**: the conversation menu has "Shared files": everything shared there as
  Media, Files and Voice. Tap to open, press and hold to save, share, or jump to the message.
- Your sign-in is only ever sent to your own server, also when a download or a video is
  redirected somewhere else.

## 0.2.0

Topics now live inside the chat, one tap away, instead of on a screen of their own.

- **Topic strip**: a row of chips under the top bar: All, General, then your topics, unread
  ones first. Tap a chip to switch; the chat changes in place. Each topic keeps its own
  place, draft and reply while the conversation is open.
- **Opens where you left off**: a conversation opens on the topic you last had open there
  (General the first time). Back leaves the conversation in one press from any topic.
- **Next unread**: when you have read a topic to the end and another one has something new, a
  "Next: topic (3)" button above the message field takes you straight there.
- **All topics**: a button in the top bar opens the full list with a search field, for
  conversations with more topics than fit on the strip.
- **Press and hold a chip** to set that topic's notifications (Default, All messages, Mentions
  only, Never), rename it, or mark it as read. A topic set to Never shows a grey count.
- **New topic**: tap "+", give it a title, and write its first message. Nothing is created
  until you send; switching away drops it.
- **Chat list**: when the newest unread message is in a topic, the topic is shown as a small
  button on that conversation. Tap it to open the conversation right on that topic.
- The separate topic list screen is gone.

## 0.1.0

The first version of TalQ for Android: a Nextcloud Talk client where topics come first.

- **Sign in** to your Nextcloud server through your browser. TalQ only ever stores an app
  password, encrypted on the phone, and removes it from the server when you sign out.
- **Chat list**: all your conversations, newest activity first, with unread counts, a mention
  marker and the latest message.
- **Topics**: a chat that has topics opens on its topic list. Each topic shows its title,
  latest message, time and how many messages you have not read. "General" holds everything
  written outside a topic. You can start a new topic, mute a topic, or switch to the
  whole-chat view where all messages appear together.
- **Chats without topics** open straight into the conversation.
- **Messages**: sender, time, formatted text (bold, italic, code, quotes, lists, links),
  mentions, reply quotes, image previews with a full-screen viewer, file cards and reactions.
- **Writing**: send a message, reply to a message (swipe or long-press), write into the open
  topic, add or remove a reaction. A message that could not be sent stays visible with a
  retry; nothing is lost silently.
- **Reading**: a chat opens at the first message you have not read. Reading one topic does not
  mark the other topics read.
- **Notifications** while the app is closed, through a UnifiedPush app on the phone (for
  example ntfy) and the server's web push. Tapping one opens the conversation, in the right
  topic. Notifications you read elsewhere disappear, and the chat you are looking at stays
  quiet. Settings shows whether notifications are working and what to do if they are not.
- **Light and dark themes** that follow the system.

Not in this version: calls, polls, voice messages, sending files, editing or deleting
messages, search.
