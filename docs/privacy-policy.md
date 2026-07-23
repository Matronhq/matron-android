# Privacy policy

The canonical privacy policy lives at **https://matron.chat/privacy** — that URL
is what the Play Store listing (and the App Store listing) point to.

This file used to hold a draft; it was superseded by the hosted policy. When the
policy changes, update the site, not this file.

Key facts the Play **data safety form** must stay consistent with (see the
hosted policy for the authoritative wording):

- BYOS: all content (messages, attachments, voice notes, credentials) goes to
  the user's own journal server, not to the publisher.
- No analytics, ads, crash reporting, or tracking SDKs in the app.
- The only publisher-operated service is the content-blind relay
  (push.matron.chat) used for push notification delivery and device-link
  rendezvous; it holds no message content. (Android push is currently dormant.)
- Microphone is used only for voice notes the user records and sends.
- Session credentials are stored on-device in EncryptedSharedPreferences
  (AndroidKeyStore-backed).
