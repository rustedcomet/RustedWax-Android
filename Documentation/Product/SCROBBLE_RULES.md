# Scrobble rules

What qualifies as a listen, what is refused, and why each refusal exists.

[← Back to the README](../../README.md)

---

### YouTube watch history (v0.9.6, optional)

**Off, and not connected, unless you sign in for it.**

The native YouTube app publishes no video id anywhere another app can read — MediaSession metadata,
queue, session activity, notification, accessibility tree and even its own exported media-browser
service were all measured empty. Inside a playlist RustedWax works the id out from the playlist. For
a single video, or for anything played with the screen off, the only surface that names the exact
video is the account's own watch history.

If you connect an account, RustedWax reads exactly one page — `youtube.com/feed/history` — and only
to check whether the track that just played is named there. It does not read subscriptions, email,
the Google account, or any other Google service, and it never posts, likes, comments or changes
anything.

- **You sign in, not the app.** Google's own sign-in page opens in a WebView. RustedWax cannot type
  into it and never sees a password or a second factor.
- **The session is encrypted at rest**, in `EncryptedSharedPreferences` under an Android Keystore
  key — the same protection as the Hive posting key, which is the correct comparison because a
  Google session cookie is the more dangerous of the two. It is never written to the event log,
  never included in an exported log, and never attached to any host but `youtube.com`. Redirects are
  not followed, so a redirect cannot walk it somewhere else. The WebView's own plaintext cookie jar
  is wiped as soon as the session has been moved into the vault.
- **Disconnect wipes it**, immediately, from the same row in the app.
- **History is a candidate, never a verdict.** The entry has to pass the same title + channel +
  duration gate every other route passes, against the frozen MediaSession values, and it has to be
  the only recent entry that does. A stale entry, or one written by another device on the same
  account, refuses rather than mis-links.

The YouTube app on the phone must be signed in to the **same** account and not playing in incognito;
otherwise nothing you play is written to the history RustedWax can read. That is detected rather
than assumed: three consecutive tracks that were absent from a successfully-read feed stop the route
outright, and the app says so on the settings row instead of quietly fetching a page per track
forever. A dead session and a paused history are YouTube's own answers and refuse on sight.

**Three tracks means three different tracks** (v0.9.8). Replaying one video used to count as a
fresh miss each time: measured 2026-08-06, one video looked up three times in three minutes stood
the route down for fifteen minutes on a claim ("the last 3 native tracks were not written") that was
true of exactly one, and two Shorts already past the threshold were refused inside that window. The
condition being diagnosed — the app is on another account — shows up as *different* tracks going
missing, so requiring different tracks costs nothing. A route that is standing down now says so in
the log and in the refusal, instead of pointing at sign-in.

### Leaving and coming back

A viewing interrupted is not a viewing abandoned. Switching to another tab and back, or letting
YouTube rebuild its media session, used to end the track and start a new one from zero — measured
2026-08-07, a 32-second Short finalized at `0s`, `3s` and `5s` across three tab switches without ever
clearing the threshold it had already earned, and a 155-second trailer banked 18 seconds of a
several-minute viewing.

Three rules now hold that together:

- A **Short** that comes back with the same identity resumes the progress it had — within 30
  seconds on identity alone, or for up to fifteen minutes when its seekbar shows it picking up where
  it stopped (v0.10.0; see below).
- The **empty metadata** YouTube publishes while recreating a session is not a track. The session
  adopts its real identity when it arrives and carries on.
- **Opening Shorts ends the video you were watching; it does not erase it.** Measured 2026-08-07: a
  trailer watched to 82% and then interrupted by the Shorts tab was discarded without a trace,
  because the Shorts tracker takes over the player and the media session was clearing its counter on
  the way past. What it had earned is now scored first.

None of them can inflate a scrobble: one video still counts once, whatever route it took to get
there.

### Who gets credited as the artist (v0.10.0)

The **YouTube app publishes the channel in the artist field.** YouTube *Music* publishes a real
artist. One rule covered both, and it was correct for one of them — so this reached the chain:

```
TITLE:  Snoop Doggy Dogg - Intro
ARTIST: King Of Rap          ← the channel
   → artist "King Of Rap", title "Snoop Doggy Dogg - Intro"
```

Both fields wrong. The YouTube app now goes through the same ladder the browser always used:
YouTube's own music credits from the watch page, then the `Artist - Track` split, then a MusicBrainz
confirmation for canonical spelling. YouTube Music keeps its metadata untouched, because its
metadata is better than anything parsing could recover.

**Nothing is lost when the ladder cannot answer.** A song whose performer cannot be established
stops claiming to be a `song` and becomes a `video` credited to its channel — which is true by
construction, since the channel *is* the uploader. The listen is kept; only the claim about it
narrows to what is provable.

### Films and episodes (v0.10.0)

A title carrying an explicit `S02E05`, `Season 3 Episode 12` or `1x04` marker is an `episode`; a
feature-length upload that says it is a complete film, or that YouTube itself files under
Film & Animation with a release year, is a `movie`. This fixes films being filed as songs.

**A trailer stays a `video`.** Crediting someone with having watched a film because they watched two
minutes of its trailer is the same false claim this project refuses everywhere else. `1920x1080` is
a resolution, not season 1920.

Classification only: `imdb_id`, `wikipedia_url`, `series_*` and `poster_url` stay absent, because
the extension fills them from a page DOM and a Wikidata lookup that has no equivalent here.

### Watching faster than real time

A video watched at 1.5× or 2× has still been watched. What counts is **content consumed**, not
minutes elapsed, so a trailer played in full at 2× is 100% played and scrobbles — the seek bar, not
the clock, decides.

Shorts got this wrong until v0.9.14: their progress was bounded by elapsed real time, so speeding one
up made almost all of it look like a seek and it earned nothing. Measured 2026-08-08, a 121-second
Short at 2× finished as `played 6s of 121s` and was refused; the same Short at normal speed scrobbled
at 98%.

Holding a Short to speed it up is the hardest case, because YouTube hides the overlay while you
hold — title, channel and buttons, and sometimes the progress bar with them. Until v0.9.15 that meant the Short was
not just unmeasurable but unrecognised, and it was dropped three seconds into every hold. When the bar survives it is simply
read, and the missing channel name is ignored — the video was already identified when it started.
When the bar goes too, what is left on screen is the speed itself, `2x`, and that is read instead: it
proves the video is playing and sets the rate the time is worth.

One limit remains, and it is deliberate. When there is no progress bar **and** no speed shown, elapsed
time is credited at normal speed, so a bar-less Short played faster is credited less than it earned.
Guessing a speed nothing displayed would mean claiming playback nothing observed.

**A title that comes and goes is not a new Short** (v0.11.1a). The footer returning in two steps was
already handled where the seekbar survives the hold; where YouTube is rendering no seekbar either, one
viewing used to become two — a Not logged row saying it played a few seconds, then a second entry in
History when the same Short finished. The same continuity rule now covers both surfaces: exactly one
side missing a title, the same owner, the same session generation, inside the same short window. Two
different titles are still two Shorts.

### Reading the title off the screen

The Shorts footer has no resource ids on it any more, so the title is found by *where it is* and
*what shape it is*, and it refuses whenever two candidates survive — a wrong title is a permanent
wrong scrobble.

What kept refusing was the **sound chip** underneath it. When a Short borrows its audio from another
video, YouTube names the chip with that video's title — so it is ordinary prose, with none of the
`Original sound` wording that used to identify it, sitting exactly where the title sits. Measured
2026-08-25: three Shorts in five lost their titles to it, including `dSYyRBKh4kA`, which was watched
end to end in picture-in-picture and reached neither History nor Not logged because nothing could
name it.

Four things now separate a chip from a title, and all four only ever *remove* candidates:

- a label belongs to the **outermost** element that reports it, because YouTube reports a chip's
  label twice — once on the chip and once on a bare node inside it;
- a label enclosed by a button is part of that button, which is what the `AI` in
  `AI: Content was made with AI` is;
- a row carrying its own icon is a chip; the title is text and nothing else; and
- a row level with the channel avatar is the channel row, which is how
  `@handle, Official Artist Channel` was competing with the title.

A footer with only one candidate still reports it whatever shape it has, and a footer with two
genuinely different sentences still refuses.

### Shorts

> **There is one Shorts switch, and it is `Disable Shorts`.** With it on — the fresh-install
> default — nothing from a proven `/shorts/` path is scrobbled, at any length. With it off, a Short
> is admitted only when it has been *proven public*, and then from 10 seconds.
>
> It used to have a neighbour, `Short clips`, which decided only whether a verified Short could use
> the lowered floor. Two switches with almost the same name is how somebody ends up certain they
> turned Shorts off while Shorts keep landing on a ledger nobody can edit. **`Short clips` was
> removed in v0.11.1** and its stored value is deleted on upgrade; `Disable Shorts` is preserved
> exactly as the user left it.

A **proven public** YouTube short scrobbles from 10 seconds instead of 30. Proven public means three
things: the address bar (or the native foreground player) showed a `/shorts/` path, the video
resolved on its own watch page, and that page said the video is **publicly listed**.

**A Short that cannot be proven public is refused, not held to 30 seconds.** This changed in
v0.11.1 and it is the substantive half of the change. Falling back to the ordinary floor looked
conservative and was not: it admitted anything unproven purely on length, and a 42-second
shorts-feed creative is longer than 30 seconds — which is exactly how one reached the chain in
v0.8.7. So each of these now fails closed:

| Observation | Verdict |
| --- | --- |
| `/shorts/` path, resolved, publicly listed | admitted from 10 s |
| `/shorts/` path, resolved, `isUnlisted` true | refused — almost certainly a feed ad |
| `/shorts/` path, resolved, listed field absent | refused — absence is not proof of public |
| `/shorts/` path, watch page did not resolve | refused — nothing separates it from an ad |
| a literal visible YouTube ad label | refused, before any of the above |
| ordinary `/watch` path | unchanged: 30 seconds, same threshold |

**Fresh-install defaults**: `Disable Shorts` starts **on**, unchanged since v0.11.0. Filling an
unerasable ledger with feed scroll is something to choose rather than inherit. The default does not
touch an existing install — see [SETUP.md](SETUP.md#what-a-fresh-install-starts-with).

The `/watch` path keeps the 30-second floor and uses the same configured progress threshold.

Why proof instead of a length rule: an ad and a real 12-second clip are the same length, so length
can't tell them apart. The first version of this guard stopped at "the page resolved", on the
assumption that ad creatives have no public watch page — they do, and an 18-second shorts-feed ad
reached the chain. What actually separates them is that an ad creative is **unlisted**, while a
short you reach by scrolling the feed is public. An explicitly unlisted `/shorts/` item is rejected
regardless of its duration or progress. If that field ever goes missing from the markup the clip is
refused rather than admitted, so drift closes the exception instead of re-opening the leak.

The tradeoff is that when a lookup times out (~12% of the time in field testing) a legitimate short
is refused rather than merely held to a higher floor. That's the direction worth failing in; a
missed entry can be earned again by watching it, and a false entry is permanent. It applies only to
Shorts, and only to installs that have deliberately turned `Disable Shorts` off.

When playback reaches the end and its position returns to the beginning, the log names a
**detected loop** and keeps the continuous viewing to one transaction regardless of kind. The
position-wrap signal survives a Chrome media-session recreation. When Chromium does not publish a
usable position reset, a verified short remaining active beyond 125% is still named as a
**probable loop**. In both cases the first qualifying viewing is kept.

### Advertisements, and what can be filtered

Ad rejection uses two independent pieces of explicit evidence:

- A dedicated `/shorts/` **ad creative** is normally unlisted. An explicitly unlisted Short is an
  unconditional veto, including one longer than the ordinary 30-second floor. It lands in **Not
  logged** as *"a short and the video is unlisted — almost certainly a feed ad"*.
- With **Browser evidence access** enabled, a visible YouTube ad control such as `Sponsored`, `Ad`,
  or `Skip ad` is bound to the exact current `/shorts/` video id only when that concrete URL appears
  in the same accessibility snapshot. That is also an unconditional veto, so it covers a public
  video that YouTube inserted as a promotion without attaching a later label to an older host-only
  URL. The evidence survives a Chrome media-session recreation and the one-minute continuation
  delay.

This mirrors the desktop connector's use of YouTube's explicit page ad state, adapted to the
evidence Android exposes. It deliberately does **not** guess from a channel, brand, title, video
length, or music classification: `PONDS CAM` is not itself proof that a video is an ad.

There is still a platform limit. Some browser/YouTube combinations may not expose the visible ad
label to Android accessibility, and the feature is unavailable when Browser evidence access is
off. A public promoted video is then indistinguishable from the same public video reached
organically and can still scrobble. Use **Never scrobble this again** for that fallback case; it is
keyed by video id and applies to the manual Broadcast button too. It cannot remove an entry already
on-chain.

Pre-roll ads on `/watch` are not scanned as Shorts ads. They inherit the real video's id, and the
identity corroboration normally rejects the mismatch; ordinary watch clips under 30 seconds also
fail the duration floor.

When an ordinary browser track is minimized or backgrounded for its whole run, the missing visible
scan is not itself a reason to discard the listen. RustedWax may recover the id only when one
bounded lookup candidate uniquely matches the available finalized fields. Normally that is title,
channel and duration; after a rebuild that omitted duration, an exact title plus that candidate's
canonical-page channel may establish only one surviving id. An ambiguous, partial or contradictory
lookup still refuses. Positive ad evidence remains a hard veto regardless of the lookup result.

This is an identity invariant, not a playlist exception. The two allowed authorities and the
classification/MusicBrainz boundary are defined once in the
[current video identity contract](IDENTITY.md#current-identity-contract).

Public `PL…` playlists and the bounded `playlistPanelVideoRenderer` queue embedded in an `RD…` Mix
watch page remain stronger direct provenance routes. A personalized Mix queue can differ from the
anonymously fetched set; when it does, the ordinary unique lookup may still identify the item, but
the Mix id by itself and private `LL`/`WL` lists never establish it.

### Which presentation owns the listen (native app)

In the YouTube app there is no page to read, only a media session, and YouTube
publishes **the video's own title and channel over its interstitials**. During a
pre-roll the session says the organic title, the organic channel, and the *ad's*
length and position. Nothing in the bundle says "ad".

That made arrival order decide which length was the real one, and arrival order is
wrong exactly when a pre-roll goes first. Measured on 2026-08-27 against a
1,192-second video:

```
22:05:23  first metadata   TITLE = <the video>   DURATION = 54s     ← the pre-roll
22:05:27  skipped at 5.8s, 3s measured
22:05:27  metadata         TITLE = <the video>   DURATION = 1192s   ← the actual video
22:05:54  finalize         played 3s of 1192s → 0%, skipped
```

The 54-second surface had become the listen, so the whole video that followed was
treated as an interruption *of it* and never measured. A video watched well past
the threshold finalized at 0%, and the same shape produced 1% on another run. That
is the "it played the whole thing and logged nothing" report, and it got worse the
more ads a video carried.

**Scale decides now, not arrival order.** A presentation dwarfed — four times over
or more — by a materially longer, non-contradictory, same-title presentation
replacing it never held the work. It is superseded: its seconds are **discarded,
never credited**, and organic measurement starts from the new presentation.

How long the viewer sat through the old surface is deliberately *not* part of this.
Interstitials arrive in pods and are frequently watched to their own end. All of
these were measured on one device in one evening, every one of them under the
video's own title, and every one of them followed by the 1,192-second work:

```
10s   13s   27s → 12s   29s → 33s   54s   58s (watched in full)
```

Time served says nothing about whether a surface is the work. What does is that a
58-second surface cannot be the 1,192-second item replacing it.

The four-times gap is what keeps the rule off real listens. A sponsored surface
published *over* an established video — 1,192 seconds becoming 2,163 under the
same title — is only 1.8x, so the established presentation keeps the listen and the
replacement is quarantined instead: measured, but never credited and never given an
outcome of its own. A short surface replacing a long one is that same quarantine in
the other direction.

Nothing here labels or infers an ad, and no control flow reads a diagnostic string.
The rule is about which presentation a listen belongs to. Where it is uncertain it
fails closed — a missed entry can be earned again by watching, an entry that should
not exist is permanent.

**Length authority follows measurement.** A presentation that contributes no accepted
playback cannot define how long the listen was — in either direction. Measured 2026-08-27: a
1,192-second video was measured to 1,072 s, 90%, and then a 1,817-second ad-inclusive surface
arrived. Its playback was correctly refused, but the finalized length was taken as the largest
number anyone had published, so the listen finalized as "1072s of 1817s" — 59%, below the
threshold, against a length the resolver then read as an identity contradiction. A surface the app
declines to measure now defines nothing: not the denominator, not the payload duration, not the
identity input.

**Duration direction or magnitude alone is not track-change evidence.** A shorter number was
already treated as churn rather than a new item; a longer one used to discard the listen and start
a fresh fragment, which is where a video's earned progress went when a sponsored surface appeared
mid-watch. Both directions are now presentation churn on one listen. What ends a listen is
unchanged and stronger: a contradicting title or artist, a different exact item id, a replay from
zero, or a lifecycle/generation boundary.

The one place magnitude still decides anything is which presentation opens a listen, and only when
one is dwarfed by the other — a pre-roll's own length cannot be the length of the twenty-minute
work replacing it. That factor is set from the gap in the field data (real works followed by bogus
surfaces reach 5.4x; interstitials replaced by their work start at 21x), it is documented as a
bounded heuristic in `PlaybackReducer`, and its failure mode is a missed listen rather than a wrong
one.

> **Known limit.** The same route also publishes *ad-inclusive timelines* — the
> same 1,192-second video was published as 2,183 s, 2,163 s and 2,125 s — which
> inflate the denominator the threshold is scored against and can contradict
> identity corroboration outright. Those sessions still refuse. See
> [LIMITATIONS.md](LIMITATIONS.md).

### Play time survives the browser rebuilding its session

Chrome destroys and recreates its media session while a video is still playing — around ad breaks
and playlist transitions. Each recreation is a new session as far as Android is concerned, so the
app's played-time counter used to restart with it.

The effect was a video watched to 80% producing nothing at all: a 196-second track arrived as three
fragments of 47s, 24s and 85s, each scored against the 60% threshold on its own and each skipped.
Progress is now carried across the restart, keyed by browser package plus the session's
title/artist/album/duration signature. A disappearing session waits up to one minute for a matching
replacement instead of finalizing its fragment. Its identity and explicit ad flag are frozen with
the progress. A replacement consumes that complete bundle; if none appears, expiry finalizes the
aggregate once without reading whatever URL is live then.

Three deliberate limits: the carried time is consumed exactly once (two sessions must never inherit
the same play time), the metadata signature must match, and it is dropped on **Stop** (which
discards the in-flight track by design).

#### The minute became a position check (v0.10.0)

One minute was measured on Chrome ad breaks, where the gaps were 18 and 21 seconds. It was never the
right size for a person who minimises the app and comes back, and on a memory-starved phone — where
Android rebuilds YouTube's session constantly — that gap is however long someone is away:

```
21:52:07  session ended, 105s carried, stopped at 105s
21:53:07  continuation expired → played 105s of 234s → 45%, skipped
21:56:10  session returns at 107968ms
21:58:25  track change     → played 129s of 234s → 55%, skipped
```

105 + 129 = 234. The whole video, watched end to end, logged as nothing. It happened 69 times in one
day.

A stopwatch was always a proxy for the real question — *is this the same viewing, or a later
separate one?* — and it answers it by assuming separate viewings are far apart in time. They need
not be. But a separate viewing **starts at the beginning**, and a continuation starts where the last
one stopped. That is the direct evidence, so it is what the rule now asks for: an exact video id on
both sides, and a resume position within 15 seconds of where the carry stopped. Time is still
bounded — fifteen minutes — but loosely, because it is no longer doing the work.

This applies to the browser as well as the native app; a browser proves its id from the address bar
instead of from the resolver. Three things keep it honest:

- **A replay from the start inherits nothing.** The carry must have stopped at least 30 seconds in,
  which is deliberately more than the 15-second tolerance, so a position near zero can never fall
  inside the band around the carried one.
- **A track that ran to its own end is not held.** It has nothing left to resume, so it finalizes on
  the ordinary schedule rather than waiting behind the ads that follow it.
- **An abandoned track is collected as soon as something else plays.** Waiting is for a listen that
  might come back; once a different, exactly identified track starts in the same app, it plainly
  will not.

The visible cost is latency. When Chrome replaces one track's controller with a
different controller instead of publishing an in-place metadata change, the
old item can remain pending for the full minute, followed by Hive confirmation
time. RustedWax cannot close it immediately merely because different metadata
appeared: that different session may be a mid-roll ad before the original item
returns. A pending entry is delayed, not lost.

### Why a scrobble is confirmed, not assumed

`condenser_api.broadcast_transaction` returns an empty result on success and validates only against
the node you happened to ask. "No error" therefore means "that node didn't object" — which is not the
same as "this is on the chain".

On 2026-07-30 a node in the failover list froze 77 minutes behind the chain and kept answering RPCs
normally. It swallowed five scrobbles into a pending block it would never produce and reported no
error for any of them, so the app displayed five successes with transaction ids that didn't exist.
Then it refused the next two with a per-block rate limit — an error that can only fire repeatedly if
the node's block never advances, which is what gave the stall away.

Three things changed as a result:

- **A node must prove it's current** before anything is sent to it, judged against the phone's clock
  rather than the node's own head time. That check also guards the call that reads the chain head,
  because a stalled clock produces an expiration that's already in the past.
- **The accepting node never confirms itself.** The app asks every other current healthy node and
  selects the strongest answer, so an early `unknown` cannot hide a later block result.
- **Three accepted states remain distinct.** `Confirmed in a block`, `seen relaying in an
  independent node's mempool`, and `accepted but confirmation unavailable` have different UI and
  History wording.
- **Rate limits queue instead of vanishing.** Hive caps `custom_json` operations per account per
  block. That's a capacity limit, not a rejection, so those retry — where before they were discarded
  permanently.

There's one deliberate asymmetry. If an independent node sees a transaction in its mempool, or the
accepting node succeeded but every independent confirmation service was unavailable, the app does
not retry automatically. Retrying would rebuild it with a new expiration and a new id, and if the
original did land the result is a permanent duplicate. Neither state is labelled block inclusion.

### Offline queue behavior

The queue is an atomic JSON file, but it is not a continuously scheduled background worker. Entries
retain their account, payload, percent, and video id. A queued entry is only signed when the saved
account matches its owner; changing accounts leaves the old entry untouched. Due entries are
flushed when the notification listener connects, when the app explicitly triggers a flush (including
**Retry now**), and after relevant account/app lifecycle events. Exponential backoff controls which
entries are eligible during a flush; it does not itself wake the process at the deadline. An entry
can therefore remain waiting until the next flush trigger. After eight failed queued attempts it is
removed and a terminal History result is added. Read/write failures are written as `STORAGE ERROR`
log entries; an unreadable queue file is preserved with a `.corrupt-<timestamp>` suffix.

### When browser evidence goes quiet

The video id comes from the browser's address bar, and the bar can stop reporting
one — Chromium collapses the omnibox to a bare host while a feed scrolls. Once
that happens, entries lose their `url` **and** shorts under 30 seconds stop
counting at all, because without a `/shorts/` path there's no proof they're shorts.

That happened for thirteen minutes in field testing and cost nine entries before
anyone noticed, so the app now says so: after three consecutive tracks with no
video id, a banner names both costs and offers a shortcut to the Accessibility
settings. Counted per track rather than on a timer — a long video legitimately
reports its id once and then stays quiet for an hour, and that's fine.

### Not logged

The counterpart to History, and there for one reason: without it a strict rule and a broken app look
identical. Watching twenty shorts and getting six entries used to leave no artifact anywhere in the
UI saying the other fourteen were seen, let alone why.

Each row carries the reason the engine already computed — `track is 7s, under the 10s minimum`,
`played 34%, below 60% threshold`, `a short, but its watch page didn't resolve`, `already scrobbled
this listen` — plus how long it played and how long it was.

Tracks that played under three seconds are left out. Those are page-load transitions, where the
browser swaps a placeholder title for the real one; in one field session they were 165 of 198
entries and buried the 33 worth reading.

**A row for a video that was resumed says so.** A percentage on its own cannot distinguish "we
measured this and it really was 10%" from "we lost most of it", and the difference matters to
whoever is reading the row. Measured 2026-08-28: YouTube resumed a 19:52 video 17:48 in and ran the
last two minutes to the end, which is an accurate 10% and reads exactly like a fault. Where content
had already elapsed on the player before RustedWax was shown the session, the reason now carries it:

```
played 10%, below 60% threshold — this session began 17:48 into it, and anything
played before that was never published to RustedWax
```

That lead-in is only ever *reported*, never credited — see
[LIMITATIONS.md](LIMITATIONS.md) on playback that nothing published. It is silent for a track
watched from its start.

### Automatic Scrobbling authorizes a time interval, not a later decision

An automatic target is writable only when both facts are true:

1. Automatic Scrobbling was on when that logical listen began; and
2. the switch is still in that same uninterrupted ON generation when the write is authorized.

Every actual OFF/ON transition advances the generation. This makes OFF → ON fail closed for old
targets: playback measured while off, including a target parked in the 15-minute continuation
window, cannot be resurrected by enabling the switch before expiry. ON → OFF also invalidates work
already resolving asynchronously. The guard is checked on entry and around asynchronous identity
work. After eligibility and the dedup claim, one short policy critical section orders OFF against
the automatic target's transport commitment. OFF first releases the claim and prevents posting-key,
privacy-secret, signing, queue and broadcast work. Commitment first transfers the complete ordered
payload batch as already-authorized transport; a later OFF does not partly cancel that batch. No
network or signing work runs inside the setting/commit critical section.

The stamp belongs to the logical listen. MediaSession recreation, `TrackProgressCarry`, continuation
expiry, native foreground-Short interruption, and Short resume preserve it. A genuinely new target
after re-enabling receives the new enabled generation and may proceed through the ordinary identity,
eligibility, dedup and signing rules.

This boundary applies to automatic finalization only. Manual finalization is an explicit write
request, and retrying an already-serialized queue entry is transport completion for work authorized
earlier; neither is reclassified as a newly observed automatic target.

### A replay is a new listen

Deduplication prevents two finalize paths from sending the same listen. Its key is the normalized
title and artist plus the exact start time frozen when that listen began. Android session rebuilds
restore the original start, and the manual button uses that same start, so those duplicates still
collide. Starting the track again creates a new start and is eligible immediately; there is no
one-per-hour cooldown.

Before 2026-08-11 this used a UTC hour bucket. That produced an arbitrary result: two starts 24
minutes apart could scrobble when they straddled an hour boundary, while starts 31 minutes apart
could be refused inside one hour. The exact-start key removes that clock-boundary behavior.
