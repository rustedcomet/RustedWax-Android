# RustedWax behavior contract

This document is the canonical behavior reference for RustedWax. It separates
observed implementation from intended product behavior so that a test, comment,
or incident note cannot silently become product policy.

The source code decides what a particular build actually does. This document
decides what a conforming build is supposed to do. `README.md` describes the
product, `TESTING.md` verifies this contract, and `PHASE*.md` files are historical
design records rather than current specifications.

> **Current checkpoint:** v0.11.1/version code 58, Architecture Phase 9 accepted
> on 2026-08-24 and preserved unchanged by the v0.11.1 UI/settings/log work. The authoritative current video-id boundary is
> [Documentation/Product/IDENTITY.md](IDENTITY.md#current-identity-contract): one
> frozen exact id or one unique finalized lookup, independent of browser
> visibility; a literal ad signal remains an unconditional veto. Automatic,
> manual, and shadow triggers now call the single `FinalizeTrackUseCase`
> directly. `MediaSessionDriver` owns reducer state, and the former
> `ScrobbleEngine` facade plus global evidence callbacks are absent from
> production. The exact installed Phase 9 APK independently block-confirmed
> real native YouTube and Brave listens with automatic scrobbling enabled.
>
> Versioned sections below remain immutable incident and design history. Whenever an older section
> requires a current-track accessibility scan, playlist provenance, foreground browser, or the
> removed `browserEvidenceUnavailableReason` before a unique lookup may scrobble, that requirement
> is superseded by the current identity contract and v0.11.0g section. Historical observations and
> transaction evidence remain factual; their former policy conclusions are not current behavior.

## As-built audit: v0.8.6 before reconciliation

This table was written against the v0.8.6 source before the reconciliation work
below changed runtime code. It is intentionally retained as an incident record.

| Area | What v0.8.6 actually does | Source of truth in v0.8.6 | Conflict found |
| --- | --- | --- | --- |
| Qualifying playback | Uses the configured threshold, a 30-second ordinary floor, and a 10-second floor for a `/shorts/` identity whose resolved facts say it is listed | `ScrobbleEngine.onTrackFinalized`, `ScrobbleRules.prefilter`, `ScrobbleRules.decide` | The Now card displays a hard-coded 60% verdict even when the configured threshold differs |
| Looping short | Rejects a verified short under 30 seconds when accumulated progress is above 200%, with `looped unattended rather than watched` | `ScrobbleRules.SHORT_MAX_PROGRESS` and `ScrobbleRules.decide` | `TESTING.md` says both “one scrobble” and “nothing”; the implementation does not detect a loop event, it only rejects high accumulated progress |
| Transaction count | Computes up to two percentages, then caps every non-song kind to one transaction | `ScrobbleRules.decide`, `ScrobbleRules.capForKind` | The existing one-transaction cap already prevents a looping video from producing a second transaction, making the separate total rejection a conflicting policy |
| Session recreation | Finalizes the disappearing fragment first, then stores its accumulated progress for a replacement session with matching package plus title/artist/album/duration metadata | `SessionProbe.Watch.onSessionDestroyed`, `Watch.dispose`, `TrackProgressCarry` | README says the listen is scored once as a whole and says carry is keyed by video; neither statement is true in v0.8.6 |
| Genuine replay near a restart | May inherit stored progress when it reappears with the same metadata within 60 seconds | `TrackProgressCarry.TTL_MS` and metadata-derived `trackKey` | Two separate partial viewings can be combined; only a replay after the TTL is guaranteed to start fresh |
| Stop | Tears down the probe without finalizing current tracks and clears notification hints and carried progress | `RustedWaxListenerService.stopProbe`, `SessionProbe.stop(false)` | It does not clear `UrlEvidence`, and `onNotificationRemoved` still reads the removed notification title while stopped |
| Notification access disclosure | Reads media-notification title, text, and subtext for Brave and Chrome only | `RustedWaxListenerService.onNotificationPosted` | The access banner incorrectly calls the listener a stub and says notification contents are not read |
| Short verification | Treats `VideoFacts.resolvedOnWatchPage` as true whenever `lengthSeconds` is present | `VideoFacts.resolvedOnWatchPage` | A YouTube Music-only fallback supplies a length and can therefore be described as a resolved watch page even though the watch page failed |
| Broadcast success | Reports `Success` for block inclusion, a final mempool observation, no expected transaction id, or a completely unanswered confirmation check | `HiveRpc.broadcast` and `HiveRpc.confirm` | UI says `Confirmed on-chain` for all four cases |
| Confirmation node | Returns the first nonblank transaction status in configured node order, normally starting with the accepting node | `HiveRpc.transactionStatus` | Comments and documentation say confirmation is checked against a different node and that every node is considered |
| Retry queue ownership | Stores the original username and serialized payload, but retries with whichever posting key is currently saved | `BroadcastQueue.Entry`, `ScrobbleEngine.flushQueue` | Changing accounts can sign an old account's queued operation with the new account's key, after which rejection removes it |
| Retry queue durability | Stores JSON in `filesDir`, silently turns read/parse errors into an empty queue, silently ignores write errors, and removes an entry after eight queued failures | `BroadcastQueue.read`, `write`, and `recordFailure` | “Durable” overstates behavior when persistence and terminal loss are not surfaced |
| Queued History data | Stores no percent or video id in the queue; a later successful retry adds a new in-memory History row using 0% and no video id | `BroadcastQueue.Entry`, `ScrobbleEngine.note` | History does not accurately describe the payload that was eventually sent |
| Manual session broadcast | Requires a buildable payload, a non-muted video, and a free dedup claim, but does not apply automatic thresholds or duration/short rules | `MainActivity.onBroadcastSession` | The button is a rules bypass even though it is labelled as an ordinary scrobble action |
| History persistence | Keeps the newest 50 records in a process-memory `StateFlow` | `ScrobbleEngine._recent` | History disappears on process death; current documentation must not imply it is an on-device permanent ledger |

## Product invariants

These are the intended rules. A behavior change must update this section first,
then implementation, tests, UI text, README, and TESTING in that order.

1. **One continuous qualifying video viewing produces at most one scrobble.**
   A video that crossed the configured threshold has earned that scrobble.
   Auto-looping may be recorded diagnostically, but must not erase the earned
   viewing or create a second transaction. A `/shorts/` source stays capped even
   when its payload is correctly classified as `song`. An observed playback
   position reset from the end of the same media item to its beginning is a loop
   for this purpose and also caps the continuous viewing to one transaction,
   regardless of payload kind. A separate viewing after the continuation window
   remains a new listen.
2. **Browser session churn is not a track ending.** Progress may move to a
   replacement session. Only a real track change, a stopped playback state, or
   expiration of the continuation window finalizes the listen.
3. **Stop is a hard observation boundary.** Pressing Stop does not finalize the
   current track, clears notification, URL, playlist, and carried-progress
   evidence, and all notification/accessibility callbacks return before reading
   content while stopped.
4. **Broadcast states remain distinct.** `in block`, `seen in an independent
   healthy node's mempool`, and `accepted but confirmation unavailable` are not
   displayed as the same state. Ambiguous acceptance is not automatically
   retried because that could create an irreversible duplicate.
5. **Queued work is account-bound and visibly durable.** An entry is only signed
   with the matching saved account. Persistence failure and terminal removal
   are visible. History reconstructed from a queue retains percent and video id.
6. **One rule implementation serves automatic behavior, manual session
   broadcast, and UI verdicts.** The configured threshold and the same duration,
   short, mute, and dedup rules apply everywhere. The separate synthetic
   broadcast test remains an explicit transport test and is not a listen.
7. **Evidence provenance is literal.** “Watch page resolved” can only be true
   when the watch-page parser resolved that page. YouTube Music fallback evidence
   is useful metadata but is not renamed into watch-page evidence.
8. **Current specifications and historical notes stay separate.** README and
   TESTING describe the shipping build. PHASE documents may explain earlier
   behavior but cannot override this contract.
9. **Explicit ad evidence is a veto, never a brand-name guess.** An unlisted
   `/shorts/` identity is never scrobbled automatically or manually, regardless
   of duration, progress, the Short-clips setting, or which enrichment source
   supplied the flag. When optional browser accessibility is enabled, an exact
   visible YouTube ad control or label (for example `Sponsored`, `Skip ad`, or
   their supported localized equivalents) bound to the current `/shorts/`
   video id is the mobile analogue of the desktop connector's `.ad-showing`
   state and is also an unconditional veto. The evidence follows the track
   across Chrome session recreation. An id and label first observed together in
   the transition frame immediately after the URL changes are provisional, not
   a veto: the same id/label pair must be re-observed in the same URL generation
   or agree with an already-established active session. Channel names, brands,
   title vocabulary, crawlability, and view counts are not ad rules. A promoted
   public video for which YouTube exposes no explicit ad label remains
   indistinguishable from organic content and is handled by the user mute list.
   Through v0.8.13 this scanner ran only for a concrete `/shorts/{id}` snapshot;
   log 19 proved that an exact label on ordinary watch playback was not even
   inspected. v0.8.14 closes that inspection gap by binding exact label
   evidence to the MediaSession track instance, never to the organic watch URL.
10. **A YouTube Music podcast type is not song evidence.**
    `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` must not classify an item as `song`.
    RustedWax has no dedicated podcast payload path, so absent independent music
    provenance it remains `video`.
11. **Every YouTube scrobble has one verified canonical hyperlink.** A concrete
    11-character video id may come from the active browser URL, a matching
    playlist entry, or search followed by identity corroboration. Search-card
    evidence is not enough when channel or duration is absent: the candidate's
    watch page must complete those fields. Multiple matching ids are ambiguous.
    If no unique id is verified, automatic and manual session paths record a
    refusal and do not broadcast. A URL-less YouTube payload is never an
    accepted degradation mode.

    **YouTube Music catalog rows (2026-08-21).** "Search-card evidence is not
    enough when channel or duration is absent" is a rule about *absent fields*,
    not about which host answered. A YouTube Music songs-filtered catalog row is
    not an ordinary search card: it carries the complete artist credit, running
    time, Music presentation type and, when the release publishes one, an album
    that ordinary search does not carry. A linked multi-artist row requires the
    player's complete credit; one credited member is not enough. Missing album
    on a single is neutral, but two present disagreeing albums are a veto.

    Exactly one matching row may stand as identity when the candidate's
    `www.youtube.com` watch page cannot be fetched or its Topic-channel alias is
    independently reconciled by the same-id Music client. Duplicate catalog
    rows may reduce to one recording only when work, complete credit, normalized
    release and Music presentation type agree; an exact player duration selects
    one differently trimmed ingest. Different releases and Song/Video types
    remain ambiguous. The id is still an exact 11-character `videoId`, final
    corroboration still binds the same id/work/duration/credit, and the payload
    still carries a canonical watch URL. This completes the invariant rather
    than degrading it. See the 2026-08-21 through 2026-08-23 field reports.
12. **A finalized track is snapshot-isolated through broadcast.** Title, artist,
    album, duration, played time, start timestamp, loop/ad state, identity and
    resolver context are the ended track's immutable evidence bundle. Resolver
    and enrichment work may complete that bundle, but may not consult a later
    foreground track or replace the ended metadata unless the resolved id is
    corroborated against that same frozen bundle. A mismatch is a visible
    refusal, never a payload assembled from two tracks.
13. **Metadata refinement is not a track change.** The same title, artist and
    album remain one continuous track when duration changes only by the small
    MediaSession rounding tolerance or arrives after being absent. Progress,
    identity, loop/ad state and the original timestamp survive that refinement.
    A material duration conflict or a title/artist change remains a real ending.
14. **Song title parsing never splits inside syntax it has not understood.** A
    separator inside balanced parentheses, brackets or quotes is not an
    artist/track boundary. Explicit quoted/performance shapes and channel-aware
    orientation outrank a generic separator. If orientation is still
    ambiguous, retain the cleaned whole title and channel rather than inventing
    confident but reversed credits.
15. **Native packages are separately opt-in and package-isolated.** Browser
    packages remain accepted exactly as in v0.8.15. `com.google.android.youtube`
    and `com.google.android.apps.youtube.music` each require their own persisted
    setting, both default off. Package, source epoch and semantic track identity
    key all progress and continuation state. Stop, opt-out, listener rebuild and
    package teardown invalidate native in-flight snapshots and clear pending
    continuation/resolver candidates. No notification hint, URL, playlist, ad,
    accessibility or resolver evidence moves between browser, YouTube and
    YouTube Music packages.
16. **Native origin is not video identity.** An enabled native package proves
    YouTube origin only. A broadcast still requires one exact 11-character id
    from MediaMetadata media id, canonical YouTube media URI or canonical
    YouTube artwork URI, or exactly one existing-resolver candidate
    corroborated by the immutable native title, artist/channel and duration.
    Every accepted route produces a canonical `youtube.com/watch?v=` link.
    Invalid, contradictory, unresolved and ambiguous identities stay
    off-chain. Runtime History access remains forbidden.
17. **Native metadata and advertisement evidence stay literal.** Supplied
    native title, artist and album remain separated and are not unnecessarily
    replaced by browser-shaped parsing or fetched presentation. Native YouTube
    Music origin is strong music context below explicit podcast/episode,
    structured non-music genre and hard format evidence; native YouTube uses the
    existing classifier. Browser accessibility coverage and visible-ad evidence
    never apply to native packages. A native ad veto requires a proven generic
    literal structured MediaSession signal. Until physical evidence establishes
    one, no title/brand/id/channel/duration/popularity/History heuristic is
    allowed and the default-off UI must disclose the limitation.

## Historical v0.8.7 reconciliation plan

This completed order is retained to explain the v0.8.7 reconciliation. It is
not the active v0.8.11 contract, which appears in its own section below:

1. Remove the high-progress short rejection, expose an inferred loop diagnostic,
   and retain the existing one-transaction cap for videos.
2. Change session disappearance into a continuation window. Consume the pending
   fragment when a matching replacement appears; otherwise finalize once when
   the window expires. Stop cancels pending continuations.
3. Make Stop clear URL evidence and guard notification-removal callbacks before
   reading extras.
4. Give broadcast results explicit block, mempool, and accepted-unconfirmed
   states. Poll every eligible independent node and select the strongest
   response instead of returning the first response.
5. Bind queue retries to the saved username, retain percent/video id, surface
   persistence errors, and record terminal queue failures.
6. Route the Now verdict and manual session button through the configured
   threshold and the same `ScrobbleRules` decision.
7. Store explicit watch-page provenance in `VideoFacts` and invalidate legacy
   cache entries that cannot provide it.
8. Correct disclosures and status wording, add regression tests for every pure
   rule introduced above, then run uncached unit tests, APK assembly, and lint.

## Reconciled implementation: v0.8.7

The working v0.8.7 implementation applies the plan as follows:

| Contract area | v0.8.7 implementation |
| --- | --- |
| Looping video | High progress no longer rejects a Short. `Decision.probableLoop` starts above 125%; the engine logs it and `capForKind` keeps every `/shorts/` source to one transaction regardless of payload kind. This is an inference from accumulated progress, not direct observation of a browser loop event. |
| Session recreation | Session disappearance stores aggregate progress and schedules a token-checked expiry. A matching replacement consumes it before any finalize; expiry finalizes once if no replacement appears. Metadata change and STOPPED remain immediate real endings. |
| Stop | User Stop cancels in-flight continuations, clears `NotificationHints`, `UrlEvidence` including playlist evidence, and carried progress. Posted and removed notification callbacks both return before reading content while stopped. |
| Broadcast evidence | `Success` requires independent block or mempool evidence and records which. `AcceptedUnconfirmed` represents acceptance with no independent answer. Confirmation excludes the accepting node, checks every other current node, and selects block over mempool over unknown regardless of node order. |
| Queue | Entries persist username, serialized payload, label, percent, and video id. Retry requires a matching current username. Writes use an atomic replace where supported; corruption is preserved and storage/terminal failures are logged and added to History when a track record is available. |
| Shared rules | Automatic finalization and manual session broadcast call the same `ScrobbleRules.decide` inputs. The Now card displays the configured threshold. The Account tab synthetic operation remains a transport-only test. |
| Evidence provenance | `VideoFacts.watchPageResolved` is set only by `WatchPageParser`; a YouTube Music-only result leaves it false. Legacy cache entries without the field are treated as misses and refreshed. |
| UI truthfulness | Notification Access disclosure names the media-notification fields read. Confirmation wording distinguishes block, mempool, and unavailable confirmation. Compose observes probe errors rather than reading an unobserved `StateFlow.value`. |

Known limits deliberately left explicit:

- A replacement is matched by browser package plus title/artist/album/duration
  metadata because Android does not put the YouTube video id in MediaSession.
  A genuine identical replay inside the one-minute continuation window is
  indistinguishable from browser churn; after the window it starts fresh.
- History and Not logged retain their newest rows in process memory, not a
  permanent local database.
- Dedup remains local to one device, so desktop and phone can still produce two
  operations for the same listen.
- MediaSession churn, Stop callback delivery, and node behavior require the
  physical-device checks in `TESTING.md`; pure JVM tests cannot simulate Android
  controllers or a live Hive network.

## v0.8.8 patch contract

The 2026-07-30 v0.8.7 device run exposed three narrower contradictions:

| Evidence | v0.8.7 behavior | v0.8.8 required behavior |
| --- | --- | --- |
| A 42-second `/shorts/` ad resolved as `listed=no (unlisted)` | It cleared the ordinary 30-second floor and scrobbled because `isUnlisted` was only consulted while selecting the lowered Short floor | Reject an explicitly unlisted Short before duration and progress rules |
| A two-minute timer returned `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` | Every `MUSIC_VIDEO_*` value counted as catalogue music, so the Education video became `song` | Exclude the podcast type from catalogue music evidence |
| The timer ended near 125 seconds and its replacement session restarted near zero | Only high progress on a verified Short inferred a loop; this watch-path `song` received the ≥160% second transaction | Detect the end-to-start position wrap across callbacks or session recreation, carry the signal, and cap the continuous viewing to one transaction |

The position-wrap rule is deliberately strict: the previous position must be in
the final 20% of the item, the new position in the first 20%, and the backward
jump at least half the duration. Ordinary backward seeking does not qualify.
MediaSession does not expose whether the reset was automatic or user-initiated,
so an immediate replay inside the same continuous session is intentionally
treated as one looped viewing. A later separate session remains eligible as a
new listen.

## v0.8.9 patch contract

The 2026-07-30 v0.8.8 device run exposed two failures that interact at the
continuation boundary:

| Evidence | v0.8.8 behavior | v0.8.9 required behavior |
| --- | --- | --- |
| The desktop connector rejects `document.querySelector('.ad-showing')`, while a public promoted POND'S Short was indistinguishable in MediaSession and watch-page metadata | RustedWax could only reject unlisted creatives or mute a leaked public video after the fact | With optional browser accessibility enabled, inspect visible YouTube UI text for exact ad labels, bind the signal to the current `/shorts/` id, carry it across session recreation, and veto both automatic and manual broadcast |
| A Karol G Short expired after the address bar had moved to `ysY13cbxJR4`; corroboration unlatched that new id but `latchedVideo ?: live` returned the rejected `live` value anyway | The payload used the next Short's title, artist and URL with the ended Short's timestamp, duration and progress | A video id rejected during corroboration cannot be returned on that pass, and a disappearing track freezes the last identity observed while it was active; continuation expiry never consults the later foreground URL |

The accessibility ad detector is deliberately narrow. It accepts explicit UI
labels and controls, not arbitrary occurrences of words such as “ad” in a video
title or description. It only creates a persistent track veto when the same
accessibility snapshot supplies a YouTube `/shorts/` id. Watch-page pre-rolls
keep their existing duration and identity-mismatch guards; marking the watch
URL itself as an ad would incorrectly veto the real content behind the pre-roll.

Identity freezing is also fail-closed. If a disappearing track was only proven
to be YouTube without a video id, continuation expiry keeps that site-only
identity and lets the engine's existing resolver try to recover the id. It does
not borrow a later tab's live id. When a matching replacement MediaSession
claims the continuation, a confirmed identity and explicit-ad flag travel with
the accumulated progress.

## v0.8.10 patch contract

Log 12 exposed six consecutive immutable `kind: video` entries whose profile
titles had no hyperlinks. All six payloads omitted `url`. The address-bar
watcher had only a bare `m.youtube.com` host, and the fallback then reported
zero or non-confident search results before the engine deliberately continued.

The search diagnosis was more specific than “YouTube had no ids.” Current
YouTube search pages render Shorts through `shortsLockupViewModel`: the id is
nested under `reelWatchEndpoint`, the title is plain `content`, and channel and
duration are absent from the card. The parser only understood older
`videoRenderer` nodes with `title`, `ownerText`, and `lengthText`, so it could
discard a page full of Short ids and report zero candidates.

v0.8.10 requires all of the following:

- parse legacy video results, modern ordinary-video lockups, and modern Shorts
  lockups;
- retry network fetches once and try raw-title, noise-stripped title+channel,
  and noise-stripped title search variants;
- treat hashtags, emoji, punctuation, quote style, and title diacritics as
  presentation noise while still requiring channel and duration agreement;
- complete incomplete Shorts candidates from their own watch pages and accept
  only one matching video id; ambiguous ids remain unresolved;
- stop automatic finalization before enrichment, payload construction, dedup,
  or signing when no id is verified;
- make payload construction and the central broadcaster independently reject a
  YouTube listen without a canonical watch URL, including manual and legacy
  queued paths; and
- surface the refusal in Not logged and in the quiet-address-bar warning.

This guarantees hyperlink presence, not perfect capture. YouTube can still
withhold enough identity evidence that a real viewing is not broadcast. That
loss is visible and retryable in a future design; an immutable linkless or
wrong-link transaction is not.

## v0.8.11 patch contract (implemented; automated gate passed)

The 2026-07-31 through 2026-08-01 v0.8.10 field run is recorded in log 14 and
was reconciled against the signed-in YouTube History plus both live
`skiptvads.vidz` profile sections. The transport and hyperlink boundary passed:
109 of 109 broadcast payloads received block confirmation, all 109 were visible,
all carried their matching 11-character YouTube hyperlink, all 54 `song`
payloads appeared under Music, all 55 `video` payloads appeared under Videos,
and no ad-like payload reached the profile.

The run was nevertheless not a clean behavioral pass. v0.8.11 implements the
required outcomes below with exact automated regressions. The APK was generated
and its first physical-device reconciliation was subsequently attempted; log 16
failed that release gate for the separate follow-up defects documented below:

| Evidence | v0.8.10 outcome | v0.8.11 required outcome |
| --- | --- | --- |
| `saGYMhApaH8`, “Me Porto Bonito”, finalized at 193/191 seconds | The payload used the following `3mchJ-EW9rM` La Bebe facts and id | The ended session either broadcasts its own corroborated id and facts or is visibly refused; the next track can never complete it |
| `aZaxQG3ggng`, “Crazy”, finalized at 194/192 seconds | The payload used the following `2QqyPy2itXw` Coming Home facts and id | Resolver, enrichment and payload construction remain bound to the immutable ended snapshot |
| `IW524Zl2Pus`, “The best cosplayer avengers”, finalized at 23/22 seconds | A stale `Sponsored` label from the preceding six-second stadium ad was bound two milliseconds after the URL advanced, so the legitimate Short was vetoed | A transition-frame label is provisional and cannot veto the new Short unless re-observed for that stable URL/session generation |
| `5YrJf3CpHNk`, “Cardi B - Trump”, published duration `227125` then `227124` ms | Exact-duration track keys split one continuous viewing into 48% and 53%; both fragments failed separately | A same-metadata duration refinement within 2 seconds remains one track and qualifies on accumulated progress |
| Four song titles containing `|`, quoted tracks or a dash inside parentheses | Generic separator parsing produced malformed or reversed artist/title pairs | Only top-level separators are candidates; explicit shapes and channel agreement determine orientation, otherwise parsing remains conservative |

### Implemented boundaries

1. **Finalize-to-broadcast isolation.** Capture one immutable finalized bundle,
   including the playlist/resolver context known while the track was active.
   Resolution returns structured evidence rather than a bare id. Before facts
   may replace session title/artist, the candidate id, title, channel and
   duration must be corroborated against the frozen bundle. No coroutine may
   read a later foreground URL or playlist generation on behalf of an ended
   track.
2. **Transition-safe ad evidence.** Give every observed URL change a generation.
   An ad label first seen in the same transition frame is held provisionally and
   becomes a veto only after the same id/label is re-observed in that generation
   or the active session was already established for that id. Clear provisional
   evidence on another URL change, label disappearance, Stop, or package reset.
   Persist the accepted veto with the track instance, not merely the package/id
   for thirty seconds.
3. **Semantic track continuity.** Replace exact string equality on duration with
   a same-track predicate. Equal normalized title, artist and album plus a
   missing-to-known duration or an absolute duration drift no greater than
   2,000 ms is metadata refinement. Preserve accumulated content time and all
   evidence. Larger changes are not automatically equivalent and must pass the
   existing real-track-change rules.
4. **Structure-aware song parsing.** Scan separators only at top level, add
   explicit `Artist "Track" (qualifier)` and `Artist Performs "Track" | Event`
   shapes, and use cleaned channel agreement to decide `Artist - Track` versus
   `Track - Artist`. The four log-14 payloads become regression fixtures. A
   low-confidence pair falls back to channel plus whole cleaned title.

### Regression fixtures and acceptance gates

- `saGYMhApaH8` must never produce `3mchJ-EW9rM` metadata or URL, and
  `aZaxQG3ggng` must never produce `2QqyPy2itXw` metadata or URL.
- The exact stadium-ad to `IW524Zl2Pus` ordering must reject the stadium ad and
  retain the legitimate Short's qualifying view.
- `227125 → 227124` ms with unchanged Cardi B metadata must produce one final
  snapshot with combined progress and one eligible decision.
- Parser outputs are pinned as `Ice Spice` / `Think You The Sh*t (Fart)` for
  `vG4h2KkwMDA`, `Sexyy Red` / `Get It Sexyy` for `VpXRPrwezQ8`,
  `6IX9INE` / `Gotti` for `z5WrgDzNIZ0`, and
  `6ix9ine & Nicki Minaj` / `TROLLZ` for `oNg3M9IJJlY`; no separator inside
  parentheses may split.
- The full existing unit suite, APK assembly and lint must remain green, then a
  physical-device run must again reconcile the log, YouTube History, block
  confirmations, Music, Videos, ads, loops and hyperlink targets.

This patch does not rebroadcast or repair immutable historical entries, change
the 60% threshold, change Hive confirmation semantics, decide the debatable
flashmob classification, or replace the prototype YouTube lookup route. Those
are separate product or distribution decisions.

## v0.8.11 field follow-up contract (implemented; automated gate passed)

The first generated v0.8.11 artifact was `dist/rustedwax-0.8.11.apk`, version
code 31, SHA-256
`bdf81bcc560dea1c5a430d869193d702480f15f95c19f4d211abbb320ca4e296`.
Its source gate completed 290 tests with no skips, failures or errors; debug APK
assembly and lint succeeded. `debug/rustedwax-log (16).txt` records that first
physical-device attempt on 2026-08-01. It includes the earlier log-15 export
plus the continued session, so log 16 is the complete app-log evidence for this
attempt. No independent YouTube History/profile reconciliation was supplied for
the run; block outcomes below come from the app's independent-node evidence.

### What the field attempt proved

- The address-bar watcher was not frozen. Chrome advanced from URL generation
  2 through generation 15, including a deliberate move from the original
  `RDws00k_lIQ9U` mix to `RD2u5UTPEDGAw` after Lollipop.
- Eighteen tracks finalized, eleven produced visible skip decisions, seven
  payloads were broadcast, and all seven received block confirmation. No
  duplicate transaction or ad-like payload was emitted.
- Finalized snapshot isolation failed safely: successor facts were refused for
  Soy Peor, Me Porto Bonito and DÁKITI rather than being mixed into an ended
  payload. That satisfies the no-corruption boundary but not the release
  requirement of zero app-side qualifying omissions.
- During the likely screen-off interval Chrome repeatedly removed and recreated
  its MediaSession. Progress for `2u5UTPEDGAw` was carried at 134, 172 and 177
  seconds without duplicate finalization. The export ended before the pending
  60-second continuation window produced a final engine outcome, so that
  viewing is unresolved evidence rather than a pass or failure.

### Newly confirmed defects

| Evidence | v0.8.11 field outcome | Required follow-up outcome |
| --- | --- | --- |
| Soy Peor: page `BAD BUNNY - SOY PEOR (Video Oficial)`, MediaSession `BAD BUNNY - SOY PEOR (Official Video)`, correct id `ws00k_lIQ9U` | The active latch rejected its own id; after the URL advanced, snapshot isolation visibly refused successor `saGYMhApaH8` | Localized promo wording is presentation, not a different track; retain the observed id when the normalized structures and duration corroborate |
| Me Porto Bonito: page credit without parentheses and `Video Oficial`, MediaSession credit with `(ft. …)` and `Official Video`, correct id `saGYMhApaH8` | The active latch rejected its own id; successor `jZGpkLElSu8` was later refused | Parentheses/punctuation around the same credit and localized promo wrappers do not disprove the observed id |
| DÁKITI: page title ending `(Video Oficial)`, MediaSession title adding `| EL ÚLTIMO TOUR DEL MUNDO (Official Video)`, correct id `TmKh7lAwnBI` | The active latch rejected its own id; successor `-r687V8yqKY` was later refused | A shared structural title core may corroborate an additional display/album suffix; the successor still contradicts it |
| `W Sound 05 "LA PLENA" - Beéle, Westcol, Ovy On The Drums`, id `F1_aOX0acbY` | The correct URL was block-confirmed, but the payload reversed the quoted work and trailing credits: artist `W Sound 05 "LA PLENA"`, title `Beéle, Westcol, Ovy On The Drums` | Recognize a conservatively proven `publisher/series "Track" - artist credits` shape as title `LA PLENA`, artist `Beéle, Westcol, Ovy On The Drums` |

### Implemented generic boundary

This is a validator/parser correction, not an embedded media database.
Production code must not contain a song/video id map, known-title list, artist
catalog, brand inference, or per-fixture branch. Exact field values belong only
to unit tests and incident documentation, which are not packaged as runtime
decision data.

1. **Shared active/final title corroboration.** Extract one pure matcher used by
   both the active latch and finalized candidate guard. Normalize Unicode,
   case, diacritics, punctuation and whitespace; remove only already-recognized
   promo-only wrappers; then require equal whole-token structure or conservative
   contiguous containment of a complete shorter structure of at least three
   tokens for truncation/additional display suffixes. The
   existing independent page/session duration contradiction remains in force.
   This matcher validates an id already observed from the browser; it does not
   discover or choose an id from a title.
2. **Quoted work with trailing credits.** Before the generic dash rule, parse a
   balanced top-level `prefix "quoted work" - trailing credits` only when the
   channel agrees with the prefix, the trailing side has credit structure, and
   it is not a promo/event suffix. Ambiguous inputs retain the conservative
   fallback rather than being reversed by guesswork.
3. **Exact positive and negative regressions.** The three log-16 title pairs
   and LA PLENA raw-title/channel input are under `app/src/test`, alongside negative
   adjacent-track/ad controls: Soy Peor versus Me Porto Bonito, Me Porto Bonito
   versus TQG, DÁKITI versus Gata Only, the observed ad/organic pairs, and quoted
   titles followed by event/promo text. No fixture enters `app/src/main`.
4. **Unchanged safety boundaries.** Keep URL generations, provisional explicit
   ad evidence, immutable final snapshots, mandatory canonical hyperlinks,
   threshold/floor/loop rules, kind immutability, manual/automatic parity and
   historical no-rebroadcast policy unchanged.

The shared matcher, both call-site replacements, and conservative parser branch
are implemented without runtime fixture data. The focused gate passed 54 tests;
the complete uncached gate passed 295 tests with no skips, failures or errors,
and debug assembly/lint succeeded. The corrected artifact remains version
0.8.11/version code 31 at `dist/rustedwax-0.8.11.apk`, SHA-256
`1b682f582dd17f287d69acd2b22313c227acffb13f13d266288c4e6df65639d5`.
Log 17 subsequently tested that artifact. It passed transport and the exact
log-16 corrections but failed the second field gate as recorded in TESTING §16.

## v0.8.12 field-correction contract (implemented; automated gate passed)

Log 17 is the evidence-backed boundary for the next patch. It finalized 123
tracks, emitted 67 payloads and visibly skipped 56. All 67 emitted payloads were
unique, exact and block-confirmed on two Hive nodes; all 60 song payload entries
and seven video payload entries appeared in the expected profile section with
canonical links. The remaining work is therefore limited to pre-broadcast
identity completeness, metadata parsing and one kind-classification false
positive. v0.8.12 implements that scope as version code 32. The automated and
artifact gate passed; the physical-device gate remains pending.

### 1. Evidence-ranked short-title corroboration

Unequal titles must no longer use a single global minimum-token answer. The
shared matcher must return evidence strength rather than only true/false:

- exact normalized structure remains strong;
- ordered containment of at least three complete tokens remains strong; and
- a contained one/two-token canonical work title is weak and may corroborate
  only an id already observed from the same active URL generation, with
  independently compatible duration and no strong identity contradiction.

Weak short-title evidence must never discover an id, rank a search result by
itself, replace a frozen id, or allow a following track to complete an ended
snapshot. Exact regressions cover `TapXs54Ah3E`, `at1axdFpcgI`,
`NgFx3aq52Vg`, `tdZsL8i5ASA`, and `tGLP74uofTo`. Negative controls include
`Bad Bunny` versus `Bad Bunny - Another Song`, adjacent one-word works, reordered
tokens and materially different duration. This is structural validation, not a
title-to-id catalog.

Two further presentation shapes were confirmed on 2026-08-16 and are covered by
the same rank rather than by new authority — see
[the field record](../Field-Reports/<redacted-private-path>):

- **The canonical work at the tail of a long presentation.** `dE8D6WY6tQQ` is
  titled `Bounce` on its own page and `Ladii Rose ft Dej RoseGold Bounce (Official
  Video)` in its MediaSession. The parsed-work route cannot see it, because `ft`
  there joins two *artists* and stripping the trailing credit removes the work with
  it. A short side that is an ordered **suffix** of the longer is therefore the weak
  rank, not a contradiction. Position is the whole discriminator: a short **prefix**
  is the uploader and must stay a contradiction, so `Bad Bunny` still does not
  corroborate `Bad Bunny - Another Song`.
- **One embedded channel mention, spelled two ways.** `aZUbc6fCNDk` writes
  `@yingyangtwins5139` on its page and `@YING YANG TWINS` in its MediaSession, with
  every other word identical. This is presentation and ranks as strong containment,
  but only under an anchored rule: the two titles must agree on everything before
  and after the mention, the disagreement must be one contiguous run on each side,
  that run must begin at an `@` on both, and the identical surrounding text must be
  at least three tokens. `Cover by @alice` against `Cover by @bob` shares two tokens
  and remains a contradiction. No fuzzy or similarity matching is admitted.

### 1a. A title-only rejection is recoverable

A video id rejected during corroboration still cannot be returned on that pass.
What changed on 2026-08-16 is that the *reason* is now recorded, because the two
reasons are not the same kind of evidence:

- a **duration** disagreement is structurally a different video and permanently
  vetoes that id for the track;
- a **title** disagreement may be YouTube writing one video's name two ways, and
  must not outrank an independent exact-id route that later proves the same id.

Only the playlist and watch-history routes may recover such an id — both prove it
by matching title, channel and duration against a bounded entry list the device
was actually playing from. Search may not. Recovery accepts nothing on its own:
the frozen id, both canonical and localized titles, the channel, the duration, the
owner handle and the final watch-page facts must all still pass. The measured
failure was `dE8D6WY6tQQ`, whose 138-of-137-second listen was discarded because the
Mix queue's later independent proof of that same id was vetoed by the earlier
title-only disagreement.

### 2. Role-aware channel corroboration

MediaSession artist/uploader text, a YouTube channel, and resolver credit lists
are related evidence but not interchangeable fields. Exact normalized channel
inequality alone must not veto an id already observed from the current URL when
title and duration independently corroborate it. Conversely, a channel match
alone must not establish an id or rescue a title/duration contradiction.

The exact `CJjvg7PbE4w` case must accept the resolver credit list
`Jon Z, Baby Rasta, & Boy Wonder CF` alongside ended uploader
`Boy Wonder Chosen Few` when the same observed id, title and duration agree.
Search-only candidates retain conservative channel requirements, and negative
fixtures cover same-title different-upload and adjacent-track cases.

### 3. Bounded resolver recovery without a media database

The resolver may keep a small memory-only index of identities verified during
the current monitoring run. It is a candidate cache, not a song database:

- insert only after an id has passed canonical title/channel/duration
  corroboration for that active track;
- key by normalized frozen metadata plus bounded duration, never by a fixture
  id or hardcoded title;
- cap entries and age, and clear them on Stop, package reset, app restart or
  monitoring reset;
- on reuse, re-fetch/re-corroborate the cached id against the new immutable
  snapshot; never broadcast directly from the cache; and
- preserve ambiguity refusal if multiple ids still satisfy the evidence.

This must recover the later `5r5UePOgMQU` replay from the already verified
same-run identity. For zero-result searches such as `QBq6rY0ZpKM`, generate
bounded presentation-cleaned query variants from the frozen title/artist and
apply the existing strict final candidate corroboration. External lookup that
still provides no unique evidence remains a visible safe omission. The
ambiguous “Best movie!!!” two-upload case must continue to refuse both ids.

### 4. Multi-separator and orientation-safe song credits

Top-level parsing must retain the structural work already implemented while
handling the seven exact log-17 payloads:

- parse `Artist - Track | album/display suffix` without falling back to the
  whole raw title when channel agreement and the top-level primary separator
  prove the artist/work boundary;
- do not flip conventional `Artist - Track ft. Featured Artist` merely because
  the channel matches a featured credit on the right;
- recognize `Track - explicit multi-artist credit list` only from structural
  credit-list evidence such as repeated separators/credit markers, not from a
  known title or artist; and
- collapse only an exact repeated trailing feature phrase, preserving one copy
  and preserving all non-duplicate credits.

Tests pin `saGYMhApaH8`, `GtSRKwDCaZM`, `AnKdQ5p5Ks8`, `qA6FBDYncGk`,
`lA8OhVn-o7M`, `UWV41yEiGq0`, and `34Na4j8AVgA`, plus ambiguous dash/pipe,
quoted separator, event suffix, ordinary artist-first and ordinary track-first
negatives. Production code must contain only grammar/evidence rules.

### 5. Strong movie-format evidence above uploader category

An explicit visible content-format marker such as the log-17 `#movie`/`#edit`
combination must be allowed to classify a narrative Short as `video` even when
the uploader or music-client microformat says `category=Music`, provided there
is no distributor provenance, YouTube Music video type, Topic/Art Track status,
or other hard music evidence. The exact `KoWNsyNVR28` fixture must become
`video`. Negative tests must keep genuine music videos, songs containing the
ordinary word “movie”, and hard catalogue provenance as `song`. This rule
classifies content kind; it must not be reused as advertisement guessing.

### 6. Unchanged safety and release boundary

v0.8.12 must preserve every v0.8.11 invariant: immutable ended snapshots,
URL-generation ad evidence using only explicit visible YouTube labels,
same-track duration refinement, mandatory canonical hyperlinks, thresholds and
floors, loop/dedup caps, kind immutability after decision, manual/automatic
parity, honest Hive status, and no historical rewrite/rebroadcast.

The automated patch is complete: the exact unit regressions and full existing
suite total 314 tests with no skips, failures or errors, and debug APK assembly
and lint pass. `dist/rustedwax-0.8.12.apk` has SHA-256
`3390df660053ca9c2c0c7665d320e67e820ce09513d4f025a172a018aa0080b3`.
A new physical-device round must
exercise every §13e fixture plus the log-17 short-title chain, Nunca Me Amó,
WHEN SINCE, the later +57 replay, all seven metadata cases and
`KoWNsyNVR28`. It must reconcile the exported log, signed-in History, two Hive
nodes, both profile sections, ads, loops and hyperlinks by id. A safe ambiguous
external lookup may remain omitted only with its exact visible refusal; an
app-side race or over-strict contradiction may not omit a qualifying known-id
fixture.

## v0.8.13 log-18 targeted-correction contract

Log 18 is the evidence boundary for this patch. Its 6,309-line export contains
129 finalizations, 73 block-confirmed logged payloads and 55 visible skip
decisions. All four configured Hive nodes returned the exact 73 logged
transactions and payloads; the same run's Amarillo operation completed after
the export ended, for 74 total operations (55 songs, 19 videos, 73 unique ids).
All operations appeared in the app-declared live profile section and all 73
unique ids existed in signed-in History. No successor-mixed payload, loop
duplicate, ad-like payload, transport failure or non-canonical link reached
Hive.

That clean delivery boundary does not make the field gate a pass. MONTERO was
permanently emitted as artist `Your Name` because ordinary title text inside
parentheses was mistaken for a `by Artist` credit. Te Bote was permanently
emitted as `video` because generic `movie` in channel `Flow La Movie` outranked
an id-bound YouTube Music OMV result. Three qualifying, uniquely identifiable
viewings failed closed on channel presentation/collaborator bylines; a fourth,
Classy 101, correctly remained off-chain because two uploads were
indistinguishable. TESTING §18 is the canonical count, transaction, omission,
coverage and reconciliation record. Historical Hive entries are never
rewritten or rebroadcast.

The v0.8.13 correction is limited to the following behavior.

### 1. Literal second-artist grammar

The track-first parenthetical form accepts only:

- `Track (by Artist)`; and
- `Track (performed by Artist)`.

No arbitrary prefix is allowed before `by`. Parentheses such as
`(Call Me By Your Name)` and `(inspired by a true story)` remain work/title
text and continue through ordinary top-level separator parsing. Existing
quoted, performed, multi-separator and duplicate-feature rules are unchanged.

### 2. Hard provenance before generic channel vocabulary

The classification ladder keeps explicit content-format vetoes—tutorial,
reaction, trailer, news and episode structures—above YouTube Music and
MusicBrainz. Generic negative words in a channel name move below those two hard
provenance sources but remain above the narrative movie/edit marker and bare
uploader category. Consequently:

- an id-bound YouTube Music OMV or MusicBrainz-confirmed recording owned by a
  channel containing `movie` remains `song`;
- a generic movie/film/cinema channel with only `category=Music` remains
  `video`; and
- a reaction, trailer, tutorial, news item or episode is not rescued merely by
  incidental catalogue/audio matching.

### 3. Bounded collaborative search bylines

Search-only identity still requires exact normalized title, channel evidence,
duration within the existing five-second tolerance and exactly one matching
id across the bounded complete candidate set. Two presentation refinements are
permitted:

1. recognized YouTube owner suffixes may be stripped repeatedly, without ever
   erasing a channel that consists solely of the marker; and
2. the leading owner in `Owner and Collaborator` or `Owner and N more` may
   satisfy channel evidence only when the parsed search card actually carries
   YouTube's collaborator-dialog command.

An arbitrary channel containing `and`, a wrong title, a material duration
conflict, a missing collaborator marker or more than one surviving upload
remains a contradiction/refusal. The app does not read or search signed-in
History at runtime. No field id/title/artist/channel map is permitted in
production source.

### 4. Release and immutability boundary

v0.8.13 uses version code 33. It must keep every prior snapshot, URL-generation
ad, mandatory canonical-link, threshold/floor, loop/dedup, kind immutability,
manual/automatic parity, honest Hive-state, cache-lifecycle and account-bound
queue invariant. A device retest must include MONTERO, Te Bote, the three
unique resolver misses, Classy 101 ambiguity, the complete §§13e/17d matrix and
an explicit visible-ad transition. Exact transaction comparison must again use
all configured Hive nodes and both live profile sections.

The implementation passed the full uncached gate: 320 tests, 0 skipped,
failures or errors; debug assembly succeeded; lint completed with 0 errors and
23 warnings. `dist/rustedwax-0.8.13.apk` has SHA-256
`ddfeb3e51cfe60fcf8fa2f13c05891989215154da948686650ae720e4ca9e026`.
This was source/artifact approval only. Log 19 later failed the v0.8.13
physical-device gate as recorded below.

## v0.8.14 log-19 correction contract

Log 19 is the evidence boundary for the next patch. Its 5,261 lines span
2026-08-02 11:48:33–19:44:40 local time and contain 114 finalizations, 53
broadcast payloads and 61 visible skip decisions. Fifty-two transactions were
confirmed directly and one offline-queued transaction later reached a block.
All four configured Hive nodes returned the exact 53 payload/transaction pairs;
the live profile contains all 51 `song` and two `video` entries. The skip ledger
is exact: 42 unverified hyperlinks, 14 below-threshold plays, three ordinary
duration-floor refusals and two listen deduplications. The Stop boundary, one
position-wrap loop cap, canonical-link gate, durable queue, successor refusal
and block-state reporting behaved correctly.

That transport result does not make the field gate a pass. Exact signed-in
History searches returned no result for Namecheap `zUaMtSMZDgg` or KaoJapan
`azTP61YoD2s`, yet both are permanently present in Videos and on Hive. No
`[ad]` evidence line exists. v0.8.13 calls the bounded accessibility-label scan
only after `videoIdInSameShortSnapshot` returns a concrete `/shorts/{id}`;
ordinary watch ads are never scanned. The resolver then treated both public
uploads as ordinary videos. Namecheap met the 30-second floor exactly and was
99% played; KaoJapan was 34 seconds and 100% played. Unique title/channel/
duration resolution and canonical links made both eligible under the current
rules. This is a coverage defect in literal ad evidence, not authority to add
brand/title ad guessing.

Four additional immutable payloads define the parser boundary:

| Id | v0.8.13 payload defect | Required generic outcome |
| --- | --- | --- |
| `TQNW0_RRicI` | title `Strictly High Grade [Official Video` | paired `[Official Video 2024]` is removed as a promo-plus-year group; artist `Marlon Asher`, title `Strictly High Grade` |
| `HtJS32n6LNQ` | artist `TVXQ! 동방신기 '주문`; title `MIROTIC' MV` | a structurally paired single-quoted work is not split internally; artist `TVXQ! 동방신기`, title `주문 - MIROTIC` |
| `ixkoVwKQaJg` | artist `Taki Taki ft. Selena Gomez, Ozuna, Cardi B`; title `DJ Snake` | conventional artist-first form remains artist `DJ Snake`, title `Taki Taki ft. Selena Gomez, Ozuna, Cardi B` |
| `BVYpT8LsjtA` | artist `BENNETT`; title retained `BENNETT - Mamma Mia (feat. Mentissa) - Techno Mix` | channel-proven primary boundary yields artist `BENNETT`, title `Mamma Mia (feat. Mentissa) - Techno Mix` |

`5GYeWpjq54Y` is a negative incident control: its mismatched `[Loving You Is in
My DNA)` delimiters already exist in the raw YouTube title and must not be
silently “repaired” by guessing. Existing conservative outputs without enough
structural evidence also remain conservative.

### 1. Exact watch-session ad evidence

The accessibility walker may scan for the existing exact/localized YouTube ad
labels whenever the visible browser host is YouTube, including a bare host or
ordinary `/watch` page. A non-Short signal must not be assigned to the address
bar video id: during a pre-roll that id names the organic content behind the
advertisement. Instead, the signal is offered to the active Chrome
MediaSession observation and may become a veto only when it is bound to one
unambiguous track-instance token/signature.

The first label observation for a new track instance is provisional unless the
same instance is already established. The same signal/instance must be
re-observed before acceptance otherwise. A metadata change, conflicting active
session, package/reset boundary, Stop, or expiry clears provisional evidence.
Once accepted, the ad flag follows only that track through Chrome session churn
and final snapshot construction. When the organic content resumes under the
same watch URL, it receives a distinct track instance and remains eligible with
its own carried progress. Automatic finalization, manual Broadcast and the Now
verdict continue to consume the same immutable ad flag and central rule.

History is a field-test oracle only. Runtime code must not read/scrape signed-in
History and must not infer ads from ids, brands, titles, channels, duration,
playback speed, public/listed state, category, crawlability or view counts. If
YouTube exposes no exact accessibility label, the app must continue to say that
the ad is unproven rather than invent evidence.

### 2. Paired delimiter and quoted-work fidelity

Bracket cleanup must preserve opener/closer pairing. A trailing four-digit year
may be ignored inside an otherwise promo-only bracket only when the remaining
tokens contain the existing strong promo marker; `(Summer 2024)`, `[Song 2024]`,
standalone years and mismatched pairs remain content. Single straight/curly
quotes may establish a work boundary only in a balanced structural title form.
Apostrophes in `Gangsta's Paradise`, `Don't Start Now`, names, possessives,
unmatched quotes and quoted event/promo suffixes must not become delimiters or
artist boundaries.

### 3. Orientation and channel-proven version suffixes

Recognized owner-suffix cleanup may compare an exact collapsed owner key so
`DJSnakeVEVO` corroborates `DJ Snake`; partial token overlap and arbitrary
substring containment remain insufficient. That strong left-owner evidence
must outrank the generic track-first multi-artist branch for a conventional
`Artist - Track ft. Featured, Artists` form. Existing explicit track-first
credit-list fixtures remain reversed only when their current structural
requirements are met.

For a title with more than one top-level dash, an exact channel-proven leading
owner may establish only the first artist/work boundary when the trailing
segment is a bounded, explicitly version-shaped suffix such as `Techno Mix`.
The suffix remains part of the work title. Unrelated publishers, event names,
promo slogans, arbitrary three-part titles, missing channel evidence and
conflicting owners retain the conservative whole-title fallback.

### 4. Lifecycle, release and immutability boundary

The patch is implemented as v0.8.14/version code 34. It does not change URL
generation semantics for Shorts, finalized snapshot isolation, threshold/
duration floors, loop/dedup caps, mandatory canonical hyperlinks, kind
immutability, resolver uniqueness, manual/automatic parity, honest Hive states,
account-bound queue behavior, cache lifecycle, or any historical operation.
Exact log-19 ids/titles/channels may appear only in tests and documentation;
production code remains generic.

Implementation was test-first: pure track-bound ad-evidence state and transition
regressions were added before accessibility observations were wired through the
existing probe/final snapshot path; the four parser regressions plus negative
controls then passed with the focused suites. The version/code is now
0.8.14/34 and this documentation describes only behavior actually present.
The complete uncached `testDebugUnitTest assembleDebug lintDebug --rerun-tasks`
gate passed: 338 tests, 0 skipped, 0 failures, 0 errors; debug assembly
succeeded; lint completed with 0 errors and 23 warnings. The tested APK is
`dist/rustedwax-0.8.14.apk`, SHA-256
`a9507c733b188f9cf3a481c8b1446535da22449343181cc17ccf556890f298b8`.
This is source/artifact approval only. The complete physical gate must replay
the log-19 watch-ad cases and the still-missing §§13e/17d/19 fixture matrix.

## v0.8.15 log-20 correction contract (implemented)

> **Historical contract, superseded for identity eligibility by v0.11.0g.** Track-bound coverage and
> literal ad capture remain implemented diagnostic/evidence mechanisms. The requirement below that a
> resolver-only browser track must have a successful scan was removed: current authority is one
> frozen exact id or one unique finalized lookup, independent of coverage. See
> [Documentation/Product/IDENTITY.md](IDENTITY.md#current-identity-contract).

Log 20 is the evidence boundary for the next patch. Its 10,945 lines span
2026-08-02 21:14:46 through 2026-08-03 10:52:51 local time. There are 227
completed finalizations: 107 automatic broadcasts and 120 visible skips. One
intentional fixed test broadcast makes 108 Hive operations total, comprising
80 `song` and 28 `video` payloads. The automatic path reported 105 direct
blocks and two durable offline-queue writes; both queued payloads later reached
blocks. Four configured Hive nodes returned identical normalized rows and the
same 108 payload/transaction pairs. The live profile grew by exactly 80 Music
and 28 Videos entries. All 107 automatic YouTube operations carry canonical
exact watch URLs and distinct video ids.

The skip ledger is also exact: 89 tracks with accepted visible ad evidence, 15
below-threshold plays, six no-duration/zero-play cases, two duration-floor
refusals, and eight final identity-corroboration refusals. Fourteen continuous
viewings were capped to one scrobble, 88 finalizations exercised playback up to
2×, six same-track session restarts carried progress, and both offline queue
items preserved their original timestamps. No crash, fatal error, URL-less
broadcast or successor-mixed payload was found.

That accounting does not make the fifth field gate a pass. Namecheap
`zUaMtSMZDgg` appeared four times. Three instances received literal `Sponsored`
or `Visit Advertiser` evidence and were vetoed. The instance beginning at
01:06:09 received no accessibility observation, played 30/30 seconds, resolved
uniquely as a public/listed video and became transaction
`8c22a93cd7d581687055240d279d8713abfcdfc9`. The last URL/ad observation before
the affected interval was at 00:58:56, generation 76; the next was at 01:35:03,
generation 77. The watcher continued to report connected and emitted no
disconnect/reconnect state while both URL and ad observations were silent for
about 36 minutes. Other advert MediaSessions in that interval stayed off-chain
only because identity, duration or progress failed. This cannot prove whether
Android stopped delivering events or Chrome stopped exposing the tree. It does
prove that `watcherConnected` is not evidence that the current track was
actually inspected.

Exact signed-in History search returned no Namecheap result. This transaction
is a new immutable leak, not a retry of log 19's Namecheap transaction
`c94c4d17c8572018ed1c000e0b78ea405ab2cbec`. History contained 103 of the 107
automatic ids; the other three absent ids were Metallica songs played through
Brave rather than the signed-in Chrome profile, so no other ad-like broadcast
was identified. History remains an external field-test oracle only.

Three additional field defects define the remaining correction scope:

| Id | v0.8.14 outcome | Required generic outcome |
| --- | --- | --- |
| `JmeUtPih4U8` | CENTRAL CEE — BOOGA played 110/110 seconds and uniquely resolved as Music/OMV, then was refused because candidate channel `Central Cee and LIVE YOURS` contradicted ended channel `Central Cee` | Accept only a unique exact-title/duration hard-music candidate whose parsed/ended owner exactly matches the leading segment of an explicitly separated collaborative candidate byline; all weaker bylines remain contradictions |
| `DGs9TJmazB0` | artist `SIP ft. Tyga, Nicki Minaj, Blueface (RapKing Music Video)`; title `6IX9INE` | conventional artist-first form remains artist `6IX9INE`, title `SIP ft. Tyga, Nicki Minaj, Blueface (RapKing Music Video)` |
| `z7DbZS6l6Vk` | artist `Bad Habits Feat. Tion Wayne & Central Cee (Fumez The Engineer Remix)`; title `Ed Sheeran` | conventional artist-first form remains artist `Ed Sheeran`, title `Bad Habits Feat. Tion Wayne & Central Cee (Fumez The Engineer Remix)` |

The required physical matrix was again incomplete. The four v0.8.14 parser
fixtures, the six absent v0.8.13 fixtures and the broader §§13e/17d
transition/short-title/cache/manual matrix do not occur. Stop/reset with a
populated cache, mute, dedup replay and real YouTube manual/automatic parity
were not exercised. The export ends with a Chrome session waiting inside its
final 60-second continuation window, so the final item has no recorded decision.

### 1. Track-bound accessibility coverage and watcher freshness

An enabled or connected accessibility service is not proof that a particular
track's visible YouTube tree was inspected. v0.8.15 represents a
successful YouTube-root scan as a separate, immutable coverage fact. Each fact
is bound by package and the same unique MediaSession track instance/signature
used by ordinary watch ad evidence. A scan may report either an exact ad signal
or no ad signal; “covered with no label” is evidence of inspection, not a claim
that the content is organic.

`UrlWatcherService` must use one bounded observation routine for both ordinary
accessibility callbacks and a bounded periodic refresh while monitoring is on.
The refresh inspects only a visible target-browser root and applies the existing
host, node-budget, recycling and exact-label rules. It must not synthesize an
event, scan another package, keep the display awake or interpret a null/inactive
root as a clean observation. While a target MediaSession continues, a prolonged
lack of successful target-root scans must be logged once as an evidence outage
and retried without claiming the service disconnected.

When Browser evidence access is enabled for the run, a finalized track whose id
is recovered only from playlist/search/watch-page/cache evidence may broadcast
automatically or manually only if at least one successful YouTube-root scan was
bound to that track during its active lifetime. Without that coverage it must
fail closed with an explicit reason such as “Browser evidence was unavailable
for this track; a visible YouTube ad could not be excluded.” It must not be
labelled an advertisement. A current-generation exact URL observation already
comes from the same successful root scan and therefore supplies coverage; this
rule targets resolver-only tracks such as the leaking Namecheap instance.

Coverage may carry across genuine same-track session recreation, but never to a
different metadata signature, successor, URL generation or package. It clears
on Stop, reset, watcher disconnect/destroy, package teardown and expiry. A scan
from before a track began cannot cover it. An ambiguous set of active sessions
cannot receive track-bound clean or ad evidence. Now, manual Broadcast and
automatic finalization must consume the same frozen coverage/ad facts and the
same central rule. If Browser evidence access is disabled, the existing
notification/lookup fallback behavior remains unchanged and the UI continues
to disclose that visible-ad protection is unavailable.

The patch must not infer advertisement status from id, brand, title, channel,
duration, playback speed, public/listed state, category, crawlability, view
counts or History. The safety action is an honest evidence-unavailable refusal,
not heuristic classification.

### 2. Featured-title orientation without losing track-first credits

A conventional top-level `Left - Right` form must not reverse solely because
`Right` contains `ft.`/`feat.` followed by multiple people. A right-hand work
prefix before the feature marker is compatible with ordinary
`Artist - Work ft. Featured, Artists` grammar, even when the uploader is an
unrelated publisher or one of the featured artists. Exact collapsed-owner proof
remains strong corroboration but is no longer required merely to preserve this
conventional orientation.

Track-first reversal remains allowed only with positive work/credit structure:
a genuinely bare trailing co-artist list, or a strongly work-shaped left side
such as the existing remix/version fixtures followed by an explicit credit
list. The log-17 `qA6FBDYncGk`, `lA8OhVn-o7M` and `UWV41yEiGq0` outputs, the
Anuel featured-channel control, Taki Taki and ordinary artist-first forms must
remain correct. The current unrelated-channel Taki Taki test must be revised:
unrelated uploader evidence is no longer authority to invert an otherwise
conventional featured title. Exact field values stay in tests/documentation;
production code contains only grammar.

### 3. Strongly corroborated collaborative search bylines

The resolver may treat `Owner and Collaborator` candidate presentation as
compatible with ended channel `Owner` only when all of the following are true:

1. the candidate is unique under the unchanged search/playlist ambiguity gate;
2. cleaned title and duration satisfy the existing strongest corroboration;
3. enrichment provides hard music provenance such as a YouTube Music OMV;
4. the parsed artist/ended channel exactly equals the complete leading byline
   segment after ordinary owner-suffix normalization; and
5. the candidate adds one or more complete collaborator segments using an
   explicit supported separator, never substring/prefix containment.

A generic channel containing `and`, a fan/publisher suffix, a partial owner,
wrong title, material duration conflict, missing hard music provenance, more
than one candidate or any competing id remains a refusal. This is candidate
presentation compatibility, not permission to read signed-in History or weaken
unique-id resolution. The existing Classy 101 ambiguity and every v0.8.13
negative remain unchanged.

### 4. Release and immutability boundary

The implemented patch is limited to the three sections above. It preserves
Short URL-generation ad evidence, finalized snapshot isolation, threshold and
duration floors, loop/dedup caps, canonical hyperlinks, kind immutability,
manual/automatic parity, honest Hive states, durable account-bound queue
behavior, verified-candidate cache lifecycle and every prior parser/resolver
regression. No historical operation may be repaired, rewritten or rebroadcast.

Implementation started with focused failing regressions, then added the pure
coverage/freshness state, watcher refresh wiring, central fail-closed decision,
parser orientation correction and collaborative-byline rule. The 176 focused
evidence/probe/carry/rules/manual/parser/resolver tests pass with no skips,
failures or errors, so the version is now v0.8.15/code 35 and this contract
describes behavior present in source. The complete uncached
`testDebugUnitTest assembleDebug lintDebug --rerun-tasks` gate also passed: 349
tests, 0 skipped, 0 failures, 0 errors; debug assembly succeeded; lint completed
with 0 errors and 23 warnings. The tested APK is
`dist/rustedwax-0.8.15.apk`, SHA-256
`242d1b76d473754494dec74e035a7731ee1311c4920458926c5dd769e7ee365c`.
The original full TESTING §21 physical matrix remains the strict historical
benchmark. The accepted practical field outcome and its exceptions are recorded
in TESTING §22.

### 5. Log-21 field acceptance and remaining boundary

The surviving v0.8.15 field export contains 228 final decisions: 98 unique
block-confirmed broadcasts and 130 skips. All 98 transactions reconcile to the
signed-in profile, including 57 songs and 41 videos. Two naturally served
Namecheap ads were rejected using exact current-track accessibility labels, and
no new ad payload was found. This physically validates the generic periodic
coverage path against the failure shape that produced the log-20 Namecheap
operation; it does not authorize brand, title, channel or History rules.

The safety bias remains conservative. Four complete organic songs failed exact
id verification, and one complete organic track was vetoed when `Visit
Advertiser` evidence from the preceding promoted music session remained bound
across the immediate metadata transition. These are false omissions, not
on-chain false positives. A successfully scanned label-free promotion remains
indistinguishable from ordinary content under the allowed evidence, and a
transition may still suppress its organic successor. v0.8.15 intentionally
prefers those losses to broadcasting an uncertain advertisement.

Log 21 exercised Chrome rather than Brave and did not physically replay every
SIP, Bad Habits, Taki Taki, BOOGA, Stop/reset, mute, dedup and historical
transition fixture. The automated suite is the evidence for those generic
rules. Phase 4 acceptance therefore means practical release acceptance with the
TESTING §22 exceptions, not a claim that the strict §21f zero-omission matrix
was completed. Native YouTube/YouTube Music app support is a separate v0.9
contract and must not weaken these browser invariants.

## v0.9.0 native YouTube apps contract (implemented; device gate pending)

v0.9.0/version code 36 adds two sources without changing the v0.8.15 browser
path. Implementation is present in source and covered by focused regressions;
physical approval remains pending under `<redacted-private-provenance>`. The later
native Shorts field run failed on stale exact-ID-less MediaSession continuation
and is evidence for a future patch, not part of this as-built contract; see
`<redacted-private-path>`.

### 1. Admission and state isolation

The target-package decision is exact. Brave and Chrome variants remain
unconditional target packages. Native YouTube and YouTube Music are admitted
only by their own setting. Those preferences persist independently and default
false.

Native snapshots carry a package-specific epoch. Opt-out, Stop/reset and
listener disconnect/rebuild advance the epoch so already-running async work
cannot sign after the boundary. Controller removal may retain same-track
progress only through the existing bounded continuation. Progress keys include
the package and semantic metadata; a concrete case-sensitive native video id
is additional contradiction evidence. A different id cannot claim a same-title
continuation. Package teardown clears pending carry and the verified-candidate
cache for that package.

The native identity path does not consume notification-hint binding,
`UrlEvidence`, playlist/URL generation, `AdEvidence`, `MediaSessionAdEvidence`
or `MediaSessionAccessibilityEvidence`. Native sessions therefore cannot
acquire browser URL/ad/coverage state. Enabling native sessions does not reduce
the browser sole-session evidence test; only browser watches participate in
that count.

### 2. Exact identity and resolver fallback

Exact native routes are ordered media id, media URI, then artwork URI. URI
parsing uses exact YouTube/ytimg hosts and exact 11-character ids; it rejects
suffix-confusion hosts and arbitrary embedded strings. All success routes emit
the canonical watch URL and diagnostics name the exact route. A Shorts media
URI retains Short-path proof.

Package-only identity is site-only. With lookup disabled it cannot broadcast.
With lookup enabled, the existing resolver may run only from a frozen nonblank
title and channel plus duration and must leave one unique matching id after its
complete candidate/ambiguity checks. Final page title and duration
contradictions still refuse the result. An exact structured native id can retain
a clean one/two-word native title when the fetched page is a longer presentation
and duration corroborates; it does not permit a wrong title or duration.

Payload construction and the central broadcast policy continue to require the
canonical URL, providing the same defense in depth for automatic, manual and
queued entry paths. No production fixture catalogue or History lookup is added.

### 3. Metadata, classification and rules

Native title/artist/album values take precedence when present. MusicBrainz may
verify them but does not replace supplied clean fields; fetched page metadata is
fallback for missing fields and corroboration/classification evidence.

Native YouTube Music context is evaluated after literal podcast type, structured
non-music genre and hard title-format rules. Native YouTube receives no package
music shortcut. Both sources keep the v0.8.15 threshold, 10/30-second floor
proof, playback-speed accumulator, loop/double-listen cap, kind decision,
dedup, mute and manual/automatic rule implementations.

### 4. Native ad and instrumentation boundary

No native ad detector is claimed in this source. The package, title, artist,
album, id, URI, artwork, duration, resolver and classifier are not ad evidence.
The settings and Now card disclose that browser visible-ad protection does not
cover native apps.

For physical measurement, every standard MediaMetadata text/numeric/bitmap
presence field and non-standard key is logged. Native PlaybackState logs numeric
state, position, buffered position, speed, action bitmask, error message, update
time, queue id, active flag, custom actions and extras. These are observations,
not heuristics. A future veto requires generic literal structured evidence from
that record and a new contract/test change.

No manifest permission was added; Notification Access supplies active
MediaSession access. The exact device-pending checklist and release risk are in
`<redacted-private-provenance>` and `TESTING.md` §23.

The complete uncached source gate passed 372 tests, 0 skipped, 0 failures and
0 errors; debug assembly succeeded and lint completed with 0 errors and 23
warnings. The tested source APK and `dist/rustedwax-0.9.0.apk` both have SHA-256
`3f8945e997d592dbf40fac6aa727f69215cbf8a805b5a4b15df031171a0ec58c`.

## v0.9.1 native foreground Shorts contract (implemented; bounded gate passed)

Foreground native Shorts are a distinct source proof, never a refinement of
the stale YouTube MediaSession. Admission requires the exact YouTube package,
one visible measured Shorts root/player, one non-control title, one exact
normalized owner handle, one valid current/total seekbar and no conflicting
structure. Identity-bearing fields must remain unchanged for 750 ms before a
new organic or ad session exists; current position is excluded from that key.

Only accepted seekbar deltas earn play time. Unchanged values, ordinary rewind,
material forward seek, pause/interaction proof loss, torn frames, PiP and
background add nothing. Samsung's unchanged cached accessibility values do not
move the elapsed bound for the next position delta. Exact scroll/seek events
freeze and reset the baseline. A strict near-end/near-start wrap may add only
traversed seconds and sets the existing loop flag/cap.

Complete proof suppresses the stale native YouTube MediaSession. Missing proof
freezes immediately; a separate freshness watchdog finalizes after the bounded
three-second no-credit grace and releases MediaSession at a fresh zero baseline.
Stop, native opt-out, observer disconnect, listener rebuild and source-epoch
change discard the in-flight foreground observation rather than sign it.

A literal supported ad label inside the proven player is the only native Short
ad evidence. It isolates organic → ad → organic state and reaches the existing
central ad veto shared by manual and automatic paths. Brand, title, handle,
CTA, duration, id, popularity and History are never ad evidence.

Exact-id recovery without browser URL proof requires exact normalized title,
duration within the existing five-second limit, exact canonical owner handle
from `ownerProfileUrl`, and exactly one fully fetched public watch-page
candidate. Missing, contradictory, legacy-cache or ambiguous handle evidence
refuses every id. The recovered canonical id then uses the same threshold,
Short floor, payload, mute, dedup, queue and signing rules as every source.

The implementation gate passed 409 tests, assembly and lint. The A12 proved
progress/seek/pause/wrap/PiP/transitions, two natural literal ads, lifecycle
invalidation and browser/YouTube Music regressions. On the authorized test
account, four exact foreground-Short payloads were block-confirmed and
independently reconciled; lookup-off, unresolved and genuine two-upload
ambiguity cases remained off-chain. The bounded Shorts gate passed. Native
sources remain experimental/default-off while broader v0.9 native-app evidence
is still pending. Artifact SHA-256:
`c6f2f1800a1cc6b6d76c260181d2402a3d648c9ecf7b3bc94ad897eeb1ce0895`.

## v0.9.2 native simplified music metadata contract (implemented; field continuation pending)

The ordinary native YouTube packages may publish a clean separated song title,
artist and duration while omitting every exact video-id route. That source may
use structured recovery only after the global raw title/channel resolver fails.
Every bounded search candidate must be completed from its public watch page;
the existing title grammar must reduce the canonical presentation title to the
exact native work, the exact native artist must be one complete structural
credit, duration must agree within five seconds, and exactly one distinct upload
may satisfy all fields. Final frozen facts repeat the same predicate. Browser,
foreground owner-handle and exact-id paths do not inherit this relaxation, and
structured recoveries do not seed the raw title/channel candidate cache.

An exact-ID-less native STOPPED callback waits at most ten seconds for the
measured metadata replacement shape. If normalized title/artist/album remain
the same but duration becomes materially contradictory, the earlier fragment
is discarded and the replacement starts at zero. No progress carries, no ad is
inferred, and no fragment payload is created. A different title finalizes the
old item immediately; an exact id and browser duration refinement retain their
prior behavior. Monitoring Stop, listener teardown and source-epoch boundaries
invalidate the pending timer before it can sign.

The source gate passed 416 tests, assembly and lint (0 errors/23 warnings).
Version 0.9.2/code 38 and its byte-identical artifact were installed on the A12
with SHA-256
`5cf7fbfdd950376c8b61ede0a0effc7f843f25b249f47c06172471f18559d072`.
Post-install A12 logs physically confirmed the transition guard: the same
`Hey DJ` metadata changed 218→20→207 seconds within the grace and both
superseded fragments were discarded with zero carry and no ad inference. The
clean 207-second phase then completed; structured proof matched two distinct
uploads (`YN-aYhtMHIw`, `1fb9DtJpbHw`), so RustedWax refused the ambiguity and
built no payload. That physically confirms the structured refusal branch. A
unique-match write and reconciliation remain pending; the four pre-patch misses
remain off-chain.

## v0.9.3 artist-aware budget and immutable native continuation contract

Structured native page budgeting counts only search cards that already prove
the exact parsed work and one complete exact artist credit, with compatible
duration where supplied. The eight-page bound, fully fetched final predicate,
uniqueness requirement and genuine ambiguity refusal remain unchanged.

A stable exact-ID-less ordinary native track may resolve while playing. A
unique fully corroborated raw or structured result is memory-only controller-
carry authority, not a MediaSession-published id. A vanished controller may
defer progress only with that authority; a replacement starts at zero and may
claim once only after independently resolving the same immutable id with
compatible semantic metadata and duration. Different ids, no id, ambiguity,
duration replacement, timeout, foreground-Short ownership, Stop, opt-out and
lifecycle invalidation cannot claim the fragment. Finalization re-fetches the
authority through its original raw or structured route and repeats final-facts
corroboration before payload construction.

Equivalent ordinary-player missing-Short diagnostics are rate-limited to one
reminder per 30 seconds after dynamic trigger prefixes and node counts are
canonicalized. Observer events, proof freshness, proof-loss grace and scoring
are unchanged; proof-frozen/expired transitions log immediately.

The source gate passed 422 tests, assembly and lint (0 errors/23 warnings).
Version 0.9.3/code 39 and its 14,048,941-byte artifact were installed on the A12
with SHA-256
`d18342325d7288f5ccfe16aa549b5752e6ba2fd63d0522cd28e5ae7e65388d88`.
The final physical-test boundary is 10:12:21 local. Diagnostic coalescing is
physically confirmed; unique-write and same-id continuation acceptance remain
pending.

## v0.9.4 structured native author/work contract

For the native-only structured music route, an exact search-card or canonical
watch-page author/channel is an independent complete artist credit even when the
presentation title parses to a collaboration. Agreement with the separated
MediaSession artist is still exact after the existing channel normalization;
partial, token-overlap, substring and fuzzy matching remain forbidden.

Candidate and native work comparison applies the same narrow trailing
`ft.`/`feat.`/`featuring` reduction to both sides. This does not remove remix
identity, arbitrary parentheticals or other words. Search-card duration
compatibility, the eight-page limit, full page fetch, exact final duration,
complete-set uniqueness, final re-fetch and fail-closed ambiguity are unchanged.
The generic raw resolver, browser sources, exact-id routes and continuation
contract are outside this patch.

The field case requiring the author rule is uniquely corroborated
`VqEbCxg2bNI`: native `Criminal` / `NATTI NATASHA` / 273s versus canonical
`Natti Natasha ❌ Ozuna - Criminal [Official Video]`, author `NATTI NATASHA`,
273s. Symmetric featured-work normalization exposes multiple `Ella Y Yo`
uploads and therefore must still refuse every id. The irreconcilable
`Si Te Dejas Llevar`, plus the measured `Te Boté`, `La Pregunta` and `Si Se Da`
ambiguities, remain off-chain.

Version 0.9.4/code 40 implements this boundary. Its focused matcher plus adjacent
resolver/carry/browser regressions passed, followed by the complete uncached
425-test suite with 0 failures, errors or skips. Assembly passed; lint passed at
0 errors/23 existing warnings; `git diff --check` passed. The source and copied
14,048,941-byte APKs have SHA-256
`cbaebadc91d0045b6bd7a2abec8aaacefb2e914782a404d705b24890d4c9ccbf`.
The exact artifact connected on the A12 at 10:59:34 local with version, installed
bytes, settings, Notification Access, both accessibility bindings and USB
stay-awake verified. Its joined `El Efecto` immediately retained the measured
two-id ambiguity refusal. Subsequent multi-id and bounded no-result observations
also remained off-chain, provisional/different-duration phases carried zero
time, and an unresolved controller replacement claimed nothing. A stable
416-second `Ella Y Yo` / `Pepe Quintana - Topic` observation uniquely resolved
to `CGjuWHEPxgc`, established immutable authority, finalized at 416/416 seconds,
re-fetched the same page and block-confirmed the one linked payload as tx
`49a46d159658d257706c9a3c6b32eed4ddd29ca1` in block 108,734,261 on two Hive
nodes, with `CGjuWHEPxgc` reconciled on the public profile. The code-40
unique-write acceptance is complete.

## v0.9.5 native playlist-derived identity contract (implemented)

Native YouTube publishes no video id on any surface a third-party app can read.
Every one was measured empty: `MediaMetadata`, the MediaSession queue, the
session-activity `PendingIntent`, the media notification, the accessibility tree
and the exported `MainAppMediaBrowserService`, which returns an
`__EMPTY_ROOT__` with zero children. Identity for native sessions therefore
cannot be sharpened by better comparison; the candidate set has to be reduced.

The playlist being played is that reduced set. A latch may be established only
from `com.google.android.youtube`, only from a visible `yt:position` parsing as
`N/M` with `1 ≤ N ≤ M`, and only with a non-blank bounded playlist name. It is
converted to a playlist id by one bounded playlist-filtered search in which
**exactly one** result's normalized title equals the observed name; zero or
several refuse, as does a result contradicting an observed owner or total.

Absence of the playlist bar never drops the latch — four mechanisms hide it
while the user is still in the playlist (pre-roll ad, miniplayer, scroll
position, fullscreen) and none is distinguishable from leaving. Only a
positively different playlist name, or an explicit lifecycle reset, replaces it.
Position and `next_video_title` are logged and must never gate a scrobble:
`yt:position` was measured reading `1/120` while the queue was demonstrably
shuffled, and shuffle state is not observable at all.

A latched playlist authorizes a scrobble only when `PlaylistPageParser.match`
finds **exactly one** entry matching the finalized title, artist and duration —
a second matching entry refuses — and every existing downstream gate still
passes unchanged. The native playlist name is carried in its own
`nativePlaylistName` field and never written into `playlistId`, which remains
proven-URL evidence only.

The full field record is `<redacted-private-path>`.

## v0.9.6 signed-in watch-history identity contract (implemented)

Resolution priority is `browser address bar → playlist entry set → watch
history → search`. History is native `com.google.android.youtube` only, guarded
on `session.isNative` and on the exact package; browsers and YouTube Music are
structurally excluded, and browser behaviour is unchanged.

**History supplies a candidate, never a verdict.** An entry is accepted only
when it passes the identical three-field gate the search route applies
(`SearchResultsParser.identityMatches`) against the frozen MediaSession tuple,
and only when it is the sole entry in the recent window that does. Two
indistinguishable recent entries refuse every id. A carried history-routed id is
re-derived from the feed at finalization and must come back the same, or the
listen refuses.

**The listen is still measured locally.** History records that a video was
started, never how much of it was played, so the threshold decision remains
MediaSession position and playback rate. Nothing about the scrobble rules
changes.

**Session handling.** The user performs the Google sign-in; the app never sees a
password. Only the resulting `youtube.com` cookie jar is kept, in
`EncryptedSharedPreferences` under an Android Keystore key. It is never logged,
never exported, never returned to a caller, and attached to exactly one
hardcoded origin; redirects are not followed, so a 302 cannot walk it elsewhere.
The WebView's plaintext jar is wiped once the session is in the vault, and
disconnecting wipes the vault.

**Fail-closed reasons are exact.** A dead session (`responseContext.loggedOut`
or a redirect to sign-in) and a paused history (YouTube's own "Turn on watch
history" control) refuse on sight. A YouTube app that is signed out, on another
account, or in incognito is not separable from the feed, so it is diagnosed
rather than guessed: three consecutive absences **from freshly-read feeds** stop
the route and name all three causes. Absence against a cached feed triggers a
re-read instead, and never counts — a lookup 60 ms into a new track was measured
missing a 0.5-second-old cache and falling through to the search route.

The build record is `<redacted-private-path>`.

## Verification mapping

Each invariant must have all four artifacts before a build is considered ready:

| Invariant | Runtime check | Automated check | User-visible check | Documentation check |
| --- | --- | --- | --- | --- |
| One looping video, one scrobble | Position-wrap detection, carried loop flag, and rules kind cap | Position-wrap, carry, and rules regressions | Log distinguishes detected wrap from inferred Short loop | README + TESTING loop cases |
| Session churn is continuous | Deferred finalization plus carry claim | Carry expiry/token tests | Whole accumulated progress in final log | README + TESTING restart case |
| Stop is a hard boundary | Callback guards and evidence clearing | Pure store clear tests plus code audit | Calm stopped state | README privacy wording |
| Honest confirmation | Structured result evidence | Status aggregation tests | Block/mempool/unconfirmed wording | README + TESTING confirmation matrix |
| Account-bound durable queue | Username match and explicit storage outcomes | Serialization/decision tests where possible | Queue/History terminal status | README queue section |
| One policy for every supported trigger | `FinalizeTrackUseCase` owns shared inputs; the live surface invokes automatic only | Automatic/manual/shadow parity plus threshold/Short tests | Configured threshold text and refusal reason; no UI broadcast control | README + current TESTING finalization cases |
| Literal evidence provenance | Explicit source flag | Parser/fallback/cache tests | Accurate listed/resolved explanation | README short verification section |
| Unlisted Short veto | Explicit pre-duration rules veto | 42-second unlisted-ad regression | Not logged names the unlisted feed ad | README + TESTING ad case |
| Visible YouTube ad veto | Exact accessibility-label detector, `/shorts/` id binding, and carried track flag | Positive/localized labels plus false-positive and carry regressions | Now and Not logged name the UI ad evidence | Accessibility disclosure + README + TESTING ad case |
| Frozen continuation identity | Last active identity captured at disappearance and carried to replacements | Rejected-live selection and identity-carry regressions | Final payload cannot acquire the next foreground id | README + TESTING delayed-finalization case |
| Finalized snapshot isolation | Frozen metadata plus structured resolver evidence carried through enrichment/payload construction | Exact Bad Bunny/La Bebe and YCB/Coming Home handoff regressions | A mismatch appears in Not logged; no mixed payload reaches broadcasting | README + TESTING v0.8.11 isolation cases |
| Transition-safe visible ad veto | URL generation plus provisional/re-observed ad state bound to the active track instance | Exact stadium-ad to cosplayer race, stable-ad positive case, Stop/URL-clear cases | Legitimate successor remains eligible; confirmed ad names its literal signal | README + TESTING v0.8.11 ad race |
| Metadata refinement continuity | Semantic same-track predicate with bounded duration drift | `227125 → 227124`, missing-to-known duration, and material-change cases | One aggregate finalization rather than two threshold failures | README + TESTING v0.8.11 duration case |
| Structure-aware song credits | Top-level separator scanner, explicit shapes and conservative channel-aware orientation | Four log-14 parser fixtures plus nested delimiter negatives | Now preview and payload show the same corrected artist/title | README + TESTING v0.8.11 parser cases |
| Evidence-ranked short canonical titles (v0.8.12) | Weak one/two-token cores restricted to the current-generation observed id plus duration agreement | Five exact log-17 omissions plus adjacent/generic-title negatives | Correct id retained or exact contradiction shown | README log-17 record + TESTING §17 |
| Role-aware channel evidence (v0.8.12) | Uploader/credit inequality cannot veto an otherwise corroborated observed id by itself | Nunca Me Amó plus same-title/different-upload negatives | Not logged distinguishes channel absence from identity contradiction | TESTING §§16–17 |
| Bounded verified-id recovery (v0.8.12) | Memory-only capped candidate cache, lifecycle clearing and full reuse corroboration | Later +57 replay, expiry/reset/change and ambiguity regressions | Log names cache candidate and final evidence source without hiding refusal | TESTING §17 resolver cases |
| Multi-separator credit fidelity (v0.8.12) | Primary separator, credit-list orientation and exact duplicate-feature rules | Seven log-17 fixtures plus structural negatives | Now preview and payload agree before immutable broadcast | TESTING §§16d–17b |
| Strong movie-format kind evidence (v0.8.12) | Explicit narrative movie/edit format above bare category, below hard music provenance | `KoWNsyNVR28` plus real-music/ordinary-word negatives | Now card shows Videos before manual or automatic broadcast | TESTING §§16e–17b |
| Literal parenthetical by-credit grammar (v0.8.13) | Only `(by Artist)` and `(performed by Artist)` take the second-artist path | MONTERO plus genuine by-credit and ordinary-phrase negatives | Now/payload preserve the full work title | TESTING §§18c–19 |
| Hard music provenance before generic channel words (v0.8.13) | YouTube Music/MusicBrainz precede channel vocabulary; explicit format vetoes remain higher | Te Bote plus weak movie-channel and reaction/trailer controls | Now shows song for the OMV before any broadcast | TESTING §§18c–19 |
| Explicit collaborative resolver bylines (v0.8.13) | Stacked suffix normalization and parser-proven collaborator leader, with strict title/duration/uniqueness | Three log-18 misses, missing-marker control and Classy 101 ambiguity | Not logged continues to explain ambiguity; unique matches get canonical links | TESTING §§18d–19 |
| Watch-session exact ad evidence (v0.8.14) | Exact accessibility label bound to one MediaSession track instance, never the organic watch URL | Namecheap/Kao positives; ad-to-organic resume, provisional, ambiguity, Stop/reset and label-free negatives | Advert session is Not logged; resumed organic content remains eligible | TESTING §20 |
| Paired promo/quote fidelity (v0.8.14) | Paired brackets plus narrowly structural single-quoted work parsing | Marlon/TVXQ positives; year, apostrophe, mismatch and event negatives | Now/payload keep balanced canonical metadata | TESTING §20 |
| Conventional credits with collapsed owner proof (v0.8.14) | Exact collapsed owner corroboration outranks track-first guessing | Taki Taki positive; existing track-first lists and unrelated-owner negatives | Now/payload retain artist-first credits | TESTING §20 |
| Channel-proven version suffix boundary (v0.8.14) | Exact leading owner plus bounded version suffix proves only the first dash | Mamma Mia positive; arbitrary three-part/event/promo negatives | Title drops duplicated artist but preserves version suffix | TESTING §20 |
| Track-bound accessibility coverage (v0.8.15, historical eligibility rule superseded in v0.11.0g) | Successful visible YouTube-root scan remains bound to one MediaSession track for diagnostics and literal ad capture; coverage no longer decides verified-id eligibility | Clean scan, exact ad, no-event Namecheap shape, stale/ambiguous/Stop/reset/screen-off cases; current background identity in TESTING §40 | Literal positive ad evidence vetoes; missing coverage does not veto a uniquely verified id | TESTING §§21–22 (historical) and §40 (current) |
| Featured-title orientation (v0.8.15) | A work prefix plus `ft.`/`feat.` on the right does not itself prove track-first orientation | SIP/Bad Habits positives; three log-17 track-first fixtures, Anuel and Taki Taki controls | Now/payload retain conventional artist and full featured title | TESTING §§21–22 |
| Collaborative search byline (v0.8.15) | Unique exact-title/duration hard-music candidate plus exact leading owner segment | BOOGA positive; partial/publisher/no-hard-music/ambiguity/title/duration negatives | Unique compatible id gets canonical link; contradictions remain Not logged | TESTING §§21–22 |
| Podcast type is not music | Catalogue-type allow/deny rule | Real timer type regression | Now card remains `video` | README + TESTING classification case |
| Every YouTube entry is linked | Video-id gate, payload-builder gate, and serialized-broadcast gate | Shorts lockup parser, completed identity, ambiguity, unresolved payload, and serialized policy regressions | Not logged names unresolved identity; quiet-bar banner says items stayed off-chain | README + TESTING v0.8.10 recovery cases |
| Native package opt-ins (v0.9) | Exact package allowlist plus independent persisted settings and source epochs | Default/persistence, enabled/disabled package matrix and epoch invalidation tests | Two default-off switches name their exact packages | README + TESTING §23 + native phase document |
| Native exact identity (v0.9) | Media id → media URI → artwork URI, then unique resolver fallback; canonical payload guard | Route priority, canonical URL, hostile-host/invalid-id, Shorts URI, ambiguity and finalized corroboration tests | Now/log name package, origin, id and exact route or honest refusal | README + TESTING §23 + native phase document |
| Native package isolation (v0.9) | Package-scoped semantic/id carry, resolver cache and lifecycle teardown; no browser evidence calls | Browser/YouTube/YouTube Music carry separation, different-id, package clear and reset-epoch tests | Source package/origin and lifecycle clears are logged | TESTING §23 + native phase document |
| Native metadata and kind (v0.9) | Supplied separated fields take precedence; native Music context below hard non-music evidence | Clean title/artist/album, podcast type/genre, native YouTube song/video tests | Now payload previews exact native fields and kind reason | README + TESTING §23 + native phase document |
| Native ad limitation (v0.9) | No native heuristic/veto without literal structured proof; full metadata/state dump | Rule fallback and source-boundary tests plus production-source audit | Both settings and Now warn browser protection is inapplicable | README + TESTING §23 + native phase document; physical gate pending |
| Foreground native Shorts (v0.9.1) | Exact-package structural proof, 750 ms identity stability, position-delta tracker, bounded missing-proof watchdog and literal in-player ad evidence | Parser/stabilizer/tracker, owner-handle resolver, central ad/manual/automatic, carry and lifecycle regressions | Separate grant/status, exact proof/reason, measured progress and foreground-only disclosure | README + TESTING §25 + PHASE_NATIVE_SHORTS; bounded device/write gate passed |
| Simplified native music metadata (v0.9.2) | Native-only fully fetched parsed work/credit/duration uniqueness plus tokenized STOPPED replacement grace | Four measured shapes, presentation/credit variants, partial/containment/duration/ambiguity negatives and exact-ID-less replacement isolation | Physical zero-carry 218→20→207 replacement and two-upload structured refusal confirmed; unique-write reconciliation pending | README + TESTING §26 + PHASE_NATIVE_PLAYLIST; installed field continuation pending |
| Native artist-aware budget and controller continuation (v0.9.3) | Exact work+complete credit before page budget; pre-resolved immutable id plus independently same-id replacement and route-matched final re-fetch | Budget noise/over-budget, same/different/unresolved id claim, duration replacement, route provenance and dynamic diagnostic-key regressions | Diagnostic coalescing, multiple unique writes and natural same-id `Unica` recreation/final write confirmed | README + TESTING §§27–28 + PHASE_NATIVE_PLAYLIST |
| Exact native author credit and featured-work symmetry (v0.9.4) | Canonical author remains an independent complete credit; the same explicit feature suffix reduces native/candidate works | Criminal exact-author positive, partial/unrelated negatives, Ella ambiguity and Si Te Dejas contradiction fixtures | Exact APK installed; ambiguity/no-result/zero-carry controls retained; unique `CGjuWHEPxgc` write block-confirmed | README + TESTING §28 + PHASE_NATIVE_PLAYLIST |

---

## v0.9.7 identity and picture-in-picture contract (2026-08-06)

Field evidence and per-change reasoning: [<redacted-private-path>](../Field-Reports/<redacted-private-path>) §8–§12. This
section records the invariants that **changed**, because three of them supersede earlier rulings.

### 1. The on-screen title is no longer identity evidence

**Superseded:** the foreground-Short route previously refused any Short whose footer title could not
be read unambiguously, discarding the measurement with it.

YouTube removed the resource ids from the Shorts footer, so the title was being chosen as the single
survivor of a blocklist. Five separate causes were corrected in one day and the measured acquisition
rate stayed at roughly **one Short in six**. The title was never authority — it was a selector.

**New rule.** `NativeShortParser.Result.Organic.title` may be null. A Short is proven by its
structural player, its exact owner handle and a readable seekbar. Identity is resolved at finalize
from the signed-in account's watch history, joined on **owner handle + duration**, and every
candidate is still re-fetched and corroborated on its own watch page. When a title *is* present it
must still agree exactly. Measured after the change: **8 of 8 Shorts reached a finalize.**

### 2. Recency breaks a tie the other two fields cannot

**Superseded:** two candidates matching all available fields refused unconditionally.

With no readable title only two fields remain, and a creator who posts several Shorts of the same
length ties them. Measured 2026-08-06: a Short counted to 100% and was discarded because its channel
had two 57-second uploads.

**New rule.** When and only when the title is unavailable and two or more candidates match owner
handle **and** duration, the most recently watched is taken. Candidates arrive in watch-history
order, newest first, and the Short being identified is the one playing now — so recency is a third
field that restores uniqueness, not a coin flip. **With a title present, two full matches still
refuse.** Handle and duration remain mandatory; recency never admits a candidate that fails either.

### 3. Collaborative bylines and the `AtVEVO` channel form

**Supersedes the §823 ruling** ("accept only a unique exact-title/duration *hard-music* candidate
whose owner matches the leading segment … all weaker bylines remain contradictions").

That rule was too narrow to survive the field. 26 listens in one session were lost to a channel
comparison that was simply wrong, and watch history had already resolved every one of them:

| watch page | media session | count |
| --- | --- | --- |
| `NickiMinajAtVEVO` | `Nicki Minaj` | 14 |
| `La Melma Music and 2 more` | `La Melma Music` | 9 |
| `Eladio Carrion and CAZZU ` | `Eladio Carrion` | 2 |
| `Lucky Brown and 2 more` | `Lucky Brown` | 1 |

**New rules.** (a) `AtVEVO` is stripped before `VEVO`, because stripping the bare suffix left a
trailing `At`. (b) A byline whose **leader** is the ended channel is not a contradiction **provided
the title and the duration also corroborate** — three agreeing fields. The hard-music and
uniquely-resolved preconditions are dropped: history resolves these, so both flags are false when
they arrive. A byline whose leader is a different channel still contradicts, and a name with no
separator is not a byline.

### 4. Picture-in-picture time may be credited, marked as inferred

**New capability, off the measured path.** PiP publishes no progress of any kind, so elapsed
wall-clock is credited on the paired evidence that YouTube holds a visible window
(`UsageStatsManager`, which names packages) *and* media audio is started (`AudioManager`, which
cannot). Neither alone is sufficient and neither is used alone.

Binding constraints:

- Never used while a readable seekbar exists; it is a fallback, not a source.
- Only for the exact measured PiP parser signature. A swipe, a blown budget or a hidden root are
  "gone", not "unmeasurable", and must never accrue.
- Credit advances only between two consecutive observations that both say playing; one step is
  capped at 3s; measured + inferred never exceeds the item's own duration.
- Carried separately as `SessionSnapshot.inferredPlayedMs` to the log and the refusal wording, so
  every such listen states how much was measured and how much deduced.
- Its own switch, on by default, inert without Usage Access, absent below API 29.

**Accepted residual risk, recorded rather than hidden:** another app playing audio while a YouTube
PiP window sits paused is indistinguishable from playback and would be credited. This is the cost of
counting PiP at all.

### 5. A completed listen is banked when it completes

**Superseded:** a foreground Short ended only when something took it away.

Left alone a Short loops, so it accumulated indefinitely and banked nothing — measured, a 105s Short
reached `measured total 461s` across four loops without a single finalize. Reaching its own length
is the end of a listen, so it finalizes there, **once** per viewing, which is all `capForKind`
permits regardless.

### Unchanged and not negotiable

Every entry still requires a verified video id and a canonical hyperlink; an unresolvable listen
still fails closed with an exact logged reason; the dedup ledger is untouched; browser behaviour is
unchanged; and no native gate applies outside `session.isNative`.

## v0.9.8 identity contract (2026-08-06)

Field evidence and per-change reasoning: [<redacted-private-path>](../Field-Reports/<redacted-private-path>) §14. One ruling
is superseded; the other two entries record rules that were never written down.

### 1. A video's two published names are both its name

**Supersedes the v0.9.7 §1 wording** that the displayed title is *substituted* for the canonical one
when it equals the frozen title.

Substitution was the defect. The displayed name replaced the canonical one whenever their title
**keys** matched, and an all-hashtag title has an empty key — so every such title matched vacuously
and the wrong one of the two was compared. Measured 2026-08-06 on `RTQFqbCPUGg`: the upload is
Spanish, the resolver's `en-US` fetch renders the same page in English, and the corroborator refused
a 100% listen because the English rendering is not the Spanish one the screen showed.

**New rule.** A watch page may publish two names for one id — `videoDetails.title` and the
`videoPrimaryInfoRenderer` rendering. Both are compared against the frozen title and the
**strongest** evidence decides; neither is preferred and neither can weaken the other. This admits
no new candidate: both names come from the one page being verified for the one id, and duration,
channel/owner handle, and the unique-id rule all still bind independently. A refusal names both.

### 2. A stood-down watch-history route must say so, and must not stand down over one track

**Not previously written down**, and both halves cost listens on 2026-08-06.

`WatchHistoryHealth` refuses the route after three consecutive misses, on the reasoning that the
YouTube app is signed out, on another account, or in incognito. That diagnosis is about *tracks*
going missing, so:

- **A miss is per track.** Consecutive misses of the same track count once. One video replayed three
  times armed a fifteen-minute pause on a claim ("the last 3 native tracks were not written") that
  was true of exactly one.
- **A refusal is never silent.** `recentShortIds` returned an empty list without a line in the log,
  and two untitled Shorts at 62% and 77% were refused inside that pause with a message blaming
  sign-in. A route that is not running states why, in the log and in the refusal the user reads.

### 3. Unmeasured lead-in is stated, never credited

**Not previously written down.** Measured 2026-08-06: YouTube published a MediaSession for
`_zR6ROjoOX0` for seven seconds, ninety-four seconds into a 227-second video, and nothing before or
after. The finalize line read `played 12s of 227s` and looked like a fault.

**New rule.** Where the player was when RustedWax first saw a track is **not** evidence that the
track played that far in this session — a resumed video opens mid-track having played nothing now —
so it is never credited. It *is* reported: a track first seen more than 10s in says so on its
finalize line, unless progress was carried across a replacement session, which accounts for its own
lead-in. Silence about what could not be measured is the failure mode this exists to prevent.

## v0.9.9 observation contract (2026-08-06)

Field evidence and per-change reasoning: [<redacted-private-path>](../Field-Reports/<redacted-private-path>) §15. Both entries
are about *reporting*; neither changes what may be credited or scrobbled.

### 1. An app is visible while any of its activities is started

**Supersedes the implicit rule** in the picture-in-picture probe that the most recent `ACTIVITY_*`
event names the app's current state.

Measured 2026-08-06: opening a Short by URL emits `RESUMED MainActivity` and then
`STOPPED Shell_UrlActivity` in the same second, so the departing activity's stop overwrote the
arriving one's resume and the probe called a visible, audible YouTube gone. PiP credit reads the same
signal, so this silently refused time to any Short opened through an activity transition.

**New rule.** Visibility is per activity. `ACTIVITY_RESUMED` and `ACTIVITY_PAUSED` both mean visible —
a picture-in-picture window leaves its activity paused, which is the case the probe exists for — and
only `ACTIVITY_STOPPED` removes one. The app is visible while any remain. Activities are keyed by
class name because instance ids are not public API, so two live instances of one class share a key
and stopping either reads as stopping both: that fails toward *not* visible, which under-credits
rather than over-credits.

### 2. An outage is only reported when a listen is being lost

**Supersedes the v0.9.x instrumentation rule** that event silence with the screen on, on any YouTube
surface, is reportable.

That rule produced 111 reports in a day, on a day that scrobbled 46 tracks. Two of its three states
were benign: a latched Short is measured by the service's own poll while YouTube emits no callbacks
at all, and a capture that finds no player is usually the user having left the Shorts feed.

**New rule.** A report requires all of: the screen on; the surface not another app; no successful
capture within the window; and independent evidence that something is playing which nothing else is
counting. "Nothing else is counting" excludes MediaSession-measured watch playback and
picture-in-picture time the inference is already crediting. The evidence is media audio `started`
paired with a visible YouTube window — never the accessibility tree, which is the suspect, and never
the MediaSession, which publishes `active=false`, `state=1` and no metadata at all while a Short
plays.

Where that evidence cannot be obtained — no Usage Access — only the state in which the service can
see no window at all still reports, and it says outright that it does not know whether anything was
playing.

**Accepted residual, recorded rather than hidden:** a Short played with its audio stopped, on a
device without Usage Access, can still be lost without a line. Under-reporting was chosen
deliberately over a report the reader learns to ignore.

## v0.9.10 Shorts measurement contract (2026-08-07)

Field evidence: [<redacted-private-path>](../Field-Reports/<redacted-private-path>) §16.

### 1. A Short is proven by its player and its handle; the seekbar is evidence, not a precondition

**Supersedes the v0.9.7 rule** that "a Short is proven by its structural player, its exact owner
handle and a readable seekbar".

Measured 2026-08-07: YouTube stopped rendering the Shorts progress bar entirely — no `SeekBar` node
in the tree and no bar on screen — while the player, the container, the exact handle and the audio
were all still there. One tap on the video restores it for the session, which an observer may not do
for the user. 47 of 71 Shorts in 85 minutes were lost, not because the wall-clock fallback refused
but because it never ran: a Short with no reading could not be *started*, and nothing that is not
started can accrue anything.

**New rule.** A visible player root, one time-bar container and exactly one exact owner handle prove
a foreground Short. A readable seekbar time is credited as measurement when present and is otherwise
absent evidence, not a refusal. Time for a Short with no reading is credited only by the
picture-in-picture inference, on the same paired evidence, and is always reported as inferred.
Until the length is known the accrual ceiling is the format's own three-minute maximum; a seekbar
appearing mid-viewing supplies the real length and continues the same Short.

**No handle and no readable time is unchanged**: that is the picture-in-picture signature, and it
still only ever credits a Short that was already proven.

### 2. Identity needs any two of title, length and handle — never one

**Extends the v0.9.7 ruling**, which dropped the title and leaned on handle + duration.

The length came from the seekbar, so it can now be absent too. The handle is the field that has
survived every restyle, so it is mandatory; the other two are interchangeable.

| available | gate |
| --- | --- |
| handle + length | unique match on handle + length, recency breaks a tie |
| handle + title | unique match on handle + title |
| handle only | refused |

A field that *is* published must still agree. Uniqueness is untouched: two candidates matching
everything available still refuse, and every candidate is still re-fetched and corroborated on its
own watch page.

## v0.9.11 – v0.9.12 continuity contract (2026-08-07)

Field evidence: [<redacted-private-path>](../Field-Reports/<redacted-private-path>) §17. Every entry here exists because one
viewing was being cut into fragments that individually cleared nothing.

### 1. An interrupted Short resumes rather than restarts

**New rule.** A foreground Short whose player goes away and returns with the same identity —
same title, same owner handle, same source epoch, and a length that has not changed — within 30
seconds resumes the progress it had earned. Measured 2026-08-07: switching between the Home and
Shorts tabs outlasts the 3-second proof grace, so one 32-second Short finalized at `0s`, `3s` and
`5s` across three switches and never reached a threshold it had long since earned in total.

Merging two genuinely separate viewings of one Short is harmless and accepted: `capForKind` and the
dedup ledger already cap a video to one scrobble.

### 2. A Short with nothing left to earn ends when it is earned

**New rule.** A Short with no readable length can never reach its own duration, so it could only end
when something took it away — one sat active for seven minutes. It now finalizes the moment its
wall-clock inference is exhausted, because nothing further can be credited. Prompt finalization also
keeps it inside the recent-history window its identity depends on.

### 3. Empty metadata is not a track

**Supersedes** the implicit rule that any metadata change with a different identity ends the current
track.

YouTube recreates its MediaSession on every tab switch and publishes empty metadata first — no
title, no duration — with the real values following a fraction of a second later. Treating that as a
track change finalized a phantom `<untitled> — played 0s of 0s` and, worse, ended the real track
against it: a 155-second trailer accumulated 18 seconds of a several-minute viewing.

**New rule.** A session holding no title, no duration and no played time has not been playing a
track that can end. When its real metadata arrives the same Watch adopts that identity, restores any
carried progress, and continues. A genuinely ended track still ends by `STOPPED`, by session
destruction, or by the replacement that follows.

### 4. A field the enrichment fetch did not carry is absence, not contradiction

**Supersedes the v0.9.7 rule** that a foreground Short requires the exact owner handle from *both*
the resolved candidate and the final watch facts.

Measured 2026-08-07: history resolved a Short and corroborated it on its own watch page, then the
enrichment fetch of that same page returned without an `ownerProfileUrl`, and an 83-second listen was
refused for a missing field.

**New rule.** A handle absent from the final facts does not refuse, because the candidate's own page
has already proven it for that same id. A handle that is **present and different** still refuses.
Seeding the run-local candidate cache continues to require both, because a cached candidate is later
re-used without its page in front of it.

## v0.9.13 hand-off and identity contract (2026-08-07)

Field evidence: [<redacted-private-path>](../Field-Reports/<redacted-private-path>) §18. The first two entries each describe
a listen that was measured correctly and then thrown away.

### 1. Handing the player to the foreground Shorts route is not a discard

**Supersedes** the implicit rule that suppressing the MediaSession for a proven foreground Short may
reset its accumulated progress.

When the foreground Shorts route acquires a Short it takes ownership of the player, and the
MediaSession Watch stops counting so the same seconds are not scored twice. Measured 2026-08-07:
zeroing its accumulator also deleted the listen it was in the middle of, with no finalization —
`THE RUN — Official Trailer`, 85s of 104s, gone between two log lines. Ten listens in one log,
up to 168 seconds each.

**New rule.** Progress accumulated before the hand-off belongs to whatever the MediaSession was
playing. It is finalized, not discarded, unless the Short taking over is that same item — decided on
published evidence: the title when both surfaces publish one, the length when the Short's footer
title is absent, and otherwise not proven. A pending continuation cannot survive the hand-off either,
because its expiry callback would land on a suppressed Watch where finalization is a no-op; it is
decided at the hand-off instead.

The asymmetry is deliberate: refusing to call two things the same costs at most one extra
finalization, which `capForKind` and the dedup ledger already cap to one transaction. Wrongly calling
them the same destroys an earned listen.

### 2. An owner handle is not an ASCII string

**Supersedes the v0.9.10 rule's implicit character set.** The handle remains the one mandatory
identity field for a foreground Short; what counts as a handle widens.

Measured 2026-08-07: the footer read `Go to channel @eduardaarebouçass` and was refused on the
cedilla, so the whole listen was refused. Every creator whose handle is not spelled in ASCII was
invisible.

**New rule.** A handle is letters, marks, digits and YouTube's `.`, `_`, `-`, three to thirty
characters, in any script. It is composed to NFC before comparison, so one handle encoded two ways is
one handle. NFC and not NFKC: canonical equivalence only, because an identity field may never fold
two distinct characters together.

The same widening applies to `ownerProfileUrl`, where the handle is corroborated. Non-ASCII characters
are percent-encoded before URL parsing and escapes are decoded only *after* the structure is settled,
and only when they stand for a character outside ASCII — so `%2F` and `%40` remain refused and no
structural character can be spelled sideways.

### 3. One banked listen is finalized once

**New rule.** A foreground Short that banks a complete listen on reaching its own length (v0.9.11) is
not finalized again when something later takes it away — not by the next Short, not by an ad
transition, not by the proof-expiry grace. Measured 2026-08-07: one viewing was resolved, enriched
and broadcast twice, and only the dedup ledger stopped the second write. The ledger is the last line
of defence, not the design.

### 4. A refusal names what it refused

**New rule.** Where two unrelated failures can produce the same refusal, the diagnostic distinguishes
them and carries the evidence it decided on, bounded. `expected exactly one exact visible owner
handle` now says whether none or several were found, and what the footer actually held. Repeated
diagnostics stay coalesced by keying the throttle on the failure's shape rather than on its detail,
so evidence never costs a log flood.

## v0.9.14 playback-rate contract (2026-08-08)

Field evidence: [<redacted-private-path>](../Field-Reports/<redacted-private-path>) §19.

### 1. A Short's progress bound is a rate, not the wall clock

**Supersedes** the implicit rule that a foreground Short's seekbar may not advance faster than
elapsed real time.

`played` has meant *content consumed* since Phase 3 — the MediaSession path scales it by the playback
rate, because a video watched in full at 2× is a video watched in full. The foreground Shorts route
bounded each seekbar delta by wall clock alone, so at 2× every delta exceeded the bound and was
discarded as a seek. Measured 2026-08-08: 58 seconds of content traversed between two polls, 2
credited, and a 121-second Short finalized at `played 6s of 121s`. The same Short at 1× scrobbled at
98%.

**New rule.** A seekbar delta is admissible up to `MAX_PLAYBACK_RATE` × elapsed wall clock, plus the
existing jitter allowance. Beyond that it is still refused in full, so a seek earns nothing and no
listen can be credited more content than could physically have been played in the time available.

`MAX_PLAYBACK_RATE` is the fastest rate the platform offers the viewer, and is a measured value, not
a guess: raising it widens the window in which a forward seek is indistinguishable from playback.

### 2. A refused delta says that it was refused

**Extends the v0.9.13 rule** that a refusal names what it refused, to the one place that had no
message at all.

A seekbar delta beyond the rate ceiling earns nothing, and until v0.9.14 it did so **silently** — no
line, no counter, nothing to grep. A Short played at 2× produced a log that looked entirely healthy
and then finalized at 5%, which is why the defect survived until the owner isolated it by hand.

**New rule.** A refused forward jump is reported with the seconds it covered and the wall clock it
covered them in, throttled on the shape of the refusal rather than its numbers, so a scrub cannot
flood the log and a rate problem is visible the first time it happens.

### 3. Inference is scaled only by a rate that was published

**Clarifies** the v0.9.7 wall-clock inference, and is **superseded in part by v0.9.15 §1** below.

Where no progress surface exists at all, elapsed wall clock is credited and reported as inferred. It
is multiplied by a playback rate **only** when something published one; otherwise it stays 1×, and a
seekbar-less Short played faster is under-credited rather than guessed at. The inference exists
because direct evidence is missing, and inventing a rate would be a claim about playback nothing
observed.


## v0.9.15 playback-rate evidence (2026-08-08)

Field evidence: [<redacted-private-path>](../Field-Reports/<redacted-private-path>) §20.

### 1. A visible speed chip is published evidence of both playback and its rate

**Supersedes v0.9.14 §3's** "no rate is ever assumed" to the extent that one is now *read*.

Holding a Short to play it faster replaces the whole overlay — title, owner handle, action column
and seek bar — with two labels, one of which is the rate itself (`2x`). So the single state in which
a Short cannot be measured at all is also the state in which YouTube says out loud how fast it is
going.

**New rule.** A speed chip visible inside the proven Shorts player is:

- **proof the player is playing**, sufficient on its own, because YouTube renders it only while the
  gesture is actively speeding playback up. It replaces the audio-plus-window pair in this state —
  that pair did not answer during a hold, and the Short was dropped three seconds in, finalizing at
  whatever it had earned before.
- **the rate the inference is credited at**, so one second of wall clock is worth what it actually
  bought. Every such credit names the rate and its source in the log.

It is read anchored — a sentence *mentioning* a speed is not a chip — and bounded to rates the
platform offers; anything outside that is ignored rather than believed. It still obeys the user's
"count what cannot be measured" switch, because that switch is about crediting inference at all.

### 2. Evidence gathered before a refusal survives the refusal

**New rule.** Where a parse refuses, facts it had already established are still reported. The rate is
attached to every refusal raised after the player is proven, not only to the one that happened to be
reached first — the first cut attached it at the handle check alone, and a hold that stopped at the
seekbar-time check reported no rate at all and was credited at 1×.


## v0.9.16 identity is latched once (2026-08-09)

Field evidence: [<redacted-private-path>](../Field-Reports/<redacted-private-path>) §21.

### 1. An acquired Short does not re-prove its handle on every frame

**Supersedes** the v0.9.10 rule to the extent that the owner handle was required on *every*
observation rather than at acquisition.

The 2× hold has two shapes. It always hides the footer; it sometimes leaves the seekbar readable —
measured 2026-08-09, `the seekbar still read 23s of 121s` while the only labels left were `2x` and
`Pull down to lock 2x speed`. In that shape nothing needs inferring: the measurement is on screen,
and it was being discarded because one frame lacked the field that establishes *identity*, which had
already been established.

**New rule.** An observation that proves the Shorts player and yields a readable progress time
credits the Short already being tracked, footer or no footer. It carries no identity, so:

- it can never **acquire** a Short — nothing is credited to a video this app has not proven by its
  owner handle;
- it is refused unless the **length still matches** the one acquired, so a scroll that lands on
  another Short mid-hold cannot have its seconds taken;
- a fresh Short still requires the handle, exactly as before.

The handle remains the identity and remains mandatory to *start* a listen. What changes is that
losing sight of it for a frame no longer discards what that frame measured.

### 2. A refusal reports the evidence it already held

**Extends v0.9.14 §2**, which required a refusal to say that it happened.

Where a parse refuses, the facts it had already gathered are reported alongside the missing one —
the rate, and whether a progress reading was available. Two releases were spent fixing the wrong half
of one state because the refusal named only the field that was absent, and the line that
distinguished the two shapes took one build to add.


## v0.9.17 watch history is a floor under the browser (2026-08-09)

Field evidence: [<redacted-private-path>](../Field-Reports/<redacted-private-path>) §22.

### 1. A browser YouTube session may use the account's own watch history

**Supersedes** the rule that the watch-history route is native-only, which rested on browsers having
the address bar.

They have it only while the address-bar watcher is alive, and Android disables an accessibility
service when it crashes — the failure `AccessibilityGrantHealth` exists to report. Measured
2026-08-09 with it dropped: a Brave Shorts session read `YouTube (site only, no video id)` on every
poll, and `#hoyoverse` / Mr Time Edits was measured at 89% and then 98% and refused both times,
because search cannot name a video whose title is one hashtag.

**New rule.** When no video id has been proven by any other route, a browser session proven to be
YouTube may be resolved against the signed-in account's watch history, on the same terms as the
native path. A browser **Short** uses history's exact ids with the **channel** standing where the
native route uses the owner handle, and every candidate is still re-fetched and must agree on title,
channel and duration, uniquely.

This cannot change browser behaviour when the address bar is working: resolution by id runs first and
a confirmed URL never reaches this route. It is a floor under the browser path, not a replacement for
it — no rule is relaxed, the same corroboration decides.

---

## v0.9.18 – v0.9.19 appearance contract (2026-08-09)

The first change in this project that alters nothing about what reaches the chain. It is recorded
here anyway, because two of its rules are about honesty and one is about traffic — and those are
contract matters wherever they happen to live.

Design source: `design/logo.jpg`, `design/design_lightmode.jpg`, `design/design_darkmode.jpg`.
The mockups were generated and are not authoritative on wording, controls or layout; they were
implemented for **colour, shape, iconography and the mark** only. No label, no control, no tab and
no ordering changed. The settings tab draws one card per row where it drew one card with dividers.

### 1. Appearance is a stored preference, not a system reading

Light, dark, or follow the system, chosen in Settings and stored as the enum name so reordering the
enum cannot silently repaint anyone's app. An unrecognised stored value follows the system rather
than refusing to start: this is the app's appearance, not one of its rules, and it fails soft.

The window background and both system bars are painted from the resolved scheme. The pre-Compose
launch window has its own `values-night` colour, which can only follow the system — the in-app
choice is allowed to disagree with it, and the first frame corrects it.

### 2. Thumbnails are lookup traffic and ride the lookup switch

**Rule.** The still frame beside a History or Not-logged row is fetched from `i.ytimg.com` keyed to
a video id the app had already proven. That is off-device traffic derived from a detected id, which
is exactly what **Look videos up** governs, so it is governed by that switch and by no second,
quieter one. With lookups off, no thumbnail request is made and the rows keep their placeholder.

Nothing new is stored. The cache file name is the video id, and the id was already on the record
that draws the row.

### 3. A failure to ask is not an answer

**Rule.** An id is written off as having no thumbnail — permanently, for the life of the process —
only on a **definite negative**: a 403/404/410, or the tiny grey placeholder YouTube serves with a
200 instead of a 404. A timeout, a 5xx, a captive portal or a dropped connection is
`Unavailable`, is never remembered, and the next composition of that row is the retry.

v0.9.18 shipped the opposite: every failure path wrote the id off, so a minute without signal would
have blanked those rows until the process was killed. Covered by `ThumbnailFetchTest`, which is
pure JVM because the decision has a lasting consequence and a device is a poor place to prove it.

### 4. One load per id, and a cache entry is whole or absent

**Rule.** Loads are deduplicated by id, and the file is staged beside its target and renamed into
place.

The same video legitimately appears more than once on screen — a repeat listen puts it in History
twice, and a track can be in History and Not logged at once. Two rows composing together meant two
downloads racing on one path: one truncating what the other was reading, the reader then deleting
the half-written file as corrupt and marking the id dead by rule 3.

### 5. The History header may only claim what the rows prove

**Rule.** The green *Confirmed on Hive* header is drawn only when every row in the list is
confirmed. A queued or rejected row replaces it with an amber count of what is **not** on-chain.

The mockup's header was unconditional. An unconditional claim over a list that can hold a queued
entry is the app asserting an entry exists when it does not, which is the one thing the Not-logged
tab exists to prevent it doing.

### Proven by

Version code 55, <redacted-device-model>, three broadcasts with their rows' thumbnails resolved:

| | |
| --- | --- |
| `tx 0416a874c8616ab400fd49a6fdd0b55a81a78d38` | browser, 100% |
| `tx d664073e8492cbaa6fccaeea3283695380445436` | native YouTube via watch history, 81% of 54s |
| `tx a0b34ee2a552b8dd68165f620ac0cdef93e75cc0` | native YouTube, 78% of 4:26, on the final binary |

The third is the one that matters for the last change. `ProduceStateDoesNotAssignValue` fires on a
`produceState` whose producer *does* assign, when the call uses named arguments — a lint false
positive, but the gate is `lintDebug` and a suppression would have to be read and re-litigated by
whoever next sees it. The composable now holds its own state and fills it from a `LaunchedEffect`,
which is unambiguous to both the reader and the tool.

---

## v0.9.20 an interrupted listen is one listen (2026-08-10)

Reported as "videos that played to the end got logged as 45% and 55%". One symptom, five defects,
all of the same shape: a rule that answered *is this the same listen?* with a stopwatch when the
evidence to answer it properly was already in hand.

### 1. Continuation is bounded by position, not by the clock

A listen measured inside one `MediaSession` restarts its accumulator when Android rebuilds that
session. The continuation window that stitched the halves back together was sixty seconds — a
number measured on Chrome ad breaks, where the observed gaps were 18 and 21 seconds. It was never
sized for a person who minimises the app and comes back, and on a memory-starved phone that gap is
however long they are away. Measured 2026-08-09: `105s + 129s = 234s`, the whole video, logged as
two skips.

A separate viewing **starts at the beginning**; a continuation starts where the last one stopped.
So the window now requires an exact video id on both sides and a resume position within 15 seconds
of where the carry stopped, and time only has to be bounded at all — fifteen minutes — rather than
bounded tightly.

Three guards keep it from admitting a replay. The carry must have stopped at least 30 seconds in,
deliberately more than the tolerance, so a position near zero can never fall inside the band. A
track that reached its own end is not held, because it has nothing left to resume. And a pending
continuation is collected as soon as a *different, exactly identified* track plays in the same app —
requiring the id is what stops an ad or transition phase ending a real listen's wait.

**This applies to the browser.** The first implementation keyed the long window on where the native
resolver stores its id, so Chrome silently kept the sixty seconds and kept splitting. A browser
proves the same fact from its address-bar latch and is entitled to the same window.

### 2. A displaced continuation is still finalized

Two viewings of one track can now overlap in a fifteen-minute window where they could not in a
sixty-second one. The older continuation is handed back to its owner to finalize rather than dropped
— a listen that nobody continued is a listen, and losing it silently is the failure the carry exists
to prevent.

### 3. Two names for one channel are not a contradiction

`VideoIdentityCorroborator` compared the MediaSession's artist against the watch page's author and
refused the id when they differed. For every VEVO upload those are two strings for one channel by
design — `BMTHOfficialVEVO` against `Bring Me The Horizon` — and they do not normalize to each
other. 46 correct ids were refused in one day's log.

The exception is keyed on the **id**: the facts were fetched for the id the resolver returned, so
the two names describe one video's one uploader by construction. What establishes the id is the
resolving route's own three-field match, which already included the channel. Title and duration
must still agree on the enriched pass, and a route that never matched a channel gets nothing.

### 4. A Short resumes on its seekbar

The same rule, in the subsystem that measures Shorts from the accessibility tree rather than from a
`MediaSession`. Its resume window was thirty seconds, measured on tab switches. A 107-second Short
taken away at `52s` and re-acquired at `52s of 107s` restarted at zero.

### Proven by

| | |
| --- | --- |
| `tx 43073e84e16bc488dc03e1abeddd8b7152884138` | native, VEVO, resumed after 2m — 99% |
| `tx 1d446e1faafa96e6ab44a1a509b36acefa146d82` | **Chrome**, resumed after 2m18s — 94% |
| `tx dfe6b4ccf7ffac17be8113b0695968e3b40ec194` | native, non-VEVO, resumed after 100s — 100% |
| `tx 54105990005ad12a40d64f0d3dfc03900b4595e5` | the VEVO id that had been refused twice |
| `tx 806a60054926052a5534deeae62ae16e09c3df98` | the Shorts path still reaching the chain |

---

## v0.10.0 expansion phases 1–4 (2026-08-10)

`<redacted-private-path>` §3–§6. Phase 5 is not started.

### 1. Only a source whose artist field names a performer is trusted

The YouTube **app** publishes the channel in the MediaSession artist slot; YouTube **Music**
publishes a real artist. One `isNative` check covered both and was correct for one of them, so
`Snoop Doggy Dogg - Intro` on `King Of Rap` reached the chain with the channel as its artist and the
artist still inside its title.

The YouTube app now runs the same credits ladder the browser always did. YouTube Music's metadata is
used untouched, because it is better than anything parsing could recover.

### 2. A claim narrows to what is provable rather than being guessed

When the ladder cannot establish a performer, the entry stops claiming to be a `song` and becomes a
`video` credited to its channel — true by construction, since the channel is the uploader. **No
listen is lost by this;** only the assertion about it weakens.

### 3. A session not proven to be YouTube leaves no trace

A browser publishes a `MediaSession` for any video site, and the app wrote the page title before
anything had proven what the page was — an exportable, timestamped record of what the user watched
elsewhere. Four separate routes wrote it and all four are closed: the metadata block, the identity
verdict, the notification hint, and the finalize line, plus the arrival, playback and continuation
lines that named the package.

What remains is a periodic count, which answers *is it running?* and describes nobody. The same
rule covers the **Not logged** tab: it is a durable record too.

### 4. Logging off means nothing is written

No file, no ring buffer, no `Log.d`, and what is already on disk is erased — the setting says
nothing is being recorded, and leaving yesterday's log would make that false the moment anyone
looked. Diagnostic logging expires on its own after 15 days.

> **Superseded in v0.11.0 and amended in v0.11.1.** The first two sentences still hold. Diagnostic
> logging is gone: a second switch that could override the first meant "is RustedWax logging?" had
> two answers and the wrong one was on screen. `Event log` is now the only control, and a fresh
> install starts with it **off**. As of v0.11.1, off also removes the `Log` destination and the
> `Export` button, and on retains at most the last 12 hours or 512 KiB, whichever binds first. See
> [v0.11.0 one switch per question](#v0110-one-switch-per-question-2026-08-10) and
> [v0.11.1](#v0111-destinations-not-tab-numbers-and-a-log-that-ends-2026-08-25).

### 5. A private listen is never broadcast in the clear

When a per-kind privacy toggle is on, the payload is replaced by the extension's envelope
(`{app, kind, timestamp, private, v:1}`), AES-256-GCM under a key derived from the posting key's
deterministic signature over `zingit:privacy-key:v1`. Fresh IV per call, because a public ledger
would otherwise reveal a replay of the same track without anyone decrypting it.

**If the secret cannot be derived the listen is held back.** Falling back to plaintext would publish
the one thing the user asked to keep private, to a ledger with no undo.

Byte-compatible with the extension in both directions: a blob produced by Web Crypto through
`privacy-cipher.ts` decrypts here, and the derived secret matches the captured parity vector exactly.

### 6. The app says who wrote it

`app` is `rustedwax/<version>`, where it was byte-identical to Hive Scrobbler's. The `custom_json`
id is deliberately unchanged — the entries belong in the same feed — but a defect here can no longer
land attributed to someone else's app.

### 7. Shorts have an off switch that is not the short-clip floor

`shortClips` decides a verified Short's *minimum length*. `disableShorts` decides whether Shorts
count at all. Both exist because conflating them is how someone ends up certain they turned Shorts
off while Shorts keep reaching the chain.

### Proven by

Version code 55 on the field build, <redacted-device-model>:

| | |
| --- | --- |
| `tx 66717ba182ad51999361215e109b21532ef5627a` | the credits ladder and the new `app` value together |
| `tx da7831b411349fd2bc0d291359bf5594c0f0ea66` | an encrypted envelope on chain, nothing about the track in the clear |
| `tx 3281238d6466a2ab2049a1c5bdc17459b3da3397` | post-fix regression check |
| `tx 6b20be7034ff6f71285bdfb35332265d5c4f5d3a` | the final binary |

Plus the Shorts switch refusing a 57-second Short by name, the app-list migration reporting
`2 allowed, 0 blocked`, and a full Chrome play-and-finalize of a non-YouTube video leaving zero
trace in the log.

**Not exercised by live playback:** the film/episode kinds and chapter parsing. Both are
unit-verified; no chaptered video and no feature-length film played during the session.

---

## v0.11.0 one switch per question (2026-08-10)

Settings, not detection. No rule about what counts as a listen changed, no identity check was
weakened, no ad guard was touched, and no threshold moved. What changed is which questions the app
asks the person using it.

### 1. A setting is a policy, not a mechanism

Four switches went away — `Native YouTube`, `Native YouTube Music`, `Look videos up`,
`Count picture in picture` — and one arrived: **`YouTube scrobbling`**.

None of the four was a policy anyone holds. *Which detector runs*, *whether YouTube Music needs
different metadata handling*, *whether identity needs a fetch to be provable*, and *whether PiP needs
its own measurement path* are things this app should decide for itself. The question a person
actually has an opinion about is "do you scrobble my YouTube", and it is now the only one asked.

**Nothing underneath was removed.** `Settings.enrichment` and `Settings.pipInference` became
read-through properties of the master, so every call site in `ScrobbleEngine` and
`NativeShortsAccessibilityService` is untouched. Both native packages keep their own entry in the
app allowlist and their own **epoch**, which is what makes an opt-out a hard boundary for
finalization already running on the IO dispatcher. Three field rounds hardened those epochs;
simplifying a settings screen is not a reason to spend them.

### 2. The switch that did not exist

Before v0.11.0 `YouTubeProbe.acceptsPackage` returned `true` for the browser packages
unconditionally. Someone could switch off both native sources and still be scrobbling YouTube,
because the setting that would have stopped it was never written. The master switch gates all three
surfaces, and `isSnapshotCurrent` refuses browser snapshots too while it is off.

### 3. Off is a boundary, not a pause

Turning it off must never be what *causes* a broadcast. On the way through, `setYouTubeScrobbling`
bumps both native epochs, `refreshTargets` sweeps every package that is no longer a source —
**including the browsers, which carry no epoch of their own** — and any part-played track is
disposed with `finalize = false`. Deferred play time in `TrackProgressCarry` is dropped in the same
sweep, so a listen cannot be resurrected by switching the source back on inside its continuation
window.

The address-bar watcher stops scanning as well. With YouTube scrobbling off there is nothing it
could legitimately be reading, and scanning a browser for evidence nobody will use is precisely the
cost the switch exists to remove.

### 4. One switch over one log file

`Diagnostic logging` is deleted. It wrote the same file as `Event log` on a 15-day timer and
**overrode it**, so the question "is RustedWax logging?" had two answers and the wrong one was the
one on screen. `Event log` already did everything it did, permanently and legibly. Its stored
deadline is deleted on upgrade rather than left behind.

### 5. A changed default may not rewrite a decision

Three fresh-install defaults changed: `Event log` on → **off**, `Short clips` on → **off**,
`Disable Shorts` off → **on**. All three are stored as "absent means the default", so shipping the
new defaults alone would have silently flipped all three for everyone upgrading.

`SettingsMigration` writes the **old** defaults down explicitly for any install that already exists,
so "absent" can only ever mean a fresh install afterwards. A value the user actually chose is never
touched.

### 6. How the four switches migrate into one

Browser YouTube was never a setting, so **every** existing install was already scrobbling YouTube
from at least one surface: there is no such thing as a user who had YouTube off. The unified switch
therefore migrates to **on** for everyone, and both halves of that are stated rather than assumed:

- **Nobody loses a source.** Migrating to off for an install whose *native* switches happened to be
  off would also silence browser YouTube — the one surface it definitely was using. A silent stop is
  the worse failure.
- **Somebody may gain one.** An install that had explicitly refused native YouTube or YouTube Music
  now accepts them, because the unified switch has no position meaning "browser but not native".
  That is a real expansion, it is recorded here and in the release notes, it is one tap to undo, and
  nothing is broadcast by it on its own: `autoScrobble` remains a separate opt-in, still off by
  default, and still requires a saved posting key.

### 7. A row links to the video it proved, or to nothing

On **History** and **Not logged**, the thumbnail and the title open the video.
`YouTubeProbe.canonicalWatchUrl` builds a link only from an exact eleven-character id — the same bar
the payload's `url` field is held to. A row without one is inert.

That is the point rather than a shortfall. The alternative is a YouTube search for the title, and
for the duplicate uploads and re-uploads this app spends most of its effort telling apart, that would
open the wrong video and look authoritative doing it. Most **Not logged** rows have no id precisely
because nothing proved one; they stay inert.

Which id counts as proven is decided in one place. `SkipRecord.videoId` used to read
`session.confirmed?.videoId`, which is only populated where the exact id arrived on the snapshot —
from a MediaSession field or the address bar. A native track identified through watch history or the
resolver has the same proof, but it lives in a local inside the finalize coroutine, so on the YouTube
app **every** Not-logged row was inert. The five skip paths downstream of resolution now pass the
corroborated id; the prefilter paths above it still pass nothing, because before resolution nothing
has been proven. `resolverContext.preResolvedNativeVideoId` is deliberately not used for this: its
own contract is "memory-only carry authority; finalization must re-fetch it", and linking from it
would publish an id the app's rules do not yet accept.

### 8. The animation waits for the app, not the other way round

The record turns while startup opens the key vault, prunes the dedup ledger and counts the send
queue — work that used to run on the main thread with the window empty behind it. The screen is
swapped the instant that work returns, so a warm start may never show it. Measured on the field
device: **636 ms** on a cold start.

`rustedwax_mark.png` bakes the wordmark into the record, so `design/split_logo.py` splits it into a
turning layer (grooves, rings, label and its gloss) and a still one (the lettering, the tonearm, the
wax drips, the rim). Drawn over each other at rest they reproduce the original mark pixel for pixel,
which the script asserts. The gloss on the label is load-bearing: a groove pattern is a solid of
revolution, so a record made only of grooves spins without anything on screen appearing to move.

### Proven by

Version code 57 on the field build, <redacted-device-model>:

| | |
| --- | --- |
| `tx e0f3a19268a5342f1ca27f2d081beb5d854863d4` | native YouTube app → watch-history identity → enrichment → `kind: song`, `app: rustedwax/0.11.0`, confirmed in a block |
| `tx 9468e850ff97e713a312a6c6eedee97afcaeef43` | the **browser** surface, Chrome → address-bar id → `kind: video`, confirmed in a block |
| `tx ce9aea51ac12c7d1369825bdd31fd7aae3814079` | the final binary, native app again, `kind: song` at 92% |

Plus, on the same device:

- A full YouTube play with the switch **off** producing **zero** log lines — not a session, not a
  package name, not a count.
- An in-flight native track discarded on opt-out: `source epoch changed — track discarded`, with no
  `[finalize]` and no broadcast.
- The migration on the owner's own v0.9.19 install: `disableShorts=false` preserved,
  `eventLogging=true` and `shortClipScrobbling=true` pinned at their pre-v0.11.0 defaults,
  `youtubeScrobbling=true` written, the superseded `enrichment` key deleted,
  `settingsSchemaVersion=1` stamped.
- A History row opening `dQw4w9WgXcQ` in the YouTube app from both the thumbnail and the title.
- A **Not logged** row whose id was proven after resolution opening `jNQXAC9IVRw`, while three rows
  beside it whose ids were never proven stayed inert under the same tap.
- `Not logged` tapped while clipped, scrolling itself fully into view.
- A cold start reporting `ready in 636ms`, with the lettering pixel-identical across two frames of
  the animation while the label's gloss moved.

---

## v0.11.0a a settings row is not a telemetry readout (2026-08-10)

Three defects, one root. Reported as a flicker; the flicker was the least of it.

### 1. A row's body is constant for a given state

`Foreground Shorts evidence` interpolated `NativeShortsObserver.Status.exactReason`, a
per-observation diagnostic that turns over several times a second. Different lengths meant a
different card height at that rate, so the settings list juddered whenever a Short was on screen —
worst in picture-in-picture, where the observer loses and re-finds the player structure continuously.

Live status now goes in `SettingRow`'s `liveNote`: one line, ellipsized, fixed height, so a change
cannot move anything below it. It passes through `settledText`, which holds a value until a new one
has survived 1.5 seconds — measured against the real cadence, which alternates at roughly one second.

### 2. "Not playing" and "playing where I cannot read it" are different answers

`completePlayerProof == false` covers both, and saying "No Shorts player on screen" for both reported
a working PiP listen as a detection failure. YouTube draws no title, no handle and no seekbar in PiP;
unreadable is the *expected* state there, and the time is still credited from wall-clock.

`Status.inferredPlaying` now publishes what every event already carried, and the note distinguishes
reading / inferring / idle. Additive only: `SessionProbe` consumes events, not `Status`, so no
detection path changed.

### 3. A claim that outlived what it described

The same row said "PiP and background time are not counted" — written before picture-in-picture
inference existed, left unrevised, and false. `tx 9a53ebdf…` and `tx b14d00fa…` are Shorts credited
in PiP. It sat one line above a note saying the opposite.

**Nothing about detection, measurement or the payload changed in this correction.** The Shorts
pipeline was never broken; the sentences describing it were.

### Proven by

Version code 57, <redacted-device-model>, all after the correction:

| | |
| --- | --- |
| `tx e85183775643fc8e25651bca9132e479940a0e22` | fullscreen Short, 17s of 17s, measured |
| `tx b14d00fa6d25bca8c002596fe617600e05239a7c` | fullscreen → PiP mid-listen, 4s measured + 17s inferred |
| `tx 6bc4cda71b9631062e85386cb3bdacbad7129e06` | native `/watch`, `kind: song`, 61% — the non-Shorts path, unchanged |
| `tx c45cd5d92107271f3c20be31be6cecc4a62e179c` | native `/watch`, `kind: video` |

Plus ten screenshots one second apart, byte-identical across the settings region, while the log shows
a second of inferred time credited on every one of them; the idle state reading `No Shorts player on
screen` and holding still for five more; dedup refusing the same looping Short three times; and the
same Despacito listen refused a second time from **Chrome** after the native one had claimed it
(`already scrobbled [despacito ft. daddy yankee|luis fonsi|496222]`), which is the dedup ledger doing
its job across two different sources.

---

## v0.11.0b evidence is not discarded for failing to repeat itself (2026-08-10)

Reported as one VEVO video refusing to scrobble in Brave. Four rules refused it in turn, and every
one of them threw away something the app had already proven.

### 1. A listing byline is a display name, not the uploader

A search card and a playlist row render a VEVO upload's owner as the Official Artist Channel —
`Bring Me The Horizon` — while the web player's `MediaSession` and the video's own page both name
the uploading channel, `BMTHOfficialVEVO`. Suffix stripping cannot reconcile them: `BMTHOfficialVEVO`
reduces to `bmth`, and no rule turns that into `bringmethehorizon`.

**When a listing's title and duration agree and only the owner name disagrees, the channel is
decided from that candidate's own watch page.** Title, channel and duration must all still agree
there, exactly one candidate may survive, and the page budget is the existing eight. A same-titled,
same-length cover by a genuinely different channel enters the dispute set and its own page refuses
it, which is where that refusal belongs.

Applies to both listing routes. A card whose byline already agrees is not in dispute and costs no
fetch.

### 2. A metadata bundle that omits DURATION says nothing about the length

YouTube's web player republishes a track with `DURATION` unset as it ends, and intermittently while
it plays. The session's established length — the value `TrackIdentity.refinedWith` already keeps,
and the thing that decides those updates describe the same track — is what the snapshot and the
latch corroboration read when the current bundle is silent. **A published length always wins.**

Without this a complete listen finalizes as `played 247s of 0s`: no percentage to clear a threshold
with, and no duration to resolve an id by.

### 3. Not confirming an id is not the same as disproving it

A latched id is dropped when the page cannot corroborate it, in both cases. Only a **contradiction**
— the page names a different title, or a duration that disagrees — follows the track as a rejection.
Evidence that is merely too weak to establish the id is not held against it, so a later pass that
has the duration, or a route that proves the id outright, is still free to establish it.

### 4. What licenses the channel alias is the route, not the player

Enriched facts fetched *for the id a route returned* describe that video's one uploader, so a second
spelling of the channel is not a second opinion. This was gated on native playback; nothing in the
argument is about native playback. It is now gated on **an id some route uniquely resolved by
matching a channel of its own against the session**. An id the address bar merely named does not
qualify and is unchanged in strictness.

### Proven by

Version code 57, <redacted-device-model>, Brave, all after the fixes. Full record in `<redacted-private-provenance>`.

| | |
| --- | --- |
| `tx badfb3faa8437f35041ccca89ed88063a32ca4a4` | the reported video — Happy Song, `GBRAnuT48qo`, 100%, disputed byline through search |
| `tx 083bffd9db19105f8290d87514e229ef794a303b` | a second VEVO upload through the same route — Teardrops, `L5uV3gmOH9g` |
| `tx f7d0a980c2ebc02ecb06e5281219a7c65d0fa47c` | **non-VEVO control** — Follow You on RockHype, `SZj34yxyq0c`, 100%, address-bar latch |
| `tx dd11ef481deb41ea71a342ed12a6fb4d1718b9df` | second ordinary listen, resolved and scrobbled at 65% |

Plus `played 240s of 236s` and `played 242s of 231s` where the same conditions previously produced
`of 0s`; four ordinary videos resolving normally and being skipped only by the 60% threshold; and
the identity guards still refusing a next video's facts against an ending track.

---

## v0.11.0c a playlist entry is not an ad break (2026-08-11)

Reported as a 2Pac playlist in a minimised Brave refusing track after track with "a visible YouTube
ad could not be excluded" — *"although what add? there was no add"*.

### 1. A playlist is context and outlives the address bar

`UrlEvidence` keeps the last `list=` per package for three hours precisely because the bar stops
naming individual videos within seconds while a playlist keeps advancing. **That store must be read.**
It had no caller, so `resolverContext.playlistId` came only from the five-minute video-id reading and
the playlist route — the exact one — went unavailable five minutes after the browser was last on
screen. Measured 2026-08-11: the bar named the playlist at 23:44 and by 00:00 a track that is entry
41 of it went to history, then six searches, then a refusal.

### 2. An id from the playlist being played carries its own ad exclusion

A browser publishes an advertisement as a track of its own, with the advertiser's title and channel
(2026-08-02, `See Spike launch his online business with Namecheap` / `Namecheap`, 29s of 30s). An id
the resolver recovered **alone** is therefore not evidence that content rather than an ad was
measured, and that refusal stands unchanged.

**An id matched against the bounded entry list of the playlist being played is different in kind.**
YouTube does not put interstitial ads into a viewer's playlist, so a session whose title, channel and
duration match an entry of it is not an ad break. This is a fact about provenance, not a judgement
about content: it infers nothing from id, brand, title, duration, category, listed state, view counts
or History, and the Namecheap ad would have failed the playlist route rather than passed it.

The exception reaches exactly one gate. The explicit ad-label veto, the floors, the thresholds, dedup
and every identity check are untouched by it.

### 3. A refusal may not read as an accusation

The rule refuses because nothing could look, not because something was seen. The reason now says the
browser was never on screen, that the ad check therefore never ran, and — in words — that this is not
a claim about the video.

### Proven by

Version code 57, <redacted-device-model>, Brave **minimised throughout**. Full record in
`<redacted-private-path>`.

| | |
| --- | --- |
| `tx 9da8e8679d32f6dd45e8cd39a6a8cdb040df95a1` | the reported track, refused at 00:00, scrobbled from a backgrounded browser |
| `tx b6c6abd15005da8923142ff7211b1464aa2f90e3` | the next track of the same playlist, same conditions |
| `tx 4f563cf78818efc8c44c9bbabb82465bbe346b22` | control — ordinary browser video via the address bar |
| `tx de3dafb809194c2da7e73968ce79cd6125f32fc4` | control — the native YouTube app |

Plus, minutes later on the same phone, a resolver-only track that was **not** from the playlist
refused under the same conditions; dedup refusing the same song again; listens refused at 52% and
54%; and a stale three-hour playlist correctly matching nothing for an unrelated video.

---

## v0.11.0d a browser tab is not a track (2026-08-11)

Reported as three separate things — a stuck "The address bar has gone quiet" card that no restart
would clear, a run of "video id could not be verified against the finalized snapshot", and a
complete listen refused with "the browser was never on screen while this track played". The first
two are one defect seen from both ends. The third is the rule working, on cases the defect was
manufacturing.

### 1. A field a browser fills with its own name is absence, not an answer

A browser MediaSession takes its fields from the page's `navigator.mediaSession.metadata` when the
page has set them, and from the **tab** when it has not. The tab's answer is the document title and
the origin:

```
TITLE  = "Bring Me The Horizon - Sleepwalking - YouTube"
ARTIST = "m.youtube.com"          unset: … DURATION …        pos=-1ms
```

129 bundles with the origin as the artist and 43 with a document title, in one 2026-08-11 log.

- A **YouTube host in the artist field** is read as absence. It is not a different channel, and the
  corroborator already declines to compare a channel it has no left-hand side for. Only a *host* is
  removed; the channel actually named "YouTube" keeps its name.
- A **title that is the site's own name**, bare or as a ` - YouTube` suffix, names the tab. Tested
  as a whole value and as an exact suffix, never as a substring: "Bring Me The Horizon - Youtopia"
  is a song and survives.

### 2. A tab-title bundle ends a track and starts none

The outgoing track finalizes against the metadata it was playing under, so the entry keeps its real
title, channel and length. After that the session is **nameless and unmeasured** until the browser
names something again. The elapsed time in that gap belongs to neither video and is credited to
neither.

Both halves are load-bearing. Letting the bundle *become* a track is how
`"2Pac - Street Fame - YouTube"` accumulated 22,425 s overnight, `"YouTube"` collected 580 s, and
`"Sleepwalking - YouTube"` took the 102 seconds the next video actually played — each then
finalizing against whichever id the address bar had latched by then. Holding the outgoing track
through the gap instead would credit it the same seconds under a different name.

The nameless state is also entered at **Watch construction**, not only from a metadata callback.
Chromium recreates its MediaSession constantly; a replacement built while the tab bundle is already
installed must not start measuring against a track nobody has named.

### 3. An artist nobody published does not end a track

`TrackIdentity` treats an absent artist the way it already treats an absent duration: silence, and a
refinement when a name arrives. Two *published* names that differ are still two tracks. Without
this, a channel dropping out mid-track ended the listen and started a duplicate — and a 187-second
play whose channel Brave never published (`text=""` throughout) was refused for contradicting a
hostname.

### 4. The quiet-bar warning is raised only by the evidence it names

The card names one cause and prescribes one fix, so a finalize that ends without an id counts toward
it **only when the address bar said nothing about that track**. A bar that named the video is not a
quiet bar, whatever happened to the id afterwards — the counter clears, which is also what makes the
card dismissable by the one observation that disproves it. A corroboration refusal is a different
failure and is already reported as itself in Not logged.

### 5. A page's shorter name for an id a route already matched is not weak evidence

`WEAK_SHORT_CANONICAL_CORE` exists so a one-token name cannot **establish** an id. It is not
establishing one when the id came from a route that matched this same title, channel and duration
against the session and the facts were fetched for that id: the shorter name is one video's other
rendering, exactly as with the channel alias, and it admits no new candidate. Relaxed on the
enriched-facts pass only, only for a uniquely resolved id whose own title agreed strongly, and only
with a corroborating duration. A page title that *contradicts* still refuses.

### 6. The uploader decides which side of a dash is the artist

Artist-first is a convention. `TitleParser` proved the uploader on the left of a dash and had no
mirror of that for the right, so `"Runaway - Linkin Park (Hybrid Theory)"` on the channel
`Linkin Park` went on chain as artist "Runaway". The right-hand proof now runs first and is
deliberately strict: the right side **with its bracketed groups removed** must exactly equal the
uploader, and the left must share no word with them. Brackets come off because
`"Linkin Park (Hybrid Theory)"` is an owner plus an album while `"Fractures (Trap Nation Release)"`
is a work whose qualifier merely mentions a channel. A shared word is not proof, so
`"Linkin Park Tribute - Linkin Park"` keeps its conventional reading.

### What did not change

> **Historical boundary, superseded by v0.11.0e and v0.11.0g below.** Mix queues gained a bounded
> direct route in v0.11.0e, and one uniquely corroborated ordinary lookup became eligible without a
> visible scan in v0.11.0g. Ambiguity, contradiction and literal ad evidence still refuse.

At the v0.11.0d checkpoint, `browserEvidenceUnavailableReason` was still fail-closed and background
listening depended on a public-playlist match or a prior visible scan. That historical behavior is
the subject of the proof below; the function and rule were removed after the later §v0.11.0g device
reproduction. Current Mix-queue misses and autoplay beyond a playlist may fall through to the
ordinary resolver, where exactly one finalized title+channel+duration match is eligible regardless
of window state. Ambiguity, contradiction and literal positive ad evidence still refuse.

### Proven by

Version code 57, <redacted-device-model>, Brave both foreground and minimised. Full record in
`<redacted-private-path>`.

| | |
| --- | --- |
| `tx 5e29c512fe74f4ea293d06bc5869ef0dabf20b9b` | Runaway — through an `ARTIST = m.youtube.com` transition, no phantom |
| `tx 9ed70a322a4b8a4c49a91db4b9d512e05e8e5a68` | Toxicity — foreground control |
| `tx 668dd3045a9ada7e16b3e2a8fa1bc9c4ccec5d93` | Chop Suey! — browser backgrounded mid-track |
| `tx 028267f40891bb032d170e9fbf52540b1f0bab98` | Sleepwalking — minimised, directly after a tab-title gap |
| `tx 988a6e7ac55cc52e81efc68ebc59db5f379a276f` | Avalanche — the §5 refusal, minimised |
| `tx 476529d2e1b10be051146d248cba024d9c7f788d` | Avalanche — its 160% second listen |
| `tx ac72513f96bddf05dbd853d310fcdeab0ac8ab90` | Runaway — §6, `Linkin Park — Runaway`, the right way round |
| `tx 4dd7f7fed96562027f6e4d9f866794c2a68a4b0f` | Runaway — its second listen |

Plus the tab-title transition reporting `played=0ms` through the gap and the next track starting at
33 ms; the outgoing track finalizing with its real title and length; and the two by-design refusals
above still refusing.

---

## v0.11.0e established final fields and bounded Mix queues (2026-08-11)

Three consecutive background Brave tracks reproduced `title, owner/channel and duration were not
all available` even though every field appeared while the track played. The final Chromium bundle
kept the title, replaced the channel with `m.youtube.com`, and dropped duration. `TrackIdentity`
retained the established fields, but `SessionProbe.snapshot()` re-read the final raw bundle for the
presentation fields and erased the owner. Finalized presentation now comes from that per-track
established identity, with the raw bundle only as a fallback for a field never established.

An `RD…` Mix now gets the same narrowly scoped ad exclusion as a public playlist when—and only
when—the watch page's `playlistPanelVideoRenderer` set contains exactly one title+channel+duration
match. Private `LL`/`WL` lists and a Mix queue miss still use search and remain subject to the
visible-ad evidence gate. On device, the first fully backgrounded Mix entry matched and scrobbled;
the following personalized entry was absent from the fetched 25-row set and still refused, proving
the Mix id itself is not a bypass.

That miss is the v0.11.0e result. v0.11.0g below later permits it only if the ordinary bounded
resolver independently returns one uniquely corroborated title+channel+duration candidate.

| | |
| --- | --- |
| `tx f067403fa4f075d92c418c18b36e8721dd2a99ce` | teardown regression control: owner and duration survived `ARTIST = m.youtube.com` |
| `tx 7650027df3370b0f0831aea9558320b5598cd3e9` | full background Mix listen resolved from the bounded queue |

---

## v0.11.0f a replay is not a UTC hour (2026-08-11)

Three Blasphemy listens began at 19:57:43Z, 20:21:08Z and 20:52:56Z. The first two scrobbled, while
the third ended above threshold and was refused as `already scrobbled this listen`. The playback
and resolver were healthy. The difference was only the dedup key: the first crossed into UTC hour
20, while the latter two both produced bucket `496244`.

The ledger answers whether two operations describe the same listen. `trackStartedAtEpochSec` is
already frozen for that purpose and is restored with carried progress when Android rebuilds a
session. The key therefore uses normalized title, normalized artist and that exact start. Manual
and automatic finalization of one listen still collide; a second viewing has a distinct start and
is eligible immediately. No clock boundary and no one-per-hour cooldown is part of the rule.

### Proven by

Version code 57, <redacted-device-model>, the old `496243` and `496244` claims left on-device. Two new Blasphemy
listens started at 21:08:54Z and 21:15:39Z in the same UTC hour and both block-confirmed. The ledger
holds two distinct `start:` claims beside both legacy claims:

| | |
| --- | --- |
| `tx 266a65d94cee3cbf42b1e1aed739fae8a6b7ab83` | first corrected-build claim; old buckets did not suppress it |
| `tx 97d265ba478b8bf8ca23d158db504c2891c27403` | fresh 281/280-second replay, seven minutes later in the same UTC hour |

---

## v0.11.0g a missing visible scan is not a missing identity (2026-08-11)

Two Sleepwalking refusals exposed different defects. In the exact-URL run, Chromium published the
outgoing Visions id `QuQW1vkDA1c` on the first callback, then the correct Sleepwalking id
`lir3dzYIhz0`. The first id was rejected, but `ResolverContext.observedVideoId` was immutable, so the
correct later generation could never corroborate the watch page's shorter `Sleepwalking` title. A
rejected observed id now yields to the first later non-rejected concrete URL. A still-valid observed
id remains frozen, so the next track cannot steal the ending track's identity.

The second refusal was the policy boundary, not a failed lookup: the finalized title, channel and
duration uniquely resolved the video, but the browser had supplied no current-track accessibility
scan. That rule discarded real background listens even though RustedWax had identified them. It is
superseded. A unique bounded resolver match remains eligible while Brave is minimized or fully
backgrounded. Missing, ambiguous, partial and contradictory resolution still refuse. Literal
positive ad evidence remains an earlier hard veto; this change does not infer that an item is or is
not an ad from its title, channel, category or listed state.

The boundary is structural rather than another exception flag. `FinalizedVideoIdentityContract`
admits only a matching frozen exact id or a resolver result marked unique after finalized-track
corroboration. `ScrobbleRules` has no browser-visibility, accessibility-coverage,
playlist-provenance or resolver-route parameters, and the removed manual-path coverage branch cannot
reintroduce a different answer. Identity is complete before duration, threshold, Short, mute, dedup
or payload rules run.

Native YouTube keeps its separate identity path and receives the same finalized resolver result; it
does not depend on browser accessibility. The same installed version-code-57 APK was exercised on a
Samsung <redacted-device-model> through both paths:

| | |
| --- | --- |
| `tx 0561da2a3a1e8dd1ab5a25cf5c5b8816547e365d` | Brave fully backgrounded, RustedWax process restarted so it held no address-bar id; unique lookup resolved `7i_2TJv96Wk` and block-confirmed |
| `tx 4e7ef050ba4a1c670fb703a1e9285f43aa48456d` | native YouTube app, no address bar; the same id resolved and block-confirmed |

The source gate passed 704 tests with zero failures/errors/skips, debug assembly and lint. The exact
Sleepwalking stale-id transition and the no-replacement-while-valid boundary are regression-tested.

---

## v0.11.0h one identity contract, enforced once (2026-08-11)

v0.11.0g corrected runtime behavior but still represented the old and new policies as combinations
of optional booleans inside `ScrobbleRules`. The automatic path supplied `verifiedLookup`; the manual
path separately called the old coverage veto; historical plans and field records continued to read
like current specifications. That was the same structural condition that let related identity bugs
return under different symptoms.

Identity authority now runs once, before enrichment, scrobble rules or payload construction.
`FinalizedVideoIdentityContract` accepts only a resolver id equal to the frozen exact id, or—when no
exact id exists—one bounded resolver result marked unique after finalized-track corroboration.
`ScrobbleRules` accepts no browser visibility, accessibility coverage, playlist provenance or route
booleans. `MainActivity` has no separate coverage decision. A literal positive ad signal remains a
central unconditional veto after identity authority succeeds.

[Documentation/Product/IDENTITY.md](IDENTITY.md#current-identity-contract) is the sole normative video-id prose.
Active documentation links or restates it; preserved historical contracts explicitly identify the
v0.11.0g supersession. `IdentityContractAlignmentTest` makes those documentation/source relationships
part of the ordinary suite instead of depending on memory.

The full uncached gate passed 712 tests with zero failures/errors/skips, debug assembly and lint.
Local and installed APK SHA-256 matched at
`5732a87bc95f8ac05c2655d8cb7735a9cb0f5037bb8f512521d9074d45cf7a1a`. The installed build
block-confirmed the control through backgrounded Brave as tx
`68a04a763aa522848c2f4ca3b5e5eaa02c4270fa`, then through native YouTube's unique finalized lookup
as tx `67ae3488bc04cabe97077440c4531c99c2194164`.

---

## v0.11.0i rebuild source proof and available-field identity (2026-08-11)

The v0.11.0h artifact still relied on `onNotificationPosted` to populate browser source proof. A
real process kill while Brave continued in the background left Android's existing media
notification active but produced no new callback, so the replacement process saw the MediaSession
and still refused it as `source not proven YouTube`. `RustedWaxListenerService.startProbe()` now
clears stale hints and replays currently active notifications through the same target-package and
media-style privacy filters before `SessionProbe.start()`. The browser package alone never proves
YouTube, and non-target/non-media notifications remain unread.

The replay then proved the site but Chromium's rebuilt MediaSession omitted duration. Current
identity therefore operates on available finalized fields: a bounded route may establish one id
from an exact finalized title plus that candidate's canonical-page channel when duration is absent.
Watch history and search retain a single-id requirement; search re-fetches at most eight exact-title
candidates. Two same-title history rows, two surviving canonical pages, a different title/channel,
or an over-budget set refuse. `FinalizedVideoIdentityContract` remains the only authority gate and
positive ad evidence remains independent.

The uncached source gate passed 716 tests, debug assembly and lint. Local and installed final APKs
matched SHA-256 `f5139ecc4d5b12900cc0653dfb342b3c5b9d6ae9534b721a78fd8750717ed999`.
With Brave backgrounded and the RustedWax process actually killed, the replacement process replayed
the active notification before discovering the no-id/no-duration session, uniquely resolved
`7i_2TJv96Wk`, and block-confirmed tx `57dbe74b2e48084eeb31a4a0bc6baeb0f01fcbeb`.
The byte-for-byte final artifact repeated the full control as Brave tx
`3d5347b1533817428f3c770c8cfdc49ac0835cbb`, then block-confirmed native YouTube at 73% with no
exact MediaSession id as tx `592f44cd57934d9789a4fd89f5048da44160aac4`.

---

## v0.11.0j consecutive-playlist identity is a sequence, not a single-video lookup (2026-08-11)

The reported `Tupac heartz of men` / `Ethereum 2.0` play was absent from signed-in history and open
search while Brave was fully backgrounded, and the last observed URL still named an older playlist.
Single-video device controls could not exercise that evidence gap. Every finalized target now leaves
an ordered per-package placeholder, filled only after the central identity and contradiction gates
verify its id. An unresolved middle track breaks the chain.

For a current id-less play, the immediately preceding two verified ids discover bounded public-list
candidates. Their titles are discovery hints only. A list qualifies only if the exact ids occur in
adjacent rows and playback order; only the immediate following row is eligible. Across every
qualifying list, exactly one video id must match the finalized title/channel/duration. Zero or two
ids refuse; multiple lists corroborating the same next id do not create false ambiguity.

The same installed APK proved the reported upload `4ZnHHd3i8I4` after both sources traversed the
first three entries: off-screen Brave block-confirmed tx
`d54244f376538597c8ff34cc2a2d497424626e6e`, and native YouTube block-confirmed tx
`305c434c526fac968b87aac5c8871e0e78aaf9c7`. The exact predecessor and intermediate transaction
record is in `<redacted-private-path>` §14 and `TESTING.md` §43.

## v0.11.0k a channel mention is presentation, and a filtered feed is not the feed (2026-08-17)

Field record: `<redacted-private-path>`. Four defects, three fixed and one
recorded open. Device-proven on the Galaxy A36 against APK MD5 `f4c8c20e…` and `8ca5aed6…`; suite at
1 184 tests / 112 classes / 0 failures.

### 1. A bounded mention difference may agree at the search gate

`SearchResultsParser.identityMatches` and `hasNoIdentityContradiction` gated on `titleKey` equality,
which folds `@` to a space. YouTube publishes one channel mention two ways — the MediaSession said
`@Sexyy Red` where the upload says `@SexyyRed` — so `sexyy red` never equalled `sexyyred` and the
correct card, first in the results with an exact channel and a one-second duration, was discarded
before channel or duration were consulted. Three plays were lost this way on 2026-08-17.

Both call sites now accept the semantic key **or** `VideoTitleMatcher.mentionSpellingOnly`. The
disagreement must be one contiguous run beginning at an `@` on both sides, with at least three
identical tokens around it and no more than a channel name's worth inside it. Channel and duration
remain independently mandatory, `Cover by @alice` still contradicts `Cover by @bob`, and two uploads
that both clear the rule stay ambiguous and are both refused. The other seven `shortTitleMatches`
callers are unchanged.

### 2. Anchoring a difference is not bounding it

The 2026-08-16 mention rule fixes where a disagreement starts and not where it stops, so a run
beginning at a mention may reach across the rest of the title —
`@SexyyRed - Different Song Entirely` against `@Sexyy Red - Slut Me Out Remix` — and be graded
presentation. That is tolerable when corroborating an id whose provenance is already established and
intolerable when *selecting* a search result.

`VideoTitleMatcher.compare` keeps the uncapped rule and is byte-identical to before.
`mentionSpellingOnly` adds `MAX_MENTION_RUN_TOKENS = 3`, the longest channel name observed in a
mention run. Any future caller for which the mention test is the primary title test must use the
capped predicate.

### 3. A search query may not carry a name the video does not have

One of the three losses was never reachable: its MediaSession published the display name
`@Maggie Rudisill` where the upload says `@spitcamuniversity`, so all six generated queries searched
words that appear nowhere on the video and the target was absent from every result page.

`VideoIdResolver.searchQueries` now also offers the title truncated at its first `@`, and only when
the title contains one — a title without a mention generates byte-identical queries in the same
order. The remainder must still be three words, so a query that is only an artist name is never
issued. `MAX_SEARCH_QUERIES` stays at six: this adds a variant, not budget. It adds a *query*, never
a match; every candidate it surfaces still clears the unchanged title+channel+duration gate.

### 4. Shorts live behind a history filter, and the unfiltered feed is not the record

YouTube moved Shorts out of the default watch-history feed. The page now renders five filter chips —
All, Videos, Shorts, Podcasts, Music — each a `browseId: FEhistory` differing only by `params`, and
selecting one rewrites the DOM without changing the URL or `ytInitialData`. The authenticated `GET`
of `HISTORY_URL` therefore returned 200 ordinary entries and zero `shortsLockupViewModel`, while the
same session's filtered response returned 200 Shorts. Every Short identification failed for want of
a feed to match against.

`WatchHistoryParser.collectShorts` was already correct and is unchanged. `fetchHistory` accepts an
optional filter appended as `?bp=<token>`; the pre-existing host assertion still runs and a query
string cannot change the host. `shortsFromFilteredFeed` issues that one extra request **only when the
unfiltered parse yields zero Shorts**, so an ordinary history read costs what it always did, ordinary
entries are never taken from the filtered response, and the route stops firing by itself if YouTube
restores inline Shorts. The token is read from the downloaded page, never hardcoded, and is accepted
only from a chip whose own endpoint is `FEhistory`. Missing chip, failed fetch, signed-out redirect
or unreadable parse each yield an empty list.

The filtered request is a plain authenticated `GET`. The `POST /youtubei/v1/browse` route the chip
itself uses requires `Authorization: SAPISIDHASH`, which would have meant new credential-signing
logic against the vault; that was not written. Nothing on this path logs the cookie or the token —
only a count.

### 5. Recorded open: music-client enrichment may not corroborate a video title

When a watch-page fetch fails, enrichment falls back to the YouTube Music client, which returns the
*song* name rather than the video title. That value is cached and later contradicts the finalized
snapshot, so a correct id — recovered from watch history — is refused. Reproduced twice on device
with `aZaxQG3ggng`.

This is unfixed. It fails closed, which is the correct trade, but costs a legitimate scrobble on any
video whose watch page cannot be fetched. Diagnostic signature: an
`enriched watch facts title … contradicts ended title …` refusal preceded by
`watch page unavailable — music client only`.

### 6. Evidence discipline

Two conclusions in this session were reached from strong evidence and were wrong. A count of zero in
our own response is evidence about the request we sent, not about what the upstream service holds;
it must be compared against the other view before the data is called absent. Absence of an id from
the event log is likewise not absence from the candidate set, because candidate ids are never
printed.

## v0.11.0l one viewing is one listen across a blinking title and a picture-in-picture handback (2026-08-19)

Field record: `<redacted-private-path>`. Two defects fixed, one reported defect
disproved, one change added and withdrawn. Galaxy A36, APK MD5 `91c0355c…`, `e772c3ac…` and the
checkpoint `765f349c…`; suite at 1 200 tests / 0 failures.

### 1. A footer title that goes missing does not start a new Short

Holding a Short at 2× takes YouTube's footer off screen, and it returns in two steps — owner handle
first, title a frame or two later. `NativeShortParser` deliberately does not refuse a missing title,
so a complete `Organic` observation arrives carrying `title = null`, and the title is part of the
identity key. `9s38_ONe2mE` was watched once, `2s → 119s of 178s`, and finalized three times — 84 s,
3 s, 24 s — logged as 48 %, 2 % and 14 %. The listen was at 66 %.

`ForegroundShortTracker.isTitleBlink` continues the listen only where **exactly one side has no
title** and the Short is otherwise strongly continuous: same owner handle, same source epoch, same
length or a length learned mid-viewing, a position that has not gone backwards and has not moved
further than `MAX_PLAYBACK_RATE` could carry it, all inside `TITLE_BLINK_WINDOW_MS`. A blink that
reveals a title adopts it; one that hides a title keeps what was already proven. Two different
non-null titles are still two Shorts, and the title is not removed from the identity key.

The seekbar-less `UnmeasuredObservation` path carries the same shape and is deliberately unchanged;
no evidence of a defect on it exists.

### 2. A Short handed back from picture-in-picture is credited what its own bar proves

Progress was accumulated only from deltas the tracker personally witnessed. `8Bh_XF6-48E` was read to
`8s of 59s`, spent 23 s in picture-in-picture, and returned at `41s of 59s` still playing; the 33
seconds its bar accounted for became 23 seconds of inference and the listen finalized at 54 %.

`pipHandbackSeconds` credits the returning position minus what the inference has already been paid
for, capped so measured plus inferred can never exceed the Short's length. It is forward only, runs
on both the in-place `frozenForMissingProof` recovery and the post-grace `resumeFor` path, and only
where the stretch that just ended was the measured picture-in-picture signature. Every other freeze
adopts the new position and credits nothing. A jump beyond what playback could have carried remains a
seek and earns nothing.

This is not general position credit, and no blanket first-position credit was added.

### 3. A stale Not logged stub is preferable to changing shared lifecycle timing

A widened no-credit grace for the handback (`PIP_HANDBACK_GRACE_MS`) was implemented and withdrawn.
It required a new marker because `Active.progressSurfaceLost` cannot identify a handback — it is
equally true of a Short YouTube drew no seekbar for — and it bought only the suppression of a
duplicate Not logged row. The listen loses nothing without it, because the credit is reclaimed on the
way back. The shared grace is unchanged at `MISSING_PROOF_GRACE_MS`.

### 4. The picture-in-picture duration cap is not a defect

"Picture-in-picture credits nothing about half the time" was reported, and the specific claim that it
depends on which app the PiP window sits over was disproved by `Phase3Telemetry.pipEvidence`: the
paired window-plus-audio evidence read `paired=true` in every sample of both cases.

Shorts auto-loop and every loop is credited, so `playedSeconds` grows without bound. A Short that has
been round once enters picture-in-picture with `measuredMs >= durationMs`, so `PipPlaybackInference`
has no room and is exhausted from its first observation, and the grace is not held open either. That
cap is the same rule that banks a full listen, so anything it silences has already been scored and
broadcast. Entered before a loop, a 26-second Short was carried to `26s of 26s` entirely by inference.

No production change was made for this finding, and none should be: crediting past the cap would let
a looping Short in picture-in-picture reach any threshold.

### 5. Evidence discipline

Three readings in this session were confident and wrong. PiP crediting was blamed on which app the
window sat over, before telemetry showed the evidence pairing was healthy in both. Three
finalizations of `9s38_ONe2mE` on device were read as the split surviving, when the same `start=`
and the same title in all three showed one listen resumed and re-reported. A diagnostic that appended
its note with `?.plus` was suspected of hiding every blink; that was a real instrumentation defect,
fixed, and still not the explanation. A rule that cannot be made to fire is not thereby proven to
fire.

## v0.11.1 destinations, not tab numbers, and a log that ends (2026-08-25)

Eight bounded changes to navigation, settings, appearance and the event log. Nothing in detection,
identity, measurement, finalization, payload construction, deduplication or transport was touched,
and the Phase 9 architecture boundaries are unchanged.

### 1. Navigation is by destination, not by index

The screen held a tab number and dispatched on it. That is safe only while the list of tabs never
changes — and hiding `Log` changes it, silently renumbering everything after it, so one stored
selection meant `Log` on one install and `Settings` on another.

Destinations are now named: `Now`, `History`, `Not logged`, `Log` (conditional), `Settings`. Adding
or removing one cannot move another. A selection that stops existing resolves to `Settings`, which
is where the switch that removed it lives — not to an arbitrary neighbour. Pages swipe horizontally
in both directions, and a swipe and a tab tap move the same state, so the strip and the pager cannot
disagree. The strip follows the settled page only, so a half-completed drag does not move the
selection under the finger. Vertical scrolling inside each page, and the switches themselves, are
unaffected: the pager is a different axis and the controls are its children.

### 2. The Account tab became a Settings row

For anyone who had already saved a key, the `Account` tab was a username, a public key and two
buttons nobody presses twice — occupying a sixth of the strip permanently. It is a *setting*, read
alongside the YouTube account setting, so it is now **Settings › Hive account**, directly beside
**YouTube watch history**.

Nothing was dropped in the move. Validate-and-save, the connected identity, the test broadcast,
`Forget key`, both disclosures (the posting-key security note and the unaffiliated-software note),
the busy indicator and the error line are all present. Key secrecy is unchanged: the field is
masked, the entered key goes straight to the encrypted vault, and the only value ever displayed is
the derived `STM…` **public** posting key.

> **Superseded by v0.11.1b below:** the synthetic test broadcast and the
> unaffiliated-software note were subsequently removed. Developer mode now
> offers a read-only Hive connection check; validate-and-save, connected
> identity, `Forget key`, the posting-key disclosure, busy state and error state
> remain.

### 3. Log and Export exist only while a log does

With `Event log` off there is no file and no ring buffer, so a `Log` destination would be an empty
screen with an `Export` button that could only attach an empty file. Both are absent instead.
Turning the switch off still erases what is already on disk — the setting says nothing is being
recorded, and yesterday's log on disk would make that false the moment anyone looked.

### 4. Automatic scrobbling is first, and the restating card is gone

`Automatic scrobbling` is the switch people open Settings for, and it was fourth, below two rows
about evidence. It is now first. The card that headed the list — "Watching YouTube in Brave and
Chrome, the YouTube app, and YouTube Music" — restated the switch two rows below it and is removed.
The monitoring status it also carried is **not** lost: it is in the strip visible from every
destination, which is where a stop control belongs.

### 5. One Shorts switch, and an unproven Short is refused

`Short clips` is removed and its stored value (`shortClipScrobbling`) is deleted by a versioned
migration. `Disable Shorts` is preserved exactly as the user left it, and still defaults **on** for
a fresh install.

The floor is no longer a preference. With `Disable Shorts` off, a Short is admitted only when it is
proven public — `/shorts/` path, resolved watch page, explicitly listed — and then from 10 seconds.
Anything else fails closed: an explicit ad label, `isUnlisted`, an absent listed field, and a watch
page that did not resolve are all refusals at any length. Holding an unproven Short to the ordinary
30 seconds looked conservative and was not — it admitted anything unproven on length alone, which is
the shape of the v0.8.7 leak, where a 42-second unlisted creative cleared 30 seconds. Ordinary
`/watch` playback is unchanged at 30 seconds. See
[SCROBBLE_RULES.md](SCROBBLE_RULES.md#shorts).

### 6. The saved theme applies to the sign-in screens

`YouTubeSignInActivity` composed a bare `MaterialTheme {}`, so a phone set to the RustedWax dark
theme opened the watch-history sign-in in default Material purple-on-white. A sign-in screen that
looks like a different app is exactly the impression a screen asking for a Google password must not
give. Every window RustedWax owns now resolves `SYSTEM`/`LIGHT`/`DARK` through one shared wrapper
that also paints the window background and system bars. Google's own page inside the WebView is
Google's and is deliberately untouched.

### 7. No connected history session is now conspicuous

Without a connected session, a native YouTube video played outside a playlist can only be identified
by search; search often cannot separate two uploads of the same thing, identity fails closed, and
**nothing is logged**. That is the app working as designed and producing no entries, which from the
outside is indistinguishable from it being broken. Nothing said so.

A warning now sits above the destinations, visible from all of them, with a `Sign in` action. Where
the existing health evidence proves a specific cause it states that cause and its recovery instead:
an expired session offers `Sign in`; a paused history offers `Open YouTube history`; an unreadable
history page offers **no** action, because signing in again cannot fix a page that changed shape.

The mismatch case is deliberately the widest. The feed can prove that what this phone plays is not
reaching the connected account's history; it cannot prove *which* of signed-out, different-account
or incognito is the cause, and Android does not expose the YouTube app's account. So the copy offers
all three and asserts none. **RustedWax never names or guesses the YouTube app's account.** The
warning chooses its action from a typed `WatchHistoryHealth.Refusal`, never from the diagnostic
prose, which is displayed but never branched on.

### 8. An enabled event log ends

Measured on the field device on 2026-08-25, before this existed: **22,606,065 bytes across 240,631
lines**, reaching back days. Two problems, and the second is the serious one — a 22 MB file is not
diagnosable, and the log is a recording of what somebody watched, so an unbounded one is a
permanent, exportable, plain-text viewing history that grows for as long as the switch stays on.

An enabled log now retains at most the last **12 hours** or **512 KiB**, whichever binds first. The
UI list and the exported file are the same retained set. Pruning runs at startup and then on a bound
— 200 lines, 64 KiB, or 5 minutes, whichever comes first — never on every append, because a line is
written several times a second while a Short is on screen. The replacement is atomic: the pruned
file is staged beside the real one and renamed, so a process killed mid-prune leaves a whole log
rather than half of one. The clock is injected, so the whole policy is decidable in a unit test.

Lines are now stamped `yyyy-MM-dd HH:mm:ss.SSS`. **A line written before this has no date, so its
age cannot be established, and it is discarded rather than assumed recent** — "at most twelve hours"
is a promise about the file, and a line that cannot be shown to keep it fails closed like everything
else here. The cost is that the first launch after upgrading discards a legacy log. That is stated
rather than hidden; the alternative is a retention bound that quietly does not apply to the 22 MB
already on the device.

## v0.11.1a a chip in the footer is not a second title (2026-08-25)

Field record: [`<redacted-private-provenance>`](../Field-Reports/<redacted-private-provenance>).
Two defects fixed, one reported symptom confirmed as the existing contract working. Galaxy A36,
YouTube 21.33.322, APK SHA-256 `9fb0859f…`; suite at 1 369 tests / 0 failures. Nothing in
measurement, eligibility, payload construction, deduplication or transport was touched.

### 1. A label belongs to the widget that owns it

`dSYyRBKh4kA` was watched to 84 % in picture-in-picture and reached **neither History nor Not
logged**. It was measured correctly and finalized as `null — played 41s of 49s`: its title was never
read, so nothing could name it.

The Shorts footer's **sound chip** is named after the video the audio came from when the audio is
borrowed, so it carries none of the `Original sound`, `with @handle` or `Effect · … Shorts` wording
the blocklist recognises. It is ordinary prose, left-aligned with the title, and it stood beside it
as a second candidate — which fails closed, correctly and expensively. Three of five Shorts sampled
on the device lost their title this way.

YouTube reports a chip's label **twice**: once on the chip, which is a `Button` and is refused on its
class, and once on a bare node nested inside it, which is not. A `uiautomator dump` collapses that
pair into one node and shows none of it; only the shipped capture's own tree does.

A label is therefore attributed to the **outermost** node that reports it, and a label enclosed by a
visible `Button` is part of that control. `isTitleLike` already refused a label reported *on* a
`Button`; these are the same fact where it survives YouTube nesting the label one level down. Two
further narrowings remove a row that carries its own icon — a chip, not prose — and a row that shares
the owner handle's own row, which is the channel row an Official Artist Channel renders as
`"@handle, Official Artist Channel"`.

**Geometry and structure narrow; they never pick.** A lone surviving candidate is still returned
whatever shape its row has; two survivors still refuse, because a wrong title is a permanent wrong
scrobble. Each of the four narrowings was checked by disabling it and confirming a test fails.

The four run **last**, on an ambiguity the original vocabulary and geometry rules have already
refused, and the player's width is measured from every candidate the footer produced rather than
from the set being judged. That ordering is what makes the change a tie-breaker rather than a
filter: any footer that resolved before these rules existed resolves identically now. Written the
other way round it did not — a rule that removed the title left a right-hand control alone in the
list, where a third of its *own* width admits it, and the control became the title.

### 2. The seekbar-less title blink is the same listen

`v0.11.0l` §1 fixed a footer title blinking out during a 2× hold on the measured observation path and
recorded the seekbar-less one as "deliberately unchanged; no evidence of a defect on it exists". The
evidence arrived: a Short held at 2× produced a Not logged row saying it played a few seconds, and
then a second, full entry in History for the same viewing.

YouTube intermittently renders no Shorts progress bar at all (v0.9.10). A footer whose title has
blinked out while that is true arrives with `title = null` on the seekbar-less path, the identity key
misses, the fragment already earned is finalized alone, and the listen restarts from zero.
`ForegroundShortTracker.isUnmeasuredTitleBlink` is the v0.11.0l rule minus the two clauses that need
a position — there is none to read on this surface — and stricter in every other respect. A different
title, a different owner, a different source epoch, and a gap past the blink window all still split.

This is proven through the reducer, which is the production boundary, and **not** on the device: a 2×
hold on the A36 leaves the seekbar readable, and YouTube withholding the bar at the same moment could
not be provoked on demand. Physically **NOT ESTABLISHED**.

### 3. An unnamed refusal staying invisible is the contract, not a fault

"It appeared in neither History nor Not logged" is two facts and only one is a fault. Every refusal
is filed into the terminal outcome and the event log unconditionally; a row with no **proven** video
id is then not shown, because the alternative is a row whose title opens a YouTube search, and for
the duplicate uploads this app spends most of its effort telling apart that opens the wrong video and
looks authoritative doing it. That is §7 of v0.11.0, asserted by
`NotLoggedHyperlinkInvariantReplayTest`, and it did not change.

What changed is that the Short now has an id to prove. The cost of the rule is real and is stated
rather than hidden: while identity fails, the app is silent about it.

### Proven by

`NativeShortParserTest` — the sound chip, the AI-disclosure chip, the artist-channel row, a
non-`Button` chip, the lone-survivor guard, and the existing two-left-aligned-candidates refusal.
`ShortsContinuityFieldTest` — the seekbar-less blink and its owner/epoch/title/window guards.
Plus a guard test asserting that narrowing never turns a resolved title into a refusal, which failed
twice before it passed.

Device: `dSYyRBKh4kA` played a few seconds in the foreground, sent to picture-in-picture, held past
its threshold and closed — the reported sequence exactly. It is now **acquired** with its title,
finalized with it, resolved from watch history **on that title**, and broadcast as transaction
`2c9184b5432d22a806620ef19085bd7a73cffa64`, read identically from `api.deathwing.me` and
`api.openhive.network` in irreversible block `109328730`. Across roughly fifty Shorts swept on the
device afterwards, none refused its title; the pre-change rate on the same device and feed was three
in five. Nine further authorized writes were made on pre-fix and intermediate builds while
reproducing the defect; the field record enumerates all of them.

## v0.11.1b the Now card stops being the diagnostics screen (2026-08-25)

A user-interface simplification. **Nothing in detection, identity,
classification, progress measurement, eligibility, finalization, payload
construction, deduplication, privacy or transport was changed**, and the Phase 9
architecture boundaries are untouched. Every removal below moved a fact to a
place that already existed for it, or removed a control whose job something else
already does.

### 1. The Now card shows what is playing, not what the app knows

The card drew, per session: the package name, the origin, the source proof, the
browser scan's coverage, the foreground observer's coverage, the native
ad-guard caveat, the owner handle, the loop flag, the video id, the canonical
URL, the route that proved the id, the pre-resolved preview id and its route, the
notification hint, a one-word verdict, the complete prospective payload with its
kind and the reason for that kind, the YouTube Music catalogue type, the
MusicBrainz match, the listed flag, and a monospace dump of every raw
MediaSession metadata line.

All of it is evidence about *this app*. None of it answers the question someone
opens the app with. It is now:

- the platform, named and drawn with its mark — **YouTube**, **YouTube Music**,
  **Brave**, **Chrome**;
- the artist or channel;
- the title;
- a progress bar with the percentage played, and the length beside it;
- the final category — **Song**, **Video**, **Movie**, **Episode**, **Podcast**;
- one short status line.

The status states the soonest thing that would stop the listen counting, and
otherwise the rule the app runs on: `Advertisement — not counted`,
`Reading what's playing…`, `Identifying video…`, `Waiting for the length…`,
`Automatic scrobbling is off`, `Threshold reached — final checks run when this
ends`, or `Scrobbles at 60% played`. Crossing the progress threshold is not a
claim of final eligibility: minimum duration, Shorts policy/proof, privacy,
mute and deduplication remain finalization-owned checks.

Nothing was deleted. The removed evidence is written to the **event log**, which
is where a recording of what the app observed belongs — behind a switch, with a
12-hour/512 KiB retention bound, and with an Export button.

The card is rendered from `NowCard`, a closed presentation model for those
on-screen concepts, because the point of the change is the *set*: a card built
from a closed model cannot regrow another diagnostic field by someone adding a
`Text` to a composable. `NowCard` participates in no decision — it reads a
snapshot the existing paths already produced. The kind still comes from the
same cache-only `NowPreview`, so the UI still starts no network work.

An unknown length draws **no** progress bar rather than one pinned at zero: "we
do not know how long this is" and "none of it has played" are different claims.

The empty state reads: *No active media session. Play something in the YouTube
app or YouTube Music.*

### 2. The manual `Broadcast this scrobble` button is gone

Removed from the Now card, and with it `MainActivity`'s manual finalization call.

The engine's `MANUAL` trigger is **deliberately not removed**. It is a
finalization boundary with its own eligibility, dedup and epoch rules, asserted
by the finalization suites; deleting a trigger in order to delete a button would
be a change to scrobbling rather than to a screen.

### 3. The always-visible strip no longer says `Monitoring`

The strip read **Monitoring**, with `Scrobbling at 60% played` underneath. The
second line is the one that says something — it states the rule the app runs on
— and the word above it only described the app watching, which reads as
surveillance and buys the person reading it nothing. While the app is running
there is now no headline at all.

`Stopped` keeps its headline, because that is the state worth noticing. The
`Start`/`Stop` control is unchanged and still reachable from every destination.
`Watching — add a Hive key to scrobble` and `Watching — automatic scrobbling is
off` became `Add a Hive key to scrobble` and `Automatic scrobbling is off`.

### 4. `YouTube scrobbling` describes the feature, not its implementation

> On — YouTube app and YouTube Music. RustedWax may look up YouTube metadata
> when needed to verify what played. Picture-in-picture time is counted where
> Usage Access allows it.

It names the two apps the switch is for and the two behaviours that deserve
disclosing. **The switch itself is unchanged**: it still gates every surface it
gated before, including browser YouTube, and `Browser evidence access` remains
its own row with its own accessibility grant and its own disclosure. This is a
change to a description, not to what is watched.

### 5. `Advanced` is replaced by developer mode

`Advanced` was a `Show`/`Hide` disclosure anybody could open, holding four
privacy switches, the Shorts rule and the event log. Its contents were not
*advanced* so much as *unasked*.

**Settings › About** now shows one line — `RustedWax version 0.11.1` — and seven
taps on it unlock **Developer mode**, which replaces the Advanced row entirely
and holds:

- **Event log** (moved out of Advanced);
- **Disable Shorts** (moved out of Advanced, default and behaviour unchanged);
- **Foreground Shorts evidence**, the accessibility grant, moved down from the
  ordinary settings list;
- **Test Hive connection**, new.

The unlock is persisted, so returning from another app does not re-lock it, and
one tap in the tier turns it off again. It gates **display only** — every
setting behind it keeps its stored value, so locking the tier can never silently
change what the app records or scrobbles.

Because the foreground-Shorts grant now sits inside this tier, an ordinary
install cannot enable it from the settings screen. That follows the default it
sits under: `Disable Shorts` is **on** for a fresh install, so no Short is
scrobbled by any route unless somebody has already gone looking.

### 6. Private scrobbles leave the menu, and none of their code leaves with them

The four per-kind privacy switches are no longer offered. `Settings.privacyMusic`
and its three siblings, `privacyEnabledFor`, `PrivacyCipher`, `PrivateScrobble`,
the derived secret and the broadcast path that consults them are **unchanged**,
and every stored answer is preserved. This is a decision about a menu — there is
currently no case for the switches — and it is meant to be cheap to reverse.

### 7. `Test Hive connection` replaces `Broadcast a test scrobble`

The test broadcast is removed, with `ScrobbleBuilder.testPayload`. It answered
"does this work?" by writing a listen that never happened onto a ledger that
cannot remove it, and all it could report was that *something* failed.

`HiveConnectionCheck` asks the same question in four read-only parts and leaves
no trace:

| Step | What it proves |
| --- | --- |
| Hive node | A node answered `get_dynamic_global_properties` and is current — head block reported |
| Account | The account exists and has a posting authority, and how many keys are on it |
| Posting key | The key stored on this device derives to a key on that authority |
| Signing | Scrobbles can be signed locally |

The two RPC calls are injected rather than taken from a client field, so the
class has no route to `broadcast` even by mistake and every branch is decidable
in a unit test. The account lookup is attempted even when the head-block call
failed — they are different methods, and one failure reported as four hides which
thing is broken. **The private key is never passed to it**: the caller derives
the `STM…` public posting key from the vault off the main thread, so nothing in
the check can print, log or return a secret because nothing in it receives one.

The `Unofficial and unsupported` note is removed from the Hive account row; it is
on the project's GitHub page. The `About your posting key` security note stays
exactly where it was.

### 8. `Log` is the last destination

The strip is `Now`, `History`, `Not logged`, `Settings`, `Log` (conditional).
`Log` was fourth, between `Not logged` and `Settings`.

It is the only conditional destination, and anywhere but the end its appearing
and disappearing shifts every destination after it — survivable since the
selection became named rather than numbered, but it still rearranges the strip
under the finger of whoever just touched the switch. At the end it comes and
goes without moving anything, so the same four positions hold either way, and a
carried pager index can no longer resolve to a different screen across the
toggle. It also reads correctly: `Log` is the raw record behind what the other
destinations summarise, and it exists only because somebody switched it on in
`Settings`.

The pager repair keyed on the destination list is **kept**. The new guarantee
comes from where the conditional destination sits, not from the wiring, and a
future conditional destination added in the middle would reintroduce the drift.
`AppNavigation.resolve` still lands on `Settings` when a selection stops
existing — now both the correct answer and the obvious one, since `Settings` is
the destination immediately before `Log`.

### 9. `Export` sits beside `Clear log`

`Export` was a conditional trailing button on the tab strip, which needed a rule
(`AppNavigation.exportVisible`) deciding when it was allowed to exist. It is now
inside the `Log` page beside `Clear log`, and the rule is deleted rather than
kept as a second answer: the guarantee it enforced — that `Export` can never
attach an empty file — holds by construction, because the `Log` destination
exists only while a log does. The `N lines` counter is removed; it was a number
nobody acts on, occupying the row the two buttons wanted.

### Proven by

`NowCardTest` — the platform names, the category words, the duration format, the
progress fraction, each status branch, and a guard asserting that no package
name, URL, id or diagnostic word can reach the model at all.
`HiveConnectionCheckTest` — every step's pass and fail branch, the dead-node
isolation, and a guard that nothing secret reaches the report.
`SettingsOutlineTest` — the new order, the developer gating, About last, the
absent `Monitoring` headline, and the exact `YouTube scrobbling` copy.
`UiSettingsLogWiringTest` — that the screen is the models' only caller, and that
each removed field, button and section is gone from production rather than
merely unreferenced.
`SettingsTest` — the developer-mode default and its persistence.

## v0.11.1c a prune that stops at the ceiling prunes on every line (2026-08-26)

A performance defect in the retention bound introduced by v0.11.1, reported from
the field as the whole app locking up. **Nothing in detection, identity,
classification, progress measurement, eligibility, finalization, payload
construction, deduplication, privacy or transport was changed.** The only
behavior change is how much of the retained window a prune reclaims.

### 1. What the ceiling actually did

`LogRetention.prune` trimmed the retained window down to *exactly* `MAX_BYTES`.
`EventLog.append` prunes whenever `retainedBytes > LogRetention.MAX_BYTES`. Put
together, those two facts mean that once a log has ever reached 512 KiB, the very
next line is over the ceiling again — so the hard cap re-arms on **every single
append**, forever.

The three documented cadences — 200 lines, 64 KiB, five minutes — never got to
govern anything, because the ceiling fired first, always. The v0.11.1 section
above already promised pruning "never on every append, because a line is written
several times a second while a Short is on screen". The implementation did not
keep that promise; this section records the repair, not a change of intent.

A prune is a whole-file read, a `SimpleDateFormat` parse of every retained line,
and a whole-file rewrite. At the ceiling that is 512 KiB and roughly 4,500 parses
**per logged line**, under one object monitor, on whichever thread wrote the
line. RustedWax is a single process with no `android:process` split, so the
notification listener and the accessibility services deliver on the same looper
the UI draws on: the thread paying for it is usually the main one.

### 2. Measured on the field device

With the log at 524,263 bytes across 4,497 lines, starting one ordinary native
YouTube video: 74 of 78 log lines came from the main thread, and the app reported
`Skipped 302 frames` — five seconds of an unswipeable, unscrollable UI — then
195, 77 and 53. Emptying the retained window and repeating the identical playback
on the identical build produced **no skipped frames at all**.

That also explains the two secondary symptoms: playback progress arrived late and
then jumped, because the probe's own tick and the source callbacks were starved
by the log's I/O on the shared looper; and a listen could finalize against a
progress figure lower than what was really played.

### 3. What a prune reclaims now

A prune trims to `PRUNE_TO_BYTES`, 384 KiB — three quarters of the ceiling — so
it buys roughly a thousand lines of headroom instead of exactly one. The target
is derived from the cap rather than passed in, so no caller can construct the
degenerate policy where the two are the same number.

**The 12-hour and 512 KiB promises are unchanged and are now kept with room to
spare**: the retained window normally sits at or below 384 KiB, and `MAX_BYTES`
goes back to being the backstop it was written to be rather than the thing doing
the work. A single line too large for the target is still dropped on its own
rather than clearing the window.

### Proven by

`LogRetentionTest` — that pruning reclaims headroom below the cap rather than
stopping at it, and that the target sits below the ceiling.
`EventLogRetentionTest` — through the real `EventLog` production boundary, that a
log opened at the byte ceiling does not evict its oldest retained line for 20
appends made well inside every cadence, plus the negative control that the
200-line cadence still fires and the export file still never exceeds 512 KiB.

## v0.11.1d a status poll may not decrypt, and a live snapshot may not diagnose (2026-08-26)

Two main-thread costs on the once-a-second status poll, reported from the field
as RustedWax going slow and laggy while YouTube Music played with Chrome open.
**Nothing in detection, identity, classification, progress measurement,
eligibility, finalization, payload construction, deduplication, privacy or
transport was changed.** Both repairs remove work that produced no observable
answer.

Both are on the same path. `MainActivity`'s `Wired` composable runs a
`LaunchedEffect` loop that re-reads grants and session state and calls
`probe.tick()` every second, and it runs for as long as the composition is
alive — which includes while the UI is not on screen.

### 1. A live snapshot builds no diagnostic dump

`AndroidSessionBinding.snapshot()` serves two callers. `publish()` builds one
per watched session per tick; `freezeAndReport()` builds exactly one per
finished listen. `metadataLines` is read only by `FinalizedTrack.from(snapshot)`
— the v0.11.1b Now-card simplification removed the raw dump from the screen and
nothing replaced that consumer — so every live snapshot was rendering a field
with no live reader.

`MetadataDump.dump` is not cheap. It reports what a source did *not* publish as
well as what it did, so it probes every standard key and every non-standard
extra: asking a **non-text** key for text makes `android.os.BaseBundle` build a
`ClassCastException` and print its **whole stack trace**, and each artwork key
marshals a bitmap across Binder. YouTube Music publishes both typed extras and
artwork; the YouTube app publishes neither, which is why one of them made the
app lag and the other did not.

Measured on the field device on 2026-08-26 with YouTube Music playing: **1,532
`W/Bundle` stack-trace lines in twelve seconds — 99% of everything the process
logged — all on the main thread.** After the repair, **zero**.

The dump is unchanged for the finalized snapshot, which is the one
`FinalizedTrack.rawLines` exports, and unchanged on the metadata-change log line.

### 2. The stored YouTube session is decrypted once, not once a second

`YouTubeSessionVault.session` read `EncryptedSharedPreferences` on every call —
four values, each an AES-256-SIV key lookup and an AES-256-GCM decrypt against
the Android Keystore. The poll called it every second on the main thread. A
`simpleperf` call graph attributed **58.88%** of the poll coroutine's
main-thread time to that one property.

The re-read itself is a requirement, not an accident: the session is written by
the sign-in activity and the refusal by the resolver on a background thread, so
neither can be cached in composition. What was never required is that the
re-read reach the Keystore. The vault now holds the last decrypted `Session` and
every writer in it drops that cache; this class is the only writer, so a stale
session cannot outlive a sign-in, a cookie rotation, a relabel or a wipe.

**Only what `Session` already exposes is held** — a display label and two
timestamps, the fields documented as safe to display or log. **The cookie is not
cached**: `secretCookieHeader()` still decrypts on every call, so the
credential's residency in memory is exactly what it was.

### 3. What this measured, and what it did not

Background main-thread CPU with YouTube Music playing, Chrome open and
RustedWax hidden, alternating installs on one device:

| Build | jiffies / 30 s |
| --- | ---: |
| Before | 132 |
| After | 65, then 70 |

Foreground frame timing over twenty scripted swipes, after discarding warm-up
runs, improved from roughly 2.1–2.5% janky frames to roughly 1.5–1.7%, and the
99th-percentile frame from ~26 ms to ~22 ms. That is a real but modest gain, and
it is stated at that size rather than inflated: the larger differential quoted
during the investigation came from an uncontrolled first measurement that
absorbed JIT warm-up and was withdrawn.

### Proven by

`MetadataDumpCostTest` — a counting `MetadataFields` fake pinning what one dump
costs: the non-text extra probed as text, every artwork key marshalled, and 29
probes for one ordinary music bundle.
`SnapshotDiagnosticCostWiringTest` — that the production `snapshot()` builds the
dump only for a finalized track, that the finalized path still builds it, that
`FinalizedTrack` still reads it, and that `publish()` requests only live
snapshots.
`YouTubeSessionVaultCacheTest` — sixty reads costing one decrypt pass, an absent
session read once, and negative controls that sign-in, wipe, relabel and cookie
rotation each reach the next read while the credential itself stays uncached.

## v0.11.1e a deferred value is not deferred if its parent reads it (2026-08-26)

The round-3 UI repair changed rapidly updating `MainScreen` parameters from
values to functions, but `MainActivity.Wired` still collected the flows with
delegated `by`. That reads each new value in the parent composable, and the
lambda then captures that new value. Every event-log append and Shorts-status
emission therefore still invalidated `Wired` and could still replace the lambda
passed to the full screen. Live MediaSession snapshots also continued to cross
the boundary as a `List` by value once a second.

The contract is now the actual ownership rule: a high-frequency value is held
behind its stable Compose `State` object, and its `.value` is read only inside
the smallest composable that draws it. Event-log lines belong to `LogList`,
Shorts observer status belongs to its one Settings row, and live position
snapshots belong to `SessionList` on Now. The destination strip receives a
separate integer session count, which changes only when a session appears or
disappears rather than on every position tick.

No detection, identity, classification, progress, eligibility, finalization,
payload, deduplication, privacy or transport behavior changes. Now still updates
live; the other destinations simply stop redrawing answers they do not show.

### Proven by

`RecompositionScopeWiringTest` fails if any of the three high-frequency values
is delegated/read in `Wired`, if `MainScreen` takes live sessions by value, or if
the lowest owning composable no longer performs the deferred read. On the A36 in
the reported YouTube Music + Chrome + Settings + Developer Mode arrangement,
the pre-fix APK produced 181 frames in the idle measurement window, 177 of them
janky; the fixed exact artifact produced only three post-navigation frames in
30 seconds while Now progress and the seven-tap unlock remained live.

## Automatic Scrobbling is temporal write authorization (2026-08-30)

Automatic Scrobbling is not a Boolean sampled only when a listen happens to
finalize. Every logical automatic-write target is stamped when it begins with
both the switch value and the current authorization generation. Every actual
ON/OFF transition advances that generation.

An automatic write is authorized only when the target began while the switch
was ON and the current setting still names that same uninterrupted ON
generation. Therefore:

- a target begun while Automatic Scrobbling was OFF remains unwritable after a
  later OFF to ON transition;
- a target begun while it was ON is cancelled if the switch changes before the
  irreversible signing/broadcast boundary; and
- a genuinely new logical target begun after re-enabling receives the new ON
  generation and may proceed normally.

The stamp belongs to the logical listen, not its current Android surface.
MediaSession recreation, `TrackProgressCarry`, continuation expiry,
foreground-Short interruption, and Short resume preserve it. A normal track
transition creates a new target and samples the then-current generation.

Authorization is checked at automatic finalization entry, around asynchronous
identity work, and before deduplication. After eligibility and the claim, a
single short policy critical section linearizes the live Auto mutation against
commitment of the target's complete ordered payload batch. If OFF wins, the
claim is released, one typed `Ignored` outcome is filed, and no posting-key,
privacy-secret, signing, queue or broadcast work starts. If commitment wins,
the batch is already-authorized transport work; OFF may return immediately but
does not create an ambiguous half-committed batch. The critical section never
contains payload serialization, posting-key, network, signing, broadcast or
queue work. Manual
finalization remains an explicit request, and retrying an already-serialized
queue entry remains transport-only work; neither enters this commit gate.

This changes no playback measurement, threshold, duration authority, identity,
classification, payload, deduplication, privacy, or queue-retry rule.

### Proven by

`AutomaticScrobbleTemporalAuthorizationTest` covers the OFF-era direct and
continuation cases, both deterministic sides of the final commit race, zero
post-revocation key/broadcaster/queue activity, claim release, fresh work after
re-enable, carry preservation, exactly one terminal outcome, and retry-queue
isolation.
`ForegroundShortAuthorizationTest`,
`AutomaticScrobbleAuthorizationWiringTest`, and the authorization-generation
case in `SettingsTest` pin production producer wiring, commit-before-transport,
the shared mutation/commit monitor, and generation advancement.
