# Limitations vs the desktop extension

What the desktop connector can do that this cannot, and why.

[← Back to the README](../../README.md)

---

## Limitations vs the desktop extension

These follow from having no DOM access, not from missing work:

- **Only what can be proven.** `platform` requires the origin to be established — from the browser's
  media notification, bound to a session by title, or from the address bar if you enabled that. No
  evidence means no scrobble. Every entry additionally needs a video id, supplied exactly by the
  address-bar/native metadata routes or recovered through the bounded playlist, Mix queue, watch
  history, search/watch-page or run-local resolver. A fallback must produce one unique match against
  the available finalized track fields; when Chromium omitted duration, exact title plus canonical
  channel can qualify only if one id survives. Window state and accessibility coverage do not change
  that identity rule. Failure to verify one is a visible refusal, never a URL-less transaction.
- **Consecutive-playlist recovery needs two verified predecessors in the same run.** It is a fallback
  for the third and later items after the current URL/history disappeared, not a way to guess the
  first unknown item after a restart. Any unresolved intervening track, lifecycle reset, ambiguous
  playlist search, non-adjacent predecessor pair, or multiple distinct matching next-row ids makes
  the route refuse and fall through. Multiple playlists that all prove the same next id are safe.
- **Movies and episodes are classified, not enriched** (v0.10.0). A `movie` or `episode` `kind` is
  set from an explicit season/episode marker, or from a release year plus YouTube's own category and
  a feature-length runtime. `imdb_id`, `wikipedia_url`, `series_*` and `poster_url` stay absent: the
  extension fills those from a page DOM and a Wikidata lookup, and inventing them from a title would
  put guesses on a ledger nobody can edit. The defect this fixes is films filed as songs, and a
  `kind` fixes that on its own. (Artist verification, which desktop gets from Wikipedia/Wikidata, is
  covered on mobile by the MusicBrainz check instead.)
- **Chapters are recorded, not scrobbled** (v0.10.0). The chapter list is parsed from the watch page
  and written to the event log; the video still scrobbles once. Nothing on chain can be amended, so
  a list read after the fact cannot retroactively split an entry — per-chapter scrobbling needs
  per-chapter measurement, dedup keys and N transactions from one video, none of which exist.
- **No dedicated podcast payload path.** Podcast-shaped titles are classified as `video`; Android
  media sessions do not provide the richer disposition the desktop connector uses.
- **Metadata quality depends on the site.** Whatever the page passes to the MediaSession API is what
  you get. Sites that don't set MediaSession metadata are invisible to the app.
- **No guest (Google) ingest path.** Mobile is Hive-only by design.

## Limits measured on the device, not inherited from the platform

These are known, accepted, and recorded here rather than left to be rediscovered:

- **A native YouTube ad-inclusive timeline can still cost a listen, in one narrowed case.**
  The YouTube app sometimes publishes a session duration that appears to include a video's ad
  breaks. Measured 2026-08-27/28, one video whose watch page states **1,192 seconds** was published
  as **2,183 s, 2,163 s, 1,817 s and 1,294 s**, always under its own title.

  The larger half of this is fixed. A surface the app refuses to measure no longer defines the
  listen's length, so a video measured to 90% of its real duration and then hit by a late
  1,817-second surface now finalizes against 1,192 s rather than 1,817 s — see
  [which presentation owns the listen](SCROBBLE_RULES.md#which-presentation-owns-the-listen-native-app).

  What remains is the case where **the true duration is never published at all**. Measured twice on
  2026-08-28, the session's only long-form duration was already the inflated one (2,163 s, then
  1,294 s); 1,192 s never appeared. There is then no in-band evidence of the real length, the
  session duration disagrees with the resolver, and identity refuses. That refusal is correct:
  nothing available distinguishes a defective field from a wrong video id, and the same check is
  what catches a genuinely stale id (226 s against 193 s, the case the duration veto was written
  for).

  Not fixed, deliberately. Recovering it would mean trusting the resolver over the session, which
  is the fail-closed identity rule this product depends on. A narrower idea — waiving the veto only
  when the observed playback envelope independently proves which duration the item actually had —
  was analysed and **deferred**, because it would not have fired on either observed case (both were
  stopped well before the end) and because the field distribution it was justified on has now
  changed. Revisit on fresh data, as its own decision.


- **Playback nothing published can be lost in silence.** RustedWax measures what the phone tells it.
  Measured 2026-08-06: a 227-second video published a MediaSession for **seven seconds**, ninety-four
  seconds in, and nothing before or after — so twelve seconds was a complete account of everything
  it was ever shown. Where the player was when the app first saw it is not evidence that it played
  that far in this session (a resumed video opens mid-track having played nothing now), so it is
  never credited; it is now *stated* on the finalize line instead.
- **A Short played with its audio stopped may be lost without a line.** The check for "something is
  playing that nothing is counting" needs Usage Access and needs the audio to be started. Without
  both, only the case where the observer can see no window at all still reports, and it says
  outright that it does not know whether anything was playing. Under-reporting was chosen
  deliberately: the previous, louder version produced 111 reports in a day, almost none of them
  real, which teaches the reader to ignore the one line that matters.
- **A browser video played with no sound is not seen at all.** Chromium gives muted media no
  MediaSession worth reading — measured 2026-08-09, Chrome published none whatsoever and Brave one
  with no duration and `position = -1`, so a Short watched to the end finalized as `played 172s of
  0s` and could not be scored however well it was identified. Unmuting restores it on the next poll.
  Nothing can be done about this from inside the app: with no session there is no event to observe.
- **Two live windows of one app screen can be confused for each other.** Visibility is tracked per
  activity class, because activity instance ids are not public API. Two live instances of the same
  class share a key, and stopping either reads as stopping both — which fails toward "not visible",
  so it under-credits rather than over-credits.
- **A Short YouTube draws no progress bar for is timed, not measured.** Its seconds come from
  wall-clock on the same audio-plus-visible-window evidence, so they are reported as inferred and
  never as measured. Until identity resolves, the app does not know how long the Short is; the
  ceiling until then is the format's own three-minute maximum, and the real length replaces it the
  moment a bar appears or the video's own page is read.
- **Picture-in-picture credit is inferred, and says so.** Another app playing audio while a paused
  YouTube PiP window sits on screen is indistinguishable from playback and would be credited. That
  is the cost of counting PiP at all; every such listen states how much was measured and how much
  deduced.
