# Detection sources

Where playback evidence comes from: native YouTube apps, and the optional browser evidence
service.

[← Back to the README](../../README.md)

---

## One switch over three surfaces (v0.11.0)

**YouTube scrobbling** is the only YouTube setting. It admits, together:

- YouTube in Brave and Chrome;
- `com.google.android.youtube`; and
- `com.google.android.apps.youtube.music`.

It replaces four switches — `Native YouTube`, `Native YouTube Music`, `Look videos up` and
`Count picture in picture`. None of those was a policy anyone holds; each was an implementation
detail asking to be configured, and one of them, browser YouTube, was not offered at all: before
v0.11.0 there was no setting anywhere that stopped Brave and Chrome from being watched.

**Nothing underneath was removed.** Lookups still run, YouTube Music still gets its own metadata
handling, PiP still uses its own measurement path, and each native package still carries its own
epoch — the boundary that makes an opt-out apply to work already in flight. What changed is who
answers for them.

Turning it off is a clean boundary in both directions: no YouTube source is watched, the address-bar
watcher stops scanning, and any part-played track is **discarded rather than finalized** — turning a
source off must never be what causes a broadcast. Stop, opt-out and listener rebuild invalidate
in-flight state and clear progress continuation and resolver candidates without moving them to
another package.

Starting again, reconnecting the notification listener, or rebuilding after a process restart does
not assume Android will repost an unchanged Brave/Chrome media notification. RustedWax clears stale
hints, replays the listener's currently active notifications through the same browser-package and
media-style privacy filter, and only then enumerates MediaSessions. Thus an already-backgrounded
YouTube session keeps its source proof without treating the browser package alone as proof of the
site.

## Native YouTube and YouTube Music (v0.9.1, experimental)

The package name proves YouTube origin but never invents a video id. Exact identity is accepted from
`METADATA_KEY_MEDIA_ID`, a canonical YouTube `MEDIA_URI`, or an `i.ytimg.com` artwork URI. Every id
is normalized to `https://www.youtube.com/watch?v=<id>`. If no exact field exists, the lookup path
may use the existing title/channel/duration resolver, still requiring exactly one corroborated
candidate. When a YouTube account is connected, signed-in watch history is another bounded fallback;
its candidate is re-fetched and must satisfy the same unique finalized-track gate. Invalid,
contradictory and ambiguous identities remain off-chain.

Native title, artist and album fields remain separated instead of being unnecessarily re-parsed as
a browser-shaped `Artist - Title`. YouTube Music package origin is strong music context, below
literal podcast/episode types, structured non-music genre and the existing hard format rules.
Native YouTube continues through the full evidence-ranked classifier.

**The watch-history route has two separate health states** (2026-08-16). A *global route fault* is
YouTube's own answer — signed out, history paused, or markup that no longer parses. It is about the
stored session, so it blocks every consumer, and after the retry interval whichever consumer asks
first — native or browser — may make one recovery probe; a response that fetches and parses clears
it. A *native-account mismatch* is this app's own inference that the phone's YouTube app is writing
to a different account, and it counts only **health-eligible native evidence**: native package, an
ordinary video rather than a proven Short, no explicit ad signal, a known positive duration, playback
at or above the configured threshold, a freshly parsed feed, a plain absence rather than ambiguity or
a network failure, and a stable key. Three *distinct* such absences stand the route down for fifteen
minutes; repeated absences of the same item never count twice however they are interleaved.

Browser playback is neither evidence for that diagnosis nor against it: a Brave lookup cannot add,
clear, consume or postpone it, and a mismatch pause does not stop Brave resolving. Ads, Shorts and
brief below-threshold previews add nothing — identity resolution still runs for them so that a
**Not logged** row keeps its exact hyperlink, but that lookup is explicitly marked non-evidence. A
Short corroborated from the account's own Shorts feed is an authoritative native hit and clears the
run. Clearing a global route fault never silently clears native mismatch evidence. The measured
failure was three ordinary Shorts advertisements standing history identity down in 57 seconds; see
[the 2026-08-16 record](../Field-Reports/<redacted-private-path>).

**A title written two ways is not a different video** (2026-08-16). Corroboration compares the page
title against the session title, and two legitimate presentation differences were being graded as
contradictions — which both dropped the latch and filed the correct id as rejected for the rest of the
track. A short canonical work that is an ordered **suffix** of a longer `Artist ft Artist Track`
presentation is now weak evidence rather than a contradiction, while a short **prefix** remains a
contradiction because that is the uploader. One embedded `@` mention spelled as a handle on one
surface and a display name on the other is presentation, under an anchored rule: identical text before
and after, one contiguous differing run on each side beginning at an `@` on both, and at least three
tokens of identical context. No similarity scoring is used anywhere.

Rejection is also no longer one undifferentiated verdict. A **duration** disagreement means a
structurally different video and permanently vetoes that id for the track. A **title-only**
disagreement still drops the latch, but must not outrank an independent exact-id route — the playlist
or watch-history route, each of which proves an id against a bounded entry list the device was really
playing from — that later names the same id. Search never recovers a rejected id, and recovery
accepts nothing by itself: the frozen id, both titles, the channel, the duration, the owner handle and
the final watch-page facts all still bind.

Ordinary native YouTube and YouTube Music still have no proven generic ad flag. Browser evidence is
never reused for them, and the app does not guess from brands, titles, ids, duration or popularity.
Foreground native Shorts are narrower: the separate YouTube-only service requires the measured
Shorts player and accepts only exact literal ad labels within it. Identity must remain stable across
the transition window before a session exists, preventing outgoing and incoming frames from mixing.
Progress is derived from seekbar movement where a seekbar exists; pause, seek, rewind and missing
proof add nothing.

Four parts of that changed once the app met real days of Shorts use — every one of them because
YouTube stopped rendering something the app had been treating as mandatory:

- **The on-screen title is not identity** (v0.9.7). YouTube removed the resource ids from the Shorts
  footer, and a Short opened straight into picture-in-picture never shows a title at all. Identity
  is resolved at finalize from the account's watch history, and every candidate is still re-fetched
  and corroborated on its own watch page. When a title *is* present it must still agree.
- **The seekbar is not a precondition either** (v0.9.10). YouTube draws no progress bar at all for a
  Shorts player that is *resumed* by tab navigation rather than opened fresh — no `SeekBar` node in
  the tree and no bar on screen. That cost 47 of 71 Shorts in 85 minutes, because a Short with no
  reading could never be *started* and so could never accrue anything. A visible player root, one
  time-bar container and exactly one exact `@handle` now prove a Short; a readable time is credited
  as measurement when present, and its absence is credited from wall-clock and reported as inferred.
  A bar appearing mid-viewing supplies the real length and continues the same listen.
- **A page publishes two titles** (v0.9.8). `videoDetails` keeps the uploaded name while the page
  renders an auto-translated one for the viewer, and which of the two the resolver sees depends on
  the language its own fetch asks for. Both are compared and the stronger agreement decides; a
  refusal names both.
- **Picture-in-picture is counted, marked inferred** (v0.9.7). PiP publishes no progress of any kind,
  so elapsed wall-clock is credited on the paired evidence that YouTube holds a visible window and
  media audio is started — never while a readable seekbar exists, capped at the item's own duration,
  and carried separately so every such listen says how much was measured and how much deduced.

Identity follows from whatever survived. The handle is mandatory — it is the field that has outlived
every restyle — and it needs **one** of the other two beside it: a title, or a length. Handle plus
title, or handle plus length, each with a unique match; handle alone resolves only when the account's
own recent history holds exactly one Short by that channel, and refuses outright when two or more are
indistinguishable.

Because it is mandatory, what counts as a handle matters more than anything else here — and until
v0.9.13 it was read as ASCII only, so `@eduardaarebouçass` was refused on the cedilla and every
listen by every creator with an accent or a non-Latin script in their handle was refused with it. A
handle is letters, marks, digits and YouTube's `.`, `_`, `-`, in any script, composed to NFC so one
handle encoded two ways is one handle. When the handle *is* refused the log now says whether none or
several were found, and quotes what the footer held — that distinction is what found this one.

**Holding a Short to speed it up hides its footer, and sometimes its seekbar too** (v0.9.15,
v0.9.16). The two states need different things and both are handled: when the bar survives, it is
measured and the missing handle is ignored — identity was proven when the Short was acquired and is
not re-proven frame by frame; when the bar goes as well, YouTube's own visible `2x` chip proves the
player is running and sets the rate the elapsed time is worth. That rate is preserved through the
parser, source adapter, tracker and inference boundary; a later delayed seekbar subtracts time
already inferred instead of counting it twice. A poll with no footer can never *start* a Short, and
is refused unless the length matches the one being tracked.

**The accessibility root and the transition each need freshness authority** (2026-08-15). Android
can retain an outgoing footer in its accessibility cache after YouTube has drawn the incoming Short.
On API 33+ each capture clears that cache, obtains a new root, requires the root to refresh, and
retries once before failing closed. A fresh tree can still publish an incoming seekbar before its
footer, so an unnamed progress frame is accepted only for an already stabilized identity and, when
known, the same duration. A scroll/navigation reset removes that authority. Finally, a tab-resumed
Short restores its original track-instance token and start time together with progress; it is the
same listen for central dedup, not a new listen that merely inherited seconds. The exact defect and
device proof are recorded in
[the 2026-08-15 native Shorts closure](../Field-Reports/<redacted-private-path>).

**A 2× hold must not make a Short unacquirable** (2026-08-16). The press-and-hold that speeds a Short
up removes the title and the owner handle for as long as the finger is down, so every frame after it
begins is an unnamed one. Requiring an *already accepted* identity therefore meant a Short whose
footer had been read once, but which had not yet crossed the 750 ms interval, could never be acquired
at all — four Shorts at 2× produced two scrobbles, a 1-of-20-second refusal and one listen that never
existed. An unnamed frame may now also complete a **pending** organic candidate, and only then: the
candidate's length must be known and the unnamed frame must publish exactly that length — stricter
than the accepted case, which tolerates an unknown length — the interval must still have elapsed, and
the identity delivered is the real footer frame. An unnamed frame still carries no identity and can
never supply one, and the scroll/navigation reset clears the candidate exactly as before.

An immediate foreground-to-PiP handoff has a separate freshness rule. If PiP hides the tree before
the 750 ms second frame, the adapter may promote the fresh complete organic candidate for at most
four seconds, and only after active media audio plus a visible YouTube PiP window independently
prove playback. Ads and identity-less frames cannot cross this boundary, and page/history
corroboration is still required before persistence or broadcast. An identity-less native
`NONE`/`STOPPED` controller remains watchable for a future callback but is not rendered as a Now item.
The handoff is applied before reset whether PiP removes the active YouTube root or leaves only its
unreadable player/time-bar containers; those are distinct production shapes of the same transition.
The final A36 installed-byte gate exercised both shapes: one eligible immediate-PiP Short produced
one linked, block-confirmed entry, and one untitled below-threshold Short recovered its canonical
title/id into a linked Not Logged row. Closing PiP left no empty `NONE`/`STOPPED` Now item.

**A video handed to the Shorts route is scored, not discarded** (v0.9.13). Opening Shorts while a
watch-page video is playing hands the player to this observer, and the MediaSession stops counting so
the same seconds are never scored twice. What it had already earned is finalized first, unless the
Short taking over is that same item.

A Short publishes **nothing** to its MediaSession — measured 2026-08-06, `active=false`, `state=1`,
`metadata: size=0` — so the accessibility observer is the only thing that can see one at all. When
it cannot, and independent evidence says something is playing that nothing else is counting, the log
says so and for how long. It stays quiet for the ordinary quiet: a latched Short is read by the
service's own poll while YouTube emits no callbacks, and leaving the Shorts feed leaves its views in
the hierarchy where they parse as "no player".

The no-broadcast A12 matrix passed, including two natural Short ads and browser/YouTube Music smoke
tests. The write/profile reconciliation remains pending, so both native toggles and the separate
Shorts grant remain experimental/default-off. Full records:
[<redacted-private-path>](../Architecture/Phases/<redacted-private-path>) and
[<redacted-private-provenance>](../Architecture/Phases/<redacted-private-provenance>).

**Interstitials are published under the video's own name.** On the watch path the app
has no page to read, and YouTube publishes the organic title and channel over its
own ads, changing only the length and position. Nothing in the bundle says "ad", so
which presentation a listen belongs to is decided by scale rather than by arrival
order: see
[which presentation owns the listen](SCROBBLE_RULES.md#which-presentation-owns-the-listen-native-app)
for the rule and the device evidence behind it, and
[LIMITATIONS.md](LIMITATIONS.md) for the ad-inclusive timeline case that is still
open.

## Browser evidence access (optional)

Off by default — it is an Android grant, not a RustedWax setting, so it stays its own row. The media
notification tells the app the origin but never which video. Browser evidence is the preferred exact
source; without it, the lookup path can still attempt recovery from the available finalized fields.
That is normally title, channel and duration. If Chromium omitted duration after a rebuild, exactly
one title candidate whose canonical page names the finalized channel can still qualify; ambiguity
refuses. If both routes fail, no YouTube scrobble is broadcast.

Browser visibility is not an eligibility rule. A minimized or fully backgrounded Brave session may
use the same unique finalized lookup as a foreground one, even when no successful accessibility
scan occurred. Accessibility coverage diagnoses the watcher and may capture a literal ad label; no
scan is absence of ad evidence, not absence of video identity. The complete normative boundary is
the [video identity contract](IDENTITY.md#current-identity-contract).

Chromium's `PLAYING` bit is also not an unlimited clock. Brave can finish a video without publishing
`STOPPED` or a readable position. Each Watch therefore arms a monotonic deadline immediately—even
when it was already playing when RustedWax found it—and re-arms on real metadata/transport changes.
The deadline uses the session duration, then the exact page's asynchronously prefetched duration,
then a bounded silence ceiling. Finalization freezes time, removes the Watch from live UI/evidence,
and rejects later callbacks. A short-lived opaque tombstone prevents an unchanged ended controller
from resurrecting after process restart while allowing a strictly newer genuine replay. The exact
failure and lifecycle contract are in
[the 2026-08-14/15 Brave record](../Field-Reports/<redacted-private-path>).

The service is additionally gated on **YouTube scrobbling**: with that off it stops scanning
entirely rather than reading address bars for evidence nothing will use.

Enabling it lets the app read the address bar in Brave and Chrome, which gives the exact site and —
when the browser exposes the full URL rather than just the host — the video id. While the current
page is an identified YouTube `/shorts/` item, it also looks for a small exact-match list of visible
YouTube ad controls such as `Sponsored`, `Ad`, or `Skip ad` (including supported Spanish and
Portuguese labels). It does not classify brand names, titles, or arbitrary page text as ads.

The service is pinned to those browser packages in its config, so the **system** prevents it seeing
any other app; that isn't a promise made by app code. Unmatched page text is not stored. A matched
label is kept only as local evidence against the current video id and is written to the diagnostic
log; it is never included in the Hive payload.

**v0.8.11 transition protection:** the Shorts URL can advance before the old ad
overlay disappears. Log 14 captured a new organic id and the preceding ad's
stale `Sponsored` label in one accessibility snapshot. URL changes now receive
track generations; a label first seen in a transition generation is provisional
until re-observed for that same instance. Another URL, label disappearance,
Stop, or package reset clears it. Accepted evidence travels with the track.

Two caveats worth knowing before you turn it on:

- The address bar describes the **foreground tab**, which isn't always the tab that's playing. A
  scan naming an id binds only to exactly one live, playing session already describing that id; zero
  or multiple matches refuse. An id-less scan binds only when one live playing session exists. A
  paused tab remains an ambiguity but cannot receive evidence. There is no “sole mismatching
  session” fallback, and a non-YouTube URL is never evidence against background playback.
- On Android 13+ a sideloaded APK's accessibility toggle is greyed out until you allow it: **App
  info → ⋮ → Allow restricted settings**. It looks broken rather than blocked.
