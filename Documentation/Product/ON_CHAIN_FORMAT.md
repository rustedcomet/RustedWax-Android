# On-chain format

The exact `custom_json` written to Hive, how artist and title are derived, and how `kind` is
decided.

[← Back to the README](../../README.md)

---

## On-chain format

Identical to the extension — do not change these without changing the indexers:

- `custom_json` id: `hive_scrobble_ai` — **shared with Hive Scrobbler on purpose.**
  The entries belong in the same feed and the same indexers should read both.
- authority: `required_posting_auths: [<username>]`
- `app`: `rustedwax/<version>`
- Payload: [`HiveScrobblePayload`](https://github.com/Holozing1/hivescrobble/blob/master/src/core/scrobbler/hive/hive.types.ts)

The `app` value was `hivescrobblesai/1.0` up to v0.9.19 — byte-identical to Hive
Scrobbler's, which made RustedWax entries indistinguishable from theirs on a
ledger nobody can edit. That contradicted the README's unaffiliated positioning
in the direction that matters: any RustedWax defect landed attributed to someone
else's app, and they had no way to tell the two apart or to filter ours out.
Only the authorship claim changed; the `custom_json` id deliberately did not.

### Private scrobbles

> **Not currently offered in the app.** The four per-kind toggles were taken off
> the settings menu in v0.11.1b because there is no present case for them. None
> of the code below was removed — the stored settings, the cipher, the envelope
> and the fail-closed behaviour are all unchanged, and every stored answer is
> preserved — so this remains the format RustedWax writes the moment the toggles
> are offered again.

When a per-kind privacy toggle is on, the public payload is replaced by the
extension's envelope, byte-compatible in both directions:

```json
{ "app": "…", "kind": "…", "timestamp": "…", "private": "<base64 blob>", "v": 1 }
```

`app`, `kind` and `timestamp` stay in the clear so an indexer can still count a
listen and place it in time. Everything that says *what* was played — title,
artist, album, url, duration, percent — is inside the blob: AES-256-GCM,
`base64(IV‖ciphertext+tag)`, with a fresh 12-byte IV per call so a public block
cannot reveal a replay of the same track without decrypting it. The key is
SHA-256 of the posting key's deterministic signature over the fixed challenge
`zingit:privacy-key:v1`, which is what lets the extension and RustedWax read
each other's entries.

If the key cannot be derived, the listen is **held back**, never broadcast in
the clear.

Scrobble rules, from `hive-scrobbler.ts#finalize`, with one deliberate deviation:

- **Song / video** — 1 tx at the configured threshold (60% by default). Mobile
  has no dedicated podcast payload path.
- **Songs only** — a 2nd tx at ≥160% (a genuine double-listen), capped at 2. Upstream doubles every
  kind; RustedWax caps non-song kinds and every `/shorts/` viewing at one tx. The path cap matters
  even when a music Short is correctly classified as `song`, because the browser still auto-loops it.
- **Movie / episode** — 1 tx at ≥80%. *Not implemented on mobile* (see Limitations).
- **Minimum length: 30 s**, or **10 s for a verified short**. Not upstream — added because YouTube
  pre-roll ads publish their own media session carrying the *video's* title and a ~6-second
  duration, which would otherwise scrobble the song on every ad. Pre-roll is a watch-page
  phenomenon. Regression evidence showed the ordinary floor excluded legitimate Shorts without
  affecting watch-path content, so verified Shorts get their own floor. See "Shorts" in
  [SCROBBLE_RULES.md](SCROBBLE_RULES.md).
- **A continuous looping video still produces at most one scrobble.** An observed playback-position
  reset from the final 20% of an item to its first 20% is logged as a detected loop and caps that
  continuous viewing to one transaction even when its payload kind is `song`. Progress above 125%
  on a verified short remains a fallback probable-loop diagnostic. Neither signal erases the
  qualifying first viewing. A separate later session earns its own new
  scrobble; the inherited ≥160% branch only applies when no loop/reset evidence
  was observed.
- **Progress is measured in content, not clock time.** Played time is scaled by the playback rate,
  because it's compared against `duration`. Watching a 76-second trailer to the end at 1.25× is
  100%, not the 67% of wall-clock that elapsed — and at 2× a fully-watched video used to read 50%
  and never scrobble at all.
- `now_playing` is never broadcast on-chain.

### Artist and title

`Artist - Track` splitting is a **music** operation — it asserts the text left of the dash names a
performer. So it only runs for `kind: song`. For a `kind: video` entry the **channel is the artist**
and the **whole title is the title**, unsplit.

That distinction is not cosmetic. Before v0.8.0 a film trailer went on-chain as
`artist: "Fall 2: Deadpoint (2026) Official Trailer 2"` with `title: "Harriet Slater, Arsema
Thomas"` — a film name in the artist field and a cast list in the title.

A trailing run of hashtags is stripped from either kind (`Rüyamda seni gördüm #dizi #blutv` →
`Rüyamda seni gördüm`). Interior hashtags are kept, because `Song #2 of the series` is doing work.
Beyond tidiness: the tag run used to be part of the title, so the same clip reposted with different
tags counted as a different listen and landed twice.

Other shapes the parser understands, several adapted from the desktop extension: `Artist "Track"`,
`Track (by Artist)`, a leading `[genre]` tag as noise, and album/vinyl track numbers (`03.`, `A1.`).
A leading **CJK** bracket is the opposite — `【Bring Me The Horizon】…` names the *artist*, so the
parser preserves it as identity rather than discarding it as a genre tag.

`album` is populated for Art Tracks, read from the fixed shape of an auto-generated description
(`Provided to YouTube by …` / `Song · Artist` / `Album`). Never guessed from a hand-written
description, and never set on a video.

### How `kind` is decided

Evidence, strongest first — a stronger layer always beats a weaker one:

0. **Auto-generated provenance** → song, above every title rule: music.youtube.com, a `- Topic`
   channel, or a description beginning *"Provided to YouTube by …"*. These are distributor feeds —
   the title is catalogue metadata, not a human description — so title heuristics don't apply.
   It's why a game soundtrack's track called `Tutorial` or `Trailer 2` stays music.
0b. **A title that is only hashtags** → video, whatever the category says. `#guitar #dubstep #fnaf`
   names no work, and a song entry that names no track is permanent playlist debt. Below real
   provenance because a distributor feed's title is catalogue metadata and never looks like this.
1. **Format evidence** → video, beating even the category (uploaders do categorize tutorials and
   music-news bulletins as *Music*): podcast / how-to / gameplay / review titles, `Official
   Trailer`, music-news headlines (`… Releases New Single …`), TV episode numbering
   (`Season 6 Ep 19`, `S06E19`, `11x24` — but not music's `EP 2`, nor `1920x1080`/`16x9`),
   clip channels (`… Movies`, `… Cinema`),
   game playthroughs without an instrument.
2. **The YouTube Music catalogue** (needs YouTube scrobbling on) → song. Keyed by video id, so it
   answers where a string-matched lookup cannot. Positive-only: indie, live and
   personal-channel uploads may not be in the
   catalogue, so absence is never evidence *against* music.
   `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` is explicitly excluded: being present in YouTube Music does
   not turn a podcast—or a timer mislabelled as one—into a song.
3. **YouTube's `Music` category** (needs YouTube scrobbling on) → song.
4. **A MusicBrainz match** on artist + recording → song.
5. **Commentary words that are also song titles** (`reaction`, `tutorial`, `interview`, `episode`,
   `Trailer 2`) → video — below provenance and MusicBrainz on purpose, so "Chain Reaction" and a
   soundtrack's "Tutorial" are rescued while the video formats are caught.
6. **Cover / playthrough vocabulary** → song, when instrument-qualified. Below the words above,
   because "Bass Cover Tutorial" is a tutorial about a cover, not a cover.
7. **A decisive non-music category** → video: Film & Animation, Gaming, News, Sports, Education…
   then **music vocabulary** → song: VEVO/label channels, lyrics / instrumental / remix /
   live-performance / official-audio wording.
8. **Weak evidence** — an `Artist - Track`-shaped title — is accepted only for ordinary videos of
   unknown category, never for shorts, sub-90-second clips, `#shorts`-tagged titles, three-part
   clip captions (`Blade II | Sewers of the Damned | ClipZone…`), **never above 8 minutes** without
   a positive music signal, and never when a known category said not-music. The long-form gate
   matters as much as the short one: a 45-minute podcast titled `Host - Guest Name` is not a song.
9. **No evidence at all** → **video**. (Revised from the original song-default: two days of field
   data showed every default-song hit was a news clip, movie scene or vlog. Real music virtually
   always carries a signal above — and MusicBrainz is the safety net for untagged uploads.)

The Now tab shows the final category without exposing the classifier's working
evidence. When event logging is enabled, the bounded event log records the
classification and identity evidence for diagnosis. Neither surface decides
eligibility or constructs a payload: those remain owned by the one finalization
boundary, because on-chain is forever.
