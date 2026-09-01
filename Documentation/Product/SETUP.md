# Setup

Installing, granting access, and adding your posting key.

[← Back to the README](../../README.md)

---

## Setup

1. Install the APK.
2. Enter your **Hive username** and **posting key** (WIF, starts with `5…`).
   The app derives the public key and checks it against your account's on-chain `posting.key_auths`
   before accepting it — a wrong key is rejected immediately, offline-verifiable against
   a current healthy Hive node.
3. Grant Notification Access when prompted.
4. Optionally enable **Browser evidence access** (for browser `url` evidence and exact visible
   YouTube ad labels) — on Android 13+, allow restricted settings from App info first.
5. **YouTube scrobbling** is on by default and covers every surface: YouTube in Brave and Chrome,
   the native YouTube app, and native YouTube Music. There is nothing further to switch on — video
   lookups and picture-in-picture counting are part of it, not separate settings.
   Foreground native Shorts additionally require the separate **Foreground Shorts evidence**
   accessibility grant, which is OS-scoped only to the YouTube package. It is in developer mode:
   open **Settings › About**, tap the version seven times, then open **Developer mode**. Background,
   screen-off and lock-screen time are never counted.
6. Grant Android's **Usage access** if you want picture-in-picture time to count. A Short in PiP
   publishes no seekbar and no MediaSession position, so without this it counts for nothing. With
   it, elapsed time is credited from two signals together — YouTube holding a visible window, and
   media audio being started — and every such listen states how much was measured and how much
   inferred. The same two signals are also what let the log say when something is playing that
   nothing is counting; that reporting credits nothing by itself. Revoking Usage access disables
   both, immediately. The settings screen offers the grant only while it is missing; once it is
   held there is nothing to configure.
7. Play something in Brave, Chrome or a native YouTube app. The **Now** tab shows the platform,
   the artist or channel, the title, the length, a progress bar with the percentage played, the
   final category and one short status line — what is playing and whether it will count.
   **History** shows the newest 50 results for the current app process, with tx ids. History is
   diagnostic memory, not permanent local storage; the chain remains authoritative.

The Now tab's per-session **Broadcast this scrobble** button was removed in v0.11.1b, along with the
**Settings → Hive account** synthetic test broadcast. Everything RustedWax puts on-chain is now
produced by automatic finalization. The diagnostic evidence the Now card used to draw — package,
origin, source proof, video id, resolver route, coverage, classification reasoning, payload preview
and raw metadata — is written to the **event log** instead, under **Settings → About → Developer
mode → Event log**, which has a 12-hour/512 KiB retention bound and an `Export` button on the
**Log** tab.

To check that this device *could* scrobble without writing anything, use **Settings → About →
Developer mode → Test Hive connection**. It reads a node's head block, the account's posting
authority and whether the saved key derives to a key on it, and broadcasts nothing.

On **History** and **Not logged**, tapping a row's thumbnail or its title opens that video in the
YouTube app. A row is only tappable when the exact video id was proven — most **Not logged** rows are
there precisely because it never was, and those stay inert rather than opening a search that might
land on a different upload of the same title.

### What a fresh install starts with

| | |
| --- | --- |
| YouTube scrobbling | **on** — every YouTube surface is watched |
| Automatic scrobbling | **off** — nothing is broadcast until you add a key and opt in |
| Event log | **off** — nothing is written to disk until you turn it on |
| Disable Shorts | **on** — a proven `/shorts/` video is not scrobbled |

Updating an existing install changes none of these: whatever you had is what you keep, including
defaults you never touched. See [<redacted-private-provenance>](../History/<redacted-private-provenance>) for exactly how the four
old YouTube switches were folded into one.

### About your posting key

The key is stored in `EncryptedSharedPreferences` backed by the Android Keystore. **There is no
biometric gate yet** — an unlocked phone can sign. The key never leaves the device — signing is
local, and only the signed transaction is sent to a Hive node.

Be aware this is a **larger blast radius than Keychain on desktop**: a raw posting key can post,
comment, and vote as you, with no per-operation prompt. If you want that reduced:

- Generate a fresh keypair, add its public key to your account's posting authority (from desktop
  Keychain / Hive Blog → Wallet → Permissions), and give the app *that* key. You can revoke it later
  without rotating the posting key you use everywhere else.

The app never asks for your active, owner, or memo key. If anything ever does, it isn't this app.
