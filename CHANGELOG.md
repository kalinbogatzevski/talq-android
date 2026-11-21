# Changelog

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
