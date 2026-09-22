# Setup

Install RustedWax, add a Hive posting key, and grant only the Android access
needed for the sources you use.

[← Back to the project README](../../README.md)

## Install the public release

1. Open the [latest RustedWax release](https://github.com/rustedcomet/RustedWax-Android/releases/latest).
2. Download the signed `rustedwax-v0.12.0-release.apk` asset.
3. Open the APK on Android 8.0 or newer. Android may ask you to allow installs
   from the browser or file manager that opened it.
4. Launch RustedWax.

The build-from-source path is for developers and is documented separately in
the [project README](../../README.md#build-from-source).

## First-time setup

1. Grant **Notification Access** when RustedWax asks. Android requires this
   special access for RustedWax to read active media sessions. The Android
   permission can expose notification content; RustedWax limits its use to
   the media/session evidence described in [How it works](HOW_IT_WORKS.md#sources-and-notification-access).
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
5. Play something. **Now** shows the current media with full-width artwork,
   service badge, progress, and status. **History** shows recent successful or
   accepted scrobbles with media artwork, without transaction IDs on its cards.
   From a finalized History item you can publish a Snap and open its Comments
   sheet to read or write replies. **Not logged** explains recorded refusals,
   including unidentified items without a link. History and Not logged are
   scoped to the connected Hive account; neither is a complete playback record.

Snaps cannot be created directly from Now in v0.12.0. Replies can be nested,
and eligible Snaps and replies can be Liked with a Hive vote. **Settings →
Snaps & Likes** controls the default strength of future Likes. While RustedWax
is running, loading a visible History Snap or opening its Comments conversation
can discover new replies and update reply attention. A newly discovered
qualifying reply can produce an Android alert if enabled; alerts are not instant
push. Likes do not generate notifications.

On Android 13 or newer, RustedWax may ask once for permission to **show reply
alerts** after a Hive account is connected. This is separate from the
**Notification Access** used to observe media. If alerts are declined or
disabled, in-app reply attention can still appear when conversations are read.

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
test scrobble. Scrobbles come from automatic finalization of real playback;
Snaps, replies, and Likes require separate user actions.

## Fresh-install defaults

| Setting | Default |
| --- | --- |
| Monitoring | **On** |
| YouTube scrobbling | **On** |
| Automatic scrobbling | **Off** |
| Event log | **Off** |
| Disable Shorts | **On** |

An update preserves stored choices. Private mode is not exposed in current
settings; compatible stored privacy preferences remain honored. Fresh installs
publish public scrobbles when automatic scrobbling is authorized. See
[Private envelope compatibility](ON_CHAIN_FORMAT.md#private-envelope-compatibility).

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
