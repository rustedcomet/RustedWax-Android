# How it works

The detection pipeline, end to end.

[← Back to the README](../../README.md)

---

## How it works

1. You grant the app **Notification Access**. It's required twice over: Android gates
   `MediaSessionManager.getActiveSessions()` behind it, and the browser's media notification is the
   default source for the page's origin (`youtube.com`) when optional Browser evidence access is off.
2. The notification listener — which the system keeps alive for as long as the grant is held — hosts
   the session watcher. On Start, reconnect or process rebuild it first replays currently active
   browser media notifications, then discovers sessions; an unchanged background notification does
   not have to be posted a second time. No foreground service, no persistent notification.
3. When an accepted browser or native YouTube package plays media, the OS
   media session exposes title, artist, album, duration and playback position. The app accumulates
   content played against the configured threshold (60% by default).
4. When a track ends, the app first requires one verified YouTube video id—either the matching exact
   id frozen while it played or one bounded lookup that uniquely corroborates the available
   finalized fields. When Chromium omitted duration, one exact title plus the canonical page's exact
   channel may qualify only if exactly one id survives. For playlist autoplay with neither a current
   URL nor a history row, two immediately preceding verified ids can qualify public playlists only
   where they appear adjacently and in playback order; exactly one id among the immediate next rows
   must match the finalized fields. Multiple lists may corroborate the same next id, but two next ids
   refuse. Browser visibility and accessibility coverage do not alter that identity
   gate; a literal track-bound ad signal remains an independent veto. The app then builds the same
   `custom_json` payload with its canonical watch link, signs it with your posting key on-device,
   and broadcasts it. If **private scrobbles** are on for that kind, everything except `app`, `kind`
   and `timestamp` is encrypted into the payload's `private` blob first — and if the key cannot be
   derived, the listen is held back rather than broadcast in the clear. Before calling it a scrobble, it normally confirms that a
   healthy independent node has included it in a block. A transaction seen relaying in an
   independent healthy node's mempool is reported separately and is not retried, to avoid creating
   a permanent duplicate. Acceptance with no available confirmation is also reported separately; see
   [Scrobble rules](SCROBBLE_RULES.md#retry).
   Definite failures and offline sends are queued.
5. When it *doesn't* broadcast, the reason lands in the **Not logged** tab. A scrobbler that
   silently declines things is indistinguishable from a broken one. One exception, added in
   v0.10.0: a session never proven to be YouTube is not listed there either. "Why wasn't this
   scrobbled" does not need answering about a site the app never scrobbles, and listing its title
   would rebuild the browsing record the event log stopped keeping.

**Stop** cuts all of that off. It tears down the session watcher, stops reading notification and
address-bar callbacks, and clears notification, URL, playlist, and carried-progress evidence —
nothing is observed while it's stopped, and the track playing when you press it is discarded rather
than scrobbled on the way out. Scrobbles already earned remain in the
offline queue and are eligible to send the next time the queue is flushed. Automatic scrobbling is
a separate, inner switch: turning *it* off leaves the app watching, which is how you check what a
title would have parsed to without writing anything to the chain. It is also a temporal write
boundary, not a finalization-time preference. Every logical listen is stamped with the exact
continuous ON/OFF generation in which it began. A listen first seen while Automatic Scrobbling was
off can never become writable because the switch is enabled later. After eligibility and the dedup
claim, the target must atomically commit its complete ordered payload batch against that same
generation before transport owns it. If OFF wins that ordering, the claim is released and no key,
signature, queue entry or broadcast is attempted. If commitment wins, the batch is already-authorized
transport work and a later OFF does not cancel half of it. The shared commit section contains no
payload serialization, posting-key, network or signing work, so the UI never waits for Hive.
MediaSession recreation, long continuation, and foreground-Short resume keep the original stamp.
Already-serialized retry-queue
work remains owed transport work and does not re-enter this decision.

```
Brave (playing) ──▶ Android MediaSession ─────┐
       media notification (origin) ───────────┼──▶ RustedWaxListenerService
       browser evidence (optional: url, id, ad UI) ┘   │  SessionProbe
                                                       │  track ends (last active evidence frozen)
                                          prefilter — reject what no lookup could rescue,
                                          so a shorts feed doesn't fetch per finalize
                                                       │
                                     verified-id recovery when needed, then
                                     enrichment (optional): YouTube Music catalogue,
                                     watch-page category + length + description
                                     credits, MusicBrainz
                                                       │
                                     ScrobbleRules (configured %, +160% songs only, length floor)
                                                       │
                                     MusicClassifier → kind: song / video
                                                       │
                                          DedupLedger + MutedVideos
                                                       │
                                       HiveScrobblePayload  ◀── same schema as extension
                                                       │
                                       local secp256k1 signing (posting key)
                                                       │
                                  broadcast to a node proven current,
                                  then confirm the tx reached a block
                                                       │  not confirmed / rate-limited
                                                BroadcastQueue ──▶ retry w/ backoff
```
