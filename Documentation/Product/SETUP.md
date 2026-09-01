# Setup

Install RustedWax, add a Hive posting key, and grant only the Android access
needed for the sources you use.

[← Back to the project README](../../README.md)

## Install the public release

1. Open the [latest RustedWax release](https://github.com/rustedcomet/rustedwax/releases/latest).
2. Download the signed `rustedwax-v0.11.1.apk` asset.
3. Open the APK on Android 8.0 or newer. Android may ask you to allow installs
   from the browser or file manager that opened it.
4. Launch RustedWax.

The build-from-source path is for developers and is documented separately in
the [project README](../../README.md#build-from-source).

## First-time setup

1. Grant **Notification Access** when RustedWax asks. Android requires this
   special access before an app can read active media sessions. RustedWax uses
   it for YouTube, YouTube Music, Brave, and Chrome playback.
2. Open **Settings → Hive account → Connect**. Enter your Hive username and a
   **posting key** (a WIF beginning with `5…`), then tap **Validate & save**.
   RustedWax derives the public key locally and checks it against the account's
   on-chain posting authority before saving it.
3. Keep **YouTube scrobbling** on if you want RustedWax to observe YouTube in
   Brave, Chrome, the YouTube app, and YouTube Music. This one switch also
   allows the metadata lookups needed to verify an otherwise ambiguous video.
4. Turn on **Automatic scrobbling** when you are ready for qualifying listens
   to be published. It is off on a fresh install and stays unavailable until a
   valid Hive key is saved.
5. Play something. **Now** shows the active platform, title, channel or artist,
   duration, progress, category, and a short status. **History** shows the
   newest 50 results for the current app process, including transaction IDs.
   **Not logged** explains eligible identified tracks that RustedWax declined.

The always-visible **Stop** button is stronger than Automatic scrobbling: Stop
ends observation and discards the in-flight track. Turning Automatic scrobbling
off leaves observation running but authorizes no new listen to be written.

## Optional access

Only enable these when you want the associated capability:

- **Browser evidence access** opens Android Accessibility settings for the
  RustedWax browser service. It is OS-scoped to Brave and Chrome and reads the
  address bar plus exact visible YouTube ad labels. On Android 13 or newer, you
  may first need to allow restricted settings from RustedWax's App info screen.
- **YouTube watch history** opens Google's sign-in page. RustedWax keeps the
  resulting `youtube.com` session encrypted on the device and reads only
  `youtube.com/feed/history` to match a just-played native video. The YouTube
  app must use the same account, with watch history enabled and incognito off.
  Disconnecting from RustedWax wipes the stored session.
- **Picture-in-picture time** needs Android **Usage access**. A Short in PiP
  publishes no seekbar or MediaSession position, so without this grant PiP time
  is not counted. With it, eligible elapsed time is marked inferred rather than
  measured. The row disappears after the grant because there is no separate
  PiP preference.
- **Foreground Shorts evidence** is experimental and needs a separate
  YouTube-only Accessibility grant. In **Settings → About**, tap the version
  seven times to unlock **Developer mode**, then use **Foreground Shorts
  evidence**. Screen-off and lock-screen time are never counted.

## Developer diagnostics

Developer mode contains the **Event log**, **Disable Shorts**, and **Test Hive
connection** controls. Event logging is off on a fresh install. When enabled,
the conditional **Log** destination keeps at most 12 hours and 512 KiB and
offers **Clear log** and **Export**.

**Test Hive connection** reads node health, posting authority, and whether the
saved key derives to an authorized public key. It does not sign or broadcast a
test scrobble. Everything RustedWax writes on-chain comes from automatic
finalization of real playback.

## Fresh-install defaults

| Setting | Default |
| --- | --- |
| Monitoring | **On** |
| YouTube scrobbling | **On** |
| Automatic scrobbling | **Off** |
| Event log | **Off** |
| Disable Shorts | **On** |

An update preserves stored choices.

## Protect your Hive account

> **Use only a Hive posting key. Never enter an active key, owner key, or Hive
> master password.**

RustedWax stores the posting key in `EncryptedSharedPreferences` backed by the
Android Keystore and signs locally. Only the signed transaction is sent to a
Hive node. There is no biometric confirmation for each signature; an unlocked
phone can sign while Automatic scrobbling is on.

A posting key can post, comment, and vote as your account. For a smaller blast
radius, create a dedicated keypair on a trusted desktop, add its public key to
your account's posting authority, and save that private posting key in
RustedWax. You can later revoke that one key without rotating the posting key
you use elsewhere.
