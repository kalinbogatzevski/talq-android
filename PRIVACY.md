# Privacy

TalQ for Android is a client for **your own Nextcloud server**. Its developer runs no server
for it and collects nothing: no analytics, no advertising, no trackers, no crash reports.

## What stays on the phone

- Your server address, login name and the **app password** your server issues at sign-in. The
  app password is encrypted with a key held in the Android Keystore that never leaves the phone.
- Settings and small working lists (for example the notification level of each topic, the tab
  last open in a conversation, uploads still in progress).
- Files you open are downloaded into the app's private cache; files you choose to save go to
  your Pictures or Downloads folder; files you send are copied to the app's cache until they are
  uploaded (photos have their location data removed unless you send the original).
- Nothing the app keeps for itself goes to cloud backup or device-to-device transfer (files you
  save to Pictures or Downloads are ordinary files there, like any other).
- Signing out revokes the app password on your server, if the server can be reached at that
  moment; otherwise remove "TalQ" under Security, Devices & sessions in your Nextcloud. It
  also deletes from the phone the account, its keys, received and outgoing files, cached
  pictures, notification levels, remembered tabs and any ERP pairing. The last server address
  (and an ERP address you typed) stay, to fill in the next sign-in.

## Where data goes

- **Your Nextcloud server**: every request, signed with your app password. The app sends your
  sign-in to no other host, also when a download is redirected elsewhere.
- **Push notifications**: your server sends them as Web Push, encrypted end to end
  (RFC 8291) with keys created on the phone, to the push service you use:
  - a UnifiedPush distributor app of your choice (for example ntfy) and its server, or
  - in the standard build, if you keep that route, Google Play services (Firebase Cloud
    Messaging). TalQ registers with it directly: no Firebase library is in the app, and no
    Google account information is used by TalQ.

  The push service carries the encrypted message. It can see when a push arrives, how large it
  is, which server sent it to which phone, and a notification number and priority the server
  attaches; not what it says.
- **Calls**: the call is set up through your Nextcloud server and its call backend (the
  signaling server and media server your Nextcloud is configured with). Your voice and, when
  the camera is on, your video go encrypted (DTLS-SRTP) to that media server, which passes them
  to the other person; where a direct path is blocked they go through the TURN relay servers
  your Nextcloud names. The app keeps no recording and no call history of its own. The
  microphone and camera are used only during a call you placed or answered, while the call's
  notification is showing; the camera only while you have it switched on.
- **Shift status (optional)**: only if an ERP address is set. Pairing sends the ERP the phone's
  model name, shown on its approval page. Once you approved the phone in your browser, the app
  sends, over https and only to that ERP, the Nextcloud user names
  of the people in your one-to-one conversations, with the key the ERP issued (stored encrypted
  like the app password, removed when you sign out or unpair). With no ERP address set nothing
  about shifts is sent anywhere.

## Permissions

Internet; notifications; running uploads in the background (foreground service, data sync);
setting the unread count on Honor and Huawei home screens. For calls, asked for when you
place or answer your first call: the microphone, and the camera for video calls; on Android
12 and later the names of Bluetooth devices, for the audio output list. Without asking:
managing its own calls with the phone's call system (Bluetooth headsets, cars, and a regular
phone call putting a TalQ call on hold), keeping a call running in the background (foreground
service for phone calls, microphone and camera), showing an incoming call over the lock
screen, changing the audio output, and turning the screen off at your ear during a call.
Added by the push and background-work libraries: keeping the phone awake briefly while a
notification is handled, checking whether a
network is available before background work, and restarting scheduled background work after
the phone restarts. Photos and files are picked through the system picker, so no storage
permission is asked.

## Contact

Questions: open an issue at https://github.com/kalinbogatzevski/talq-android/issues.
