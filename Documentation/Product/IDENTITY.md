# Identifying the video

The authoritative current contract for how a viewing becomes one specific video id, followed by the
lookup details, YouTube policy tradeoff, and MusicBrainz verification.

[← Back to the README](../../README.md)

---

## Current identity contract

This section is normative. It supersedes every older phase plan, field conclusion, test instruction
or code comment that required a visible accessibility scan, playlist provenance, or a foreground
browser before an otherwise verified lookup could scrobble.

Architecture Phase 9 does not change these identity decisions. It removes the
compatibility route around them: every supported finalization trigger enters
the same `FinalizeTrackUseCase` directly when invoked, which consumes typed
identity outcomes and fails closed on ambiguity or contradiction. Automatic
finalization is the current live production caller; manual and write-suppressed
shadow remain supported and behaviorally tested trigger modes without a current
UI or service caller. Reducer state belongs to `MediaSessionDriver`; Android
callback/evidence collection remains at the source composition edge.

1. **Every YouTube payload has one verified id and canonical hyperlink.** There is no URL-less
   fallback.
2. **Only two authorities may establish that id:** an exact id frozen while the track was active, or
   one bounded lookup candidate that uniquely corroborates the available finalized fields. Normally
   those fields are title, owner/channel and duration. If Chromium omits duration after a rebuild,
   one exact finalized title plus the candidate's canonical-page channel may establish one id only;
   two same-title survivors remain ambiguous. A plausible result, multiple matching uploads, a
   partial match, or a contradiction establishes nothing.
3. **A rejected exact id may be replaced; a valid one may not.** Chromium can publish the outgoing
   URL on the first callback of the successor track. Once that id is disproved, the first later
   non-rejected exact URL becomes the observation and generation. Until rejection, the id remains
   frozen so a successor cannot steal an ending listen.
4. **Foreground state and accessibility coverage are not identity inputs.** The same unique lookup
   is valid with Brave visible, minimized, fully backgrounded, or after RustedWax restarts with only
   the MediaSession remaining. Native YouTube follows the same finalized-identity rule without any
   browser dependency.
5. **Ad evidence is independent and positive-only.** A literal ad signal bound to the track is an
   unconditional veto. No successful scan means no ad signal was observed; it does not invalidate a
   verified id and must never be translated into “the browser was never on screen.”
6. **Classification cannot establish identity.** YouTube Music, MusicBrainz, category, uploader
   shape, public/listed state, title words and brand names may refine metadata or kind only after the
   id gate. None can choose an upload or rescue an ambiguous id.
7. **Finalization is immutable.** Lookup and enrichment are corroborated against the ended track's
   available established fields—not the live address bar, the next track, or a browser teardown
   bundle. An absent duration is not a contradiction and cannot erase strong title/channel evidence.
8. **A rebuild must reconstruct source proof before discovering sessions.** Listener reconnect,
   process restart and monitoring Start clear stale browser hints, replay Android's currently active
   media notifications through the same target-package/media-style privacy filter, and only then
   enumerate MediaSessions. They do not wait for Android to post an unchanged notification again.

The code makes the two-authority boundary explicit in `FinalizedVideoIdentityContract`; the
automatic engine cannot reach scrobble rules without it. `ScrobbleRules` accepts no browser
visibility, accessibility-coverage, playlist-provenance or resolver-route arguments, so those facts
cannot silently become an eligibility veto again. Current regression gates are in
[TESTING.md §§40–42](../Testing/TESTING.md#40-v0110g-finalized-identity-in-foreground-background-and-native-playback).

> ### Prototype status and YouTube access
>
> RustedWax is currently a personal concept-testing project, not a policy-cleared release. Its
> video lookup path automatically fetches YouTube watch, playlist and search pages
> and calls an undocumented YouTube Music player endpoint. That is technically useful and deliberately
> retained during prototyping, but it relies on unsupported interfaces, can break without notice, and
> conflicts with YouTube's written restrictions on automated access, scraping and undocumented APIs.
> Personal or non-commercial use does not create an exception to those terms.
>
> A YouTube entry now requires a verified video id and canonical hyperlink. The exact id can come
> from optional address-bar evidence without a lookup; when the browser exposes only a host, recovery
> uses the unsupported playlist/search/watch-page path above. If neither source verifies an id, the
> viewing stays off-chain and appears in **Not logged**. A distribution candidate should replace or
> remove the unsupported lookup path and ship with an appropriate privacy policy. See
> [YouTube policy and prototype tradeoff](#youtube-policy-and-prototype-tradeoff).

### Looking videos up

With a video id available, the app can ask two things about it, cached, from outside the browser.
These are additional requests from the app, separate from the browser session: they disclose the
phone's network address and exact video id to Google even when the browser was signed out, blocking
trackers, or using different cookies. It is therefore a visible switch. Neither metadata lookup is
required for a video whose id was already captured, but a verified id is required before any
YouTube payload can be broadcast.

**The watch page** (one GET) gives YouTube's category — a far better answer to song-vs-video than any
title heuristic — the video's length, whether it is publicly listed, and the description, where cover
uploads credit the original artist and Art Tracks name the album.

**The YouTube Music catalogue** (one small POST to `music.youtube.com`) gives `musicVideoType`: does
YouTube Music hold this video as a recording? Adapted from the desktop extension, and the strongest
music signal available, because it is keyed by **video id** rather than by a parsed artist/track
string — it answers for the Spanish-language and small-channel uploads MusicBrainz returns `no match`
for. Read positive-only: absence is not evidence against music.

On an **Art Track** (`MUSIC_VIDEO_TYPE_ATV`, what a `- Topic` channel serves) it also gives canonical
credits — `Daddy Yankee` / `Con Calma` instead of whatever the uploader typed. On an official music
video it deliberately does *not*: there the "author" is just the channel, so a guitar cover would
come back as `Elena Verrier` / `Metallica - Blackened (guitar cover)` when the title parse already
yields `Metallica` / `Blackened`.

At ~10 KB against ~615 KB for the watch page, it is also the fallback when the page fetch fails —
which happened on ~12% of ids in field testing, and is how a stale video id once reached the chain
with the wrong `url`.

The video id is **latched when the track is first identified** and kept for the track's lifetime
unless that observation is disproved. Chromium can briefly report the outgoing URL after the next
track has started; a rejected outgoing id therefore yields to the first later non-rejected exact URL,
while a still-valid id remains frozen against successor URLs. The latch is then corroborated two
independent ways: the fetched page's own title
must match what the session is playing, **and** its length must agree with the session's duration.
Either disagreeing discards the id, because no `url` beats a wrong `url`. A rejected live id cannot
be returned merely because no older latch exists.

When Chrome removes the media session, the app freezes the ended track's identity before opening
the one-minute continuation window. Any replacement that claims the carried progress also claims
that frozen identity. If no replacement arrives, expiry finalizes the frozen value — it never
consults the then-current address bar, which may already name a later Short.

Both checks are needed because either can be unavailable. The title is the stronger signal but
absent whenever the fetch failed — and that is exactly how a playlist track once went on-chain
linking to the *previous* entry: the bar was 7 seconds late, the page fetch for the stale id had
timed out, so there was no title to compare. The durations were 226 s against 193 s.

When the bar never names the video at all — a playlist advancing behind a hidden toolbar fires no
accessibility event, so it can stay silent for tens of minutes — the app recovers the id through
bounded routes, in order:

1. **A playlist proven by consecutive playback.** RustedWax keeps a run-local placeholder for every
   finalized track and the exact id after one verifies. If the immediately preceding two tracks both
   verified, their ids and titles are used to discover candidate public lists. Titles are discovery
   hints only: every returned public list is inspected, and it qualifies only where the exact
   predecessor ids occur in adjacent rows and in playback order. Only each pair's immediate next
   row may represent the current play; exactly one video id across those rows must match the
   finalized title, channel and duration. Multiple lists may prove the same id, but two surviving
   ids refuse. An unresolved middle track breaks
   the chain, packages never share it, and lifecycle reset clears it.
2. **The directly observed playlist or Mix queue.** If the bar ever mentioned a `list=`, the app remembers that context
   for three hours. Public playlists expose a bounded entry list; `RD…` Mix watch pages expose a
   bounded `playlistPanelVideoRenderer` queue. Exactly one title+channel+duration row establishes the
   id. A playlist/Mix id by itself, a private `LL`/`WL` id, or a queue miss does not.
3. **Your own watch history** (available to native and browser fallback, off until you connect an
   account — see "YouTube watch history" below). The feed names the exact video the phone played,
   which can answer for a single video with no playlist around it, or for anything played with the
   screen off. The candidate is still re-fetched and must uniquely corroborate.
4. **A YouTube search** for the session's title and channel, when there's no playlist or the track
   isn't in the part of it that loaded. The resolver understands ordinary result cards and the
   current Shorts lockup shape. Since a Shorts card omits channel and duration, up to eight
   highest-ranked plausible ids are checked against their own watch pages before one can qualify.

So the full order is **exact frozen id → run-local candidate → playlist proven by two adjacent
predecessors → directly observed playlist/Mix queue → watch history → search**. Direct context is tried
before history because it needs no credentials and one fetch serves every track in it; both are
tried before search because search is only *plausible* where they are exact — searching for one
tested track returned a different upload of the same song by the same artist at the same length,
which no amount of matching strictness can separate. Every route must agree on title, duration and
channel, and multiple matching ids are refused as ambiguous — including two entries of one playlist
and two entries of the recent history. Anything less certain is recorded in **Not logged** and never
reaches the chain. URL-less YouTube entries are no longer a supported fallback.

That order is unchanged by window state. A unique finalized lookup remains authoritative while the
browser is minimized or fully backgrounded and when no successful accessibility scan occurred.
Coverage remains useful for diagnosing the watcher and for capturing literal ad UI, but its absence
does not demote identity. If an exact ad label was captured for the track, the independent ad veto
wins even after identity succeeds.

Playlist and video replacement are atomic when Chromium catches up after a transition. If the
outgoing id was rejected and a later URL supplies the current id and a different `list=`, both fields
move to the later URL generation. Keeping the old playlist beside the new video would silently send
the resolver back into the list that just ended.

Two amendments came out of the field, both about the *title* half of that agreement:

- **A video has two published names.** YouTube auto-translates titles for the viewer, so a watch page
  carries the uploaded name in `videoDetails` and the rendered one in its page data, and which is
  which depends on the language the fetch asks for — verified by fetching one page as `en-US` and as
  `es-419` and getting the two names swapped. Both are compared against what was playing and the
  stronger agreement decides. Both come from the one page being verified for the one id, so nothing
  new is admitted; duration, channel/handle and the single-match rule are untouched.
- **A foreground Short may have no readable title at all**, and refusing on that threw away a
  measurement that was already complete. Handle + duration + a unique match carry it instead; where
  no title exists and one channel has two uploads of the same length, the most recently watched is
  taken, because candidates arrive newest-first and the Short being identified is the one playing
  now. With a title present, two full matches still refuse.
- **It may have no length either** (v0.9.10). YouTube draws no progress bar for a Shorts player that
  is *resumed* by tab navigation, so the field that used to be mandatory can simply not exist. The
  `@handle` is what has outlived every restyle, so it stays mandatory and needs **one** of the other
  two beside it:

  | available | gate |
  | --- | --- |
  | handle + length | unique match; recency breaks a tie between same-length uploads |
  | handle + title | unique match |
  | handle alone | resolves only if the account's recent history holds exactly **one** Short by that channel — and recency may **not** break a tie here, because nothing is left to prefer one by |

  A field that *is* published must still agree, every candidate is still re-fetched and corroborated
  on its own watch page, and the length the page reports is what the percentage is then measured
  against.
- **A handle may be spelled in any script** (v0.9.13). It was read as ASCII only until then, so
  `@eduardaarebouçass` was refused on the cedilla — and since the handle is the one mandatory field,
  the listen went with it. A handle is now letters, marks, digits and YouTube's `.`, `_`, `-`, three
  to thirty characters, in any script, composed to NFC so one handle encoded two ways compares as
  one. The same applies where the handle is corroborated against the video's own owner URL.

**Watch history also backs up the browser** (v0.9.17). The browser routes lead with the address bar,
and that is read by an accessibility service Android switches off whenever it crashes. With it off,
a video whose title is a single hashtag cannot be named by search at all — measured 2026-08-09,
`#hoyoverse` watched to 98% and refused. When no id has been proven by any route, the signed-in
account's own history is asked, and a Short is matched on title with the **channel** doing the job the
native route gives the `@handle`. The candidate's own page still has to agree, uniquely. A working
address bar always wins; this only ever runs when there was nothing.

> This scrapes an undocumented blob out of the watch page and **will** break when YouTube changes it.
> Failures are logged as `EXTRACTION FAILED` precisely so breakage is distinguishable from a video
> that simply had nothing to add.

### YouTube policy and prototype tradeoff

The lookup path is an explicit prototype compromise, not a claim of YouTube compliance:

- watch, playlist and search metadata is extracted from YouTube web-page internals;
- the music verdict comes from the undocumented `youtubei/v1/player` endpoint with a `WEB_REMIX`
  client identity; and
- the fetches use a desktop browser user-agent because the mobile shell does not contain the same
  data.

YouTube's Terms of Service prohibit automated access such as scraping without prior written
permission, and its API Developer Policies separately prohibit scraping YouTube applications and
using undocumented APIs without express permission. The app does not download media, extract audio,
alter playback, automate engagement or block ads; the concern is the metadata-access method itself.

This behavior is retained for concept testing because category, listed status, catalogue membership
and fallback id recovery materially improve fidelity. Before broader distribution, the intended
compliant direction is to measure what the native YouTube/YouTube Music media sessions expose, keep
the Android-only best-effort path, and remove or replace unsupported lookups. Turning **Look videos
up** off exercises that conservative path today.

Official references, checked 2026-07-30:
[YouTube Terms of Service](https://www.youtube.com/static?template=terms) and
[YouTube API Services Developer Policies](https://developers.google.com/youtube/terms/developer-policies).

### MusicBrainz verification

The same switch also checks the parsed artist/track pair against [MusicBrainz](https://musicbrainz.org),
the open music database. A match requires the artist name **and** the recording title to both agree —
title-only matching would claim every clip named like some song. A confirmed match:

- counts as **explicit music evidence** (it can qualify a short performance clip that has no music
  words in its title), and
- supplies the **canonical spelling** of artist and title, so entries line up with scrobbles of the
  same recording from anywhere else.

MusicBrainz never selects or verifies a YouTube video id. It runs only after the finalized identity
gate and cannot rescue an unresolved or ambiguous upload.

Small or unsigned artists simply aren't in the database — that's a missed upgrade, never a wrong
one; classification proceeds as if the check hadn't run. Lookups honor MusicBrainz's 1-request/second
etiquette and are cached (including "no match") so each track is asked about once.
