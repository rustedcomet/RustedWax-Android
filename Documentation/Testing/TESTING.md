# Testing RustedWax

## Non-negotiable end-to-end scrobble acceptance rule

For any production change that can affect playback detection, progress,
identity, enrichment, classification, eligibility, finalization, payload
construction, deduplication, queuing, signing, or broadcasting, automated tests
alone are not acceptance evidence.

The affected real source path must be tested end to end on the exact APK under
review:

1. Capture the working or failing behavior before the change when a usable
   baseline exists.
2. Keep automatic scrobbling **on** for the acceptance run.
3. Play through the real eligibility threshold and let the production
   finalization path complete, including its actual lifecycle timers.
4. Require a `broadcasting` payload and a returned blockchain transaction ID.
5. Read the transaction back from at least two independent Hive RPC nodes and
   confirm the operation bytes match the device payload.
6. Re-test every affected source or behavior boundary. A package/capability
   isolation test may prove that an unrelated surface is unreachable from the
   change; otherwise that surface needs its own device transaction.

An auto-scrobble-off run proves only observation, identity, or no-write safety.
It **never** proves that scrobbling works and cannot close a behavior regression,
release gate, or architecture-phase entry gate. If the exact reviewed APK does
not produce and return the expected blockchain transaction, the end-to-end
result is **NOT ESTABLISHED**, regardless of unit-test, lint, build, resolver,
payload-preview, or non-broadcast smoke results.

Architecture cleanup is behavior-preserving work. Keep the last working path
intact until the cleaner implementation has passed the same real transaction
gate. A source change after device proof invalidates that proof whenever it can
reach a decisive condition exercised by the run; rebuild, reinstall, and
repeat the transaction test on the new exact bytes.

**Current development gate (2026-08-24):** v0.11.1, version code 58,
**1,319 unit tests in 128 suites** with zero failures, errors, or skips; debug
main/test APK assembly successful; lint at **0 errors / 38 warnings / 2
informational**. The exact Android gate contributed 1,281 tests in 125 suites;
the explicit extracted-module gate contributed 38 Hive tests in 3 suites.
Counts are parsed from JUnit and lint XML, not inferred from the Gradle console.
§§1–51 retain their historical gates; §52 records Phase 8 dependency-direction
acceptance and its current-artifact physical boundary.

**Current local/clone/installed main APK:** SHA-256
`a1e615788a6aa4d0728d408ab973b567cd3e394f9ec126feb8d1329a4e40943c`.

**Current local/clone test APK:** SHA-256
`53c63e18023784480f4de2f43eb984737fd19a355ee82ef846b50951f309cae7`.

**Historical 2026-08-20 behavior-fix main APK:** SHA-256
`dca2c02eb141a36ce1258e9be4981e593289c6933c6aad116bfcc97956ed2908`.
That behavior-fix local build, supporting independent no-hardlinks clone, and
then-installed Galaxy A36 `base.apk` were byte-identical. It is retained as the
§49 artifact ledger, not the current installed build. The unchanged test APK is
`4e2769a304a19ded570a2bdd08a567ac59c11f0a818b0216c3cec8bec075f983`.
No current `dist/` artifact was authorized or used as acceptance evidence.

The following paragraph is the retained Phase 0/1 checkpoint, not the current
project phase. The Phase 0/1 gates §44 left open — a literal field corpus, the global one-outcome rule, and the
distributed artifact — are closed in §45, two of them by an explicitly recorded amendment to the
plan rather than by satisfying the original wording: the 189-row batch is not recoverable and was
not fabricated, and true old/new shadow parity cannot exist until Phase 2 produces a second
implementation to compare against. Phase 2 is the next phase, and it owes transactions of its own.

The normative video-id rules are [Documentation/Product/IDENTITY.md](../Product/IDENTITY.md#current-identity-contract).
Older sections that require a visible scan or playlist provenance for a resolver-only browser track
are preserved as historical gates, not current expectations. The current architecture makes that
distinction compile-time visible: `ScrobbleRules` has no coverage/visibility/route inputs, and
`FinalizedVideoIdentityContractTest` owns the exact-id-or-unique-lookup boundary.

v0.8.7 is the contract-reconciliation build. It removes the high-progress
Short rejection, defers finalization across browser session churn, makes Stop a
complete evidence boundary, separates block/mempool/unconfirmed broadcast
states, binds queued work to its account, and makes the Now/manual paths use the
configured rules. The canonical contract and the exact v0.8.6 discrepancies are
in [BEHAVIOR_CONTRACT.md](../Product/BEHAVIOR_CONTRACT.md).

v0.8.8 is the rule-correction patch from the next physical-device run:
explicitly unlisted Shorts are rejected at every duration, YouTube Music's
podcast-episode type is not song evidence, and an observed end-to-start
playback-position reset is carried across Chrome session recreation and caps
the continuous viewing to one transaction regardless of payload kind.

v0.8.9 adds an explicit visible-ad veto for the Shorts feed and closes the
late-address-bar race exposed by log 11. Exact YouTube controls such as
`Sponsored` are bound to the current Short; brand and title text are not used as
ad guesses. A track waiting through Chrome's one-minute continuation window now
keeps its frozen identity and ad evidence instead of consulting a later Short's
live URL when it finally ends.

v0.8.10 makes a canonical YouTube hyperlink mandatory. It parses the modern
Shorts search shape that log 12 exposed, corroborates title-only Short
candidates against their watch pages, rejects ambiguous ids, and refuses
automatic, manual, or queued YouTube payloads whose id was not verified.

Log 14 remains the broad v0.8.10 field-verification record. Its chain/profile
boundary passed completely, but it exposed four pre-broadcast losses and four
song-credit parser failures. v0.8.11 implements the exact automated regressions
in §13: finalized snapshot isolation, URL-generation ad evidence, bounded
duration refinement, and structure-aware song credits. The v0.8.11 APK was
generated and passed its source gate. Its first physical-device gate was
attempted in log 16 and failed; §14 preserves that evidence and §15 records the
implemented generic correction. Its automated gate passed; log 17 then tested
the corrected artifact and again failed the device gate while proving a clean
67/67 transport/profile reconciliation. Sections 16–17 preserve that evidence
and define the implemented v0.8.12 correction. Section 18 records the broad
log-18 v0.8.12 device round; §19 defines and verifies its v0.8.13 correction.
Section 20 records the failed log-19 v0.8.13 device round and the implemented
v0.8.14 correction. Section 21 records the failed log-20 v0.8.14 device round,
the complete reconciliation and the bounded implemented v0.8.15 correction.
Section 22 records the final log-21 v0.8.15 field round, chain/profile
reconciliation, accepted limitations and Phase 4 release decision.

From the 2026-07-29 46-video run (0.8.0): verified shorts scrobble from 10
seconds (§6), a **Not logged** tab giving every skip a visible reason, durations
recovered from the watch page when the media session omits them, and
`Artist - Track` splitting restricted to songs so trailers stop going on-chain
with a cast list in the title.

From the Chrome run that evening (0.8.1), both of which are what §6 and §7 now
test: the shorts ad guard is `isUnlisted` rather than "the page resolved", after
an 18-second ad reached the chain — YouTube serves shorts ads at real `/shorts/`
URLs with real watch pages. And **played time is now scaled by playback speed**,
because it wasn't: a trailer watched to 79% at 1.25× went on-chain as 67%, and at
2× a fully-watched video read 50% and wouldn't have scrobbled at all.

From reading the desktop extension side by side with this app (0.8.2): a
**YouTube Music catalogue lookup** that answers where MusicBrainz returns `no
match`, the **`album` field** finally populated, **upper duration gates** so a
45-minute podcast can't parse as a song, four more title shapes, and a
**duration cross-check** on the latched video id — which closes a path that had
already put a wrong `url` on-chain. §8 covers all of it.

RustedWax is an independent app. It is not part of scrobble.life, Hive Scrobbler
or Web Scrobbler, and is not supported by them — see the README.

> **Prototype policy note.** Tests run with **YouTube scrobbling** on exercise automated YouTube page
> extraction and an undocumented YouTube Music endpoint. Those interfaces are retained for concept
> testing but are unsupported, fragile, and conflict with YouTube's written automated-access,
> scraping and undocumented-API restrictions. This is not a policy-cleared distribution build.
> Testing with lookups off covers the conservative Android-metadata-only path. See
> [README.md](../../README.md#youtube-policy-and-prototype-tradeoff).

Automatic scrobbling is **off until you enable it**, and the switch is disabled
until a key is saved. **Monitoring** (the outer switch) is on by default; Stop
tears down all observation and discards the in-flight track without
broadcasting it.

## Before you start

Broadcasts are **permanent and public**. Use a throwaway Hive account, or a
posting key you're willing to rotate. See "About your posting key" in the app.

Ideally: from desktop Keychain, add a fresh public key to your account's posting
authority and give the app that key. Then revoking it later costs you nothing.

## Install

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Verified debug artifact SHA-256:

```text
f5139ecc4d5b12900cc0653dfb342b3c5b9d6ae9534b721a78fd8750717ed999
```

## 1. Key validation (no chain writes)

Settings → **Hive account** → **Connect** → enter your Hive username and posting key →
**Validate & save**.

The app derives the public key locally and compares it against your account's
on-chain `posting.key_auths`. Things worth trying:

| Input | Expected |
| --- | --- |
| Correct username + posting key | "Key verified … and saved", the `STM…` key shown |
| Correct key, wrong username | Rejected, naming the key it derived vs what the account lists |
| A typo'd key | Rejected as not a valid posting key (checksum fails locally, no network call) |
| Your **public** key pasted in | Rejected — it isn't a WIF |
| Airplane mode | "Couldn't reach a Hive node" — never a silent pass |

Nothing is written to the chain by any of this. The key is stored in
`EncryptedSharedPreferences` under an Android Keystore master key.

## 2. Test broadcast (writes to the chain)

Settings → **Hive account** → **Manage** → **Broadcast a test scrobble**. Sends a fixed payload with
`title: "RustedWax Mobile test scrobble"`, `platform: "test"` so it's obviously
synthetic.

### Where the transaction id appears

In the app, in two places:

1. **Settings → Hive account**, in the status line under the buttons:
   `Confirmed in a block — tx f0569fe0…`

   Mempool and unavailable-confirmation outcomes use the distinct wording in §10.
2. The **Log** destination, which also records the exact payload sent, tagged `[hive]`.
   It exists only while `Event log` is on; so does **Export**.
   Long-press or use **Export** to get the text off the device.

The id is computed on-device (`sha256(serialized tx)[0..20]`) because
`condenser_api.broadcast_transaction` returns an empty result on success. It's
the same id the explorers use — verified against dhive in the unit tests. Since
0.8.4 the app also looks that id up before calling anything a success. A block
status is the normal confirmed path. An independent mempool observation and an
unanswered independent confirmation are retained without retrying, but neither
is called block inclusion. See the README's confirmation section before
interpreting a History tx id as irreversible inclusion.

### Where to look it up

Paste the id into any Hive explorer:

```
https://hiveblocks.com/tx/f0569fe0...
```

Alternatives if one is down or slow to index: `https://hivehub.dev/tx/<txid>`,
or `https://hivexplorer.com/tx/<txid>`.

**Easier route — skip the id entirely.** Open your account's history and look at
the most recent operation:

```
https://hiveblocks.com/@your-username
```

A successful scrobble shows as a `custom_json` operation. Expand it and check:

- `id` → `hive_scrobble_ai`
- `required_posting_auths` → `["your-username"]`
- `json` → the payload, with `app: hivescrobblesai/1.0`

**Timing:** a transaction takes ~3 seconds to enter a block, and explorers can
lag a few seconds more. If the tx page 404s immediately after broadcasting, wait
10 seconds and reload before concluding anything went wrong — the account
history view is usually the faster of the two to update.

This is **gate G4** — the one thing the unit tests can't prove. The signing math
is verified byte-for-byte against dhive, but only a real broadcast proves the
whole chain of assumptions.

Errors worth recognising:

| Message | Meaning |
| --- | --- |
| `missing required posting authority` | The key doesn't actually hold posting authority — validation should have caught this |
| `insufficient RC` / `resource credits` | Account is out of resource credits; not a bug |
| `Couldn't reach a node` | Transport; every node failed or was too stale to use |
| `Not on-chain yet — …` | Independent healthy nodes explicitly did not find it; the automatic path queues these, the manual button asks you to retry |
| `Seen relaying in an independent node's mempool` | Independently observed but not yet in a block; not retried |
| `Accepted … confirmation was unavailable — do not retry` | The accepting node succeeded but independent services could not answer; not presented as block inclusion and not retried |

## 3. Real scrobble from YouTube

1. Play a YouTube video in Chrome (or Brave, on a real device).
2. Now tab → the session card appears.
3. Check the **payload** block — this is what would be written:
   - `artist` should be the *real* artist, not the channel. For
     `"Korn - Trash (Official Audio)"` on channel `KornVEVO`, expect
     `artist: Korn`, `title: Trash`.
   - **kind because** names the rule that decided `song` vs `video`;
     **category** shows what YouTube's own page said (needs address bar +
     lookups on); **yt music** shows the catalogue's `musicVideoType` or
     `not in the catalogue`; **musicbrainz** shows `✓ Artist — Title` on a
     confirmed match, `no match`, or `— (not checked yet)`; **listed** is what
     the verified-Short floor turns on. **album** appears on Art Tracks only.
   - Titles are normalized to the original recording: `(Live)`,
     `(Instrumental)` and `【Guitar Cover】` are stripped; `(Remix)` is kept.
4. Once the configured threshold and duration rules are satisfied, tap
   **Broadcast this scrobble** → verify on hiveblocks as above. Before the
   threshold, the same button must refuse with the automatic rule's reason.

The badge tells you whether it's broadcastable:

| Badge | Meaning |
| --- | --- |
| `payload OK` | Proven YouTube *with* a video id — full payload including `url` |
| `waiting for verified video id` | Proven YouTube origin, but no payload exists until finalization recovers an id |
| `not proven YouTube` | Can't identify the site — deliberately refuses to guess |
| `no duration` | No DURATION, so percent-played can't be computed |

Non-browser apps (Spotify, podcast players) no longer appear at all — the log
records a single `ignored: <package>` line and nothing is read from them.

## 4. Automatic scrobbling (the Phase 3 feature)

Flip **Automatic scrobbling** on at the top of the screen, note the configured
threshold, then play a track in Chrome/Brave and **let it finish** — or skip to
the next track once you're past that threshold. A scrobble fires when the track
ends, not merely when it crosses the threshold.

Then check the **History** tab: each entry shows percent played, status, and the
transaction id. History holds the newest 50 results for the current app process;
it is diagnostic memory and is expected to clear after process death.

What to try:

| Case | Expected |
| --- | --- |
| Play a song past 60%, then skip to the next | One scrobble at the percent you reached |
| Play a song to the end | One scrobble at 100% |
| Skip out at 20% | Nothing — the Log says "below 60% threshold" |
| Let the same **song** play twice through (~170%) | **Two** scrobbles: 100% and 70% — the double-listen rule |
| Let a **short** loop past 160% | **One** scrobble — the `/shorts/` source is capped at one tx even if classified as `song` |
| Replay a song immediately from the beginning | A second eligible listen and a second tx; its dedup key has a different exact `start:` value |
| Play with the app closed | Still scrobbles; detection lives in the listener service |
| Turn on airplane mode, play a song through | Scrobble is queued, "N waiting to send" appears; turn networking back on and hit **Retry now** |
| A pre-roll ad, or any watch-page clip under 30s | Ignored — **Not logged** says "under the 30s minimum (not a verified short)" |
| A **verified short** of 10s or more, watched through | Scrobbled since v0.8.0 — see §6. A short whose watch page didn't resolve is still held to 30s, and says which |
| A clip under 10s | Ignored on every path, shorts included |
| Park on a looping 10s short for minutes | **One** scrobble. Log says `probable short loop detected — first viewing kept`; no `looped unattended` rejection |
| A short whose media session publishes no duration | Scrobbled if it clears the rules — the length comes from the watch page, and the Log says "duration recovered from the watch page: Ns" |
| Press **Stop** mid-track past 60% | Nothing broadcast, nothing in History — Stop never scrobbles on the way out |
| Press **Start** again | Monitoring resumes without touching Notification Access |
| Stop while a full video URL is visible, navigate elsewhere, then Start | Pre-Stop URL/playlist evidence is not reused; the new session must earn fresh evidence |
| While stopped, dismiss a Brave/Chrome media notification | No notification/title line is added; removal callbacks return before reading extras |
| YouTube in one tab + another audio site in another | Each session keeps its own origin; closing one tab doesn't kill the other's scrobble |
| Browse shorts with Browser evidence access on | The video id is latched at track start — the `url` on-chain matches the short you watched, not the next one |
| Tap **Broadcast this scrobble** below the configured threshold | Nothing sent; status gives the same below-threshold reason as automatic scrobbling |
| Tap **Broadcast this scrobble** after it qualifies, then let the track finish | **One** scrobble. The manual send claims the ledger, so the automatic finalize is blocked — Log says "already scrobbled" |
| Tap **Broadcast this scrobble** twice | The button reads `Sending…` and is disabled during the send; a second tap after it returns is refused with "Already scrobbled" |

Watch the **Log** tab while testing. Every decision is recorded: `[finalize]`
when a track ends, then `[engine]` with either the payload broadcast or the
reason it was skipped. **Not logged** is the same skip reasons without the
grepping — use it first, and fall back to the Log when you need the surrounding
`[url]` / `[identity]` / `[enrich]` lines.

### Does it survive the app being closed?

This is the one behaviour that needs a **physical device** — BlueStacks won't
tell you anything useful. Swipe the activity away, keep playing music, reopen it
before Android kills the listener process, and check History. Detection runs
inside the notification listener, which Android keeps bound while the grant is
held. If Android kills the whole process, in-memory History resets; verify the
chain and exported Log instead.

If the screen says "Waiting for Android to start the listener service", toggle
Notification Access off and on.

## 5. Compare against desktop

The strongest check: scrobble the same song from the desktop extension and from
the phone, then compare the two `custom_json` payloads field by field. They
should differ only in `platform`, `url`, `percent_played` and `timestamp`.
Any difference in `title` or `artist` is a metadata-filter gap worth reporting.

## 6. Verified Shorts and shorts-feed ads

A **proven public** YouTube short scrobbles from 10 seconds instead of 30. That
means three things: the address bar (or the native foreground player) showed a
`/shorts/` path, the video resolved on its own watch page, and that page said the
video is **publicly listed**.

There is one Shorts switch, **Disable Shorts** in Settings → Advanced, and it is
**on** on a fresh install. The second switch, *Short clips*, was **removed in
v0.11.1**: it only decided whether a proven Short could use the lowered floor, and
two switches with almost the same name is how somebody ends up certain they turned
Shorts off while Shorts keep landing on the chain.

**An unproven Short is now refused rather than held to 30 seconds.** The old
fallback admitted anything unproven on length alone, which is the shape of the
v0.8.7 leak. A failed lookup, an absent listed field and an `isUnlisted` page are
all refusals at any length.

Everything on `/watch` still needs the 30-second floor and the configured
threshold. Nothing about the watch-path floor changed.

### 6a. The happy path

1. Open a shorts feed in the browser and scroll through 15–20 clips, letting
   each play to the end but not parking on any of them.
2. Check **History** — short clips of 10 s and up should now appear.
3. Check **Not logged** — this is the new tab, and it is the point of the
   exercise. Every clip that *didn't* scrobble should be there with its reason:

   ```
   track is 7s, under the 10s minimum (verified short)
   played 34%, below 60% threshold
   track is 12s, under the 30s minimum (a short, but its watch page didn't resolve …)
   ```

   A skipped clip with **no row here and no reason** is the bug to report.

4. If the Log says `(watch page unavailable — music client only)`, a clip under
   30 seconds must still say its watch page did not resolve. A YouTube Music
   length/listed result may recover metadata, but must not be renamed into
   watch-page proof for the lowered floor.

### 6b. Ads in the shorts feed — explicit evidence

**Use Chrome, not Brave: Brave blocks the ads, so the case can't be reached.**

There are now two independent ad vetoes:

- An explicitly unlisted `/shorts/` item is rejected at every duration.
- With **Browser evidence access** on, an exact visible YouTube control such as
  `Sponsored`, `Ad`, or `Skip ad` is bound to that `/shorts/` video id and
  rejected at every duration.

The second path is the mobile equivalent of the desktop Hive Scrobbler
connector checking YouTube's explicit `.ad-showing` page state. It does not
guess from `PONDS CAM`, another brand/channel name, the title, or generic words
about advertising.

1. On **Chrome**, not on a Premium plan, scroll a shorts feed long enough to hit
   several ads. Keep **Browser evidence access** on.
2. For an explicitly unlisted creative, **Not logged** should read:

   ```
   a short and the video is unlisted — almost certainly a feed ad
   ```

3. For a public promotion whose page visibly says `Sponsored` (or another
   supported exact label), the Log should contain both:

   ```
   [ad] com.android.chrome → YouTube UI marked <video-id> as an ad ("Sponsored")
   [ad] com.android.chrome bound explicit ad evidence to <video-id>: "Sponsored"
   ```

   Its **Not logged** reason should be:

   ```
   YouTube's visible UI marked this Short as an ad ("Sponsored")
   ```

4. Both are unconditional vetoes. Test an ad longer than 30 seconds if one
   appears, and test the Now tab's manual **Broadcast this scrobble** button. No
   `[engine] broadcasting` line may follow either explicit ad reason.
5. Check that ordinary content is not guessed to be an ad. Titles/channels such
   as `PONDS CAM`, `Bad and Boujee`, `Includes paid promotion`, `Adidas`, and
   `Add to queue` do not match by themselves.
6. If the Log's latest URL evidence is only a bare `youtube.com` host, a visible
   label must not be attached to the previously seen Short id. Exact ad evidence
   requires the concrete `/shorts/<id>` URL and label in the same accessibility
   snapshot.
7. The enrichment line remains useful for the unlisted path:

   ```
   [enrich] CYgQQqvwwsY → category=People & Blogs originalArtist=— listed=no (unlisted)
   ```

   `listed=?` still fails closed for the lowered Short floor. If a visible ad
   produces neither an `[ad]` line nor an unlisted result, export that log: the
   likely boundary is that this YouTube/browser build did not expose its label
   through Android accessibility. In that case **Never scrobble this again**
   remains the fallback.

### 6c. Leaving one looping

Park on a single short for 3–4 minutes with the screen on. It should land in
**History exactly once**, normally at 100%. When Chromium publishes the position
wrap, the Log should say:

```
playback position wrapped from end to start — loop detected
```

If it does not publish a usable position transition, the Short fallback should
still say:

```
probable short loop detected — first viewing kept; video remains capped to one scrobble
```

It must not appear in **Not logged** as `looped unattended rather than watched`.
The app only calls the wrap detected when the previous position was in the final
20%, the new position in the first 20%, and the jump covered at least half the
duration.

Then reproduce the v0.8.7 timer case on a normal `/watch` URL:

1. Play `2 Minute Timer Bomb [COOKIE] 🍪` through the end and let it restart.
2. Its YouTube Music line may say `MUSIC_VIDEO_TYPE_PODCAST_EPISODE`, but the Now
   card must remain `kind: video`.
3. If Chrome rebuilds its media session at the loop boundary, the resumed line
   must also say it carried a detected loop.
4. Exactly one transaction may be sent. The v0.8.7 run sent two identical
   `kind: song` transactions.

## 7. Playback speed

Not tested before 0.8.1, and it found a real bug: played time was counted in
wall-clock seconds and compared against the video's duration, so any sped-up
viewing under-reported itself.

1. Play a video of a minute or more and set the speed to **1.25×** or **1.5×**
   partway through. Let it finish.
2. `percent_played` should reflect **how much of the video you saw**, not how
   long you sat there. Watching a 76-second video to the end at 1.25× is 100%,
   not 67%.
3. The finalize line names the rate when it wasn't 1×:

   ```
   [finalize] … — played 76s of 76s (up to 1.25× speed)
   ```

4. The case that used to fail outright: play a video **fully at 2×**. It should
   scrobble at 100%. Before this fix it computed 50% and fell below the
   threshold, so it produced no entry at all.
5. Also try **0.5×** — it should count *less* than the wall-clock time, since you
   consumed less video than seconds elapsed.

Pausing mid-track must not be affected: a paused session reports `speed=0.0`, and
that falls back to 1× rather than erasing the time already played.

## 8. Adapted from the desktop extension (new in 0.8.2)

### 8a. The YouTube Music catalogue

Every video now also gets a small POST to `music.youtube.com`, keyed by video id.
It's the signal MusicBrainz couldn't give, because it matches on the id rather
than on a parsed artist/track string.

1. Play something MusicBrainz has never heard of — a small Latin or regional
   channel is the reliable case. The Now card should show:

   ```
   yt music     ✓ MUSIC_VIDEO_TYPE_ATV
   musicbrainz  no match
   kind because YouTube Music catalogue
   ```

2. On an **Art Track** (`…_ATV`, a `- Topic` upload) the artist and title should
   be the catalogue's, not the uploader's — `Daddy Yankee` / `Con Calma`.
3. On an **official music video** (`…_OMV`) the kind should be `song` but the
   credits should still come from the title parse. For a guitar cover expect
   `Metallica` / `Blackened`, **not** `Elena Verrier` / `Metallica - Blackened
   (guitar cover)`. The extension trusts OMV credits; we deliberately don't.
4. A non-music clip should read `not in the catalogue` and change nothing.
5. `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` must read as not recognised as music. It
   must not overrule an Education category or turn the timer regression into a
   song.

The log carries it on the existing per-video line:

```
[enrich] GQwj_FRntp8 → category=Music originalArtist=Daddy Yankee listed=yes ytmusic=MUSIC_VIDEO_TYPE_ATV
```

**Worth watching for:** the lookup is positive-only by design. If you ever see a
real song *demoted* to `video` because of it, that's a bug — absence of a
catalogue entry must never count against music.

### 8b. `album`

Play a `- Topic` upload (an auto-generated Art Track). The Now card should show an
`album` row and the payload should carry it — the first time RustedWax has ever
populated that field.

A hand-written description must yield **no** album. If you see an album on a
cover upload or a trailer, that's the prose guard failing.

### 8c. Upper duration gates

Play something long with a dash in the title — a podcast episode, a 45-minute
interview, a stream VOD — with lookups **off** so there's no category to help.

Expected: `kind: video`, reason `long-form (45 min) with no explicit music
signal`. Before 0.8.2 that became a `song`, because a dash was read as naming an
artist.

Then check the reverse: a **full album upload** or a **DJ set** of the same length
should still be `song`, because `full album` / `live at` / `mix` vocabulary is
checked first.

### 8d. Title shapes

| Title | Expected |
| --- | --- |
| `BABYMETAL "Gimme Chocolate!!"` | artist `BABYMETAL`, title `Gimme Chocolate!!` |
| `Nevada (by Vicetone)` | artist `Vicetone`, title `Nevada` |
| `03. Trash` | title `Trash` |
| `[Future Bass] Vicetone - Nevada` | artist `Vicetone`, title `Nevada` |
| `【Bring Me The Horizon】I Used to Make Out With Medusa` | artist **`Bring Me The Horizon`** — CJK brackets still name the artist |
| `1999`, `7 Rings` | unchanged — a number that *is* the title |

### 8e. The latched-id duration cross-check

This is the one that fixes a wrong `url` already on-chain. The failure needs a
**playlist** to reproduce, because it depends on the address bar lagging a track
change:

1. Play a music playlist and let it advance on its own several times, ideally with
   the app in the background and the toolbar hidden.
2. For every scrobble, check that the `url` opens **the track that was scrobbled**.
   That is the whole test.
3. When the app catches a stale id, the log says so explicitly:

   ```
   [identity] com.brave.browser unlatched GQwj_FRntp8: page is 193s but the session is playing 226s
   ```

   A track whose id cannot be recovered must appear in **Not logged** as
   `video id could not be verified`. Any broadcast without `url` is a failure.

## 9. Muting a video, and the quiet-bar warning (new in 0.8.3)

### 9a. Never scrobble this again

A dedicated **unlisted** creative is caught by the rules (§6b). A public
promotion is also caught when YouTube exposes an exact visible ad label through
Android accessibility. If it does not expose that label—or Browser evidence
access is off—the promoted video remains identical to the same public video
reached organically. `PONDS CAM — Consigue tu rutina ahora.` reached the chain
through that former evidence gap.

So: **History** rows now carry a **Never scrobble this again** button.

1. Find an entry you don't want counted again and tap it. The row should change to
   `Muted — this video won't scrobble again`, and the log should say
   `muted <id> — "<label>" will not scrobble again`.
2. Play that same video again and let it finish. **Not logged** should read
   `muted video — you asked never to scrobble this one again`.
3. Open the same video on the Now tab and press **Broadcast this scrobble**. It
   must refuse — `That video is muted…`. If the manual button gets through, the
   mute is bypassable and that's the bug.

It cannot remove the entry already on-chain. Nothing can — that's the point of the
chain, and the confirmation message says so.

### 9b. Browser evidence going quiet

The 0.8.2 run lost 13 minutes to this without saying a word: the watcher reported
`connected`, read the collapsed omnibox once, and went silent. Historically that
created five entries without links and lost four more shorts. v0.8.10 instead
attempts recovery and keeps any still-unresolved viewing off-chain.

To reproduce deliberately: with monitoring and lookups on, **revoke Accessibility
from system settings mid-session**, then keep watching shorts.

After three consecutive tracks with no video id you should get a red banner —
*"The address bar has gone quiet"* — naming both costs and offering an
Accessibility button, plus one log line:

```
[url] the address bar has named no video for 3 tracks in a row — unresolved
      tracks will not be broadcast. Check Accessibility…
```

Two things to confirm:

- **Re-granting clears it.** The counter resets on the first track that gets an id.
- **A long video must not trigger it.** Play a 30-minute video on the watch path:
  the bar names the id once and then goes quiet for the whole video, and that is
  normal. If the banner appears there, the counter is measuring the wrong thing.

## 10. Broadcast confirmation and node health (new in 0.8.4)

On 2026-07-30 the app reported seven scrobbles it had not made. `api.openhive.network`
froze 77 minutes behind the chain, kept answering RPCs normally, swallowed five
transactions into a block it would never produce, and refused two more with a
per-block rate limit that only makes sense if the block never advances.

Accepted outcomes now remain distinct instead of sharing one success label.

### 10a. The happy path reads differently

Scrobble anything and watch the timing. A broadcast can take **3–15 seconds
longer** than the accepting RPC, because the app polls independent healthy
nodes for block or mempool evidence. That delay is the feature.

- `Confirmed in a block — tx …`: look it up on an explorer; it must exist.
- `Accepted by <node> and seen relaying in an independent node's mempool — tx …`:
  it is independently relaying but is not yet claimed to be in a block.
- `Accepted by <node>, but confirmation was unavailable — do not retry — tx …`:
  independent services did not answer. This deliberately keeps the manual dedup
  claim because retrying could create a second transaction.

Before v0.8.7 all three were displayed as `Confirmed on-chain`, even though the
last two are not block proof.

### 10b. Force a stale node

The check that would have prevented the whole incident. If you can point the app
at a known-stale node, do that; otherwise this is mostly a code-reading check.

Expected: the stale node is skipped silently and the next healthy one is used. If
**every** node is stale you should get `Couldn't reach a node: <host>: Ns behind
the chain` — never a false success.

### 10c. Transient refusals queue instead of vanishing

Hive allows a limited number of `custom_json` operations per account per block. To
provoke it, scrobble while something else on the same account is also posting
custom_json — your `flix` app, a Hive frontend, anything chatty.

Expected: **History** shows `waiting to retry — …` and the queue count goes up.
The listen must **not** disappear. Then hit **Retry now** and confirm it lands.

Change the saved account while an entry is queued. **Retry now** must leave the
entry untouched and log that it belongs to the original account. Restore the
matching account/key, retry, and verify that History retains the original
percent and video mute action. After eight failed queued attempts, a terminal
History row must say it was removed; the loss must not be silent.

Before 0.8.4 that path discarded the scrobble permanently, which is how KAROL G
and FloyyMenor were lost.

### 10d. The error messages have numbers in them now

Any chain rejection in the log should name values, not templates:

```
rejected: … Account skiptvads already submitted 5 custom json operation(s) …
```

If you ever see a literal `${a}` or `${n}` again, the extraction has regressed —
and that's worth reporting, because those placeholders are what hid a frozen node
for a full session.

## 11. Play time across a session restart (new in 0.8.5)

The bug that had been misread as random loss for three sessions. Chrome destroys
and recreates its `MediaSession` mid-video; each fragment used to be scored
against the 60% threshold on its own.

Play a **long video (3+ minutes) on the watch page, from a playlist, with ads**,
and let it run to the end. The point is to provoke at least one teardown — an ad
break usually does it.

1. Watch the log for `[session] −` followed by `[session] +` while the same video
   is still playing. That's the churn.
2. Each restart should log:

   ```
   [session] com.android.chrome resumed "LUNA" after a session restart —
             carrying 47s of play time forward
   ```

   If the old fragment ended near the media duration and the replacement starts
   near zero, the line must additionally report that the loop signal was
   carried. An ordinary mid-video restart must not.

3. There must be **no** `[finalize]` and no **Not logged** row for an early
   fragment. Session disappearance should instead log that it is waiting up to
   60 seconds for a replacement.
4. The final `[finalize]` should report the **whole** watch, not the last
   fragment — `played 156s of 196s`, not `played 85s of 196s`.
5. The video should scrobble once.

### 11a. Frozen identity during the one-minute delay (v0.8.9)

This regression came from the first Short in log 11. The ended track was
`grNk0DpiaEE`, but the address bar had advanced through later Shorts and named
`ysY13cbxJR4` by delayed finalization. Corroboration rejected the later id, then
the old fallback expression returned it anyway.

1. Start a Short and let it qualify, then scroll fast enough to make Chrome
   remove/recreate its media session while the first item is pending.
2. Continue through several different Shorts during the 60-second continuation
   window.
3. If the original session is resumed, it must carry the original confirmed id
   and any explicit ad flag. If it never resumes, wait for
   `session continuation expired`.
4. The original item's final snapshot must contain either its frozen original
   identity or no video id. It must never contain a later Short's id, title
   facts, or ad evidence.
5. In particular, seeing `ysY13cbxJR4` rejected by an `[identity]` line and then
   used as the original track's `url` is a failure.

Three things that must **not** happen:

- **No early score.** A fragment crossing the threshold must still wait for a
  replacement or continuation expiry; browser churn is not a track ending.
- **Stop still discards.** Press **Stop** mid-video, then **Start** and replay the
  same video. The old time must be gone — Stop discards the in-flight track by
  design.
- **A genuine rewatch starts fresh after the continuation window.** Watch
  something, leave it more than a minute, then watch it again. It should start
  from zero, not inherit. Identical metadata returning inside the one-minute
  window is inherently indistinguishable from browser churn and is documented
  as such.
- **A session that never returns still finalizes once.** Close a qualifying tab
  and wait just over 60 seconds. One `session continuation expired` finalize
  should appear.

### 11b. Mandatory hyperlink recovery (v0.8.10)

This is the regression for the six unlinked Video rows in log 12. Use Chrome's
mobile Shorts feed with **YouTube scrobbling** on. Collapse the address bar until
the log reports only `host=m.youtube.com video=—`, then play several Shorts
long enough to qualify, including titles with emoji and trailing hashtags.

For every finalized item, exactly one of these outcomes is valid:

1. The address bar supplied an id and the payload contains the matching
   canonical `https://www.youtube.com/watch?v=<11-character-id>` URL.
2. The resolver logs search candidate counts, then
   `after Shorts watch-page corroboration`, and the payload contains that id.
3. The resolver logs `no verified id` or `ambiguous identity`; **Not logged**
   says the video id could not be verified and there is no broadcast line.

Also verify:

- A modern search page should no longer say zero candidates merely because all
  its results are Shorts lockups.
- The Now-card manual button refuses while it says
  `waiting for verified video id`.
- No log line contains `broadcasting WITHOUT url`.
- Every ordinary YouTube broadcast JSON contains `"url":"https://www.youtube.com/watch?v=`.
- Every new Music or Video profile title is a hyperlink. Compare its target id
  with YouTube History where History retained the item.
- A deliberately ambiguous same-title, same-channel, same-duration result is
  not guessed; it belongs in **Not logged**.

## 12. Field-verification record: log 14 (v0.8.10)

**Run window:** 2026-07-31 22:14 through 2026-08-01 12:22 local time
(2026-08-01 03:14–17:22 UTC), with the log beginning during an already-active
session. **Evidence:** `debug/rustedwax-log (14).txt`, the signed-in YouTube
History page, and both live `skiptvads.vidz` profile views (Music and Videos).

The reconciliation used the 11-character YouTube id as the join key. It did
not infer success from the app's History count or from a locally computed
transaction id. A payload counted as delivered only when the log showed block
confirmation and the matching id was present in the expected live profile
section with a hyperlink.

### 12a. Counts and clean boundaries

| Check | Result |
| --- | --- |
| Finalized tracks | 174 |
| Engine skip decisions | 66: 34 below 60%, 15 under the 10-second hard floor, 12 explicit UI-ad vetoes, 3 unresolved-id vetoes, 2 dedup vetoes |
| Broadcast payloads | 109: 54 `kind: song`, 55 `kind: video`, 106 unique YouTube ids |
| Confirmation | 109 in block; 0 mempool-only, accepted-unconfirmed, queued or failed outcomes |
| Live profile placement | 54/54 song payloads under Music; 55/55 video payloads under Videos |
| Hyperlinks | 109/109 payloads visible with `/music?v=<same-id>` targets; no URL-less payload |
| Ads on profile | 0 ad-like payloads |
| Loop diagnostics | 33 detected/inferred loops capped; no looped Short/video produced a second transaction |

This proves the v0.8.10 mandatory-hyperlink boundary and the Hive/profile
transport path for every payload the engine actually emitted. It does **not**
prove that every qualifying viewing reached the engine, which is where the four
failures below occurred.

### 12b. Confirmed qualifying misses

All four ids below were present in YouTube History and absent from both profile
sections. They are app-side omissions, not delayed node confirmation or a
scrobble.life rendering delay.

| Id | Viewing evidence | Actual outcome |
| --- | --- | --- |
| `saGYMhApaH8` — Me Porto Bonito | 193/191 seconds | Finalization enriched and broadcast the following `3mchJ-EW9rM` La Bebe identity instead (log lines 635–637) |
| `5YrJf3CpHNk` — Cardi B - Trump | One continuous 227-second play | An exact-duration key change (`227125` to `227124` ms) split it into 48% and 53%; both fragments failed separately (lines 3128–3129 and 3186–3187) |
| `IW524Zl2Pus` — The best cosplayer avengers | 23/22 seconds | The URL changed at line 3852 and stale `Sponsored` followed two milliseconds later at line 3853; the organic Short was vetoed at lines 4009–4010 |
| `aZaxQG3ggng` — YCB Frenzy - Crazy | 194/192 seconds | Finalization enriched and broadcast the following `2QqyPy2itXw` Coming Home identity instead (lines 9474–9476) |

The two mixed-identity cases narrow the old race. v0.8.9 correctly stopped a
rejected live URL from being resurrected inside the probe and froze identity
during continuation. Log 14 shows the ended track can still be completed with
the next track's id/facts later, in asynchronous resolution, enrichment or
payload construction. The next contract therefore covers the full
finalize-to-broadcast pipeline rather than another probe-only fallback.

The ad case narrowed “same accessibility snapshot.” The snapshot literally held
the new id and old `Sponsored` label together, so same-snapshot id binding was
necessary but not sufficient. URL generation and stabilization are now part of
the implemented v0.8.11 evidence rule.

### 12c. Expected skips and non-failures

- Ads named Rexona, Lysol, Vicks/VapoRub, Zuko, Heineken, the stadium,
  Emzoom/GAC, Balboa, Bimbo and YouTube bumpers stayed off-chain. Three
  unresolved 15-second Vicks creatives also failed closed.
- Ordinary history entries below the configured threshold were correctly
  omitted. The closest final omissions were 54% and 53%; a 59% football Short
  was skipped on one attempt but qualified and appeared after a later viewing.
- The 27:13 Davoo Xeneize video finalized at 86%, appeared under Videos and had
  the correct hyperlink.
- YouTube History exposed 197 unique loaded ids but did not expose 17 early
  Shorts that the log and profile both contained. Those 17 are not counted as
  app misses; History was not a complete independent source for that interval.
- `Gata Only` produced two song payloads with one start timestamp after 149
  seconds were carried across a leave/return inside the 60-second continuation
  window and the aggregate reached 170%. No end-to-start wrap was observed, so
  the engine treated it as a cumulative double-listen rather than a loop. This
  remains the documented ambiguity for identical metadata returning inside the
  continuation window, not one of the 33 loop-cap failures.

### 12d. Section and metadata quality

All payloads reached the section their immutable `kind` requested. One result
is a product-classification edge rather than a proven implementation error:
`aiQOD-G9SR0`, a Titanium flashmob at a Spider-Man fan event, appeared under
Music because the available fallback reported YouTube category `Music`. Keep
that behavior until the product decides whether Music means any musical
performance or only a track/music video.

Four `kind: song` entries were unquestionably malformed before broadcast:

| Id | Broadcast result | Required parser fixture |
| --- | --- | --- |
| `vG4h2KkwMDA` (line 3286) | artist `Ice Spice Performs … Live On The BET Stage!`; title `BET Awards '24` | artist `Ice Spice`; title `Think You The Sh*t (Fart)` |
| `VpXRPrwezQ8` (line 7599) | artist `Sexyy Red "Get It Sexyy" (Official Video`; title `No Skits)` | artist `Sexyy Red`; title `Get It Sexyy`; never split inside the parentheses |
| `z5WrgDzNIZ0` (line 8797) | artist `6IX9INE "Gotti" (WSHH Exclusive`; title `Official Music Video)` | artist `6IX9INE`; title `Gotti`; ignore the nested promo separator |
| `oNg3M9IJJlY` (line 12123) | artist `TROLLZ`; title `6ix9ine & Nicki Minaj` | artist `6ix9ine & Nicki Minaj`; title `TROLLZ`; choose track-first orientation from channel agreement |

## 13. v0.8.11 regression matrix (automated gate implemented)

The runtime changes and exact unit fixtures in §§13a–13d are implemented. They
describe the generated v0.8.11 build. Section 13e was attempted and failed;
§14 is the first field record, §15 is its implemented correction, §16 is the
second failed field record, §17 is the implemented v0.8.12 correction/matrix,
§18 is its failed device record, and §19 is the v0.8.13 correction.

### 13a. Finalized snapshot isolation

Build pure fixtures from the exact log-14 orderings:

1. Finalize Me Porto Bonito with site-only/unresolved frozen identity, then make
   La Bebe the live foreground evidence before resolution completes.
2. Finalize YCB Frenzy - Crazy, then make Coming Home live before enrichment
   completes.
3. Assert that resolver context is part of the ended bundle, the result is
   corroborated against its title/channel/duration, and facts cannot replace
   session metadata until that check passes.
4. Valid outcomes are the ended track's own verified id or a visible
   `video id could not be verified` refusal. The next id, title, artist or facts
   are never valid.

### 13b. Transition-safe explicit ad evidence

Reproduce the exact ordering: stadium ad id and `Sponsored`, URL changes to
`IW524Zl2Pus`, stale `Sponsored` arrives two milliseconds later, then the real
cosplayer MediaSession metadata appears.

- The first new-id/old-label pair remains provisional and is discarded when it
  is not re-observed for that stable URL generation.
- A real promoted Short whose same id and explicit label remain stable across
  the required observations is still vetoed.
- Another URL change, label disappearance, Stop and service/package reset clear
  provisional evidence.
- An accepted veto is carried with the track instance across session churn and
  does not poison a later organic viewing of the same public id.

### 13c. Same-track duration refinement

- Unchanged title/artist/album with duration `227125 → 227124` ms is one track;
  progress remains cumulative and finalizes once.
- Missing duration becoming known and a drift up to 2,000 ms are refinements.
- A title or artist change is a real ending. A duration change beyond the
  tolerance is not silently merged and must follow the normal corroboration
  path.
- The Cardi B fixture reaches one eligible decision; it does not create two
  below-threshold Not logged rows.

### 13d. Structure-aware song credits

Add the four ids in §12d as literal raw-title/channel fixtures. Separator
scanning ignores balanced parentheses, brackets and quotes. Explicit quoted
track and performance shapes run before generic separators. Generic
orientation uses channel agreement; if neither orientation is strong, the
conservative output is channel plus whole cleaned title.

Video-kind behavior remains unchanged: videos never run through artist/track
splitting.

### 13e. Historical physical-device release gate (five attempts failed before log 21)

After targeted tests, run the full uncached unit suite, assemble the APK and run
lint. Then perform another mixed watch-page/playlist/Shorts session with ads,
loops, 2× playback, a long video, title-parser fixtures and deliberate rapid
Short transitions. Reconcile by id against YouTube History and both live profile
sections.

The first 2026-08-01 attempt used the original artifact, did not execute every
targeted fixture below, and failed the zero-qualifying-omission/parser
requirements; it is recorded in §14. The corrected artifact from §15 underwent
the broad log-17 round in §16. It passed transport and the exact log-16 cases
but again failed capture/metadata requirements and still did not execute every
fixture below. The v0.8.12 artifact underwent the broad log-18 round in §18;
transport and isolation remained clean, but two payload defects and incomplete
fixture coverage failed the gate. The v0.8.13 artifact underwent the log-19
round in §20; transport again reconciled exactly, but two ordinary watch-page
ads reached Hive, four metadata payloads were defective/conservative, and the
mandatory fixtures were not replayed. The implemented v0.8.14 artifact repeated
the broad field round in log 20 (§21); transport and most rule behavior again
reconciled, but a Namecheap ad escaped during an accessibility-observation
outage, one qualifying organic song was refused, two payloads reversed credits,
and the fixture matrix was still incomplete. The v0.8.15 artifact must
repeat the complete gate.

Use this exact field checklist:

1. Install the source-gate-approved
   future `dist/rustedwax-0.8.15.apk` with `adb install -r`, confirm the app
   reports v0.8.15, export/clear the prior log, and record the test
   account plus the Monitor, Auto-scrobble and Browser evidence
   settings. (Historical: on v0.11.0 and later the last two YouTube-side entries
   are one *YouTube scrobbling* switch.)
2. Play `saGYMhApaH8` past 60%, then move immediately to `3mchJ-EW9rM` before
   the continuation window closes. Repeat `aZaxQG3ggng` → `2QqyPy2itXw`.
   Each ended item must use its own id/facts or show a visible verification
   refusal; no mixed payload is allowed. For the follow-up candidate, also run
   the exact log-16 Soy Peor → Me Porto Bonito and DÁKITI → Gata Only
   transitions from §14c. Each first song must retain its own correct id despite
   localized/presentation differences; a safe omission is no longer a pass for
   those known-equivalent title pairs.
3. In the Shorts feed, capture a visible `Sponsored`/ad-labeled item and swipe
   rapidly to organic `IW524Zl2Pus` while the overlay is transitioning. The ad
   must remain off-chain and the organic successor must remain eligible. Also
   leave one stable labeled ad on screen long enough for re-observation; it
   must still be vetoed. Exercise another URL, label disappearance, Stop, and a
   monitor restart while provisional evidence exists.
   Separately run ordinary `/watch` or playlist playback until an in-stream ad
   exposes an exact accessibility label. Capture both a public 30+ second ad
   and a second ad followed by the organic content under the unchanged watch
   URL. The ad track instance must be vetoed, while the resumed organic track
   keeps/earns only its own progress. A label-free promotion remains an explicit
   unproven limitation; History may verify the field outcome but is never a
   runtime input. The original v0.8.15 gate also required the §21
   coverage-unavailable refusal; that requirement is superseded by §40. Missing coverage alone must
   not suppress one unique finalized lookup, while ambiguity and literal ad evidence still refuse.
4. Play `5YrJf3CpHNk` continuously through the threshold and confirm any
   `227125 → 227124` metadata refinement produces one accumulated finalization,
   not two Not-logged fragments. Include a missing-to-known duration case and
   a deliberate different-title/material-duration transition as controls.
5. Qualify all four parser fixtures: `vG4h2KkwMDA`, `VpXRPrwezQ8`,
   `z5WrgDzNIZ0`, and `oNg3M9IJJlY`. Verify their exact artist/title pairs from
   §12d in both the payload log and profile. Also qualify `F1_aOX0acbY` and
   require title `LA PLENA`, artist `Beéle, Westcol, Ovy On The Drums`. Run the
   complete additional log-17/log-18/log-19/log-20 id matrix listed in §21f;
   the older five fixtures are necessary but no longer sufficient.
6. Include an ordinary watch-page item, a playlist advanced with the toolbar
   hidden, a genuine organic Short, a looping Short/video, a 2× play, and long
   video `fZGvnNVKGPs`. Exercise one manual Broadcast action only after checking
   its Now-card verdict, then let automatic finalization run to confirm shared
   deduplication. Turn the screen off during one qualifying Chrome track, allow
   at least two MediaSession recreations, and verify progress is carried once.
   Deliberately jump to another playlist after a completed song.
7. Export the complete app log and signed-in YouTube History. For every emitted
   id, record the payload kind/url, honest delivery state, block transaction,
   Music-or-Videos placement, and live hyperlink target. Separately account for
   every history-backed qualifying id that did not emit a payload using its
   visible Not-logged reason. End the final browser session and wait at least
   60 seconds for its continuation timer before pressing Stop or exporting.

The patch passes only with all of these simultaneously:

- zero qualifying history-backed known-id viewing omitted by an app-side race
  or over-strict contradiction when sufficient unique evidence exists;
- zero ad-like payloads and zero organic successors falsely vetoed as ads;
- one finalization for bounded duration refinements;
- no mixed identity/facts payloads;
- the three log-16 equivalent-title pairs retaining their own correct ids;
- every emitted payload confirmed or reported in its honest transport state;
- every emitted YouTube payload visible in the correct section with its exact
  hyperlink; and
- every parser/resolver fixture in §§13e and 21f carrying its intended,
  non-truncated credits or its explicitly required ambiguity refusal.

A viewing whose external candidates remain genuinely indistinguishable may
still fail closed, but the log must name the competing evidence and the
reconciliation must not count that as an app-side race correction.

## 14. v0.8.11 physical-device field record: log 16

**Run window:** 2026-08-01 13:59:50 through 14:42:52 local time
(18:59:50–19:42:52 UTC). **Artifact tested at that time:**
`dist/rustedwax-0.8.11.apk`, version code 31, SHA-256
`bdf81bcc560dea1c5a430d869193d702480f15f95c19f4d211abbb320ca4e296`.
**Evidence:** `debug/rustedwax-log (16).txt`. Log 16 contains the
earlier log-15 export plus the continued session and is therefore the complete
app-log record for this attempt. No independent signed-in YouTube History or
live-profile reconciliation was supplied; delivery statements below use the
app's independent-node block evidence only.

### 14a. Counts and transport outcomes

| Check | Result |
| --- | --- |
| Finalized tracks | 18 |
| Engine skip decisions | 11: 4 below threshold, 3 finalized-snapshot identity contradictions, 2 unresolved-id refusals, 1 under the 10-second floor, 1 no-duration/too-little refusal |
| Broadcast payloads | 7 |
| Confirmation | 7 in block; 0 mempool-only, accepted-unconfirmed, queued or failed outcomes |
| Address-bar evidence | Chrome advanced from generation 2 through generation 15; the watcher did not freeze |
| Duplicate/ad outcome | No duplicate transaction and no ad-like payload among the seven emitted entries |

The seven block-confirmed ids were `jZGpkLElSu8` (TQG), `xKKeqlBQ3Js`
(Me Acostumbré), `CUYrEiymUMY` (Tu No Vive Asi), `-r687V8yqKY` (Gata Only),
`AhQcNVyndSM` (Asesina), `5ospiemGG3M` (Lollipop), and `F1_aOX0acbY`
(LA PLENA). The final id had the correct canonical link but malformed credits,
so block confirmation is a transport pass rather than a metadata pass.

Two ad-like MediaSession creatives remained off-chain without title/brand
guessing. No explicit YouTube ad-label event was present, so the engine did not
classify them as ads from their names. Instead, the 15-second Mastercard and P&G
items first saw the following organic URL, then lost that id when fetched page
facts contradicted the active MediaSession. Search could not verify a different
canonical id, so both failed closed. The organic Me Acostumbré and LA PLENA
sessions later established their own identities and remained eligible.

### 14b. Address-bar and screen-off findings

The initial report of a frozen address bar was not supported by the complete
log. Chrome reported `ws00k_lIQ9U`, `saGYMhApaH8`, `jZGpkLElSu8`,
`5r5UePOgMQU`, then generations 6–13 through the original mix. After Lollipop,
the URL changed to `2u5UTPEDGAw&list=RD2u5UTPEDGAw` at 14:38:04. A later
Chrome event returned to `F1_aOX0acbY` at generation 15. A Brave observation of
`2u5UTPEDGAw` was package-scoped and did not become Chrome identity evidence.

The log has no screen-on/off event, so the exact display transition cannot be
proved. The likely interval began around 14:40:20, when Chrome repeatedly
removed and recreated its MediaSession while Fantasy Pool Party continued.
v0.8.11 carried the same semantic track at approximately 134, 172 and 177
seconds. This is a positive device result for session-restart continuity: the
screen did not need to generate address-bar events for Android MediaSession and
notification callbacks to continue, and no duplicate finalization appeared.

The final Fantasy Pool Party session disappeared at 14:42:34 after crossing
the 60% threshold, but the export ends before its 60-second continuation timer
expired. The log then contains about five seconds of a restarted LA PLENA
session and ends at 14:42:52 with Chrome destroyed/waiting. There is no final
engine decision for either pending item and no `[probe] stopped` line. They are
unresolved field evidence, not counted as successful or failed scrobbles. A
future run must wait at least 60 seconds after ending the last browser session
before pressing Stop or exporting.

### 14c. Qualifying identity omissions

The three qualifying omissions share one cause. `SessionProbe.titlesMatch`
still uses literal case/whitespace/substring comparison while finalized
candidate corroboration has a more tolerant normalized comparison. The active
latch therefore discarded each correct address-bar id before the following URL
arrived:

| Ended viewing | Correct active evidence rejected | Safe final outcome |
| --- | --- | --- |
| Soy Peor, 271/269 seconds | `ws00k_lIQ9U`; page `Video Oficial` versus MediaSession `Official Video` | Successor `saGYMhApaH8` facts contradicted the ended title and were visibly refused |
| Me Porto Bonito, 193/191 seconds | `saGYMhApaH8`; parentheses around `ft. Chencho Corleone` plus `Video Oficial`/`Official Video` | Successor `jZGpkLElSu8` facts were visibly refused |
| DÁKITI, 215/213 seconds | `TmKh7lAwnBI`; page ended at `(Video Oficial)` while MediaSession added `| EL ÚLTIMO TOUR DEL MUNDO (Official Video)` | Successor `-r687V8yqKY` facts were visibly refused |

This is an important partial pass: v0.8.11 prevented every adjacent track from
creating a mixed immutable payload. It is still a release failure because the
correct qualifying viewings were omitted unnecessarily.

### 14d. LA PLENA credit failure

The raw song title was:

```text
W Sound 05 "LA PLENA" - Beéle, Westcol, Ovy On The Drums
```

The parser's quoted-track rule accepts a quote only when no substantive credit
text follows it. This input therefore fell through to the generic dash rule and
was broadcast as:

```text
artist = W Sound 05 "LA PLENA"
title  = Beéle, Westcol, Ovy On The Drums
```

The required structural interpretation is:

```text
artist = Beéle, Westcol, Ovy On The Drums
title  = LA PLENA
```

This is not a request for a song database. It is one observed fixture for the
generic `publisher/series "quoted work" - trailing artist credits` grammar.

### 14e. Gate decision

The first v0.8.11 physical-device gate **failed**. The generated build preserved
canonical links, block-confirmed every emitted payload, rejected adjacent-track
mixing, kept the two ad-like creatives off-chain through identity failure, and
carried playback through likely screen-off MediaSession churn. It did not meet zero qualifying omissions or
correct song-credit output, and the run did not include an independent
History/profile reconciliation.

## 15. v0.8.11 field-follow-up correction (implemented; automated gate passed)

The runtime correction is deliberately limited to generic validation and
structural parsing. Exact ids/titles/artists appear only in unit-test fixtures
and historical evidence, never in the APK's runtime decision data.

### 15a. Shared structure-aware title corroboration

The duplicated active/final title predicates now use one pure matcher:

1. Normalize Unicode, case, diacritics, punctuation and whitespace.
2. Remove only existing recognized promo-only wrappers such as `(Official
   Video)` and `(Video Oficial)`; retain names, track words, album/event words
   and credits.
3. Accept equal whole-token structure or conservative contiguous containment
   of the complete shorter structure, requiring at least three tokens when the
   two normalized forms are not equal.
4. Keep the independent page/session duration contradiction unchanged: a
   difference must exceed both 5 seconds and 5% before duration disproves the
   id.
5. Use the matcher only to validate an id already observed from that browser
   track. It must not derive, choose or map a video id from a title.

The exact positive tests are the Soy Peor, Me Porto Bonito and DÁKITI pairs in
§14c. Exact negative tests keep Soy Peor/Me Porto Bonito,
Me Porto Bonito/TQG, DÁKITI/Gata Only and the observed ad/organic pairs as
contradictions. These negative controls, including a bare two-word artist versus
a longer title, are present at both the shared matcher and finalized guard
boundaries.

### 15b. Quoted work with trailing credits

One parser branch now runs before the generic top-level dash rule for:

```text
prefix "quoted work" - trailing credits
```

It applies only with balanced top-level quotes/separator, nonempty components,
channel agreement supporting the prefix as publisher/series, trailing credit
structure, and no recognized promo/event suffix. A proven shape returns the
quoted work as title and trailing credits as artist. Ambiguous cases retain the
current conservative whole-title/channel fallback rather than guessing.

LA PLENA is pinned as the positive raw-title/channel fixture. Negative fixtures
include `Artist "Song" - Official Video` and `Artist "Song" - Live at Wembley`
so format/event text cannot become an artist merely because it follows a dash.

### 15c. Implementation and release gates

The implementation and automated portion are complete:

1. the shared matcher is used by both active and finalized corroboration;
2. the conservative quoted-work/trailing-credit branch is present;
3. 54 focused title/corroboration/parser tests passed with no failures or skips;
4. the full uncached `testDebugUnitTest assembleDebug lintDebug` gate passed:
   295 tests, 0 skipped, 0 failures, 0 errors; debug assembly and lint succeeded;
5. the corrected version 0.8.11, version-code 31 artifact was generated at
   `dist/rustedwax-0.8.11.apk`, SHA-256
   `1b682f582dd17f287d69acd2b22313c227acffb13f13d266288c4e6df65639d5`; and
6. the then-remaining gate was to repeat §13e. Log 17 performed a broad second
   device round and is recorded in §16. It proved the corrected log-16 cases and
   transport boundary, but exposed new defects and did not exercise every exact
   §13e fixture, so the artifact is not field-approved.

The follow-up must not change URL generations, explicit visible-ad policy,
snapshot isolation, same-track duration refinement, thresholds, loop/dedup
rules, kind immutability, mandatory hyperlinks, manual/automatic parity or the
no-historical-rebroadcast rule. Keeping version 0.8.11/version code 31 identifies
this as the corrected candidate for the same unreleased checkpoint; the APK at
the install path now refers to the checksum above, not the artifact exercised
in log 16.

## 16. v0.8.11 second physical-device field record: log 17

**Run window:** 2026-08-01 15:55:52 through 19:18:18 local time
(20:55:52–00:18:18 UTC). **Artifact tested:** corrected
`dist/rustedwax-0.8.11.apk`, version code 31, SHA-256
`1b682f582dd17f287d69acd2b22313c227acffb13f13d266288c4e6df65639d5`.
**Evidence:** `debug/rustedwax-log (17).txt`, signed-in YouTube History, both
live `skiptvads.vidz` profile sections, and independent account-history queries
to `api.hive.blog` and `api.openhive.network`. Unlike log 16, this round has a
complete History/profile/blockchain reconciliation. Profile Today/Yesterday
headings are UTC groupings; reconciliation therefore uses video id rather than
the displayed day boundary.

### 16a. Counts and transport/profile outcome

| Check | Result |
| --- | --- |
| Finalized tracks | 123 |
| Engine decisions | 67 broadcasts plus 56 skips |
| Skip breakdown | 23 below 60%, 17 under the 10-second floor, 6 unresolved ids, 6 finalized-snapshot contradictions, 4 no-duration/too-little decisions |
| Payload kinds | 60 `song`, 7 `video` |
| Confirmation | 67 in block; 0 mempool-only, queued, accepted-unconfirmed or failed outcomes |
| Independent Hive comparison | 67/67 transaction ids present on each of two nodes; 67/67 payload JSON values exact; blocks 108653616–108657522 |
| Profile comparison | 60/60 song payload entries in Music; 7/7 video payload entries in Videos; 0 expected ids found only in the wrong section |
| Links and duplicates | 67/67 canonical watch hyperlinks; 67 unique transaction ids; no duplicate transaction |
| Runtime failures | 0 exceptions/crashes; 15 watch-page timeouts and 2 YouTube Music timeouts degraded through existing fallbacks |

There are 66 distinct emitted video ids because `xKKeqlBQ3Js` was played and
qualified twice about three hours apart. Those are two real listens, not a
duplicate finalization. Seven playback-position wraps were observed; none
created an extra transaction for the same continuous viewing.

### 16b. Confirmed v0.8.11 improvements

- Finalized snapshot isolation kept successor facts out of every ended-track
  payload. No mixed identity reached Hive even when the following URL was the
  only id left at finalization.
- The corrected log-16 cases succeeded on device: Soy Peor used
  `ws00k_lIQ9U`, Me Porto Bonito used `saGYMhApaH8`, DÁKITI used
  `TmKh7lAwnBI`, and LA PLENA used `F1_aOX0acbY` with artist
  `Beéle, Westcol, Ovy On The Drums` and title `LA PLENA`.
- Every attempted payload completed the local-to-Hive-to-profile path exactly.
  The release failure is therefore pre-broadcast capture/metadata behavior,
  not signing, transport, confirmation or profile ingestion.
- No advertisement-like payload reached Hive. The hard floor and mandatory-id
  boundary also kept the observed short creatives off-chain without using
  brand/title guesses.

### 16c. History-confirmed qualifying omissions

Eight qualifying organic viewings were visible in signed-in YouTube History
but had no log-17 transaction:

| Correct id and viewing | Final progress | App-side cause |
| --- | --- | --- |
| `QBq6rY0ZpKM` — VYBZ KARTEL WHEN SINCE | 138/147 seconds | The resolver returned zero search candidates and failed closed |
| `CJjvg7PbE4w` — Nunca Me Amó | 205/204 seconds | Exact channel-key inequality treated resolver credits and the MediaSession uploader as contradictory roles |
| `TapXs54Ah3E` — Ay Vamos | 268/266 seconds | Canonical page title had two tokens and failed the three-token unequal-title containment floor |
| `at1axdFpcgI` — Esta Noche | 241/239 seconds | Same two-token corroboration failure |
| `NgFx3aq52Vg` — Me Reclama | 196/194 seconds | Same two-token corroboration failure |
| `tdZsL8i5ASA` — Recuerdos | 267/266 seconds | One-token canonical title failed corroboration |
| `tGLP74uofTo` — Voy Después | 216/211 seconds | Two-token canonical title failed corroboration |
| `5r5UePOgMQU` — +57, later replay | 301/301 seconds | The address bar did not yield the current id; 65 search candidates failed verification even though the same id had been verified earlier in this monitoring run |

The five short-title cases are a regression in the implemented log-16
follow-up, not proof that Chrome's address bar remained physically frozen. The
correct ids were initially observed and latched, then rejected when the
YouTube Music fallback returned the canonical one/two-word work title. The URL
later advanced, and finalized snapshot isolation correctly refused the
successor rather than corrupting the ended payload. The safety boundary passed;
capture completeness failed.

`Best movie!!! #movie #shorts` was a ninth qualifying local viewing at 137/173
seconds. Search found two uploads with indistinguishable title, channel and
duration (`PlTiqSpwzTI`, `VL_1TfgB2pw`) and refused both. Neither id was visible
in the currently loaded History page, so this remains a safe ambiguous omission
rather than one of the eight History-confirmed app-side misses. The planned
patch must preserve that fail-closed outcome.

### 16d. Metadata defects that reached Hive

The ids and links below are correct, but the immutable payload metadata is not:

| Video id | Log-17 payload defect | Required generic parsing outcome |
| --- | --- | --- |
| `saGYMhApaH8` | artist `Bad Bunny`; title retained `Bad Bunny ft. Chencho Corleone - Me Porto Bonito \| Un Verano Sin Ti` | artist `Bad Bunny`; title `Me Porto Bonito` |
| `GtSRKwDCaZM` | artist `Bad Bunny`; title retained `BAD BUNNY - YO PERREO SOLA \| YHLQMDLG` | artist `Bad Bunny`; title `YO PERREO SOLA` |
| `AnKdQ5p5Ks8` | artist `Ayer ft. Dj Nelson`; title `Anuel` | artist `Anuel`; title `Ayer ft. Dj Nelson` |
| `qA6FBDYncGk` | artist `🌡105F RMX`; title was the full Kevvo/featured-artist list | title `🌡105F RMX`; artist is the top-level trailing credit list |
| `lA8OhVn-o7M` | artist `Me Llama Todavía [Remix]`; title was the Super Yei/featured-artist list | title `Me Llama Todavía [Remix]`; artist is the top-level trailing credit list |
| `UWV41yEiGq0` | artist `Diles`; title was the Bad Bunny/Ozuna/featured-artist list | title `Diles`; artist is the top-level trailing credit list |
| `34Na4j8AVgA` | title `Starboy ft. Daft Punk ft. Daft Punk` | remove only the exact repeated trailing credit, preserving one `ft. Daft Punk` |

The first two expose the current conservative fallback whenever the right side
of a top-level dash contains another top-level pipe. The next four show that
channel agreement can match a featured/uploader token and reverse conventional
artist-first input, while track-first titles followed by explicit multi-artist
credit lists need separate structural evidence. The Starboy case requires
exact duplicate-credit cleanup, not general removal of feature credits.
Existing on-chain entries are historical evidence and must not be repaired or
rebroadcast.

### 16e. Kind and advertisement evidence

`KoWNsyNVR28`, title `Just how many superpowers does he have? #movie #foryou
#edit #funny`, was published as `song` and appears in Music. Its watch page
timed out, the music-client microformat reported `category=Music`, YouTube Music
provided no music-video type, and MusicBrainz did not match. The v0.8.11
classifier let the uploader category outrank this explicit movie/edit format.
This likely kind false positive became a v0.8.12 regression, with
music-video/song-title negative controls so a bare word such as “movie” is not
made a universal veto.

No explicit visible `Sponsored`/`Ad` label was captured in log 17. The 15-second
P&G and stadium creatives remained off-chain because no canonical id could be
verified, not because transition-safe ad evidence was exercised. Therefore the
absence of ad payloads is a positive safety result, but the exact
stadium-ad-to-`IW524Zl2Pus` regression remains device-unproven.

### 16f. Gate decision and incomplete fixture coverage

The second v0.8.11 physical-device gate **failed**, with considerable progress.
Transport, exact hyperlinks, profile placement, loop deduplication, corrected
log-16 cases and no-successor-contamination all passed. Eight
History-confirmed qualifying organic viewings were omitted, seven emitted songs
had malformed credits, and one likely movie Short was placed in Music.

This broad round was not the complete §13e matrix. Log 17 does not contain the
`aZaxQG3ggng → 2QqyPy2itXw` handoff, explicit `IW524Zl2Pus` ad transition,
`5YrJf3CpHNk` duration-refinement case, or original parser ids
`vG4h2KkwMDA`, `VpXRPrwezQ8`, `z5WrgDzNIZ0`, and `oNg3M9IJJlY`.
Consequently those device gates remain pending independently of the newly found
defects.

## 17. v0.8.12 correction implemented; log-18 result in §18

The v0.8.12 runtime and exact automated regressions are implemented from the
corrected v0.8.11 source. Log 17 remains immutable field evidence. The generated
review artifact uses version `0.8.12` and version code `32`. Log 18 attempted
this matrix incompletely and failed for the independent defects in §18.

### 17a. Implemented sequence

1. Replace the boolean-only shared title result with an evidence-ranked result
   (`exact`, `strong containment`, `weak short canonical core`, or
   `contradiction`). Keep active and finalized call sites on the same function.
   Weak short cores are usable only for the current-generation browser-observed
   id when duration independently agrees.
2. Change final channel corroboration from exact key inequality to a
   role-aware guard. Channel disagreement alone is absence of support for an
   already observed id; it becomes a veto only alongside a real identity
   contradiction. Keep search-only resolution strict.
3. Add a bounded, memory-only verified-identity candidate cache and
   presentation-cleaned resolver query variants. Re-corroborate every reused id
   against the immutable snapshot, clear the cache at lifecycle boundaries, and
   retain multiple-match refusal.
4. Extend the top-level parser with primary-separator/multi-suffix handling,
   featured-credit-safe artist-first orientation, structurally proven
   track-first credit lists, and exact duplicate-feature collapse.
5. Add a strong narrative movie-format rule above bare uploader category but
   below distributor/YouTube Music provenance. Keep this entirely separate from
   ad detection.
6. Update version name/code and only then change documentation from “planned”
   to “implemented” for behavior covered by tests. Do not modify or rebroadcast
   any log-17 Hive entry.

### 17b. Exact automated regressions

| Boundary | Positive fixtures | Required negative controls |
| --- | --- | --- |
| Short canonical titles | `TapXs54Ah3E`, `at1axdFpcgI`, `NgFx3aq52Vg`, `tdZsL8i5ASA`, `tGLP74uofTo` retain their already observed ids | `Bad Bunny`/different Bad Bunny song, reordered short words, successor id and material-duration conflict remain contradictions |
| Channel roles | `CJjvg7PbE4w` credits/uploader difference does not veto matching id/title/duration | Same title on a different unobserved upload and any title/duration contradiction remain refused |
| Resolver recovery | Later `5r5UePOgMQU` is recovered as a re-corroborated same-run candidate; cleaned WHEN SINCE query variants reach strict candidate evaluation | expired/cleared cache, changed metadata, ambiguous duplicate uploads and “Best movie!!!” remain refused |
| Multi-separator credits | Exact seven ids from §16d produce their documented artist/title output | nested separators, promo/event suffixes, ordinary artist-first, ordinary track-first and non-duplicate feature credits remain unchanged |
| Kind | `KoWNsyNVR28` is `video` from strong movie/edit format in the absence of hard music provenance | real music videos, Topic/Art Tracks, YouTube Music types and ordinary song titles containing “movie” remain `song` |

The old log-14 and log-16 regressions remain mandatory. New tests may use exact
field strings and ids under `app/src/test`, but `app/src/main` must have no
fixture map, known-song list, known-channel list, brand inference or title/id
branch.

### 17c. Automated and artifact gate

Run the focused tests while developing, then the complete clean gate:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew testDebugUnitTest assembleDebug lintDebug --rerun-tasks
```

Report the authoritative total, skipped/failure/error counts, assembly and lint
outcomes, final APK path and SHA-256. The APK is for review/field testing only;
do not commit, push or open a PR without explicit approval.

The exact gate passed: 314 tests, 0 skipped, 0 failures and 0 errors; debug APK
assembly and lint succeeded. The review artifact is
`dist/rustedwax-0.8.12.apk`, SHA-256
`3390df660053ca9c2c0c7665d320e67e820ce09513d4f025a172a018aa0080b3`.

### 17d. Physical-device gate used for log 18 (incomplete; failed)

Repeat all of §13e, then deliberately add:

1. the five-song short-canonical-title sequence from §16c with the toolbar both
   visible and hidden;
2. `CJjvg7PbE4w`, `QBq6rY0ZpKM`, and two separated qualifying plays of
   `5r5UePOgMQU` in one monitoring run;
3. all seven §16d metadata fixtures and `KoWNsyNVR28`;
4. one stable explicitly labeled YouTube ad and the exact stadium-ad to
   `IW524Zl2Pus` transition; and
5. screen-off MediaSession recreation, seven-or-more loop opportunities,
   deliberate playlist navigation, one manual Broadcast, and a final wait of at
   least 60 seconds before Stop/export.

Reconcile every History-backed qualifying id against the log and Hive, not
only emitted entries. The gate requires zero app-side omissions for the known
fixtures, zero mixed identities, zero ad payloads, correct artist/title/kind,
one transaction per continuous looped viewing, exact canonical hyperlinks and
honest delivery/profile placement. A genuinely ambiguous external lookup may
fail closed, but its competing ids and visible reason must be recorded.

## 18. v0.8.12 physical-device field record: log 18

**Run window:** 2026-08-01 22:48:54 through 2026-08-02 02:29:39
local time (EST/Panama). **Artifact:** `dist/rustedwax-0.8.12.apk`, version
code 32, SHA-256
`3390df660053ca9c2c0c7665d320e67e820ce09513d4f025a172a018aa0080b3`.
**Primary evidence:** `debug/rustedwax-log (18).txt`, 6,309 lines. The
reconciliation additionally used signed-in YouTube History, the Music and
Videos views for `skiptvads.vidz`, and immutable account history from
`api.hive.blog`, `api.deathwing.me`, `api.syncad.com`, and
`api.openhive.network`. Browser inspection was read-only. YouTube History is
audit evidence only; the app does not read it at runtime.

The exported log ends after resolving Amarillo to `KHAgoT4FZbc`, before its
broadcast line. All four Hive nodes and the live Music profile prove that the
same finalized run subsequently emitted one additional operation:
block `108666132`, transaction
`8199121759bb5f126a2fd09c7f1e6100fe34fa54`, song `J Balvin — Amarillo`,
duration `2:45`, 100%, canonical URL and media timestamp
`2026-08-02T07:26:58.000Z`. It is counted in the complete run totals below but
not invented as a line inside the exported log.

### 18a. Counts, transport and immutable-payload reconciliation

| Check | Result |
| --- | --- |
| App lifecycle | 2 app session-start markers; 1 listener connection; 1 probe start; Auto-scrobble enabled once |
| Finalizations in the log | 129 |
| Logged payloads | 73 broadcasts; all 73 later reported `scrobbled (block)` |
| Logged skip decisions | 55: 29 below 60%, 24 unverified hyperlink, 1 under 10 seconds, 1 ambiguous identity |
| Complete on-chain run | 74 operations: 55 songs, 19 videos, 73 unique ids |
| Duplicate-id audit | `ko70cExuzZM` appears twice from separate full Taylor Swift listens 62 minutes apart, with separate timestamps and transactions |
| Payload-schema audit | 0 errors: required fields present, canonical watch URLs, durations at least 10 seconds, percent values 62–100 |
| Delivery failures | 0 broadcast failures, queued outcomes, mempool-only claims or accepted-unconfirmed claims |
| Runtime failures | 0 app exceptions/crashes, watch-page timeouts or YouTube Music timeouts; 14 MusicBrainz lookup failures degraded safely |

Every one of the 73 logged transaction ids and serialized payloads matched the
exact immutable `custom_json` returned by all four configured Hive nodes. The
nodes returned the same account-history fingerprint
`bf20333bc9bf846fe6b7078c712bc63f164313f2fd5a21bcf89233b87f70ba5f`;
there was no missing transaction or payload mismatch. The refreshed profile
showed every logged operation in the section declared by its payload kind, plus
Amarillo at the top of Music. This is a complete signing, transport,
confirmation and profile-ingestion pass; section consistency does not excuse a
wrong kind chosen before broadcast.

Signed-in History contained all 73 unique on-chain ids. Older Shorts were
visible only after paging the Today's Shorts carousel, so checking only the
initial History viewport would have produced false omissions. This external
check also established that the four qualifying resolver refusals in §18d were
real viewings rather than synthetic app sessions.

### 18b. Isolation, ads, loop caps and payload safety

Nine playback-position wraps were logged. Eight qualifying continuous
viewings reached the engine's `playback loop detected — first viewing kept`
cap; none produced a loop duplicate. The ninth wrap remained unbroadcast and
unverified. Thirty-two ids were unlatched after successor/ad separation.
The clearest race was `CzkwUsUpvEM`: the old “Hello peter” id was unlatched when
successor metadata arrived, yet the frozen ended snapshot later broadcast its
own correct payload without successor title, channel, duration or id.

Promotional-looking sessions remained off-chain, normally because no canonical
id could be verified. No explicit `[ad]` or visible-label evidence line occurred,
so the stable labeled-ad detector and exact stadium-ad-to-`IW524Zl2Pus`
transition were not physically exercised. The result is a positive no-ad/
successor-isolation observation, not proof of the explicit-label gate.

Every emitted URL was canonical and every emitted id existed in History. No
mixed successor payload, loop duplicate, malformed transport state or missing
hyperlink reached Hive.

### 18c. Two permanent metadata/kind defects

The field gate failed on two operations whose transport was correct but whose
immutable content was not:

| Video and transaction | Emitted payload | Required generic outcome | Cause |
| --- | --- | --- | --- |
| `6swmTBVI83k`, `c2e5252d8d15615ec1416752220c2b4ed8f509d8` | artist `Your Name`; title `Lil Nas X - MONTERO` | artist `Lil Nas X`; title `MONTERO (Call Me By Your Name)` | The `Track (by Artist)` regex accepted arbitrary words before `by`, so `(Call Me By Your Name)` was misread as a credit |
| `9jI-z9QN6g8`, `41e19fec2c7ce82710efaea48c598932528935d6` | `kind=video`, Flow La Movie — Te Bote Remix | `kind=song` with the parsed artist/title | Generic channel keyword `movie` ran before the id-bound YouTube Music `MUSIC_VIDEO_TYPE_OMV` result and bare `category=Music` |

The Te Bote entry therefore appears consistently in the Videos profile but is
still semantically wrong under the contract: YouTube Music's id-bound OMV
provenance must outrank a generic word inside a music owner's channel name.
Neither historical operation is to be repaired, rewritten or rebroadcast.

One additional 59-second Short, `DWpPhwhBD3A` (“Halsey deserves better”), was
classified as song from `category=Music` with no YouTube Music type. That is
permitted by the v0.8.12 ladder and is recorded as a playlist-quality question,
not a release-contract failure.

### 18d. Four qualifying safe omissions

| History-backed id | Final progress | Final resolver result |
| --- | --- | --- |
| `lZizLbWxr_E` — Spice, Sean Paul, Shaggy / Go Down Deh | 181/185 seconds | No verified id among 108 unique candidates; the search byline `Spice Official` did not equal compound session channel `SpiceOfficialVEVO` |
| `dn3d8awSA0c` — Kybba, Ryan Castro, Sean Paul & Busy Signal / BA BA BAD REMIX | 148/149 seconds | No verified id among 100; YouTube's collaborative byline was not treated as the same owner role |
| `napM9rZUzmU` — Blaiz Fayah X Maureen / Money Pull Up | 118/137 seconds | No verified id among 91; YouTube's `Blaiz Fayah and 2 more` collaborative byline was rejected |
| `cD5T1Y4b7wA` — Feid, Young Miko / Classy 101 | 194/195 seconds | Correctly refused because `cD5T1Y4b7wA` and `DwUA6misBRg` both matched title, channel and duration |

The first three are conservative false negatives and motivate only bounded
channel-presentation/collaborator handling. Classy 101 must remain off-chain
unless the current-generation address-bar watcher supplies its exact id; the
resolver may not choose between indistinguishable uploads.

### 18e. v0.8.12 fixture coverage and gate decision

Log 18 physically exercised `saGYMhApaH8` and `5r5UePOgMQU`. It did not
exercise the five short-title fixtures `TapXs54Ah3E`, `at1axdFpcgI`,
`NgFx3aq52Vg`, `tdZsL8i5ASA`, `tGLP74uofTo`; channel fixture
`CJjvg7PbE4w`; WHEN SINCE `QBq6rY0ZpKM`; ambiguous movie ids
`PlTiqSpwzTI`/`VL_1TfgB2pw`; the seven §16d parser ids; or movie fixture
`KoWNsyNVR28`. Some appear in History from older sessions, which cannot count
as physical evidence for this monitoring run.

The log contains cache `remembered` lines but no later cache-recovery hit. It
also lacks a Stop/reset/cache-clear boundary, explicit labeled-ad event, manual
Broadcast parity case and the exact remaining §13e transition/duration
fixtures. Those boundaries remain device-pending.

The v0.8.12 log-18 field gate **failed**. Transport, node agreement, canonical
links, profile ingestion, snapshot isolation, loop caps and no-ad-payload
behavior were excellent. MONTERO carried wrong credits, Te Bote carried the
wrong kind, three uniquely recoverable qualifying songs were omitted, and the
complete mandatory fixture matrix was not run. Classy 101's ambiguity refusal
was correct and must be preserved.

## 19. v0.8.13 targeted log-18 correction

The correction is deliberately narrow and generic. Runtime source contains no
log-18 id, song, artist or channel lookup table; exact field values live only in
tests and this immutable incident record.

1. Restrict the second-artist parser shape to literal `(by Artist)` and
   `(performed by Artist)`. Ordinary parentheses that merely contain the word
   `by` fall through to the normal top-level separator parser, preserving title
   text such as `(Call Me By Your Name)`.
2. Keep explicit tutorial/reaction/trailer/episode format vetoes above hard
   music provenance, but move generic channel-name words below YouTube Music
   and MusicBrainz. A bare `movie` channel word still beats weak
   `category=Music`; an id-bound OMV or confirmed recording wins.
3. Normalize stacked YouTube owner suffixes one layer at a time and recognize a
   search byline's leading owner only when the card carries YouTube's explicit
   collaborator-dialog marker. Exact normalized title and bounded duration
   remain mandatory, all queries/results remain capped, and the complete
   candidate set must still contain exactly one id. Two matching Classy 101
   uploads remain an ambiguity refusal.
4. Preserve all v0.8.12 snapshot, ad-generation, mandatory-link, threshold,
   loop/dedup, manual/automatic parity, honest-transport, memory-only cache and
   no-historical-rebroadcast boundaries. The runtime never consults YouTube
   History.

The patch is version `0.8.13`, version code `33`. Exact positive and negative
regressions cover MONTERO, both legitimate `(by ...)` forms, non-credit
parentheses, Te Bote, weak movie-channel evidence, stacked owner suffixes, the
three unique resolver misses, collaborator-marker absence and the two-upload
Classy 101 refusal. The focused parser/classifier/resolver suite passed before
the complete gate below was run. The full uncached gate then passed 320 tests
with 0 skipped, failures or errors; debug assembly succeeded and lint completed
with 0 errors and 23 warnings. The generated review artifact is
`dist/rustedwax-0.8.13.apk`, SHA-256
`ddfeb3e51cfe60fcf8fa2f13c05891989215154da948686650ae720e4ca9e026`.

## 20. v0.8.13 physical-device field record: log 19 and v0.8.14 implementation

`debug/rustedwax-log (19).txt` is the 5,261-line export from the v0.8.13/
version-code 33 APK. It begins at 11:48:33 and ends at 19:44:40 local time on
2026-08-02. Two app session-start markers appear; after key verification the
listener/probe started, Auto-scrobble was enabled, and the user exercised a
Stop/Start boundary at 11:50:18–11:50:19 before the accessibility watcher
connected at 11:50:30.

### 20a. Exact decision, transport and profile ledger

The log reconciles without a missing final decision:

| Evidence | Count/outcome |
| --- | --- |
| Finalizations | 114: 105 continuation expiries and 9 direct track changes |
| Broadcast payloads | 53 unique: 51 `song`, 2 `video` |
| Visible skips | 61: 42 unverified hyperlinks, 14 below threshold, 3 ordinary duration-floor refusals, 2 deduplications |
| Direct block reports | 52 |
| Offline queue | Gangsta's Paradise queued once at 18:26:41 and later reported one block transaction at 19:08:36 |
| Loops | One end-to-start wrap; the engine kept the first viewing and emitted one transaction |
| URL/identity safety | 48 stale or contradictory ids were unlatched; no successor-mixed payload was found |
| Canonical links | 53/53 use `https://www.youtube.com/watch?v=` plus one 11-character id |
| Profile | All 53 ids appear in the app-declared live Music/Videos section |

Four independent calls to `condenser_api.get_account_history`—one through each
configured Hive node—returned the exact 53 logged payload objects and the exact
53 logged transaction ids. Each node returned the same latest operation,
Good Love transaction `63efa2a6a0456e1d4223b2ef63b3ef60b6e23e7f`; no later
account operation existed at reconciliation time. The queued Coolio payload is
transaction `cb98d3d7681d0a01eb72d557f95c8a154d2bd9fb` and matches its
original immutable timestamp rather than the later retry time.

There are 54 account operations whose payload timestamps fall within the first
and last RustedWax payload timestamps. The additional No Clarity operation,
transaction `0fca967d12f727f3ca8590c7fe513ea243d8874f`, is not in the app
log and uses `platform: youtube embed` with a `https://youtu.be/` URL. That is
not RustedWax's mandatory mobile payload form and is recorded as a concurrent
other-client operation, not a missing RustedWax log line or duplicate.

The Stop boundary was positive but shallow: it occurred before any verified-id
candidate was remembered, so it proves calm probe teardown/no finalization but
does not physically prove clearing a populated candidate cache. The log later
contains 58 `remembered` lines and no run-local cache-recovery hit. Manual
Broadcast parity was not exercised. MusicBrainz recorded 9 confirmations, 98
no-match responses and 34 lookup failures; those network outcomes did not cause
a broadcast-state discrepancy. Two resolver search fetches failed for the same
ad-like query and failed closed.

### 20b. Two ordinary watch-page advertisements reached Hive

The only two `video` payloads in this RustedWax run are:

| Id and transaction | Final evidence | External reconciliation |
| --- | --- | --- |
| Namecheap `zUaMtSMZDgg`, `c94c4d17c8572018ed1c000e0b78ea405ab2cbec` | 29/30 seconds, 99%; uniquely resolved by title+channel+duration; public/listed; no YouTube Music type | Present in Videos; exact signed-in History search returned “This list has no videos.” |
| KaoJapan `azTP61YoD2s`, `fe0cd98b6761818d0512c027d5bee453404897cf` | 34/34 seconds, 100%; uniquely resolved; public/listed; title ends `動画広告` | Present in Videos; signed-in History search for `IROKA` returned “This list has no videos.” |

The user noticed Namecheap; the same reconciliation independently identifies
KaoJapan as a second leak. History is evidence for this field audit only and
must never be read by the runtime.

No `[ad]` line appears anywhere in log 19. This is explained by the source, not
by a missing threshold rule: `UrlWatcherService` calls its bounded
`findAdSignal` walker only when `videoIdInSameShortSnapshot` returns a concrete
`/shorts/{id}`. Ordinary `/watch` playback and bare `m.youtube.com` observations
therefore skip the scan. Binding watch-page pre-roll UI to the address-bar id
would also be wrong because that id names the organic content behind the ad.
The v0.8.14 implementation instead binds an exact label to the active MediaSession
track instance.

The other ad-like sessions normally failed closed because no unique canonical
id could be verified, remained under the ordinary 30-second floor, or did not
reach the 60% threshold. That accidental safety does not count as ad detection.
Namecheap passed because 30 seconds is eligible, not “under 30”; KaoJapan passed
at 34 seconds. Both met all ordinary-video rules once the resolver found their
public ids.

### 20c. Other rule outcomes

- **Transport and honest state passed.** All 53 exact payloads reached blocks;
  the one transient failure was reported as queued before its later independent
  block confirmation.
- **Finalized isolation passed observationally.** Forty-eight ids were
  unlatched when page/session metadata contradicted them, and none of the 53
  payloads mixes the ended title/time/progress with a successor id.
- **Threshold/floor accounting passed.** The minimum emitted percentage is 70;
  every below-60% decision stayed off-chain. Three verified ordinary items of
  10, 15 and 29 seconds were refused by the 30-second floor.
- **Loop/dedup passed.** The Alone, Pt. II wrap produced one payload; replayed
  Ring Ding Dong and Nobody listens inside the dedup window were refused.
- **Canonical-link and profile ingestion passed.** No URL-less, wrong-shape or
  missing-profile RustedWax operation was found.
- **Explicit ad evidence failed coverage.** No Short or watch-session label was
  captured, and two ads reached Hive.
- **v0.8.13 correction coverage failed.** MONTERO `6swmTBVI83k`, Te Bote
  `9jI-z9QN6g8`, the three unique resolver fixtures `lZizLbWxr_E`,
  `dn3d8awSA0c`, `napM9rZUzmU`, and Classy 101 `cD5T1Y4b7wA` do not occur in
  the log. The broader short-title/channel/cache/movie/transition/manual matrix
  likewise was not run, so no device approval can be inferred from unrelated
  successful songs.

### 20d. Metadata defects and controls

The correct ids and transactions reached Hive, but four payloads are not the
required generic metadata:

| Id | Emitted | Cause and implemented regression |
| --- | --- | --- |
| `TQNW0_RRicI` | Marlon Asher — `Strictly High Grade [Official Video` | `[Official Video 2024]` is retained because the year is not a promo word, then the closing bracket/year cleanup leaves an unmatched fragment. Require paired promo-plus-year cleanup with ordinary year/title negatives. |
| `HtJS32n6LNQ` | `TVXQ! 동방신기 '주문` — `MIROTIC' MV` | Top-level separator scanning protects double quotes but not balanced single-quoted works. Add structural single-quote support plus apostrophe/unmatched/event negatives. |
| `ixkoVwKQaJg` | `Taki Taki ft. Selena Gomez, Ozuna, Cardi B` — `DJ Snake` | `DJSnakeVEVO` does not exactly corroborate spaced `DJ Snake`, so the generic multi-artist branch mistakes the featured list for track-first credits. Add exact collapsed-owner proof while preserving all explicit track-first fixtures. |
| `BVYpT8LsjtA` | BENNETT — `BENNETT - Mamma Mia (feat. Mentissa) - Techno Mix` | A second top-level dash triggers conservative whole-title fallback. Allow only an exact channel-proven primary owner plus a bounded version-shaped suffix; preserve the suffix in the title and keep arbitrary three-part negatives conservative. |

The raw title for `5GYeWpjq54Y` already contains mismatched
`[Loving You Is in My DNA)` delimiters. Preserving that source text is not the
same bug as manufacturing the unmatched Marlon fragment and is an explicit
negative against guessed bracket repair. Conservative uploader/title results
without enough structural evidence are quality limitations, not authority to
invent credits.

### 20e. Gate decision

The v0.8.13 log-19 field gate **failed**. Transport, all four nodes, the durable
queue, profile ingestion, canonical hyperlinks, threshold/floor decisions,
Stop teardown, snapshot isolation, loop caps and deduplication performed well.
Two confirmed advertisements nevertheless became immutable operations, four
song payloads expose parser defects/limitations, and the exact v0.8.13 plus
combined historical fixture matrix was absent. No historical operation is to
be repaired, rewritten or rebroadcast.

### 20f. Implemented v0.8.14 automated matrix and device matrix later failed in log 20

Implemented in this order:

1. Add a pure, testable watch-session ad-evidence model keyed by package plus a
   unique track-instance token/signature. Retain the existing Short-id store and
   provisional/re-observed generation behavior unchanged.
2. Let `UrlWatcherService` scan the existing exact/localized label set whenever
   the visible host is YouTube. Concrete Shorts continue through the current
   id-bound path. Non-Short signals are offered without a video id to
   `SessionProbe`, which may bind them only to one established/unambiguous active
   browser track instance.
3. Freeze accepted watch-session evidence into `Watch`, carry, snapshot, Now,
   manual and automatic decision paths exactly as current Short evidence is
   frozen. Clear provisional state on track conflict/change, label absence,
   Stop, reset, package teardown and expiry. Never attach it to the organic
   watch URL.
4. Add the four generic parser corrections in §20d. Do not add runtime media
   catalogs or field-id branches.
5. Focused ad/store/probe/carry/rules and parser suites pass. The version is
   `0.8.14`, version code `34`, and documentation records only behavior
   actually present.
6. The full uncached gate passed against this versioned source:

   ```bash
   JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
     ./gradlew testDebugUnitTest assembleDebug lintDebug --rerun-tasks
   ```

7. The tested APK was copied to `dist/rustedwax-0.8.14.apk`. The authoritative
   result is 338 tests, 0 skipped, 0 failures, 0 errors; debug assembly passed;
   lint completed with 0 errors/23 warnings. SHA-256 is
   `a9507c733b188f9cf3a481c8b1446535da22449343181cc17ccf556890f298b8`.
   No stage, commit, push or PR was made.

Required automated positives and negatives:

| Area | Positive | Required negatives |
| --- | --- | --- |
| Watch-session ad | Exact label twice on the same 30+ second public ad track vetoes automatic and manual paths; accepted evidence survives session churn | No label, brand/title/channel/duration guesses, ambiguous active sessions, stale signal on the resumed organic track, label disappearance before confirmation, Stop/reset/package clear |
| Watch ad → organic resume | Same `/watch` URL, ad track B labeled, organic track A resumes: B vetoed, A carries only A progress and remains eligible | Ad flag must not bind to URL id, successor metadata or another package/session |
| Paired promo year | `[Official Video 2024]` strips as one paired promo group | `(Summer 2024)`, `[Song 2024]`, `1979`, mismatched open/close pairs |
| Single-quoted work | TVXQ shape yields artist prefix plus intact work containing a dash | `Gangsta's Paradise`, `Don't Start Now`, names/possessives, unmatched quote, quoted event/promo suffix |
| Owner-proven featured title | `DJSnakeVEVO` exactly-collapsed to `DJ Snake`; conventional artist-first title retained | partial/substrings, unrelated owner, existing Anuel featured-channel control, all three explicit log-17 track-first credit lists |
| Version suffix | exact owner plus `Artist - Track - Techno Mix` keeps `Track - Techno Mix` | publisher/event/promo/arbitrary three-part forms, missing/conflicting owner, nested brackets/quotes |

The log-20 physical round was expected to include both log-19 ads while their
exact visible labels are captured, an ad-to-organic resume under the unchanged
watch URL, the
four log-19 parser ids, all six absent v0.8.13 correction ids, the full §§13e/
17d transition/short-title/cache/manual matrix, an offline queue event, a loop,
2× playback, screen-off session churn, and a populated-cache Stop/reset. Export
only after the final continuation window, then reconcile the complete log,
signed-in History, all configured Hive nodes and both profile sections by id.
Section 21 records why that round did not satisfy this matrix.

## 21. v0.8.14 physical-device field record: log 20 and implemented v0.8.15 correction

> **Historical gate.** The coverage-unavailable refusal specified in this section was removed in
> v0.11.0g. Keep these cases as evidence that literal ad labels and coverage binding are track-local;
> do not expect missing coverage itself to refuse a uniquely verified id. Current expectations are
> in §40 and the identity contract.

`debug/rustedwax-log (20).txt` is the 10,945-line, 1,248,861-byte export from
the v0.8.14/version-code 34 APK. It begins at 21:14:46 on 2026-08-02 and its
last line is 10:52:51 on 2026-08-03 local time. The listener and probe started,
Auto-scrobble was enabled and the address-bar watcher reported connected at
21:16:08. No later watcher disconnect/reconnect line appears.

The export was made before the final continuation window completed. Its last
line says a roughly three-second Chrome Rondo session ended and is waiting 60
seconds for a replacement. That incomplete tail could not qualify and is not
part of the 227 finalized decisions below, but it independently fails §20f's
instruction to wait through the final continuation before export.

### 21a. Exact decision, transport, node and profile ledger

| Evidence | Count/outcome |
| --- | --- |
| Completed finalizations | 227 |
| Automatic broadcasts | 107: 105 direct block reports and two durable offline queue writes |
| Visible skips | 120 |
| Fixed test broadcast | One `platform=test` payload before playback, for 108 total account operations in scope |
| Payload kinds | 80 songs and 28 videos including the test payload |
| Canonical YouTube links | 107/107 automatic operations use `https://www.youtube.com/watch?v=` and an 11-character id |
| Duplicate broadcast ids | None among the 107 automatic operations |
| Offline queue | Mbappe Comps and Toe-To-Toe were queued, then independently reported in blocks as `638410bc26d61e807062d78ad1a21f5d0c230336` and `a4f270164705f363de6509f98a3742e41166a88b` |
| Live profile change | +80 Music and +28 Videos, exactly matching all 108 operations |
| Runtime failures | No crash, fatal/uncaught marker or broadcast without URL |

The ledger identity is exact. After the pre-run account boundary, normalized
log payloads and normalized Hive payloads both contain 108 rows and hash to
`7681df2bff0f2cb47be6961977fa0b3ef2d5aa143b5cdcd50fbc1a1d41ae46a6`.
All four configured nodes returned the same 108 exact operation rows; the
four-node normalized row set hashes to
`ecab872ce030c25a7112206eb083f98e726b9b6786de044aee9b743ace142d33`.
The first scoped operation is the fixed RustedWax test in transaction
`4ced349631401d002546fc5d9221eaf580aa83c0`; the last is Metallica — The
Unforgiven in transaction `c10f723ffab75cf1d98732dc9258e3cbb88c24f8`.
No historical operation was modified or replayed.

Signed-in Chrome YouTube History contained 103 of the 107 automatic ids. Exact
Namecheap search returned “This list has no videos.” The other three absent ids
were Metallica `fctnSdDjxiY`, `neHql1zl9wQ` and `Axw30njaMlY`; the log proves
those sessions occurred in Brave, not the signed-in Chrome profile used for the
History audit. Their exact ids, titles, durations and block transactions are
internally consistent. No second ad-like broadcast was identified.

### 21b. Namecheap leak and accessibility-observation outage

The same public Namecheap creative occurred four times:

| Finalization | Accessibility evidence | Decision |
| --- | --- | --- |
| 21:40:04, 30/30 seconds | `Visit Advertiser`, then `Sponsored`, bound to MediaSession track 15 | skipped: visible UI marked the track as an ad |
| 22:47:47, 30/30 seconds | `Visit Advertiser`, then `Sponsored`, bound to track 64 | skipped: visible UI marked the track as an ad |
| 01:07:39, 30/30 seconds | no `[ad]` or current-track `[url]` observation | resolved to `zUaMtSMZDgg`, broadcast at 01:07:49, block transaction `8c22a93cd7d581687055240d279d8713abfcdfc9` |
| 08:54:09, 27/30 seconds | accepted `Sponsored` evidence | skipped: visible UI marked the track as an ad |

The leaking MediaSession began at 01:06:09 with title `See Spike launch his
online business with Namecheap`, artist `Namecheap`, duration 30,081 ms and a
bare `m.youtube.com` notification hint. Search uniquely resolved the public,
listed Science & Technology upload by title, channel and duration. With no
literal ad flag, 30/30 seconds met the ordinary floor and 60% threshold exactly.
This was normal application of the central rules to incomplete evidence, not a
queue resend or a hidden brand exemption.

The missing evidence was not confined to Namecheap. The final accessibility URL
before the interval was `kF4MVeWFiDs`, generation 76 at 00:58:56. The next was
`pSY3i5XHHXo`, generation 77 at 01:35:03. No `[ad]` line appears during that
roughly 36-minute interval even though Super99, Finelo, Superloop, Namecheap,
PO Trade, Hostinger and Panama-telecom advert MediaSessions appeared. The
service still claimed connected and produced no outage warning. Most adverts
remained off-chain only because the resolver, duration floor or identity gate
failed. Namecheap alone was public/searchable and exactly 30 seconds.

There are 608 total `[ad]` diagnostic lines. Accepted MediaSession binding
occurred for 89 unique track tokens, and the final ledger contains exactly 89
explicit-ad skips. The many “0 active MediaSession tracks” refusals were late
page observations after the corresponding sessions ended and did not taint an
organic successor. Thus the implemented track binding worked whenever an
observation arrived; the defect is silent observation coverage/freshness.

### 21c. Other rule outcomes

The 120 skip decisions reconcile exactly:

| Rule | Count | Outcome |
| --- | ---: | --- |
| Exact visible ad evidence | 89 | all stayed off-chain: 57 finalized with `Sponsored`, 32 with `Visit Advertiser` |
| Below configured 60% threshold | 15 | all stayed off-chain |
| Missing duration plus zero played seconds | 6 | all stayed off-chain |
| Duration floors | 2 | one six-second item failed the hard ten-second floor; one unverified 15-second ordinary item failed the 30-second floor |
| Final id corroboration | 8 | seven ads/placeholders safely refused; one qualifying BOOGA song was a false omission (§21d) |

Seventeen position-wrap diagnostics were emitted and 14 completed continuous
viewings explicitly logged “first viewing kept; continuous viewing capped to
one scrobble.” No automatic id was broadcast twice. Six same-track session
restarts carried accumulated progress; one PO Trade advert carry also retained
its explicit ad evidence. Eighty-eight finalizations exercised playback up to
2×. The two offline payloads later confirmed with their original media
timestamps. No successor-mixed identity, missing canonical URL or finalization/
broadcast accounting gap was found.

All three automatic broadcasts shorter than 30 seconds were exact verified
Shorts (`RoPdKANOD4g`, `UxcsVnVIUfY`, `CbVOScRrjWI`) and were found in History
under their exact `/shorts/` identities. Ordinary threshold and duration rules
therefore remained distinct from the Short exception.

This log contains no user Stop/monitor-off event, muted-track refusal or
already-scrobbled dedup refusal. It does not physically prove populated-cache
Stop/reset, mute, replay dedup or a real YouTube manual/automatic parity case.
The fixed test payload is transport evidence, not manual YouTube parity.

### 21d. Qualifying omission and malformed credits

One of the eight id-corroboration refusals was a real organic loss:

- `JmeUtPih4U8`, `CENTRAL CEE - BOOGA (MUSIC VIDEO)`, played 110/110 seconds.
  Search returned one title/channel/duration match and enrichment reported
  category Music plus YouTube Music OMV. Final corroboration rejected candidate
  channel `Central Cee and LIVE YOURS` as contradicting ended channel
  `Central Cee`. Signed-in History contains the exact id. The other seven
  refusals were ad/placeholder sessions and correctly remained off-chain.

Two immutable song payloads used the correct ids and canonical links but
reversed artist/title:

| Id and raw title | Channel | Emitted | Required generic result |
| --- | --- | --- | --- |
| `DGs9TJmazB0`, `6IX9INE - SIP ft. Tyga, Nicki Minaj, Blueface (RapKing Music Video)` | `RapKing` | artist `SIP ft. Tyga, Nicki Minaj, Blueface (RapKing Music Video)`; title `6IX9INE` | artist `6IX9INE`; preserve the full cleaned right-hand work/feature title |
| `z7DbZS6l6Vk`, `Ed Sheeran – Bad Habits Feat. Tion Wayne & Central Cee (Fumez The Engineer Remix) [Official Video]` | `Tion Wayne` | artist `Bad Habits Feat. Tion Wayne & Central Cee (Fumez The Engineer Remix)`; title `Ed Sheeran` | artist `Ed Sheeran`; title `Bad Habits Feat. Tion Wayne & Central Cee (Fumez The Engineer Remix)` |

The deterministic source cause is the conventional-dash branch that reverses
whenever the right side looks like an explicit multi-artist list and the left
does not. Exact collapsed-owner proof runs first, but an unrelated publisher or
featured-artist channel does not prove the left. The existing unrelated-channel
Taki Taki regression currently expects reversal, so it encodes the structural
assumption that log 20 disproved. The correction must distinguish a right-hand
work prefix followed by featured credits from a genuinely bare trailing artist
list, while preserving every proven log-17 track-first fixture.

One classification/credit edge, football compilation `jTg-fpSHLdU`, remains
questionable but is not evidence-strong enough for this patch: its title names
both player and backing song, its channel/category say Sports, and the music
endpoint says UGC. No production rule is authorized from that ambiguity.

### 21e. Gate decision

The v0.8.14 log-20 field gate **failed**. Transport, four-node agreement,
profile ingestion, canonical links, accepted ad-track binding, thresholds,
duration floors, loop caps, playback speed, same-track carry and the durable
queue performed well. One advertisement nevertheless became a second permanent
Namecheap operation during a silent accessibility-observation outage; one
History-confirmed qualifying organic song was refused; two song payloads
reversed credits; the mandatory fixture matrix was absent; and the export ended
before its last continuation window. No historical operation may be repaired,
rewritten or rebroadcast.

### 21f. Implemented v0.8.15 automated matrix and strict physical matrix

The implementation followed the fixed order below. The source, complete
uncached gate and artifact copy/hash are complete; the physical round remains
pending:

1. Add failing focused regressions for a successful clean root scan, an exact
   ad scan, a resolver-only public 30-second track with no scan, stale coverage,
   ambiguity, session carry, Stop/reset/package clear and Browser evidence off.
2. Add a pure track-bound accessibility-coverage/freshness model. Coverage is a
   successful visible YouTube-root inspection, not watcher connection and not
   an “organic” assertion.
3. Refactor `UrlWatcherService` so normal callbacks and a bounded periodic
   refresh share one root-observation routine. Poll only a visible target-
   browser root while monitoring; preserve node budgets/recycling and log one
   evidence-outage transition while target MediaSessions continue.
4. Freeze coverage through Watch/carry/snapshot. With Browser evidence enabled,
   refuse automatic and manual broadcast for a resolver-only track that has no
   current-track coverage. Use an evidence-unavailable reason, never an ad
   claim. Exact current-generation URL observations already supply coverage.
5. Correct feature-list orientation so `Artist - Work ft./feat. A, B` is not
   reversed solely by its right-hand credits. Keep bare trailing credit lists
   and strongly work-shaped log-17 track-first forms intact.
6. Permit BOOGA's collaborative candidate byline only under unique exact-title/
   duration resolution, hard music provenance and exact complete leading-owner
   agreement. Preserve every ambiguity, partial, publisher and mismatch refusal.
7. Run focused watcher/evidence/probe/carry/snapshot/rules/manual/parser/resolver
   suites. Only when they pass, update to v0.8.15/version code 35 and change
   documentation from planned to implemented.
8. Run the complete uncached gate:

   ```bash
   JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
     ./gradlew testDebugUnitTest assembleDebug lintDebug --rerun-tasks
   ```

9. Copy only that tested artifact to `dist/rustedwax-0.8.15.apk`, calculate its
   SHA-256, then perform the complete device gate below. Do not stage, commit,
   push or open a PR before user review and a successful field round.

The focused implementation gate covers 176 tests across accessibility
coverage, exact ad evidence, URL generation, track identity/carry, latch
corroboration, central scrobble rules/manual parity, title parsing, search
candidate parsing, finalized identity corroboration and verified-candidate
cache lifecycle. It passes with 0 skipped, 0 failures and 0 errors. Version name
`0.8.15` and version code `35` were applied only after this result.

The complete uncached
`testDebugUnitTest assembleDebug lintDebug --rerun-tasks` gate then passed 349
tests with 0 skipped, 0 failures and 0 errors. Debug assembly succeeded and lint
completed with 0 errors and 23 warnings. The tested artifact is
`dist/rustedwax-0.8.15.apk`, version code 35, SHA-256
`242d1b76d473754494dec74e035a7731ee1311c4920458926c5dd769e7ee365c`.

Required focused positives and negatives:

| Area | Positive | Required negatives/controls |
| --- | --- | --- |
| Accessibility coverage | Successful clean scan binds only to the one active track; same-track session recreation retains it | connection alone, old scan, new track, ambiguous sessions, other package, null/inactive root, Stop/reset/destroy/expiry do not cover a track |
| Coverage-unavailable rule (historical v0.8.15; superseded by §40) | The old binary refused Browser-evidence-enabled resolver-only tracks with no current scan | Current control is the opposite: one unique finalized lookup remains eligible regardless of coverage; ambiguity and literal ad evidence refuse |
| Exact visible ad | Existing label path still vetoes the track and survives genuine session churn | no brand/title/channel/duration/public-state inference; organic successor never inherits ad or clean coverage |
| Feature orientation | exact SIP and Bad Habits fields above remain conventional artist-first | qA6/lA8/UWV track-first cases, Anuel, Taki Taki, bare co-artist lists, unrelated/featured channels and arbitrary dashes |
| Collaborative byline | BOOGA uniquely resolves under exact title/duration, OMV and leading-owner agreement | arbitrary `and`, substring/prefix, fan/publisher suffix, wrong title/duration, absent hard-music provenance, multiple ids and Classy 101 ambiguity |

The strict physical round specified at implementation time was to include:

1. Namecheap and KaoJapan with literal visible labels; a labeled ad followed by
   organic content under the unchanged watch URL; and, for the historical artifact, an observed
   evidence-outage/resolver-only refusal plus a clean-scanned organic control. Current builds use
   §40's background unique-lookup positive and explicit-ad/ambiguity negatives instead.
2. Log-20 fixtures `JmeUtPih4U8`, `DGs9TJmazB0`, and `z7DbZS6l6Vk` with exact
   id, artist, title, kind and canonical-link assertions.
3. Log-19 parser ids `TQNW0_RRicI`, `HtJS32n6LNQ`, `ixkoVwKQaJg`,
   `BVYpT8LsjtA` and mismatched-delimiter negative `5GYeWpjq54Y`.
4. The absent v0.8.13 fixtures `6swmTBVI83k`, `9jI-z9QN6g8`, `lZizLbWxr_E`,
   `dn3d8awSA0c`, `napM9rZUzmU`, `cD5T1Y4b7wA`.
5. All §§13e/17d transition and parser fixtures, including `saGYMhApaH8`,
   `GtSRKwDCaZM`, `AnKdQ5p5Ks8`, `qA6FBDYncGk`, `lA8OhVn-o7M`,
   `UWV41yEiGq0`, `34Na4j8AVgA`, the four log-14 parser ids and LA PLENA.
6. A genuine verified Short below 30 seconds, an ordinary under-30-second video,
   one loop, a true dedup replay, mute, 2× playback, an offline queue event,
   screen-off same-track churn and Stop/reset after the candidate cache is
   populated.
7. One Now-card/manual Broadcast attempt for an eligible YouTube track and one
   for each new refusal class, followed by automatic finalization to prove
   central-rule parity.

End every browser session, wait past the final 60-second continuation window,
then Stop and export. Reconcile every finalized decision and every automatic/
manual/test payload against signed-in History, all configured Hive nodes and
both profile sections. The gate requires zero qualifying evidence-backed
omissions, zero advertisement payloads, zero credit reversals, exact decision/
transport/profile accounting, no duplicate id, and every required fixture with
its specified outcome.

## 22. v0.8.15 final physical-device field record: log 21

`debug/rustedwax-log (21).txt` is the final v0.8.15 Chrome device record. It has
14,464 lines, is 1,648,507 bytes, and has SHA-256
`5a999242ff55c9cd32906d47b59efaf101522d2b1ceab2a7da3aa5547b7c183d`.
The surviving export covers 2026-08-03 12:56:49 through 18:06:25 local time.
Android playback timestamps reset into low uptime ranges twice, consistent with
the two reported device reboots; the second restart also left a NUL gap in the
export. No RustedWax exception, fatal, ANR, out-of-memory, watchdog or explicit
crash marker appears. The record cannot establish overheating as the reboot
cause, and neither History nor Hive can reconstruct sessions lost before they
reached a final decision.

### 22a. Complete surviving decision ledger

The export contains 228 `[finalize]` decisions: 98 broadcasts and 130 skips.
Every broadcast reached `[engine] scrobbled (block)`. The 98 payloads contain 98
unique YouTube video ids and 98 unique transaction ids, split into 57 songs and
41 videos; no duplicate payload URL was found.

| Final decision | Count | Audit result |
| --- | ---: | --- |
| Exact visible-ad veto | 85 | Stayed off-chain |
| Below threshold or no qualifying duration/progress | 32 | Stayed off-chain |
| No verified video id | 11 | Stayed off-chain |
| Hard duration floor | 2 | Stayed off-chain |
| Broadcast and block-confirmed | 98 | 98 unique ids/transactions |

The 32 threshold decisions include values displayed as rounded percentages.
For example, 110/185 seconds is 59.46% and correctly remains below a 60% gate
even if the user-facing diagnostic rounds it to `60%`. The two hard-floor
decisions occurred at 6 and 8 seconds. Thirteen loop/repeat diagnostics produced
no duplicate video id. Same-track MediaSession restarts retained progress and
evidence, and 2× playback, playlist changes, Shorts, minimizing Chrome,
screen-off intervals, pause/resume and playback stops all occurred in the run.

### 22b. Hive and profile reconciliation

All 98 log-21 transactions were present on the signed-in `skiptvads.vidz`
profile; the comparison returned no missing id. The profile snapshot contained
440 Music and 231 Videos entries in total, including 153 Music and 69 Videos for
the day. Every scoped log-21 operation reconciled to its logged id and block
transaction. The older immutable Namecheap transaction
`8c22a93cd7d581687055240d279d8713abfcdfc9` visible on the profile belongs to
log 20/v0.8.14, not this build. Log 21 created no advertisement payload found by
the log, chain and profile comparison.

Two naturally served Namecheap ads supplied the most important v0.8.15 field
check. `See Spike launch his online business with Namecheap` was skipped on the
exact `Sponsored` signal, and `Watch Yeti find his ideal web hosting service
with Namecheap` was skipped on `Visit Advertiser`. This is evidence that the
new periodic accessibility observation closed the silent Namecheap path that
failed log 20; it is not a production brand rule.

### 22c. Conservative omissions and metadata limitations

History cross-checking separated the 11 unverified-id refusals into seven ad or
promotional sessions absent from History and four full organic songs present in
History:

- `We Found Love (Album Version)` (`n6N1_sxlBU8`)
- `Poker Face` (`oG-4Uvhm4lI`)
- `I Gotta Feeling` (`hs_yk24ghzA`)
- `Why Don't You Get A Job` (`mQYJYY4VkWA`)

Those four tracks are false omissions caused by conservative identity
resolution. They produced no malformed or unlinked payload. The 85 explicit-ad
decisions contained one additional conservative false omission: `Zun Da Da`
(`KbhLOifTuF4`) played for 303/304 seconds and appears in History, but inherited
a still-active `Visit Advertiser` observation immediately after the preceding
promoted music video `Mi Xico` transitioned to the organic track. The promoted
video itself is absent from History and was correctly kept off-chain. This
transition poisoning is a known false-negative boundary; it did not pollute
Hive.

Eleven accessibility evidence outages were diagnosed and one recovery was
observed; the other outages ended with the track/session or app lifecycle.
No final decision reached the explicit `Browser evidence was unavailable...`
branch because identity resolution failed first in those physical cases. The
central fail-closed branch remains covered by automated regressions.

Two accepted song payloads were useful but conservatively uploader-attributed:
`#TPL BM (OTP) - London View | Pressplay` used artist `Pressplay Media`, and
`CHARLY BLACK & J CAPRI - WHINE & KOTCH - - HEAD CONCUSSION - 21ST HAPILOS`
used artist `Hapilos`. Several music-oriented Shorts classified as songs from
YouTube Music/category evidence; this matches the configured product behavior
and the user's acceptance criteria.

### 22d. Coverage boundary and release decision

Log 21 exercised Chrome only. Brave continues to share the same production
path and has prior device evidence, but was not independently replayed in this
round. The exact SIP, Bad Habits, Taki Taki and BOOGA historical fixtures were
not replayed; their v0.8.15 corrections remain automated-regression evidence,
not new physical evidence. Mute, a deliberate true dedup replay, a populated-
cache Stop/reset and every item of the broad §21f fixture list were likewise not
forced in one session.

For the observable qualifying population, the practical capture estimate is
98 / (98 broadcasts + 4 resolver misses + 1 transition-poisoned organic track),
or approximately **95.1%**. This excludes threshold/floor refusals and tracks
lost before finalization during reboots, so it is a field estimate rather than
a formal accuracy or recall benchmark.

The strict §21f zero-omission/every-fixture matrix was therefore not literally
passed. The user accepted v0.8.15 as the final Phase 4 product because the broad
real-use run produced no new ad payload, reconciled 98/98 broadcasts exactly,
showed no duplicate or crash signature, and exceeded the stated 80% practical
cataloging target. The five known organic false omissions, two uploader-credit
limitations and incomplete historical fixture replay are documented release
exceptions. No immutable historical operation will be repaired, rewritten or
rebroadcast. Native YouTube and YouTube Music app ingestion begins separately
in v0.9; it is not part of this release.

## 23. v0.9.0 native YouTube apps implementation and pending device gate

v0.9.0/version code 36 implements independent default-off MediaSession sources
for:

- `com.google.android.youtube`; and
- `com.google.android.apps.youtube.music`.

This section distinguishes source behavior from physical evidence. The
implemented code and JVM regressions do not establish which metadata or ad
signals current production app versions expose on the device. The detailed
field inventory, rationale and expanded checklist are in
`<redacted-private-provenance>`.

### 23a. Implemented automated matrix

- Both settings default off, persist independently and admit only their exact
  package when enabled; the Brave/Chrome allowlist is unchanged.
- Media-id, canonical media-URI and canonical artwork-URI extraction obey the
  required priority and always create a canonical watch link.
- Shorts media URIs retain path proof; hostile suffix hosts, malformed ids and
  arbitrary artwork paths remain unresolved.
- Package origin without an id is site-only. Payload construction refuses it
  unless the lookup-enabled resolver later supplies exactly one corroborated
  id under the existing ambiguity gate.
- Browser, native YouTube and native YouTube Music cannot claim one another's
  progress. Same-package recreation retains a compatible track once; a
  different case-sensitive exact id cannot inherit it.
- Package opt-out clears that package's pending continuation without clearing
  the other package. Stop/reset/listener epochs invalidate both native
  snapshots while preserving the settings themselves.
- Supplied YouTube Music title, artist and album are preserved; a literal
  podcast type, structured non-music genre or hard episode/format evidence
  remains video. Native YouTube retains the evidence-ranked song/video ladder.
- Thresholds, duration floors, Short proof, playback-speed scoring, loop caps,
  song-only repeat cap, mute/dedup and the shared manual/automatic rule result
  continue through the existing regression suite.
- Native resolver-only fallback does not require browser accessibility
  coverage. Native packages do not read browser notification hints, URL/ad
  evidence or accessibility coverage.
- No production id/title/artist/brand fixture catalogue, History runtime read
  or native title/brand/id/duration/popularity ad heuristic was added.

The exact uncached command required for this phase passed 372 tests, 0 skipped,
0 failures and 0 errors. `assembleDebug` succeeded. `lintDebug` completed with
0 errors and 23 warnings. The tested source APK and copied
`dist/rustedwax-0.9.0.apk` are byte-identical and both have SHA-256
`3f8945e997d592dbf40fac6aa727f69215cbf8a805b5a4b15df031171a0ec58c`.

### 23b. Implemented diagnostics and permission boundary

Now shows the source package and origin. Identity logs name `media id`,
`media URI`, `artwork URI` or `corroborated resolver`; unresolved and ambiguous
resolver outcomes retain their exact refusal. Native settings and Now warn that
browser visible-ad protection does not cover native apps.

Every native metadata callback dumps the standard MediaMetadata surface and
non-standard keys. Every native state callback additionally records numeric
state, position, buffer, speed, actions, error text, update time, queue id,
active flag on API 31+ (explicitly marked unavailable below it), custom actions
and extras. This instrumentation does not interpret an ad. No manifest
permission was added: Notification Access remains the only grant required for
native MediaSession access.

### 23c. Required physical checklist — not yet completed

Keep automatic broadcast off for the initial instrumentation pass. Export the
complete log only after every 60-second continuation expires. Then, for any
broadcast-enabled pass, reconcile log payload, canonical video id/link,
transaction id, at least two current Hive nodes and the expected profile
section. Signed-in History may be an external post-run oracle only.

1. Confirm both native toggles are off after a fresh preference state and remain
   independent across process restart.
2. Exercise each native package disabled and enabled; confirm Chrome and Brave
   remain unchanged with native sources off and on.
3. Switch browser → YouTube → YouTube Music → browser with identical-looking
   metadata; verify no progress, identity, URL, ad, coverage, resolver candidate
   or continuation state transfers.
4. Capture ordinary YouTube videos and YouTube Music songs. Record media id,
   media URI, all artwork URIs, non-standard metadata and the successful exact
   id route. Verify clean YouTube Music title/artist/album preservation.
5. Capture YouTube Music podcasts and numbered episodes, including title,
   `GENRE` and any `MUSIC_VIDEO_TYPE_PODCAST_EPISODE` lookup evidence; they must
   remain Videos absent stronger legitimate music provenance.
6. Capture native YouTube songs/music videos and ordinary non-music videos;
   compare the Now kind reason with the eventual immutable profile section.
7. Capture native Shorts: whether a MediaSession exists, whether an exact
   `/shorts/` URI is published, duration-floor behavior and continuous-loop cap.
8. Test playlists/automatic next, pause/resume, minimizing/backgrounding,
   screen-off playback and a complete 2× listen.
9. Force same-track MediaSession/controller recreation; accumulated progress
   must survive once. Change to a different exact id under identical metadata;
   nothing may carry.
10. With lookup off, an exact-id-less native item must remain off-chain. With
    lookup on, test one unique title/channel/duration recovery, one unresolved
    case and a deliberately ambiguous same-work upload pair.
11. Exercise manual and automatic parity for threshold, floor, canonical id,
    mute, dedup, loop and kind cap.
12. Disable each native toggle mid-track, run Stop/reset with populated progress
    and resolver cache, and toggle Notification Access off/on. No old native
    state may complete or sign after the boundary.
13. Capture YouTube and YouTube Music pre-roll and mid-roll ads, plus a native
    Shorts promotion if sessions exist. Preserve exact before/during/after
    metadata, PlaybackState, controller/session recreation and organic resume.
14. Determine whether ads publish a literal structured signal generic across
    packages and creatives. If not, record the disclosed limitation; do not add
    brand/title/channel/id/duration/History guesses.
15. Confirm all native payloads use exactly
    `https://www.youtube.com/watch?v=<same verified id>`, with no duplicate id,
    wrong profile section or immutable historical rewrite.

Until §23c is reconciled and the native advertisement shape is reviewed, both
native toggles remain experimental, device-pending and default-off.

## 24. Native YouTube Shorts field investigation — partial gate failed

The 2026-08-03 Samsung Galaxy A12 run completed the first focused native
Shorts measurement but did not approve v0.9.0 native input. The complete
evidence, sample matrix, implementation contract and next physical gate are in
[<redacted-private-path>](../Architecture/Phases/<redacted-private-path>).

`debug/rustedwax-log (22).txt` and `debug/mob001.jpeg`/`mob002.jpeg` show the
original failure: after entering recommendations beneath an ordinary video,
YouTube History recorded six different Shorts while 26 MediaMetadata callbacks
continued to publish BELLAKEO. Native session recreation carried that stale
track's accumulated time from 6 seconds through 71 seconds. No Short title or
id appeared in the RustedWax log. An exact-ID-less native continuation can
therefore absorb unrelated Shorts playback and is the first v0.9.1 safety fix.

Direct ADB observation on YouTube `21.30.209`, Android 12/API 31 established:

- foreground Shorts expose `reel_watch_fragment_root`, `reel_watch_player`,
  title, exact `@handle`, and a live current/total `reel_time_bar` description;
- sampled ads expose literal `Ad` labels inside the same player tree;
- the MediaSession remains stopped/position-zero/empty and cannot identify or
  measure the Short;
- PiP removes title, handle and seekbar proof, so the first implementation must
  stop counting at the last valid foreground observation; and
- privileged activity dumps are unavailable to the app and were stale anyway.

A temporary, fully removed real-resolver probe resolved one of three tuples.
The two misses used accessibility handles `@Status_svijet` and `@Beredist`,
while their watch pages used display authors `Status` and `Beredits`. The same
canonical pages expose exact matching handles in
`playerMicroformatRenderer.ownerProfileUrl`. The implemented v0.9.1 path carries
that dedicated owner handle through watch facts/cache/resolution and retains
title + duration + handle + unique-candidate fail-closed corroboration.

At the time of that investigation no production code or APK had changed. The
subsequent v0.9.1 implementation/result is recorded in §25. Signed-in History
remains forbidden as a runtime input.

The implemented v0.9.1/version-code 37 patch is bounded to:

1. disabling native MediaSession progress carry without a live exact id;
2. adding a second, independently disclosed accessibility component whose OS
   package scope is only `com.google.android.youtube`;
3. structurally parsing and tracking foreground Shorts from conservative
   seekbar deltas with literal ad vetoes and PiP/background fail-closed;
4. adding explicit native foreground-Short source proof to the existing
   snapshot/rules/classifier/UI path;
5. extending exact-id resolution with canonical owner-handle corroboration;
   and
6. preserving the existing browser, YouTube Music, resolver ambiguity,
   canonical-link, threshold, loop, mute, dedup, queue and signing boundaries.

The feature remains experimental/default-off unless the complete automated and
physical acceptance matrix in `<redacted-private-path>` passes. That document is
the record; the work it describes was implemented and its device result is in
§25 below.

## 25. v0.9.1 native foreground Shorts implementation and device result

v0.9.1/version code 37 implements the bounded §24 follow-up. The native
MediaSession carry safety fix landed first: a native controller without a live
exact source id cannot remember or claim progress across recreation. A second
accessibility service is independently disclosed and Android-scoped only to
`com.google.android.youtube`; the browser service remains scoped to its prior
browser packages.

The pure parser and lifecycle require a single measured Shorts root/player,
one title, one exact normalized owner handle and one current/total seekbar.
Unsupported/localized/conflicting/hidden/non-player shapes refuse. A 750 ms
stability gate prevents outgoing footer fields from pairing with an incoming
duration or ad seekbar. Progress is position-delta only, unchanged/pause/seek/
rewind/missing-proof safe, and strict end-to-start wraps remain capped by the
shared rules. The independent freshness watchdog prevents a blocked Samsung
accessibility lookup from extending the three-second proof grace. Samsung also
exposed a standalone clickable semantic hashtag node beside a full caption;
the parser excludes exactly one bare hashtag chip while preserving fail-closed
behavior for multiple prose title candidates.

Automated release commands, all uncached/rerun:

```text
:app:testDebugUnitTest  PASS — 409 tests, 0 failures/errors/skips
:app:assembleDebug      PASS
:app:lintDebug          PASS — 0 errors, 23 warnings
git diff --check        PASS
```

The 2026-08-04 Samsung <redacted-device-model> Galaxy A12 / Android 12 pass left
`autoScrobble=false`. It verified independent setting/grant combinations;
exact foreground Sona/Status/Beredist proof; stale MediaSession suppression;
sparse sequential deltas; threshold; pause; forward seek; rewind; loop;
rapid transitions; PiP below/above threshold; Home/search/channel/sound/
comments/ordinary-watch refusal; Stop; native opt-out; accessibility and
Notification Access reconnect; app reinstall; exact-ID-less controller churn;
and Chrome, Brave and YouTube Music smoke tests.

Two natural Short advertisements were sampled: Finelo and Pocket Toons. Both
published literal `Ad`, finalized the preceding organic item, remained isolated
from the successor, and produced no broadcast with automatic mode off. The
rabbit-hole pass initially exposed 200–250 ms torn accessibility frames; the
stability gate was added generically and the rapid-transition/ad pass was
repeated successfully.

After the account was confirmed as a test account, four lookup-on native Shorts
were written and block-confirmed: `s8ZQSxuKPb0` at 110/158 s (tx
`d57d3087890bfd3c96f82b4e68d825c28fd1c61a`, block 108722003),
`orsMh4bNeGE` at 103/139 s (tx
`69a8934932c388fbbd57a0b132f0a6a3957e7b00`, block 108722091),
`lw8InLbiBfM` at 45/48 s (tx
`4b70556927a08195593a6cc4f32d3099b9806d21`, block 108722137), and
`sMyyk-OlizA` at 73/95 s (tx
`6a8ce57cd8513fa6cad8ad50db543914491fbb33`, block 108722284). Exact custom-json
payloads reconciled on `api.hive.blog`, `api.deathwing.me` and
`api.openhive.network`; the public Videos profile showed exactly four scrobbles
and four unique ids for the day.

The rebuilt artifact then passed three physical fail-closed cases. Lookup-off
`QjR-m6q0wN4` refused the missing verified hyperlink. Lookup-on
`Bf7Qtyr-2IQ` refused because no exact title+duration+handle search candidate
was available. The genuine same-owner pair `PlTiqSpwzTI`/`VL_1TfgB2pw`
produced an explicit two-id ambiguity refusal. All three stayed off-chain and
the profile remained at four. A live native Short cannot expose the manual
button because its exact id is not resolved until finalization; central
manual/automatic rule and ad-veto parity remains covered by the automated
suite. The bounded foreground-Short gate is approved, while the native options
remain experimental/default-off pending broader v0.9 native-app evidence.

The source APK and copied artifact are 14,032,557 bytes and byte-identical:

```text
c6f2f1800a1cc6b6d76c260181d2402a3d648c9ecf7b3bc94ad897eeb1ce0895  app/build/outputs/apk/debug/app-debug.apk
c6f2f1800a1cc6b6d76c260181d2402a3d648c9ecf7b3bc94ad897eeb1ce0895  dist/rustedwax-0.9.1.apk
```

## 26. v0.9.2 native simplified-playlist metadata field failure and patch gate

The live 2026-08-04 Samsung pass switched naturally into the YouTube playlist
`Reggaeton 2016,17,18` and exposed a separate ordinary-native identity gap.
Automatic, Monitoring, lookup and the native toggle were on and all services
were connected. `Se Preparó` (188/188 s), `Felices los 4` (224/230 s),
`Si Tu Novio Te Deja Sola` (158/244 s) and `No Quiere Enamorarse` (207/213 s)
all cleared threshold but stayed off-chain because the MediaSession supplied no
exact id and bounded search could not satisfy raw title+channel+duration
identity. No signer, RPC or queue failure occurred.

The canonical field record and version-code 38 patch contract are in
`<redacted-private-provenance>`. The automated gate must prove a source-scoped
structured resolver: fully fetched public-page title grammar must reduce to the
exact separated native title and a complete exact native artist credit, duration
must remain within five seconds, and exactly one upload may match. Global raw
title/channel resolution, exact-id precedence and foreground owner-handle rules
must not loosen.

The same pass exposed exact-ID-less same-metadata duration replacement shapes
including 32 → 7 → 188 seconds and 7 → 257 seconds. Because those fragments
may be transitions or ads with organic metadata already installed, v0.9.2 must
discard the prior fragment and restart at zero on a material duration conflict.
It must not infer an ad, finalize the fragment, merge its progress, or carry it
into the replacement.

Implemented additions:

- all four measured structured music identities plus official-video/audio,
  lyric, `Video Oficial`, pipe-suffix and channel-display variants;
- partial artist strings, non-structural containment, duration conflict,
  unrelated uploader and genuine two-upload ambiguity negatives;
- native same-metadata duration-conflict discard with no finalized snapshot and
  zero carried time, alongside existing bounded refinement/different-id cases;
- unchanged browser, foreground Short, exact native id, manual/automatic,
  canonical-link, threshold, dedup and lifecycle suites; and
- uncached unit tests, debug assembly, lint, `git diff --check`, exact APK copy,
  SHA-256 match and installed-device version/service/settings verification.

Do not backfill or rebroadcast the four missed operations. Physical acceptance
uses only subsequent natural playlist transitions after the exact APK is
installed.

The v0.9.2 implementation completed this source gate:

```text
:app:testDebugUnitTest  PASS — 416 tests, 0 failures/errors/skips
:app:assembleDebug      PASS
:app:lintDebug          PASS — 0 errors, 23 warnings
git diff --check        PASS
```

`app-debug.apk`, `dist/rustedwax-0.9.2.apk` and the installed A12 base APK are
14,032,557-byte identical artifacts with SHA-256
`5cf7fbfdd950376c8b61ede0a0effc7f843f25b249f47c06172471f18559d072`.
The device reports version 0.9.2/code 38; automatic, Monitoring, lookup, both
native switches, Notification Access, both accessibility bindings and USB
stay-awake were preserved. YouTube continued without deliberate navigation.
RustedWax joined `Hey DJ` mid-track, so that partial item is not a clean
structured-resolution acceptance sample. It did physically validate the new
transition guard: the same title/artist changed 218→20 seconds after 8.3 seconds
of STOPPED grace, then 20→207 seconds after 5.4 seconds. Both times the old
fragment was discarded with zero carry and no ad inference, and neither built a
payload. The clean 207-second `Hey DJ` phase then completed. Structured recovery
found two exact candidates (`YN-aYhtMHIw`, `1fb9DtJpbHw`), logged the ambiguity,
refused every id and built no payload. This physically passes the ambiguity
refusal branch. A later natural unique match remains the successful-resolution,
write and reconciliation acceptance sample.

## 27. v0.9.3 artist-aware budget and immutable native continuation gate

The post-v0.9.2 field continuation is recorded in
`<redacted-private-provenance>`. It proved one complete transport path (`Dile Que Tu
Me Quieres`, `jc70ZO9X0XA`, tx
`dfb56dd52634f1da35d34a56e6b0b4a328d3a0d3`) while four popular titles were
prematurely rejected by a title-only eight-page ceiling, `La Rompe Corazones`
was split into 52% and 50% fragments by a native controller recreation, and
`Te Vas` correctly refused two exact uploads.

The v0.9.3 source gate includes regressions proving:

- the search-card prefilter requires exact parsed work plus a complete exact
  artist credit before a candidate consumes the eight-page budget;
- partial artist substrings, unrelated channels, duration contradictions and
  more than eight exact work/credit candidates still refuse;
- a native continuation is stored only with a uniquely corroborated immutable
  id, an exact same-id replacement claims it once, and an exact different-id or
  unresolved replacement cannot claim it;
- applying memory-only carry authority does not disable exact-ID-less material
  duration replacement isolation;
- a carried pre-resolution records raw versus structured provenance and is
  re-fetched through that same predicate at finalization; structured authority
  never seeds generic raw-title/channel authority;
- ambiguity, Stop/opt-out/source epoch, foreground Short ownership, timeout and
  package isolation remain fail-closed; and
- identical ordinary-player missing-Short diagnostics are rate-limited while a
  changed or safety-critical diagnostic is emitted immediately.

Then run the complete uncached unit, assembly and lint gate, `git diff --check`,
copy/hash the exact APK, install it without deliberate YouTube navigation, and
verify version, services, settings, USB stay-awake and installed APK identity.
Do not backfill any earlier miss; physical acceptance uses only natural
post-install items.

The completed source/deployment gate is:

```text
:app:testDebugUnitTest  PASS — 422 tests, 0 failures/errors/skips
:app:assembleDebug      PASS
:app:lintDebug          PASS — 0 errors, 23 warnings
git diff --check        PASS
```

`app-debug.apk`, `dist/rustedwax-0.9.3.apk` and the installed A12 base APK are
14,048,941-byte identical artifacts with SHA-256
`d18342325d7288f5ccfe16aa549b5752e6ba2fd63d0522cd28e5ae7e65388d88`.
The device reports version 0.9.3/code 39; automatic, Monitoring, lookup, both
native switches, Notification Access, both accessibility bindings and USB
stay-awake were preserved. YouTube continued without deliberate navigation.

Two provisional code-39 audit builds preceded the final artifact and do not
belong to its field ledger. The final exact install connected at 10:12:21 local
time. It joined `Sexo, Sudor y Calor` at a 251-second stable phase and launched
early resolution immediately; that response contained zero parseable search
candidates, so no authority or payload was manufactured. Equivalent ordinary-
player missing-Short events produced one bounded diagnostic instead of the
prior callback-rate flood, physically passing the coalescing check. Natural
unique-write and uniquely same-id controller-recreation samples remain pending.
The joined item later finalized at 215/251 seconds; final search again returned
zero candidates and built no payload. The subsequent `Te Boté (Remix)` phase
found two exact structured uploads (`9jI-z9QN6g8`, `bvEf7FOscm8`) and correctly
established neither carry authority nor a scrobble. Missing-Short reminders were
measured about 30–46 seconds apart. These pass refusal/coalescing only; they do
not close the unique-write or same-id continuation rows.

## 28. v0.9.4 exact structured author credit and featured-work symmetry gate

The untouched v0.9.3 field continuation through 10:44:56 local is recorded in
`<redacted-private-provenance>`. It produced four unique structured native writes in
one healthy run (`K6aqdUp-OgY`, `YxZXLWIx6ik`, `Hn2l8LbvXsY`,
`0XvGIM_hwDc`); each exact custom-json operation was independently present on
two Hive nodes and all four ids were present on the public Music profile. The
remaining qualifying tracks reached their thresholds but constructed no payload
because identity was absent or ambiguous.

The patch is intentionally matcher-only. Its focused automated gate must prove:

- `Natti Natasha ❌ Ozuna - Criminal [Official Video]`, exact author
  `NATTI NATASHA`, page duration 273s and native `Criminal` / `NATTI NATASHA` /
  273s selects `VqEbCxg2bNI` uniquely;
- a title-parsed collaboration does not authorize a partial credit, and an
  unrelated page author cannot substitute for the native artist;
- the same explicit trailing `ft.`/`feat.`/`featuring` grammar reduces the native
  title and candidate work symmetrically, without removing `(Remix)` or arbitrary
  parentheticals;
- the measured duration-compatible `Ella Y Yo (Feat. Don Omar)` pages remain an
  ambiguity after symmetric work normalization;
- the 228-second Ozuna/Juanka page and 218-second `Release - Topic` page for
  `Si Te Dejas Llevar` both remain contradictions to the native tuple;
- the existing `Te Boté (Remix)`, `La Pregunta`, `Si Se Da (Remix)`, partial
  artist, over-budget and unrelated-channel refusals remain fail-closed; and
- the raw resolver and browser target-package tests remain unchanged.

After focused tests, run `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug
--rerun-tasks`, `git diff --check`, copy and hash the exact version-code-40 APK,
install that artifact without navigating or force-stopping YouTube, and verify
the installed bytes, version, Automatic/Monitoring/lookup/native switches,
Notification Access, both accessibility bindings and USB stay-awake. Physical
acceptance requires a natural unique structured match to build one canonical
linked payload and block-confirm; a genuinely ambiguous item must construct no
payload. Do not backfill any pre-install miss. A same-id controller recreation
may close its existing v0.9.3 row only if YouTube naturally produces one.

The implementation gate completed as follows:

```text
:app:testDebugUnitTest  PASS — 425 tests, 0 failures/errors/skips
:app:assembleDebug      PASS
:app:lintDebug          PASS — 0 errors, 23 warnings
git diff --check        PASS
```

`app-debug.apk`, `dist/rustedwax-0.9.4.apk` and the installed A12 base APK are
14,048,941-byte identical artifacts with SHA-256
`cbaebadc91d0045b6bd7a2abec8aaacefb2e914782a404d705b24890d4c9ccbf`.
The device reports v0.9.4/code 40. Automatic, Monitoring, lookup, both native
switches, Notification Access, both accessibility bindings and USB stay-awake
remained enabled. YouTube was not navigated or force-stopped; the exact artifact
connected at 10:59:34 local while `El Efecto` was already playing. Its two exact
structured candidates (`hEiI7FT84kY`, `gENTa8g6x78`) were again refused, proving
the ambiguity gate remained intact under code 40.

The untouched continuation added physical negative coverage. `La Forma En Que
Me Miras`, `Diosa`, `Noches de Aventura`, `Por Amar A Ciegas` and `Hace Mucho
Tiempo` each retained multi-upload ambiguity refusal. `La Occasión` and
`Más Que Ayer` produced no fully verified bounded candidate. Provisional and
different-duration phases (including 15→236 and 185→239 seconds) discarded
the old fragment with zero carry, and the unresolved `La Forma En Que Me Miras`
controller recreation claimed no progress.

The required clean unique sample then occurred naturally. `Ella Y Yo (feat.
Farruko, Ozuna, Arcangel, Anuel AA, Bryant Myers, Kevin Roldan, Ñengo Flow,...)`
/ `Pepe Quintana - Topic` discarded its 12- and 30-second provisional phases,
settled at 416 seconds and uniquely resolved to `CGjuWHEPxgc`. It established
immutable authority, played 416/416 seconds, re-fetched the same page and built
one canonical linked payload. Tx
`49a46d159658d257706c9a3c6b32eed4ddd29ca1` was independently returned with the
exact operation in block 108,734,261 by `api.hive.blog` and
`api.deathwing.me`; the public profile also indexed `CGjuWHEPxgc`. This closes
the code-40 unique-write row without changing the playlist or backfilling any
earlier miss.

Immediately before installation, YouTube naturally closed and recreated the
v0.9.3 `Unica` controller. Each side independently resolved `7uxTya2PX3c`; the
replacement claimed 87 seconds, the aggregate finalized once at 215/218 seconds,
and tx `70cc45e0c27cff4a0d758c2cf414b4de6a521b38` was independently present in
block 108,733,719 on two Hive nodes and on the public profile. This closes the
same-id recreation row without forced controller churn.

## 29. v0.9.5 native playlist-derived identity gate

The full field record is `<redacted-private-path>`. The automated gate
covers the parser across both watch-screen layouts (collapsed bar and expanded
queue panel), the miniplayer, ad absence, malformed positions, queue-row
confusion and invisible bars; the latch across replacement, both flavours of
absence and reset; and the name→id resolver across unique, zero, several,
contradicting-owner and contradicting-total results.

Field acceptance on the <redacted-device-model>, playlist entered through "Play all":

- every track in the `<redacted-private-provenance>` failure table resolved, each to
  the id the playlist page predicted;
- a 90-second miniplayer period produced **zero** latch drops, after the fix
  that made absence stop dropping the latch;
- `Báilame (Remix)` carried **125 seconds** across a MediaSession teardown keyed
  on the playlist-supplied id — the lock-screen/minimize "song splits in two"
  symptom, fixed as a side effect of having an immutable id.

## 30. v0.9.6 uniqueness, cache freshness and watch-history identity gate

Build record: `<redacted-private-path>`. **496 tests, 0 failures**; lint 0
errors. New automated coverage:

| Area | What it must prove |
| --- | --- |
| `PlaylistPageParser` | two same-title, same-duration entries in one playlist refuse every id; a same-title entry of a *different* length does not make the real one ambiguous |
| `PlaylistRefreshThrottle` | a freshly fetched playlist is never re-read on the next track; a stale one is re-read at most once per interval; a playlist RustedWax is not playing costs a bounded number of page reads; each playlist has its own budget; reset restores it |
| `WatchHistoryParser` | newest-first ordering preserved across day sections; signed-out vs paused vs empty vs markup-changed are four distinct answers; the `lockupViewModel` shape and `richItemRenderer` wrapping are both read; duration falls back to the overlay badge |
| `WatchHistoryMatcher` | the measured duplicate-upload pairs resolve to the played upload; the matched position is reported; two matching recent entries refuse; the window bound holds; revalidation refuses a moved id |
| `WatchHistoryHealth` | one miss is not a diagnosis and three are; a hit clears it; a refused route re-probes once per interval; dead session and paused history refuse on sight; network failures say nothing about the account |
| `YouTubeSessionVault` | a jar with no session cookie is not a sign-in; `=` inside a cookie value survives parsing |

### Field record, <redacted-device-model>, 2026-08-04 19:03–19:06

Native YouTube, no playlist — the case v0.9.5 could not answer:

```
19:04:01.085  [finalize]     [track change] … La Formula — played 0s of 236s
19:04:03.771  [history]      read 153 watch-history entries
19:04:03.788  [history]      resolved "… La Formula" → gmc4tkVJow8 (entry 0 of 153, 0 = newest)
19:05:56.442  [resolve]      re-fetching pre-resolved native carry authority gmc4tkVJow8 (HISTORY)
19:05:59.197  [history]      resolved … → gmc4tkVJow8 (entry 0 of 153, 0 = newest)
19:05:59.230  [native-id]    verified gmc4tkVJow8 via corroborated resolver (watch history)
```

Both open measurements are answered: a play appears in history **within ~2.7 s**
of the track change, and the newest entry **was** the current track on all three
lookups. The `(entry N of M)` instrumentation stays in so a future `N > 0` is
visible rather than silent.

The same run exposed and produced fixes for two defects:

1. A lookup **60 ms** after a track change hit a feed cached **0.5 s earlier**
   to finalize the previous track, missed, and fell through to the search route
   — the route measured three times choosing a duration-identical wrong upload.
   Absence against a cached feed now forces one fresh read before anything is
   concluded.
2. That same artefactual miss counted toward the "your YouTube app is on another
   account" diagnosis, so pure timing could have stood the route down after
   three tracks. Only absence from a freshly-read feed counts now.

A third defect was found while verifying grants rather than while testing:
`NativeShortsAccessibilityService.isEnabled` read only
`enabled_accessibility_services`, so after every reinstall the UI reported
"Granted" for a service Android had stopped feeding. It now also requires
`accessibility_enabled == 1`. `UrlWatcherService.isEnabled` has the identical
defect and was left untouched under the v0.9.6 no-browser-change constraint.

### Checking the grants after an install

```bash
adb shell settings get secure accessibility_enabled            # must be 1
adb shell settings get secure enabled_accessibility_services   # must name both
```

`dumpsys accessibility` lists *installed*, not enabled, services and will
mislead. Take the reading a few seconds after the install settles: measured
twice this session, the flag reads `0` immediately after `adb install` and then
returns to `1` on its own for services the previous APK already had. The
strongest evidence is the service's own `connected` line in the event log, which
only a live service emits — but the check is still required, because last
session's grants stayed off until re-enabled by hand.

### Testing the watch-history route

1. Connect an account: **Settings → YouTube watch history → Sign in**. The
   disclosure appears before Google's page loads. Sign in yourself.
2. Confirm the YouTube app on the phone is signed in to the **same** account and
   is not in incognito, or nothing played reaches the readable history.
3. Play a native video **outside any playlist** and watch:

```bash
adb shell run-as com.rustedwax.app cat files/rustedwax-log.txt | grep history
```

A hit reads `resolved "…" → <id> from watch history (entry N of M, 0 = newest)`.
An empty feed prints a shape report — counts of renderer *names* only, never
content — which distinguishes an account with nothing recent from markup this
parser does not read.

4. Verify no credential ever reaches the log:

```bash
adb shell run-as com.rustedwax.app cat files/rustedwax-log.txt | grep -cE "SID=|SAPISID|LOGIN_INFO|__Secure"
```

Must print `0`. It did on the 2026-08-04 run.

## Running the unit tests

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
```

The corrected v0.8.11 source gate ran the full `testDebugUnitTest assembleDebug lintDebug
--rerun-tasks` sequence: 295 tests, 0 skipped, 0 failures, 0 errors; debug APK
assembly and lint both completed successfully. The physical-device gate is
separate evidence: the first artifact failed as recorded in §14, and the
corrected artifact failed the second field round as recorded in §16.

The v0.8.12 source gate then ran that same uncached sequence: 314 tests, 0
skipped, 0 failures and 0 errors; debug assembly and lint succeeded. Log 18
then failed its physical-device gate exactly as recorded in §18.

The v0.8.13 source gate ran the exact full sequence from §17c: 320 tests, 0
skipped, 0 failures and 0 errors; debug assembly succeeded and lint completed
with 0 errors and 23 warnings. The review artifact is
`dist/rustedwax-0.8.13.apk`, version code 33, SHA-256
`ddfeb3e51cfe60fcf8fa2f13c05891989215154da948686650ae720e4ca9e026`.
Its physical-device gate later failed in log 19 as recorded in §20.

The v0.8.14 source gate ran the same exact uncached sequence: 338 tests, 0
skipped, 0 failures and 0 errors; debug assembly succeeded and lint completed
with 0 errors and 23 warnings. The review artifact is
`dist/rustedwax-0.8.14.apk`, version code 34, SHA-256
`a9507c733b188f9cf3a481c8b1446535da22449343181cc17ccf556890f298b8`.
Log 20 then failed its physical-device gate as recorded in §21. The v0.8.15
focused source gate passes 176 tests with no skips, failures or errors. Its
complete uncached gate passes 349 tests with no skips, failures or errors plus
debug assembly and lint with 0 errors/23 warnings. The tested APK and hash are
recorded in §21f; its physical-device gate remained pending at artifact time.
Log 21 then supplied the accepted Phase 4 field record in §22. It did not
literally complete the historical every-fixture matrix, and its documented
conservative omissions remain release exceptions.

The v0.9.0 source gate ran the same exact uncached sequence: 372 tests, 0
skipped, 0 failures and 0 errors; debug assembly succeeded and lint completed
with 0 errors and 23 warnings. The byte-identical source and copied APK hash is
recorded in §23a. The partial native Shorts gate then failed as recorded in
§24; the broader native-app evidence remains pending under §23c.

The suite includes kind classification, title parsing, threshold/floor/loop
rules, continuation expiry tokens, frozen carried identities/ad evidence,
watch-page provenance, independent-node status aggregation, dhive-compatible
crypto/serialization vectors, payload construction, id resolution, rejected
live-id corroboration, exact YouTube ad labels, notification binding, YouTube
Music parsing, playback-speed scaling, dedup, and MusicBrainz matching.
Use the Gradle result as the authoritative count; this list describes coverage
rather than freezing a number that drifts whenever a regression is added.

## Known gaps after the accepted v0.8.15 log-21 field round

- **The strict historical matrix never reached a zero-defect pass.** Log 16 confirmed three
  qualifying omissions caused by inconsistent active/final title
  corroboration plus malformed LA PLENA credits. The generic correction in §15
  fixed those exact field cases. Log 17 then reconciled 67/67 emitted payloads
  but confirmed eight other qualifying organic omissions, seven malformed
  metadata payloads and one likely movie-Short classification error. The
  generic correction is implemented and source-tested in §17. Log 18 then
  passed the full transport/profile reconciliation but found the two permanent
  MONTERO/Te Bote payload defects, three uniquely recoverable resolver misses
  and incomplete fixture coverage in §18. The v0.8.13 correction passed its
  source gate in §19; log 19 then reconciled 53/53 RustedWax payloads but
  admitted two ordinary watch-page advertisements, exposed four parser
  defects/limitations and again omitted the required fixture matrix. The
  v0.8.14 correction in §20 is implemented and passed its full source gate.
  Log 20 then reconciled all 108 scoped operations but admitted a second
  Namecheap ad during a silent accessibility-observation outage, refused one
  qualifying BOOGA song, reversed two conventional featured titles and again
  omitted the required fixture matrix. The implemented v0.8.15 correction in
  §21 then produced the accepted log-21 field result in §22: no new ad payloads,
  exact 98/98 chain/profile reconciliation, four resolver omissions and one
  transition-poisoned organic veto. The remaining limitations are accepted for
  the Phase 4 final product, not erased from the record.
- **Desktop and phone together will double-scrobble.** Dedup is per-device; the
  cross-device check is not implemented. If you run the extension and the app on
  the same account at the same time, expect two scrobbles per listen.
- **No biometric gate** on the stored key — an unlocked phone can sign.
- **Video-id recovery can still miss, but cannot create an unlinked entry.** The
  address bar is exact when it reports, but can expose only a bare host for long
  stretches. Playlist and search/watch-page recovery require a unique title,
  channel, and duration match. If YouTube omits those signals, changes its
  undocumented markup, or returns ambiguous duplicate uploads, the viewing is
  shown in **Not logged** and remains off-chain. Recovery requests need
  **YouTube scrobbling** on.
- **No privacy mode.** All payloads are plaintext on-chain. Phase 5.
- **A broadcast takes roughly 15 seconds longer** when block inclusion is not
  seen immediately, because every independent healthy node is polled for up to
  five block intervals. Block, mempool, and unavailable evidence are displayed
  separately; that delay is the intended trade.
- **A normal track can appear roughly one minute late.** When Chrome destroys
  the old controller and creates a different one, the old item waits through
  the continuation window before finalizing; different metadata might be a
  mid-roll ad before the original returns, so it is not sufficient evidence to
  close the old item immediately. Hive confirmation time follows that wait.
- **Visible ad detection depends on browser accessibility output.** v0.8.9
  rejects a public promoted Short when YouTube exposes an exact visible ad
  control and Browser evidence access is on. If no such label is exposed, the
  public video is identical to organic playback in the remaining evidence and
  can still scrobble. Log 19 proved that v0.8.13 never inspected an exact label
  unless the same snapshot had a concrete Short id; Namecheap and KaoJapan
  therefore reached Hive as normal public videos. The implemented v0.8.14
  track-instance path closes the unscanned watch-page case whenever an
  accessibility observation actually arrives. Log 20 proved the service can
  remain nominally connected while URL/ad observations go silent for about 36
  minutes; a public 30-second Namecheap ad then passed the ordinary resolver.
  v0.8.15 recorded successful current-track scan coverage separately and refused
  resolver-only broadcasts during an evidence outage. That historical eligibility rule was removed
  in v0.11.0g: a unique finalized lookup remains eligible regardless of coverage, while literal
  positive ad evidence still vetoes. Log 21 correctly vetoed
  two naturally served Namecheap ads and produced no new ad payload, while also
  exposing one conservative ad-evidence carry into the following organic track.
  It still does not
  fill a genuinely label-free, successfully scanned page with channel/title
  heuristics; §9a's mute remains the fallback, and **Disable Shorts** is the kill
  switch if you'd rather have neither.
- **Playback speed is trusted as reported.** Played time is scaled by
  `PlaybackState.playbackSpeed`, clamped to 4× and falling back to 1× for absent,
  zero or non-finite values. A browser that misreports the rate would mis-measure
  progress in proportion; nothing observed does.
- **Short-clip scrobbling needs its evidence.** With *YouTube scrobbling* off, or
  with neither *Browser evidence access* nor the Native Shorts grant live, there is
  no proof a clip is a real short, so the ordinary 30-second floor applies and the
  setting does nothing. Stated in the UI rather than left as an unexplained no-op.
- **~1 in 8 legitimate short clips is still dropped.** Enrichment failed on ~12%
  of ids in the 2026-07-29 session, and it is required to stay non-blocking
  (D8), so a clip whose fetch times out is held to 30 seconds. Deliberate
  direction: a missed entry can be earned again, a false one is permanent. The
  reason shows in **Not logged**.
- **Untagged music can be missed.** Since v0.5.1 the last-resort default is
  `video`, so a fan upload of a real song with a bare title, no category and
  no MusicBrainz entry is filed as a video. Deliberate: a missed song costs one
  playlist entry, a false song is permanent curation debt.
- **The site canonicalizes by first writer.** scrobble.life keeps one record
  per video id, seeded by the *first* scrobble's kind — a pre-fix `song` entry
  keeps that video listed as music for everyone, whatever later ops say. Not
  fixable app-side; raised with the site's developer.

### Implemented in v0.8.11 (source gate passed; both field gates failed)

- **Finalization could mix adjacent identities.** Finalized snapshots now carry
  their own resolver context, and structured candidate/facts evidence is
  corroborated against the ended title, channel, and duration. The exact
  `saGYMhApaH8`/`3mchJ-EW9rM` and `aZaxQG3ggng`/`2QqyPy2itXw` handoffs refuse
  successor facts.
- **A transition-frame ad label could poison an organic successor.** URL
  generations plus provisional/re-observed state pin the stadium-ad to
  `IW524Zl2Pus` ordering while retaining stable explicit-ad vetoes.
- **Exact duration equality split continuous playback.** Same normalized
  title/artist/album with missing-to-known duration or ≤2,000 ms drift preserves
  progress and evidence; the `227125 → 227124` Cardi B fixture reaches one
  eligible decision.
- **Song credits split inside syntax or remained reversed.** Top-level scanning,
  explicit quoted/performance shapes, and conservative channel agreement pin
  `vG4h2KkwMDA`, `VpXRPrwezQ8`, `z5WrgDzNIZ0`, and `oNg3M9IJJlY`.

### Fixed in v0.8.9

- **Public promoted Shorts ignored YouTube's visible ad state.** Exact
  accessibility-visible YouTube ad labels are now bound to the current
  `/shorts/` video id and veto both automatic and manual broadcasts. Brand,
  channel, and title guesses are explicitly excluded.
- **A rejected later URL could become an ended track's identity.** The
  corroboration fallback no longer resurrects a rejected live id. Identity and
  explicit ad evidence are frozen with progress through Chrome's one-minute
  continuation delay and consumed together by a replacement.

  This remains true for that probe-level fallback. Log 14 later proved that an
  unresolved ended snapshot could still acquire successor facts/id downstream;
  v0.8.11 closes that separate finalize-to-broadcast boundary above.

### Fixed in v0.8.8

- **A 42-second unlisted Shorts ad cleared the ordinary floor.** Explicit
  `isUnlisted == true` now vetoes a `/shorts/` item before duration and progress
  are evaluated.
- **`MUSIC_VIDEO_TYPE_PODCAST_EPISODE` counted as catalogue music.** It is now
  explicitly excluded from song evidence.
- **A watch-path timer loop produced two transactions.** A strict end-to-start
  playback-position reset is detected inside one media session or across Chrome
  recreation, carried to the final snapshot, and caps every kind to one.

### Fixed in v0.8.7 (contract reconciliation)

- **Looping verified Shorts were erased above 200%.** The rule now keeps the
  earned first viewing, logs a probable loop above 125%, and relies on the
  existing video-kind cap for exactly one transaction.
- **Session fragments were finalized before being carried.** Disappearance now
  opens a one-minute continuation window. A replacement consumes progress; only
  a real track end or continuation expiry finalizes.
- **`Confirmed on-chain` covered three different states.** Independent block,
  independent mempool, and accepted-without-confirmation now remain distinct,
  and the accepting node never confirms itself.
- **Stop left URL evidence alive and read removed-notification titles.** Both
  paths now obey the hard Stop boundary.
- **Queued entries could be signed with another account's key and lost
  silently.** Queue ownership, percent/video metadata, storage errors, and
  terminal retry exhaustion are now explicit.
- **UI and manual broadcast used different rules.** The Now threshold uses the
  configured value and manual session broadcasting runs the same eligibility
  decision as automatic finalization.
- **YouTube Music fallback length impersonated watch-page proof.** Provenance is
  now stored explicitly and legacy cache entries without it are refreshed.

### Partially fixed in v0.8.5–0.8.6 (the session-churn incident)

- **A video watched to 80% produced no entry.** Chrome recreates its media
  session mid-video around ad breaks and playlist transitions, which reset the
  play-time counter, so a 196-second video arrived as three fragments of 24%, 12%
  and 43% and every one was skipped. v0.8.5 carried play time across the restart,
  but still finalized each fragment first; v0.8.7 completes the fix by deferring
  that finalization.
- **`NxNN` episode numbering wasn't recognised** (0.8.6). `Season 6 Ep 19` and
  `S06E19` matched, but `3x1` and `11x24` didn't — the notation TV clip channels
  actually use — so two Walking Dead clips went on-chain as `kind: song`.

### Fixed in v0.8.4 (the frozen-node incident)

- **The app reported seven scrobbles it never made.** A node froze 77 minutes
  behind the chain and kept answering RPCs; "no error" was being read as success,
  and the tx id shown in History was computed locally rather than confirmed.
  v0.8.4 added transaction lookup. v0.8.7 separates block, independent mempool,
  and unanswered confirmation instead of displaying all three as confirmed.
- **No node freshness check.** Enforced at broadcast *and* when reading the chain
  head, since that's what the transaction is built from — a stale node's clock
  yields an expiration that's already in the past.
- **A per-block rate limit was treated as permanent** and the listens discarded.
  Transient refusals now queue and retry.
- **Chain errors logged uninterpolated templates** (`${a}`, `${n}`) instead of the
  actual values, which is what hid the stall for a whole session.
- **Dead node removed** (`hive-api.arcange.eu`) and the frozen one moved off the
  front of the failover list.

### Fixed in v0.8.3 (from the 2026-07-29 late run)

- **A promoted public video reached the chain as an entry.** It was
  indistinguishable in the evidence v0.8.3 consumed, so History gained **Never
  scrobble this again**, keyed by video id and bound on the manual path too.
  v0.8.9 adds the explicit visible-label path described in §6b.
- **The address bar went quiet for 13 minutes and nothing said so.** Cost five
  urls and four entries outright. Now warned after three consecutive tracks with
  no video id.

### Fixed in v0.8.2 (from reading the desktop extension)

- **A stale video id put a wrong `url` on-chain.** A Danger Man track latched the
  previous playlist entry's id because the address bar was 7 seconds late, and the
  title-based corroboration failed open — the page fetch for that id had timed
  out, so there was no title to compare. Now cross-checked against duration too
  (226 s against 193 s, which was sitting right there).
- **Enrichment failing no longer means no evidence at all.** The YouTube Music
  lookup is 10 KB against ~615 KB for the watch page and carries the length,
  category and listed flag, so it stands in when the page is unavailable.
- **`album` was never populated**, despite being a payload field since Phase 0.
- **A long video with a dash in its title could scrobble as a song.** Upper
  duration gates at 8 and 15 minutes, both adopted from the extension.
- **Songs MusicBrainz has never heard of are now recognised** via the YouTube
  Music catalogue, which matches on video id rather than on a parsed string.

### Fixed in v0.8.1 (from the 2026-07-29 Chrome run)

- **A shorts-feed ad reached the chain.** v0.8.0 gated the 10-second floor on the
  video resolving on its watch page, assuming ad creatives have none. They do.
  The gate is now `isUnlisted`, and an absent field fails it rather than passing.
- **Playback speed was never counted.** Played time was wall-clock seconds
  compared against the video's duration, so a trailer watched to 79% at 1.25×
  went on-chain as 67% — and at 2× a fully-watched video read 50% and produced no
  entry at all.
- **`FactsCache` didn't carry the new flags**, so a cache hit would have
  inherited a silent "public". Both are nullable and round-trip.

### Fixed in v0.8.0 (from the 2026-07-29 46-video run)

- **A third of the shorts feed produced no entry.** All 24 of the session's
  30-second-floor rejections were `/shorts/` URLs and none were `/watch` — the
  floor was doing nothing on the path it was written for. Verified shorts now
  count from 10 s.
- **10 shorts were skipped as "no duration"** while `videoDetails.lengthSeconds`
  for those same ids was already being fetched and discarded. Now the fallback.
- **Skips were invisible.** Every reason was computed and sent only to the event
  log, which made a strict rule and a broken app look identical. New **Not
  logged** tab.
- **Hashtag runs went on-chain as part of the title** (`katter — #guitar
  #dubstep #djdubstep #fnaf`), which also meant the same clip reposted with
  different tags dedups as a different listen. Trailing runs are stripped;
  a title that is *only* tags can no longer be a `song`.
- **The kind was computed and then never consulted when building credits**, so a
  trailer went out as `artist: "Fall 2: Deadpoint (2026) Official Trailer 2"` /
  `title: "Harriet Slater, Arsema Thomas"` — a film name and a cast list.
  `Artist - Track` splitting is now song-only.
- **`FactsCache` silently dropped `autoGenerated`**, so a cache hit downgraded a
  distributor-fed Art Track to whatever the title heuristics made of it. The
  session took 22 cache hits.

### Fixed since v0.3.x

- Stale/cross-tab site hints (per-session binding + taint, v0.4.0); trailers
  and film clips scrobbled as `song` (evidence-layered classifier, v0.4.1);
  the finalize-time address-bar race, which both lost video ids and attached
  the *wrong* id to a track (latching at track start, v0.4.2); looping shorts
  double-scrobbling (song-only double-listen, v0.5.0); tutorials and music-news
  bulletins that YouTube itself categorizes as *Music* (format evidence now
  outranks the category, v0.5.1); MusicBrainz lookups timing out in the
  rate-limit queue before their request started, which is why early testing saw
  "— (not checked yet)" everywhere (v0.5.1); TV episode compilations reading as
  `Artist - Track` offline (v0.5.2); **`url` silently missing on ~15% of
  scrobbles** because nothing re-ran identity when the address bar updated, so
  the video id was only ever picked up if the app happened to be open
  (v0.5.3) — and a notification hint with an unparseable host vetoing a
  perfectly good video id (v0.5.3); **the manual Broadcast button bypassing the
  dedup ledger**, which duplicated listens both against the later automatic
  finalize and against a second tap of its own (v0.5.4).
- **Brave on a physical device is verified** — a full day of tablet use
  (2026-07-23/24) confirmed the notification sub-text behaves as Chrome's does.

## 31. v0.9.7–v0.9.9 field rounds, and what to watch for in the log

Full evidence: [<redacted-private-path>](../Field-Reports/<redacted-private-path>) §8–§15. **574 tests,
0 failures**; lint 0 errors. New automated coverage:

| Area | What it must prove |
| --- | --- |
| `VideoIdentityCorroborator` | either of a page's two published titles may corroborate; an all-hashtag title (empty key) no longer matches vacuously; a candidate whose *both* names disagree is still refused, and the refusal names both |
| `SearchResultsParser` | `U+200B` between hashtags is not identity evidence — screen titles carry them, watch pages do not |
| `WatchHistoryHealth` | one track missing repeatedly is one data point; three *different* tracks still arm the refusal |
| `AccessibilityEventSilence` | a measuring observer with zero events is alive; an idle YouTube with nothing playing is never an outage; something playing that nothing counts names itself and its duration; the report is rate-limited and its recovery states the total |
| `VisibleActivities` | a departing activity's `STOPPED` after the arriving one's `RESUMED` does not hide the app; stopping the last one does; tracking is bounded |

### What a healthy log looks like now

```
foreground Short proof acquired: "…" / @handle / 1s of 60s
foreground Short seekbar advanced to 37s of 60s; credited 5s (measured total 35s)
foreground Short completed a full listen of 60s while still on screen; banked it
[history] resolved Short "…" → <id> from watch history, corroborated on its own watch page
[native-id] verified <id> via corroborated resolver (run-local verified candidate)
[engine] scrobbled (block): @handle — … — tx <64 hex>
```

### Lines that are new in this range, and what each means

| Line | Meaning |
| --- | --- |
| `nothing observable for Ns with the screen on (… ; YouTube is playing audio with a visible window)` | **The one to report.** Something is playing that nothing is counting — a real loss, in progress |
| `accessibility observation recovered after Ns` | that outage ended, and this is how long it lasted |
| `not offering Shorts candidates: …` | the watch-history route is standing itself down, and why |
| `first seen Ns in — anything played before that was never published to RustedWax` | the player was already part-way through when the app first saw it; that lead-in was never measurable and is not credited |
| `Ns measured from the seekbar + Ns inferred in picture-in-picture` | a PiP listen, stating the split |
| `candidate title "X" (displayed "Y") contradicts ended title "Z"` | identity refused after comparing **both** of the page's names |

### Lines that are gone, and why

`accessibility event stream silent for Ns …` no longer exists. It counted
accessibility callbacks, which legitimately stop while a latched Short is being
measured perfectly by the service's own poll — it reported 111 outages in a day
that scrobbled 46 tracks. See FIELD §15.

### Useful greps

```bash
adb shell run-as com.rustedwax.app cat /data/data/com.rustedwax.app/files/rustedwax-log.txt > log.txt
grep -c 'scrobbled (block)' log.txt              # what landed on chain
grep 'skipped:' log.txt | sed 's/.*skipped: //' | sort | uniq -c | sort -rn   # why the rest did not
grep 'nothing observable' log.txt                # real losses, in progress
grep 'could not be verified' log.txt             # identity refusals, with their exact reason
```

**A note on searching by video id:** an id is only written *after* identity
resolves, so a listen that never resolved never records one. An id search cannot
tell "never identified" from "never observed" — a partial **title** can, because
titles and owner handles are logged before identity runs. That is how all six
untraceable ids of the 2026-08-06 acceptance day were finally accounted for
(FIELD §14.8).

### Reproducing the seekbar-less Short

YouTube renders a Shorts player **without its progress bar** when the player is *resumed* by tab
navigation rather than opened fresh. This is the state that cost 47 of 71 Shorts on 2026-08-06
(FIELD §16), and it reproduces first time:

1. Open the YouTube app.
2. Tap a Short **on the home feed** — it plays *with* a seekbar.
3. Tap **Shorts** in the bottom menu → **the bar is gone**.
4. Tap **Home** → the Short from step 2 resumes, still no bar.
5. Tap **Shorts** → the Short from step 3 resumes, still no bar.

A swipe to the next Short restores it, which is why the state looks intermittent in a day's log.
Sending a Short to picture-in-picture and expanding it again reaches the same state, for the same
reason.

Confirm the state rather than assuming it:

```bash
adb shell uiautomator dump /sdcard/s.xml && adb pull /sdcard/s.xml
grep -c 'android.widget.SeekBar' s.xml     # 0 in this state, 1 normally
grep -c 'reel_watch_player' s.xml          # 1 either way — the player is there
```

What the log must then show, and what it means:

| line | meaning |
| --- | --- |
| `foreground Short proof acquired without a seekbar: "…" / @handle` | the Short started anyway — this is the fix working |
| `foreground Short has no seekbar; credited 1001ms of inferred wall-clock` | one second per second, on audio + visible-window evidence |
| `played Ns of an unknown length (0s measured from the seekbar + Ns inferred …)` | the finalize; the length is not known until identity resolves |
| `duration recovered from the watch page: Ns` | and then it is |

Leave it untouched for a minute, then swipe once to finalize. Both the inferred Short and the
swipe-restored measured one should reach the chain — verified 2026-08-07,
`tx 80c427db974908fb096607e381de57aca7136282` and `tx 48f78ee76de4bf99828b88aecb6350e3535713fe`.

### Continuity across interruptions (v0.9.11–v0.9.12)

Two viewings that used to be cut into unscoreable fragments. Both reproduce from the Mac.

**A Short across a tab switch.** Play one past 60% of a length over 20 seconds, tap **Home**, wait
~10s, tap **Shorts**, let it finish.

```
foreground Short proof acquired: "…" / @handle / Ns of Ms      ← before
[finalize] … — played Ns of Ms                                  ← the interruption
foreground Short proof acquired: "…" / @handle / Ns of Ms      ← after
[finalize] … — played (more than it earned since re-acquiring)  ← the resume working
```

**A trailer across a tab switch.** Play any watch-page video, tap **Shorts**, tap **Home**, let it
finish. What must **not** appear:

```
[track]    track change after 0s played
[finalize] <untitled> — played 0s of 0s
```

That phantom is the placeholder bug; `grep -c '<untitled> — played 0s of 0s'` should stay at zero
while a video is interleaved.

### A video handed to the Shorts route (v0.9.13)

The reported shape, and the one that deleted ten listens in a day. Play any watch-page video past the
threshold, minimize it with the down arrow so the bottom bar shows, then tap **Shorts**.

```
[finalize] … [foreground Short proof took over playback] <the video> — played Ns of Ms
[native-shorts] MediaSession hidden while complete foreground Shorts proof is active
[native-shorts] foreground Short proof acquired: …
```

The finalize must come **first** and must carry the seconds actually watched. Verified 2026-08-07,
`tx 99dae0633362c43d017a001e3027dbffd9125541` — 142s of 146s.

The complement matters as much: scrolling Shorts normally must produce **no**
`[foreground Short proof took over playback]` line at all, because there the MediaSession is
describing the same Short the route is scoring.

### A handle that is not spelled in ASCII (v0.9.13)

Open any channel whose handle carries an accent or a non-Latin script — `@eduardaarebouçass` is the
measured one — play one of its Shorts past the threshold and swipe.

```
foreground Short proof acquired: "…" / @eduardaarebouçass / Ns of Ms
resolved Short "…" → <id> from watch history, corroborated on its own watch page
scrobbled (block): @eduardaarebouçass — …
```

Before v0.9.13 the first line never appeared: the refusal read `expected exactly one exact visible
owner handle` and the listen ended there. Verified `tx 7eb3a47e4452bc74e6a4c777a882c966c52189ae`.

The refusal itself is now evidence, and worth reading when a Short is not picked up:

| what it says | what it means |
| --- | --- |
| `found none; no visible label in the player carried an @ at all (N labelled nodes)` | the footer is not being drawn — picture-in-picture, or the window is gone |
| `found none; the labels that did carry one were …` | the footer **is** there and the parser would not take it — quote the labels, they are the fix |
| `found 2: @a, @b` | two footers on screen at once, or a handle mentioned in the title |

### One banked listen, finalized once (v0.9.13)

Let a Short reach its own full length while staying on it, then swipe.

```
foreground Short completed a full listen of Ns while still on screen; banked it …
[finalize] … — played Ns of Ns
```

`grep -c` that title in `[finalize]` lines must return **1**, not 2. The second one used to be
stopped only by `skipped: already scrobbled`, after a full resolve and enrich.

### A Short played faster than real time (v0.9.14)

Hold a Short at 2× (press and hold on the video) past 60% of a length over 20 seconds, then swipe.

```
foreground Short seekbar advanced to Ns of Ms; credited Ks (measured total …)
```

The check is arithmetic: **`credited` must be able to exceed the wall-clock seconds between two
polls.** Before v0.9.14 it never could, so a 121-second Short at 2× finalized at `played 6s of 121s`
— 58 seconds of content traversed between two polls, 2 credited — while the same Short at 1×
scrobbled at 98%.

Nothing beyond twice wall clock is admitted, so a forward seek still earns zero. The unit tests pin
both ends: `a Short played at double speed earns the content it traversed`, and
`nothing faster than the platform's own maximum is admitted`.

**The refusal is now visible, and that half you can drive from the Mac.** Scrub a Short's seek bar
forward and the log must say so:

```
foreground Short seekbar jumped 88s in 0.8s of wall clock — faster than 2.0× playback
can account for, so it is a seek and earns nothing
```

Verified 2026-08-08, and the same viewing still scrobbled once it had earned it —
`tx 533cf8df1446151e693925091519bd29c1737202`. Before v0.9.14 this refusal printed nothing at all,
which is the only reason 2× playback presented as "the scrobbler stopped working".

**The 2× half can be driven from the Mac after all.** On the earlier 720-wide Galaxy A12 capture,
the working gesture was a hold near the *top right*, which
`adb shell input motionevent DOWN 620 350` reproduced. The later Galaxy A36 build placed the
working hold around the *top left*; host scripts must confirm the visible `2x` chip instead of
assuming one device's coordinates apply to another:

```
foreground Short seekbar jumped …                      ← locked 2x, measured path
credited 2002ms of inferred wall-clock at 2.0× off YouTube's own speed chip
[finalize] … — played 169s of an unknown length (0s measured + 169s inferred …)
scrobbled (block): … — tx c81e2af54febaa8dc784eabb14d0d05dba1a00a0
```

Holding strips the **entire** overlay — title, handle, action column and seek bar — leaving `2x` and
`Pull down to lock 2x speed`. So a hold must produce a `speed chip` credit line, not a run of handle
refusals ending in a finalize at 4%. Verified on `tlESWX9_tyo`, the same Short that refused at 5%
before the fix.

**The hold has two shapes, and they need different things.** It always hides the footer; it
*sometimes* leaves the seekbar readable. Check which one you are in from the log:

| line | shape | what should follow |
| --- | --- | --- |
| `… the seekbar still read Ns of Ms` — never appears once measurable | footer only | `seekbar advanced … with the footer off screen; credited Ns` |
| `credited …ms of inferred wall-clock at 2.0× off YouTube's own speed chip` | footer **and** bar | inferred credit at the chip's rate |

Both must keep the Short alive. A run of handle refusals ending in a finalize at 4% is the v0.9.15
failure — verified fixed 2026-08-09, `tx 356a1bc7d857ed3702fd7abc044c9a6cca09d2c4`,
`played 134s of 121s` on a few seconds at 1× then held at 2× then released.

**Mixed speeds are the case worth testing, because it is what people do.** Watch part at 1×, then
hold. The finalize must show both halves in one total:

```
[finalize] … — played 161s of 154s (65s measured from the seekbar + 96s inferred from wall-clock …)
scrobbled — tx cf91cedf7bb5ae72c1aebce970c2722be8c17968
```

A listen clears the threshold when `measured% + (rate × hold seconds ÷ length) ≥ 60%`.

**Install, then wait for the observer.** Reinstalling blinds the accessibility service for over a
minute (`accessibility event stream silent for 45s … the service cannot see`, recovered after 124s).
Two runs in the measuring session were made against a blind observer and read as failures of the fix.
Wait for a `seekbar advanced` line before concluding anything.

**The older note, kept because it is still true of the other methods:** Five methods were measured and none reach it:
`input touchscreen swipe` with equal endpoints, a raw `motionevent` hold, a hold with micro-movement,
and a `sendevent` multi-touch stream at the centre and right edge — the first three open the Shorts
context menu, which has no speed entry, and the last is ignored. Setting 2× in the watch page's speed
sheet persists there but does not carry into Shorts. It needs a real finger.

### A browser Short search cannot name (v0.9.17)

Play a YouTube Short in Brave or Chrome whose title is a hashtag or an emoji — `#hoyoverse` is the
measured one — past 60%, then swipe.

```
[finalize] com.android.chrome … #hoyoverse — played 138s of 135s
[history]  resolved browser Short "#hoyoverse" → DDOsRtzFAUQ from watch history,
           corroborated on its own watch page
scrobbled — tx 500c886a8de42a5bf14a1456de625367a73eaaa1
```

Before v0.9.17 this refused with `no verified id for "#hoyoverse" / "Mr Time Edits" (135s) among 73
unique search candidates`. History runs **only** when nothing else proved an id, so with the address
bar working you will see the URL route answer instead and never reach this line — that is correct,
and the faster path.

**Two device states to rule out first, both of which look exactly like a broken app:**

| symptom | cause | check |
| --- | --- | --- |
| every browser finalize reads `played Ns of 0s` | the video is **muted** — Chromium gives muted media no usable MediaSession, Chrome none at all | unmute; `DURATION` appears on the next poll |
| `YouTube (site only, no video id)` on every poll | the address-bar watcher is not enabled | `adb shell settings get secure enabled_accessibility_services`, and the app's own `[health]` line says so |

Repeated reinstalls are what drop that grant: Android disables an accessibility service it has to
replace too often.

### Three traps that cost this project hours

- **`uiautomator dump` disconnects the accessibility service.** UiAutomation takes exclusive control,
  and the log records `native Shorts accessibility service disconnected; in-flight foreground Short
  discarded` each time. Dump before or after a run, never during one, or the measurement becomes the
  cause.
- **The log spans days and its timestamps carry no date.** `grep -n '^11:46'` will happily match a
  line from two days ago — which once produced a "79,921 lines in 33 minutes" reading and a theory
  about a log flood that did not exist. Anchor a window on a unique string: a tx id, a title, a
  `[probe] started`.
- **A pulled log can be truncated and still look whole.** `adb exec-out run-as … cat` returned 7.8 MB
  of a 10.5 MB log when the cable moved, ending mid-line at a plausible timestamp — which reads as
  "the log stops here", not "the transfer stopped here". Compare against
  `adb shell run-as com.rustedwax.app stat -c %s files/rustedwax-log.txt` before trusting the tail.

---

## 32. v0.9.20 – v0.10.0 field round: what to exercise and what the log should say

Session record with the on-chain evidence: [<redacted-private-provenance>](../Field-Reports/<redacted-private-provenance>).

### 32.1 The split-listen family (v0.9.20)

Every one of these was a rule answering "is this the same listen?" with a stopwatch. The test for
all of them is the same shape: **play, interrupt for longer than a minute, come back, finish.**

| what to do | expected line |
| --- | --- |
| Play a VEVO song to 100% | no `contradicts ended channel`; a tx |
| Native: play 90s, HOME 100s, return, finish | `waiting up to 15m for this listen to resume near Ns`, then `resumed "…" after a session restart — carrying Ns of play time forward` |
| **Chrome**: same, on a `m.youtube.com` video | identical lines for `com.android.chrome` |
| Let a video run to its own end, then let ads follow | `waiting 60s` — **not** 15m, because a finished listen has nothing to resume |
| Play A, HOME, then play a different video B | A is collected within ~60s, not fifteen minutes |
| Replay the same video from the start | **no** `resumed …` line; the earlier fragment finalizes separately |
| Shorts: watch >30s in, leave >30s, return | `measured total` continues rather than restarting at 0 |

Chrome is the one people skip and it is the one that was broken: the first cut of the fix keyed the
long window on where the *native* resolver stores its id, so browsers silently kept the old
behaviour. Test it separately, every time.

### 32.2 Expansion Phases 1–4 (v0.10.0)

- **The credits ladder.** Play a native YouTube song titled `Artist - Track`. The payload's `title`
  must be the *track alone* — if the artist is still inside the title, the ladder is not running.
  YouTube Music must be unchanged: its `ARTIST` is a real artist and is used as-is.
- **The `video` fallback.** A music-category upload with no `Artist - Track` separator should
  broadcast `kind: video` credited to the channel, not `kind: song` credited to the channel.
- **The `app` field.** Every payload should read `"app":"rustedwax/<version>"`. If it still says
  `hivescrobblesai/1.0`, an old build is installed.
- **Private scrobbles.** Settings → Advanced → Private music, then play a song. The broadcast must
  be `{app, kind, timestamp, private, v:1}` with **no** `title`, `artist`, `url`, `duration` or
  `percent_played` anywhere in it, preceded by `[privacy] encrypted a song scrobble before broadcast`.
- **Disable Shorts.** The only Shorts switch since v0.11.1. With it on, a Short that plays past any threshold
  must be refused with `Shorts are turned off — you asked never to scrobble a Short`.
- **The app list.** On first launch after upgrading, `[apps] migrated the per-app switches into the
  app list: N allowed, M blocked` appears exactly once. Nobody should have to re-enable a source.

### 32.3 The privacy test that actually works

§4.1 took four passes because each fix looked complete until the next test. The method that found
every remaining leak:

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "https://www.w3schools.com/html/mov_bbb.mp4" \
  -n com.android.chrome/com.google.android.apps.chrome.Main
# tap play, wait ~100s so the continuation window expires and it finalizes
adb shell run-as com.rustedwax.app cat files/rustedwax-log.txt | tail -250 > /tmp/rw.txt
awk '/--- session start/{last=NR} END{print last}' /tmp/rw.txt   # anchor on the session, not a time
grep -icE "chrome|w3schools|mov_bbb" <(tail -n +"$LAST" /tmp/rw.txt)   # must be 0
```

**Wait for the finalize.** Three of the four leaks only appeared after the session ended — the
continuation-wait line and the finalize line came a full minute after playback stopped, long after a
shorter test would have declared the log clean.

### 32.4 A fourth trap, added to the three above

- **A green suite proves nothing about the play clock.** Two regressions this session — a claim that
  stopped the accumulator, and a fifteen-minute wait on an abandoned track — passed 600+ tests and
  were obvious in the device log within seconds. The tell was a `[playback]` line whose `pos=` kept
  climbing while `played=` stood still. When measurement changes, read that pair on a real session
  before believing the suite.

---

## 33. v0.11.0 settings round: what to exercise and what the log should say

Nothing about detection, measurement, identity, ad rejection or the payload changed in v0.11.0. The
things worth testing are the settings themselves, the boundaries around the new switch, and the two
lists that gained a link.

### 33.1 Testing fresh-install defaults without losing the key

Fresh-install defaults are only observable from an install that has never stored anything, and
`pm clear` on the field device would take the posting key in `EncryptedSharedPreferences` with it.
Use the `freshtest` build type instead: it is `debug` with `applicationIdSuffix = ".freshtest"`, so
it gets its own preferences file and its own everything.

```bash
./gradlew assembleFreshtest
adb install -r app/build/outputs/apk/freshtest/app-freshtest.apk
adb shell am start -n com.rustedwax.app.freshtest/com.rustedwax.app.MainActivity
adb shell run-as com.rustedwax.app.freshtest cat shared_prefs/rustedwax_settings.xml
adb shell run-as com.rustedwax.app.freshtest ls files/     # rustedwax-log.txt must NOT exist
adb uninstall com.rustedwax.app.freshtest
```

It will hold no key and be granted nothing, which is fine — the defaults are all readable from the
Settings tab, and the missing log file is the strongest evidence of the one that matters.

What a fresh install must show: **YouTube scrobbling on**, **Event log off** (so no **Log**
destination and no **Export** button),
**Disable Shorts on**, and **no `rustedwax-log.txt` on disk at all**. Its preferences file should
contain `settingsSchemaVersion=1`, the two YouTube packages in `allowedPackages`, and nothing else
of substance — the new defaults are defaults, so they are not written.

### 33.2 Testing the upgrade path

The point of the migration is that it changes nothing for someone who already has the app, so it can
only be tested against a *real* older install. Capture the preferences first:

```bash
adb shell run-as com.rustedwax.app cat shared_prefs/rustedwax_settings.xml > before.xml
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.rustedwax.app/.MainActivity
adb shell run-as com.rustedwax.app cat shared_prefs/rustedwax_settings.xml > after.xml
diff before.xml after.xml
```

The diff must show exactly four additions and one removal, and nothing else:

| | |
| --- | --- |
| `settingsSchemaVersion=1` | added — the marker that stops this ever running twice |
| `youtubeScrobbling=true` | added — see the behavior contract for why it is always `true` |
| `eventLogging=true` | added **only if absent before** — pinning the pre-v0.11.0 default |
| `shortClipScrobbling=true` | added **only if absent before** — same |
| `enrichment` | removed — nothing reads it, and a stored `false` would contradict the switch |

Any key the user had actually set — `disableShorts`, a privacy toggle, the threshold — must be
byte-identical on both sides.

### 33.3 The switch, and the two things it has to get right

**Off means nothing is read.** Play a full YouTube video, native or browser, with the switch off. The
correct result is **not a single line** in the log: no session, no package name, no identity verdict,
no periodic count naming anything. `grep -c youtube` on the session must be `0`.

**Off must not cause a broadcast.** This is the one that needs care to stage, because tapping the
switch requires foregrounding RustedWax, which normally pauses playback and ends the session before
the switch is touched. Put YouTube into picture-in-picture first:

```bash
adb shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=<id>" \
  -p com.google.android.youtube
sleep 40
adb shell input keyevent KEYCODE_HOME     # YouTube drops to PiP and keeps playing
adb shell am start -n com.rustedwax.app/.MainActivity
# tap the YouTube scrobbling switch
```

Expected, and nothing else:

```
[native]  com.google.android.youtube source epoch changed — track discarded
[youtube] YouTube scrobbling off — every YouTube surface is ignored, in-flight
          tracks are discarded rather than finalized, and no lookup runs
```

**There must be no `[finalize]` line and no `[engine] broadcasting`.** If either appears, the switch
has become a way to *cause* the thing it was meant to stop.

Repeat the same with Chrome. Browsers carry no epoch, so they take a different route out —
`refreshTargets` sweeps them, and the tell is
`… is no longer a source — deferred play time dropped` when there was carried time to drop. That
sweep is v0.11.0's one device-found defect: before it, a browser listen deferred for continuation
survived the opt-out and became finalizable again if the switch came back on inside its window.

### 33.4 The links on History and Not logged

Tap a row's thumbnail, then its title. Both must open **that exact video** in the YouTube app.
Nothing else in the row may be tappable — not the percentage, not the tx id, not the reason.

Then find a **Not logged** row with no proven id, which is most of them. It must do nothing at all.
A row that opens a YouTube *search* for its title is a defect, not a convenience: for the duplicate
uploads and re-uploads this app spends most of its effort telling apart, a search opens the wrong
video and looks authoritative doing it.

### 33.5 The tab strip and the loading screen

Open the app on a 720-wide screen, where `Not logged` is clipped by the edge. Tap it. It must end up
fully visible with a margin, and the next tab should still peek out so the strip reads as
continuing. Tapping a tab that is *already* fully visible must not move the strip at all — scrolling
on selection exists to fix a clipped tab, not to re-centre on every touch.

For the loading screen, the number to read is the log line, not the animation:

```bash
adb shell am force-stop com.rustedwax.app
adb logcat -c
adb shell "am start -n com.rustedwax.app/.MainActivity; for i in 1 2 3 4 5 6 7 8; do screencap -p /sdcard/f\$i.png; done"
adb logcat -d -s RustedWax:D | grep 'ready in'
```

`ready in <n>ms` is what says whether the screen is doing a job or standing in the way — 636 ms on
the field device. The screencap burst catches the animation itself; in two consecutive frames the
lettering, tonearm, drips and rim must be pixel-identical while the label's gloss has moved.

### 33.6 A trap this round added to the list

- **`adb install -r` drops the app's accessibility services.** They vanish from
  `enabled_accessibility_services`, which is byte-identical to the §2.3 crash-drop this project has
  been bitten by — and the app will correctly report it as a drop, because from its side it *is*
  one. Before diagnosing a dropped grant, check whether you reinstalled. Re-enable both services and
  confirm `[url] address-bar watcher connected` and
  `[native-shorts] foreground Shorts observer connected` before trusting anything a browser test
  says.

---

## 34. v0.11.0a: the Shorts settings row, and how to not misread it

### 34.1 Reproducing the flicker that was fixed

Play a Short, send YouTube to picture-in-picture with **Home**, then open RustedWax on **Settings**.
The `Foreground Shorts evidence` row must sit perfectly still. The check is mechanical rather than
visual, because the eye is bad at this and the PiP window itself changes every frame:

```bash
for i in $(seq 1 10); do adb shell screencap -p /sdcard/k$i.png; sleep 1; done
for i in $(seq 1 10); do adb pull /sdcard/k$i.png /tmp/k$i.png; done
# compare the settings region only — crop above the floating PiP window
python3 - <<'PY'
from PIL import Image; import hashlib
for i in range(1, 11):
    im = Image.open(f"/tmp/k{i}.png").convert("RGB").crop((0, 380, 720, 1000))
    print(i, hashlib.md5(im.tobytes()).hexdigest()[:12])
PY
```

All ten hashes must be identical **while** the log is crediting time on every one of them:

```
[native-shorts] … Short still playing in picture-in-picture — credited 1002ms of inferred wall-clock
```

Identical frames with no credit lines proves nothing; it just means nothing was playing.

### 34.2 The three states the note must distinguish

| what is happening | the note must say |
| --- | --- |
| a readable Shorts player on screen | Reading a Short now |
| playing in PiP, no readable player | Playing in picture-in-picture — counting elapsed time |
| nothing playing | No Shorts player on screen |

The middle one is the one that matters. `completePlayerProof` is `false` for both of the last two,
and reporting them identically is what made a working PiP listen read as a detection failure.

### 34.3 Two traps that cost real time this round

- **`am force-stop` on RustedWax drops its accessibility services.** They vanish from
  `enabled_accessibility_services` with no crash logged, which is byte-identical to the §2.3
  crash-drop. `adb install -r` does **not** do this — that was the wrong suspect for an hour. If a
  Shorts test suddenly stops observing, check the grant before checking the code.
- **"It stopped detecting" and "it stopped saying it detected" look the same from the settings
  screen.** Before believing a detection regression, play one Short fullscreen and grep for
  `proof acquired` and `seekbar advanced`. Those two lines settle it in fifteen seconds, and they
  are what proved the pipeline healthy while the UI was claiming otherwise.

---

## 35. v0.11.0b: the VEVO byline round, and how to reproduce a browser listen with no id

Full session record in `<redacted-private-provenance>`. This section is the reproduction recipe, because
three of the four defects were only reachable in a state that is awkward to arrange on purpose.

### 35.1 Getting the "no video id" state deliberately

The bug only appears when the resolver has to earn the id. If the address bar names the video, it
latches and none of this runs.

1. Open the **playlist page**, not a watch URL:
   `https://m.youtube.com/playlist?list=<id>` in Brave or Chrome.
2. Scroll to the entry and **tap the row**. Mobile YouTube navigates through the History API, so the
   bar frequently never publishes the new video id.
3. Confirm the state before spending four minutes on it:

```
[identity] com.brave.browser → YouTube (site only, no video id) via notification sub-text
[metadata]     TITLE = "…", ARTIST = "…VEVO", DURATION = <ms>
```

Title, artist and a duration, and **no `[url]` line for this track**. That is the field condition.

### 35.2 Ending the track without poisoning the test

**Do not navigate to another video to force the finalize.** The new URL is read about a second
before the track change, so the ending track can be finalized carrying the *next* video's id, and
the corroborator then correctly refuses on a title contradiction:

```
[engine] skipped: … enriched watch facts title "Avalanche (Official Video)" contradicts
         ended title "Bring Me The Horizon - Happy Song (Official Audio)"
```

That reads exactly like a bug in whatever is being tested and is not one. Leave to a **non-video
page** — `https://m.youtube.com/feed/subscriptions` — or let the video end and autoplay move on.

### 35.3 What the log should say

Resolution through the disputed byline, on either route:

```
[resolve] resolved "<title>" → <id>: search lists the owner as "<artist name>" but its watch page
          names "<CHANNEL>VEVO", which is the session's own channel; title and duration agree
```

A duration that survives the drop — the number after `of` must not be `0s` on a completed listen:

```
[finalize] … — played 240s of 236s
```

An id that could not be confirmed but was not condemned, said **once** per id per track:

```
[identity] unlatched <id>: page title "<short form>" is only weak short-title evidence without
           same-generation duration corroboration; not confirming the id, and not counting
           this against it
```

If that line repeats more than once per track, the log-suppression set has regressed — it reached
114 lines in 40 seconds during this round, and the same passes were wiping the track's identity into
`skipped: source not proven YouTube`.

### 35.4 The control that matters

Play an **ordinary non-VEVO** upload whose byline agrees — any normal channel — past 60% in the same
browser, and confirm it still scrobbles by the pre-existing path (`via address bar + notification →
<id> (latched)`). The disputed-byline route must not be involved at all; if its resolve line appears
for a video whose byline already matched, the dispute predicate is too wide.

### 35.5 Two traps this round added to the list

- **Muted autoplay publishes no position and no duration.** Opening a watch URL by intent starts the
  web player muted; until a real gesture the session reports `position=-1` and `metadata: size=3`,
  and nothing can be measured. Several runs looked like app failures and were not. Unmute, then
  test. `dumpsys media_session | grep -m1 state=PlaybackState` settles it in one command.
- **A video that reaches its end and sits there never finalizes.** With autoplay off the player
  idles at the last frame, the session stays `PLAYING`, and no track change ever arrives. Waiting
  for a `[finalize]` that cannot come has cost this project twenty minutes twice now.

---

## 36. v0.11.0c: the backgrounded browser, and proving the ad gate still works

Full record in `<redacted-private-path>`. This is the reproduction, and it is short.

### 36.1 The failing case

1. Open a real playlist page in Brave: `https://m.youtube.com/playlist?list=<id>`. This alone seeds
   the three-hour playlist store — check for `[url] … ("m.youtube.com/playlist?list=…")`.
2. Tap a row to start playback (the tap is also the gesture that makes the web player publish a
   duration; see §35.5).
3. **Press HOME.** Leave the browser minimised for the whole track — that is the condition.
4. Let it play to the end and autoplay to the next entry.

The log must show the playlist route, not six searches:

```
[resolve] playlist <id> → N entries cached
[resolve] resolved "<title>" → <videoId> from playlist <id>
[engine]  scrobbled (block): … — tx …
```

If instead you see `[history] …` then `[resolve] search 1 …`, the playlist id was not carried and
the three-hour store has regressed.

### 36.2 Proving the protection still refuses

> **Superseded by §40.** A uniquely verified title+channel+duration lookup now remains eligible
> while the browser is backgrounded. This historical test still applies to an ambiguous, partial or
> contradictory lookup, and explicit ad evidence remains a hard veto.

This matters more than 36.1, because 36.1 is a relaxation. In the **same** backgrounded browser,
play something that is *not* in that playlist — let autoplay wander off it, or open a single video.
It must still refuse, and the reason must name the cause rather than the video:

```
[engine] skipped: the browser was never on screen while this track played, so YouTube's own ad UI
         could not be checked, and the id came from a lookup rather than the address bar.
         Nothing here says this was an ad; it says the check that rules one out never ran.
```

Seeing a track scrobble here means the exception has leaked past playlist provenance.

### 36.3 The controls that must keep working

- **Address bar, browser on screen** → `via address bar … (latched)` and a tx.
- **Native YouTube app** → unaffected; it never reaches this gate at all
  (`browserEvidenceEnabled` is `!isNative`).
- **The same listen finalized twice** → `already scrobbled […]`; replaying the song from the
  beginning is a new listen and must remain eligible (§39).
- **A playlist track below 60%** → refused on the threshold, not admitted by the exception.

### 36.4 Two things that will still refuse, by design

> **Superseded in part by §40.** A Mix-queue miss or an item beyond the fetched playlist window
> may now use the ordinary resolver, but only one uniquely corroborated candidate is eligible.

- **YouTube Mixes (`RD…`)** have no fetchable entry list, so there is no bounded set to match and no
  provenance to claim. A backgrounded browser playing a mix refuses.
- **Playlist entries past the first 100.** The page returns 100; a 180-entry playlist leaves 80
  reachable only through search, which then refuses when backgrounded.

Both are honest refusals, not regressions — but if someone reports "some tracks of my playlist
scrobble and some don't", these are the first two things to check.

## 37. v0.11.0d: the tab title, and how to tell a phantom from a track

The defect this round removes is invisible unless you read the metadata dumps, because everything it
produced *looked* like ordinary output: real finalize lines, real skip reasons, real warnings. What
made it a defect is that the track being described did not exist.

### 37.1 The shape to recognise in an old log

```
12:51:11  [finalize] Sleepwalking — played 228s of 236s        ← real, scrobbled
12:51:11  TITLE = "Bring Me The Horizon - Sleepwalking - YouTube"
          ARTIST = "m.youtube.com"                             ← the tab, read as a new track
12:52:10  [url] …watch?v=QuQW1vkDA1c…                          ← what is actually playing now
12:52:54  [finalize] Sleepwalking - YouTube — played 102s of 0s
12:52:54  [engine] skipped: … candidate channel "BMTHOfficialVEVO"
                   contradicts ended channel "m.youtube.com"
```

Three tells, any one of which is enough: a finalized title ending in ` - YouTube`, a finalized title
that is exactly `YouTube`, or a skip reason containing `ended channel "m.youtube.com"`. **None of
those may appear in a v0.11.0d log.**

### 37.2 Forcing the transition on purpose

It happens by itself at most track changes, but to make it happen now:

1. Play any YouTube video in Brave with RustedWax monitoring.
2. While it plays, type a *different* watch URL into the address bar and press enter.

The log must read:

```
[track]    replaced the track with its tab's own title after Ns played
[finalize] [the browser named its tab instead of a track] <real title> — played Ns of Ms
[metadata] (the browser named its tab, not a track)   TITLE = "YouTube"
[playback] … played=0ms                               ← nothing is being measured
[metadata] (the browser named a track again)          TITLE = "<the new video>"
[playback] … played=33ms                              ← the next track, from zero
```

`played=0ms` in the gap is the assertion that matters. If it climbs, the gap is being credited to
something. So is the second `played=` after the gap: it must be near zero, not the length of the
gap — that was the seven-minute retroactive credit measured on 2026-08-11, where the state suppressed
reporting but left the clock running.

The gap can last minutes. Brave has been observed publishing nothing for seven minutes while a video
played. That is not a fault to chase; it is the condition this state exists for.

### 37.3 The warning that must not appear

Play three browser tracks with the address bar working normally and let any of them be refused for
any reason. **"The address bar has gone quiet" must not appear.** It is now raised only by finalizes
where the bar named nothing at all, and it clears the moment the bar names a track.

To see it legitimately, revoke Accessibility for RustedWax and let three tracks finish. To clear it,
grant it again and let one track resolve.

### 37.4 The VEVO title shape

The likeliest remaining refusal to misread. A playlist row says `Artist - Work (Official Video)`;
the watch page says `Work (Official Video)`. That used to refuse:

```
[engine] skipped: … enriched watch facts title "Avalanche (Official Video)" is only weak
         evidence and cannot establish video id UNaYpBpRJOY for the ended track
```

It must now scrobble, **provided the duration agrees**. Break the duration and it must refuse again
— that is the only thing still holding the id, so it is the check worth confirming by hand.

### 37.5 Which side of the dash is the artist

```
Runaway - Linkin Park (Hybrid Theory)      channel: Linkin Park   → Linkin Park — Runaway
Linkin Park - Numb                          channel: Linkin Park   → Linkin Park — Numb
Metallica - Sad But True                    channel: camille martin → Metallica — Sad But True
Illenium - Fractures (Trap Nation Release)  channel: Trap Nation   → Illenium — Fractures
Linkin Park Tribute - Linkin Park           channel: Linkin Park   → Linkin Park Tribute — …
```

Only the first flips. If the third or fourth ever flips, the proof has been loosened from "the right
side *is* the uploader" to "the right side mentions them", which is the failure mode to watch for.

### 37.6 Still refuses, unchanged from §36.4

> **Superseded by §40.** These routes no longer refuse solely because the browser stayed in the
> background; they still refuse if the fallback is absent, ambiguous or contradictory.

YouTube Mixes (`RD…`) and autoplay past a playlist's last entry, both with the browser never on
screen for the whole track. Those are §36's by-design refusals and this round did not touch them.

### 37.7 A known diagnostic wart

While the browser sits on a tab-title bundle, `[identity] … latched video X for this track` can be
written roughly once a second — 490 lines in five minutes was measured. The unlatch line beneath it
is repeat-throttled and the latch line is not, so the pair loops. It is noise, but the silent
unlatch behind it also costs `session.confirmed` on VEVO-shaped titles, which is what arms §36.2 for
a backgrounded browser. Not addressed in this round.

## 38. v0.11.0e: established teardown fields and bounded Mix queues

### 38.1 Brave may erase a field only from the current bundle

Play a full-length Brave track while minimized and inspect the last two metadata bundles before the
track changes. The regression shape is:

```
TITLE = "Krazy"  ARTIST = "2Pac - Topic"  DURATION = 315881
TITLE = "Krazy"  ARTIST = "m.youtube.com"  DURATION unset
```

The finalize line must still name `Krazy`, `2Pac - Topic`, and 315 seconds. Resolution must not say
`title, owner/channel and duration were not all available`. The refined per-track identity owns
fields established while the item played; the raw final bundle is only a fallback for a field that
was never established. `BrowserTabMetadataTest` carries this exact teardown fixture.

### 38.2 A Mix row is evidence; an `RD…` id is not

Open an `RD…` Mix watch page, allow the current item to publish title, channel and duration, press
HOME, and let it end. For an item present in the page's bounded `playlistPanelVideoRenderer` set,
the log must show:

```
[resolve] Mix queue <RD id> → N entries cached
[resolve] resolved "<title>" → <video id> from Mix queue <RD id>
```

The parser must return exactly one row matching all three fields. A title-only row, a duration
conflict, two matching rows, a private `LL`/`WL` id, or the `RD…` id by itself establishes nothing.
The field gate used `2pac- Shorty Wanna Be A Thug`, fully backgrounded, and block-confirmed tx
`7650027df3370b0f0831aea9558320b5598cd3e9`.

The next personalized Mix item was absent from the anonymous 25-row set. At the v0.11.0e boundary
it refused; under the later §40 rule it may proceed only if the ordinary bounded resolver finds one
unique title+channel+duration candidate. This distinction prevents a historical expected refusal
from being mistaken for the current rule.

## 39. v0.11.0f: duplicate finalization is not replay suppression

### 39.1 One listen still produces one claim

Use the Now card's manual broadcast on a qualifying listen, then let the same session finalize.
Both paths must calculate the same normalized `title|artist|start:<epoch>` key. The manual send lands;
automatic finalization reports `already scrobbled this listen`. Two callbacks finalizing one frozen
snapshot must behave the same way. `DedupLedgerTest` pins both cases.

### 39.2 A replay from the beginning is immediately eligible

Play a song to completion, start it again from zero, and complete it again. Do not wait for a clock
hour. Both listens must broadcast, and the persisted claims must differ only in their exact `start:`
values. The six-hour prune is storage retention for old claims, not a cooldown.

The device reproduction deliberately retained the old Blasphemy bucket claims `496243` and
`496244`. Corrected starts at 21:08:54Z and 21:15:39Z, both inside UTC hour 21, created claims
`start:1786482534` and `start:1786482939` and block-confirmed as:

```
266a65d94cee3cbf42b1e1aed739fae8a6b7ab83
97d265ba478b8bf8ca23d158db504c2891c27403
```

A test straddling an exact UTC boundary and another with two starts 31 minutes into the same hour
must both report distinct keys. If either uses `startedAt / 3600`, the regression has returned.

## 40. v0.11.0g: finalized identity in foreground, background and native playback

### 40.1 Rejected outgoing URL must yield to the current URL

Reproduce the exact transition `QuQW1vkDA1c` (Visions) → `lir3dzYIhz0` (Sleepwalking). It is valid
for Chromium's first Sleepwalking callback to still carry the outgoing Visions URL. Once that id is
rejected against the new metadata, the later exact Sleepwalking address-bar observation must replace
the rejected observed id and its generation. Finalization must accept the watch page's shorter
`Sleepwalking` title against `Bring Me The Horizon - Sleepwalking`; it must not report that the short
title cannot establish `lir3dzYIhz0`. A later successor URL must not replace an observed id that has
not been rejected.

### 40.2 Brave fully backgrounded, with no address-bar id in process

Start a normal watch video in Brave, background Brave, restart RustedWax without clearing data, and
leave Brave off screen through finalization. This removes the in-memory URL observation while keeping
the MediaSession. The log must show an accessibility evidence outage, one unique
title+channel+duration resolution, `broadcasting` with that id, and a block-confirmed tx. An
ambiguous or contradictory lookup must still stay off-chain. Re-run with a literal positive ad
signal; it must refuse even when the lookup is unique.

### 40.3 Native YouTube parity

Play the same video in `com.google.android.youtube` on the same APK. With no address bar, the native
resolver must uniquely establish the same id, broadcast, and reach a block-confirmed tx. This is an
independent path: browser accessibility coverage must neither be required nor consulted.

The 2026-08-11 device gate used `7i_2TJv96Wk`. Brave, fully backgrounded and with no URL in the
restarted process, produced tx `0561da2a3a1e8dd1ab5a25cf5c5b8816547e365d`. The native YouTube app
then produced tx `4e7ef050ba4a1c670fb703a1e9285f43aa48456d`. The full gate passed 704 unit
tests, debug assembly and lint.

### 40.4 The negative control is part of the gate

Repeat either source with metadata that resolves to two equally valid uploads. The log must name
both ids, refuse every id, and build no payload. The device control found `ZnZqB5Z75zI` and
`jFSnwuJwTH0` at 19:06:54 and stopped before signing. A literal positive ad signal is an independent
earlier veto and must likewise win even if identity resolution is unique.

### 40.5 The boundary must remain structural

`FinalizedVideoIdentityContractTest` must prove both accepted authorities—a matching frozen exact id
and one unique finalized lookup—and both refusals: a non-unique lookup and any resolver id that
differs from a frozen id. `ScrobbleRules.decide` must not accept browser visibility, accessibility
coverage, playlist provenance or resolver-route parameters. If a future change needs one of those
facts for identity eligibility, it is a contract change requiring an update to
`Documentation/Product/IDENTITY.md`, this section, both Brave/native device controls, and the ambiguity/ad negatives;
it cannot be introduced as another exception boolean.

## 41. v0.11.0h: contract and documentation consolidation

This gate exists because individually correct historical fixes still left multiple places able to
decide identity. It is not complete merely because §40 remains green.

1. `FinalizedVideoIdentityContract` is called in `ScrobbleEngine` immediately after resolution and
   before enrichment, scrobble rules or payload construction. A matching frozen id and one unique
   finalized lookup are the only positives. A non-unique result and a resolver/frozen-id mismatch are
   negatives.
2. `ScrobbleRules.decide` exposes no browser visibility, accessibility coverage, playlist
   provenance or resolver-route inputs. The manual path has no separate coverage veto.
3. `IdentityContractAlignmentTest` verifies the normative statements in `Documentation/Product/IDENTITY.md`, all
   active user documentation, the supersession markers on historical specifications, and absence of
   the removed production veto.
4. Run the full uncached command—not only the two focused test classes—and compare the installed
   `base.apk` hash with the local artifact.
5. Exercise the installed artifact through Brave and native YouTube. Brave must block-confirm after
   being backgrounded; native must block-confirm through a unique finalized lookup with no exact id.

The completed gate passed 712 tests with zero failures/errors/skips, debug assembly and lint. Local
and installed APK SHA-256 was
`5732a87bc95f8ac05c2655d8cb7735a9cb0f5037bb8f512521d9074d45cf7a1a`. The same control video
`7i_2TJv96Wk` block-confirmed from backgrounded Brave as tx
`68a04a763aa522848c2f4ca3b5e5eaa02c4270fa` and from native YouTube's unique finalized lookup as tx
`67ae3488bc04cabe97077440c4531c99c2194164`.

## 42. v0.11.0i: listener/process rebuild and missing-duration recovery

This gate reproduces the two masks found only after §41's exact artifact was stressed harder.

1. Start a Brave YouTube video, background Brave, and verify an active media notification exists.
   Kill the RustedWax process without stopping Brave. The replacement listener must log the
   notification before it logs the new Brave session; identity must say site-only/no exact id.
2. `RustedWaxListenerService.startProbe()` must clear `NotificationHints`, call
   `replayActiveBrowserNotifications()`, and only then call `SessionProbe.start()`. Live and replayed
   notifications must share `recordBrowserNotification`, including target-package and media-style
   filters. `IdentityContractAlignmentTest` enforces this source ordering.
3. Complete/finalize the rebuilt session when Chromium supplied no duration. Exactly one
   exact-title history entry, or exactly one bounded search candidate whose canonical page names the
   finalized channel, may become `uniquelyResolved`; two same-title ids, two canonical survivors,
   a contradictory title/channel, or more than eight candidates must refuse.
4. Repeat on native YouTube. It must use the same centralized finalized authority and lookup
   uniqueness, with no browser dependency.
5. Run the full uncached source gate, compare local/installed APK hashes, and leave Notification
   Access plus both accessibility services enabled and bound.

The completed gate passed 716 tests with zero failures/errors/skips, debug assembly and lint. Local
and installed APK SHA-256 was
`f5139ecc4d5b12900cc0653dfb342b3c5b9d6ae9534b721a78fd8750717ed999`.
The no-id/no-duration process-restart Brave control on those exact bytes block-confirmed tx
`3d5347b1533817428f3c770c8cfdc49ac0835cbb`; native YouTube on the same artifact block-confirmed tx
`592f44cd57934d9789a4fd89f5048da44160aac4`.

## 43. v0.11.0j: consecutive-playlist recovery after the Ethereum recurrence

The single-video controls in §§40–42 did not reproduce a public playlist whose Brave plays never
appeared in the connected watch-history feed. On 2026-08-11, Brave was fully backgrounded and
`Tupac heartz of men` / `Ethereum 2.0` finalized at 287/284 seconds. The carried playlist was still
the earlier Bring Me The Horizon list, all 111 ordinary history rows omitted the play, and 66
open-search candidates produced no exact id. The actual public playlist was
`PLPGNhn7dCuh08XdX9XGwbbGkIHZRrAjU3`; its exact third upload is `4ZnHHd3i8I4`.

Required gates:

1. Record every finalized target as an ordered placeholder. Only fill it after the centralized
   finalized-id contract and contradiction check pass. An unresolved middle item breaks the pair;
   coroutine completion order cannot change playback order; packages and lifecycle clears isolate it.
2. Use the immediately preceding two verified ids and titles to discover bounded public-playlist
   candidates. Titles are search hints only. Inspect every list containing the exact id pair
   adjacently in that order, restrict identity candidates to each pair's immediate next row, then
   apply the existing title+channel+duration gate across that combined set. Exactly one video id
   must survive; zero or multiple ids refuse. Multiple playlists are not ambiguity when they all
   prove the same next upload.
3. When a rejected outgoing URL yields to the current URL, replace its playlist id at the same time.
   A same-video transition into a different playlist gets a new generation and notifies the probe.
4. The production history window remains 30. The temporary diagnostic activity and 111-row widening
   used to prove the feed omission must not ship.
5. Install the final APK and play the reported playlist through at least its first three entries with
   Brave off screen and no URL evidence after a RustedWax process restart. The third must log
   `adjacent verified predecessors … uniquely resolved current upload`, resolve `4ZnHHd3i8I4`, and reach a
   block-confirmed tx. Repeat through native YouTube on the same APK and require its own tx.

Device result on the same installed APK:

- Brave was off screen for the sequence. `8RddqlctLnk` and `FGjyRjd1_jQ` block-confirmed as
  `774a0ba5008e00829c3c52330b0050a7dddf9622` and
  `b90884d3a63b412ee561e360497842664f44f65c`. YouTube's id-only playlist query returned zero
  lists, the title discovery query returned 20, and five contained the exact pair adjacently. Their
  immediate next rows left the single matching id `4ZnHHd3i8I4`; Ethereum 2.0 block-confirmed as
  tx `d54244f376538597c8ff34cc2a2d497424626e6e`.
- Native YouTube then played the same first three entries at 2×, including real native ad
  transitions. The predecessors block-confirmed as `c5f59b0da94ef5e6fd16b2d78e68b5d668e31b85`
  and `0d66fb9b4568c7db991ac7b82f1e52c11f464c75`. The shared recovery route again uniquely selected
  `4ZnHHd3i8I4`, and Ethereum 2.0 block-confirmed as tx
  `305c434c526fac968b87aac5c8871e0e78aaf9c7`.

The final uncached 723-test/lint/assembly rebuild was installed and its `base.apk` independently
matched SHA-256 `9b48b6ae2afd7acdb7fb7606bbf0c151a8a158254171590ff60e5b4a1377741d`.
Those exact bytes then block-confirmed the 31-second no-duration control through background Brave as
tx `5b5e096bab2e6e4a1b54c9495d7de959da929f7b` and native YouTube as tx
`9bc61d5ef26b3cbd3b4db23b424312411e88e003`.

## 44. Architecture migration Phase 0/1: replay harness and audit repair

Implementation record: [<redacted-private-provenance>](../Architecture/Phases/<redacted-private-provenance>). Plan of record:
[<redacted-private-provenance>](../Architecture/<redacted-private-provenance>).

The audited suite is now **796 tests, zero failures, errors or skips**, with debug assembly
successful and lint at 0 errors / 29 warnings. The 723 tests recorded in §§38–43 are unchanged and
still passing; 72 tests are in the
replay corpus in `app/src/test/java/com/rustedwax/app/replay/`.

What is new is *what* they exercise. Every earlier automated gate drove a pure helper, a parser or a
single policy. These drive `ScrobbleEngine.onTrackFinalized` itself — the real method, with its real
resolution ordering, identity contract, rules, dedup and broadcaster call — from an ordered
playback/evidence/lifecycle trace. Payloads and user-visible refusals are exposed as typed test
outcomes. The disk, the network and the chain are the only substitutions, made at the
`EnginePorts` boundary. This is characterization, not a claim that every production early return
already emits a typed refusal: monitoring-off, auto-off, non-target, stale-epoch and missing-key
paths remain intentionally silent, and the existing repeat-listen rule can emit two payloads.

The scenario matrix represents Brave and native YouTube, foreground/background/minimised, ordinary
videos and Shorts, picture-in-picture, YouTube Music, VEVO and channel aliases, auto-translated
titles, playlists and Mixes, consecutive playback, watch history connected and unavailable,
MediaSession recreation, process restart, multiple browser tabs, visible and unlisted ads, network
failure and recovery, accelerated playback, named Sleepwalking and Ethereum 2.0 regressions, and a
generated 189-track resource-pressure run. The historical number was 111 ordinary watch-history
rows plus 78 Shorts returned by the signed-in feed. The retained log records the counts and selected
matches/refusals, not all row bodies; the literal source batch is not in the repository and has not
been converted. The generated run is not a substitute for it.

The port extraction adds test seams only:
the diff to `ScrobbleEngine` changes field declarations and the `init` wiring, every production
port wraps the same concrete dependency as before, every adapter is a plain forwarder,
and the replay-only entry points are `internal` with no caller in `app/src/main`. The device-tested
APK and the transaction ids recorded in §43 establish the pre-audit baseline. The audit repair also
closed a title-only identity defect in the current working tree, so the current artifact was rebuilt
and re-run on the device. Local `app-debug.apk` and installed `base.apk` both matched SHA-256
`b9ae5f4fe3de0aefd819f78931fe580b87b4bae9376cb8abb61b2eda789bd92c`.

On those exact bytes, native YouTube verified `7i_2TJv96Wk` from watch history and block-confirmed tx
`fca5a06aabe4b4909824db31bb53b09c1149e647`. Brave verified `dQw4w9WgXcQ`, recovered its canonical
213-second duration and hyperlink, and block-confirmed tx
`87305d1fb20e010cf3d6948651b47bd49276b428`. No current v0.11.0 artifact exists in `dist/`, so the
separate distributed-APK hash gate remains open. `<redacted-private-provenance>` states the remaining Phase 0/1
gates in full, including why ordinary `SurfaceChanged` markers are modeled invariants rather than
production `SessionProbe.Watch` coverage until the real reducer is extracted.

Two divergences between the harness's modelled probe half and the real `SessionProbe` were found and
corrected while writing the corpus — a disproved video id drops the *reading* rather than the
verdict, and the identity latch has no URL-generation guard. Both are documented in
`<redacted-private-provenance>`; neither changed production code.

## 45. Phase 0/1 completion: terminal outcomes, imported field corpus, designated artifact

Implementation record: [<redacted-private-provenance>](../Architecture/Phases/<redacted-private-provenance>). Trace provenance:
[<redacted-private-path>](<redacted-private-provenance>). Plan of record, as amended
2026-08-12: [<redacted-private-provenance>](../Architecture/<redacted-private-provenance>).

The suite is **818 tests, zero failures, errors or skips**, with debug assembly successful and lint
at zero errors / 29 warnings on an uncached run, and `git diff --check` clean. 94 of those tests are the replay
corpus; one of them replays 1,414 imported field finalizations.

Three blockers recorded in §44 are now closed.

**One terminal outcome per finalized track.** The audit's original "exactly one payload or one
typed refusal" wording was unsatisfiable — it had no name for the deliberately silent boundaries
and forbade the repeat-listen rule's second payload. It was amended to require exactly one typed
`Ignored` / `Refused` / `Eligible` outcome per finalized target, and the engine now files one from
inside each branch through a no-op production observer. `ReplayScenarioTest` holds every scenario in
the package to the count, not just the ones written for it.

**The 189-video batch.** Not recoverable as rows, and not fabricated: the number is 111 ordinary
history rows plus 78 Shorts, and the retained log holds the counts and individual lookups rather
than the row bodies. What was recoverable has been imported instead — 1,414 finalized tracks from
the retained device log, sanitized to one-way pseudonyms by
`<redacted-private-path>`, of which 952 prefilter-determined refusals are reproduced
*exactly* and 188 securely paired broadcasts replay to the same canonical hyperlink and
`percent_played`. The raw log is never checked in. The importer also found and excluded 69
adjacency mis-pairings caused by asynchronous finalization.

**The distribution artifact.** *(Superseded by §46; `dist/rustedwax-0.11.0.apk` now holds the
Phase 2/3 bytes. Retained as the Phase 0/1 record.)* Local build, installed `base.apk` and
`dist/rustedwax-0.11.0.apk`
all hashed to `7c05a5908f764f5b146c68d78baa20aef9f9a71c4452f858206e5f29c922f754`. Installed with
`adb install -r` without clearing app data; Notification Access and both accessibility services
survived and were bound.

Because `app/src/main` changed, both live-source transactions were re-obtained on those exact bytes:

| Source | Video | Canonical link | Measurement | Transaction |
| --- | --- | --- | --- | --- |
| Brave | `dQw4w9WgXcQ` | `https://www.youtube.com/watch?v=dQw4w9WgXcQ` | 229 s, no session duration, 213 s recovered from the watch page, 100% | `f24081705f5e769c012a6dadec6c0274e714e32b` |
| Native YouTube | `7i_2TJv96Wk` | `https://www.youtube.com/watch?v=7i_2TJv96Wk` | 27 s of 31 s, no exact MediaSession id, verified through watch history, 90% | `8a61aa9f783b7dda3c5811d9aabbcdb381d62661` |

Both block-confirmed.

**Still modelled, and still labelled as such.** Ordinary `SurfaceChanged` markers do not execute
`SessionProbe.Watch`; they assert an intended MediaSession invariant. The Shorts/PiP
progress-loss and inference cases do change measurement and are meaningful. Real reducer surface
transitions are a Phase 3 gate, and shadow *parity* — as opposed to shadow *safety*, which is
proven here — begins in Phase 2 when a replacement output first exists.


## 46. Phase 2/3: explicit domain objects and the deterministic playback reducer

Implementation record: [<redacted-private-path>](../Architecture/Phases/<redacted-private-path>). Plan of record, as
amended 2026-08-12: [<redacted-private-provenance>](../Architecture/<redacted-private-provenance>).

The suite is **893 tests, zero failures, errors or skips**, with debug assembly successful and lint
at zero errors / 29 warnings on an uncached run. 119 of those tests are the replay corpus, one of
which still replays all 1,414 imported field finalizations — now twice, once per transport object,
as the Phase 2 parity gate.

**Phase 2 introduced the seven domain objects and their adapter.** `SourceSessionId`,
`TrackInstanceId`, `SourceDescriptor`, `TrackMetadata`, `PlaybackMeasurement`, `IdentityEvidence`
and `FinalizedTrack`. Nothing in the production pipeline consumes them yet; `SessionSnapshot`
remains the transport end to end, which is what the audit's strangler order requires.
`FinalizedTrackAdapterTest` proves the decomposition lossless by reflecting over `SessionSnapshot`'s
own backing fields, so a 28th field added without updating the adapter fails on the next run.

**One production defect was found and fixed.** `VerifiedPlaybackSequence` identified its entries by
`(packageName, trackStartedAtEpochSec)`, so two listens beginning inside one wall-clock second were
one entry: `begin()` created no boundary and `remember()` overwrote the earlier track's verified id.
The pair handed to `resolveEvidenceFromAdjacentPredecessors` could therefore be two tracks that
never played consecutively, and that pair is what recovers a public playlist — a wrong attribution
rather than a lost scrobble. The sequence is now keyed by `TrackInstanceId`, whose token survives
MediaSession recreation with the carried progress. Five regressions cover it, including a
same-second transition and a same-second overwrite.

**Old/new parity is scoped to what Phase 2 actually replaced.** `DomainParityReplayTest` runs the
real engine twice — once on the snapshot the probe froze, once on that listen decomposed into
`FinalizedTrack` and rebuilt — and compares broadcast payload **bytes** and typed terminal outcomes.
Ten named scenarios plus all 1,414 field records. A negative control feeds a deliberately corrupted
decomposition and requires the comparison to notice. The replacement side is shadow-only throughout,
*(Superseded 2026-08-13 — §47. `ShadowDedupClaims` no longer exists; the shadow boundary takes the
dedup claim and releases it at once, so the duplicate answer is real and nothing is retained.)*

with the one documented exception of the duplicate-gate scenario: `ShadowDedupClaims` retains
nothing by design, so a shadow ledger can never refuse a second claim and dedup parity is only
observable on a live in-memory ledger.

**Phase 3 extracted the playback state machine.** `detect/PlaybackReducer.kt` is
`reduce(state, event) -> (state, effects)` with no Android types, no package names and no side
effects; the four former `isNative` branches ask `core/PlaybackSourceCapabilities` instead.
`SessionProbe.Watch` became an Android registry that translates callbacks into inputs and performs
effects in order. `PlaybackReducerTest` drives it directly — 25 tests covering the exact gap the
audit named as "no behavioral test driving the real … state machine".

**`PlaybackTrace`'s modelled half is deleted, not kept alongside.** The replay corpus now drives the
production reducer for measurement and lifecycle, and the real `TrackProgressCarry` for session
recreation. `SurfaceTransitionReplayTest` replaces the previously inert `SurfaceChanged` markers
with real transitions through that reducer, which is the Phase 3 acceptance gate the audit amendment
moved here.

**Still standing in for a device, stated rather than absorbed:** the metadata bundle (no
`MediaMetadata` on the JVM), the two delayed timers (no `Handler`, so a trace proves nothing about
*when* the stopped-replacement grace or a continuation deadline fires), and the foreground-Shorts
surface, which is `ForegroundShortTracker`'s own tested code and is suppressed on the MediaSession
path by design.

**Device gate.** Local build, `dist/rustedwax-0.11.0.apk` and the installed `base.apk` on the
<redacted-device-model> all hash `ad94bba23ecf09a6b992e9bbfdd27429a95475d0b662f47f3efc8b8fc8ae616f`; a later
uncached rebuild reproduced the same hash. Both live sources produced block-confirmed transactions
from those exact bytes: native YouTube `7i_2TJv96Wk` at 95% in block 108976745
(`ed46ab516e01fbac6156e1542e75c52f2da36f0e`), finalized through the exact-ID-less
`stopped replacement grace expired` timer; and Brave `dQw4w9WgXcQ` at 100% in block 108976708
(`3a9f0024aba37941579a9bc971d2fb850220aa16`), finalized through the **new idle deadline**.

**The abandoned-playback defect and its fix, verified on-device.** Brave never publishes
`STATE_STOPPED` — across the whole retained log its 835 finalizations break down as 749 track
change, 49 tab-title replacement, 34 continuation expiry, 3 probe ended, and **zero** stopped — and
for this video it published no `DURATION` either. A finished video left alone therefore accrued wall
clock indefinitely: measured in the field at 6,421 s on a 186 s song, which minted *two* on-chain
transactions for one sitting, and observed again on-device at 9+ minutes on a 3:33 song. The listen
is now ended on a deadline derived from the item's own length — recovered from the latched video's
cached page when the session publishes none — with a 15-minute silence ceiling as the last resort:

```
22:11:26  [playback] com.brave.browser played 260s of a 213s item with no further transport
          update; treating the listen as ended rather than counting wall clock nobody was watching
22:11:26  [finalize] com.brave.browser [playback ran out without the source saying so]
22:11:42  scrobbled (block): tx 3a9f0024aba37941579a9bc971d2fb850220aa16
```

One payload, at 260 s. `ScrobbleRules.capForKind` additionally refuses the 160% second transaction
when no position was ever readable, so elapsed time alone can no longer claim a second listen.

**What the device run did not establish.** No same-second transition occurred, so the Phase 2
collision was not reproduced on-device and its coverage is the unit regression; the continuation
deadline did not fire; the 15-minute silence ceiling was not reached, because the cached-page length
armed a precise deadline first, which is the intended order; and `STATE_STOPPED` was not exercised
on the browser path at all, because Brave does not produce it.


## 47. Phase 2/3 audit repair — 2026-08-13

The Phase 2/3 record in `<redacted-private-path>` claimed gates that its own tests could not
support. This section is the gate for the repair. What was wrong, and what each fix is:

| # | Defect | Fix | Coverage |
|---|---|---|---|
| 1 | no second implementation left to compare against | Phase 0/1 model recovered verbatim and retained | `ReferenceParityTest` (11) |
| 2 | shadow mode was a broadcaster returning `Rejected` through the live engine | `ScrobbleEngine.finalizeInShadow`, which never enters dispatch | `ShadowBoundaryReplayTest` (8) |
| 3 | `reduce(s, e)` mutated `s` through a mutable `PipPlaybackInference` | the accumulator is a value returning its successor | `ReducerPurityTest` (3), `PipPlaybackInferenceTest` (10) |
| 4 | the browser scan selector tried elimination before the named-video route | `detect/BrowserScanBinding.kt`, pure, two routes kept apart | `BrowserScanBindingTest` (11) |
| 5 | the idle deadline was never armed at construction, nor re-armed when a length arrived late | `detect/IdleFinalization.kt` with a scheduler seam; `ScrobbleEngine.onVideoFactsResolved` | `IdleFinalizationTest` (14) |
| 6 | an unexpected throw in the async half produced *no* terminal outcome | one failure boundary around `finalizeResolved` | `FinalizationFaultReplayTest` (5) |
| 7 | the foreground-Shorts measurement and the process-restart clear were the harness's own | the harness drives the real `ForegroundShortTracker`; `RunScopedEvidence.clearAll` is shared with the listener service | the whole `replay` package |
| 8 | ordinary `SurfaceChanged` rows were described as production coverage | recorded, not dispatched, and asserted as such | `SurfaceTransitionReplayTest` (8) |
| 9 | the seven Phase 2 types accepted states no listen can be in | `require` on each, checked against all 1,414 field records | `DomainInvariantsTest` (13) |

### The exact uncached gate

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew testDebugUnitTest assembleDebug lintDebug --rerun-tasks
```

```
:app:testDebugUnitTest  PASS — 961 tests, 0 failures, 0 errors, 0 skipped, 86 classes
:app:assembleDebug      PASS
:app:lintDebug          PASS — 0 errors, 29 warnings, 2 informational
git diff --check        clean
```

Both XML reports were read directly rather than trusting the console:
`app/build/test-results/testDebugUnitTest/*.xml` and
`app/build/reports/lint-results-debug.xml`.

The gate was run twice, uncached both times, and the second run reproduced the **identical** APK
hash — so the artifact hashed below is byte-reproducible from this source rather than an artefact of
one build.

Package breakdown: `detect` 505, `enrich` 194, `replay` 133, `hive` 38, `scrobble` 57, `storage` 17,
`replay.reference` 11, `ui` 6.

One build-file change accompanies this: `testOptions { unitTests { isReturnDefaultValues = true } }`.
`EventLog.append` calls `android.util.Log.d`, which throws on the JVM unless stubbed, so without it
the only way to observe the log from a unit test is to leave it switched off — which makes "a shadow
run writes nothing to the log" untestable, because the assertion would be about a log nobody had
turned on.

### Device gate

Production bytes changed, so the full device gate applies.

| Artifact | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `090bd300800b3d600f21329645b3714f4f222cc768b467ccff41bd3068d92b7c` |
| `dist/rustedwax-0.11.0.apk` | `090bd300800b3d600f21329645b3714f4f222cc768b467ccff41bd3068d92b7c` |
| Installed `base.apk`, <redacted-device-model> (R58R215V2SA) | `090bd300800b3d600f21329645b3714f4f222cc768b467ccff41bd3068d92b7c` |

Installed with `adb install -r` without clearing app data. Notification Access and both
accessibility services were re-read from `settings get secure` rather than assumed: the listener,
`UrlWatcherService` and `NativeShortsAccessibilityService` are all still enabled, with
`accessibility_enabled = 1`.

Both live sources produced a block-confirmed transaction from exactly these bytes, and each was
verified on **two independent Hive nodes** (`api.hive.blog`, `api.deathwing.me`) by **transaction
lookup and by account history**, which agreed on block, transaction number and payload bytes:

| Source | Video | Result | Tx | Block | History seq |
|---|---|---|---|---|---|
| Brave | `7i_2TJv96Wk` | `video`, 100%, one payload | `80495b977b20036c3accffb86b4a26a5f4167106` | 108978645 | 4344 |
| Native YouTube | `7i_2TJv96Wk` | `video`, 90%, via watch history | `d820dfa3eb46a9707e54c62be70f2bae2f7ad7a2` | 108978675 | 4345 |

The Brave listen published **no session `DURATION` at any point** and `pos=-1` throughout — the
abandoned-playback shape — and recovered 0:31 from the video's own watch page. The native listen
finalized through the exact-ID-less `stopped replacement grace expired` timer, which is one of the
two delayed timers the replay harness cannot schedule.

The two listens are the same video minutes apart and were correctly *not* deduplicated: the dedup
key includes the frozen start, and these are two genuine viewings rather than one snapshot
presented twice.

**What this device run did not establish.** The new idle deadline did not fire — Brave auto-advanced
before it matured, so the Brave listen finalized on a track change; the previous build's run (§46)
is where the deadline itself fired on-device. No same-second transition occurred. The continuation
deadline did not fire. The 15-minute silence ceiling was not reached. `STATE_STOPPED` was not
exercised on the browser path, because Brave does not produce it. Shadow execution has no production
caller and was not exercised on-device at all, which is deliberate: `finalizeInShadow` is `internal`
and referenced only from the test source set.

### The one gate that is half-met, stated rather than absorbed

Real **foreground/background/minimised** transitions through the production reducer are still
modelled, and cannot be otherwise from a JVM trace: an ordinary MediaSession publishes no callback
when its window moves, so there is no production input for a transition to become. The measurement
*across* the interval is production; the claim that the transition itself does nothing is asserted
structurally — the markers are recorded in `PlaybackTrace.surfaceMarkers` and a run carrying them
produces byte-identical payloads to a run without them. Picture-in-picture is production and does
reach the reducer's inference gate. Closing the remaining half needs the instrumented suite
`<redacted-private-provenance>` §8 already lists as missing.

## 48. Brave lifecycle and native Shorts field closure — 2026-08-14/15

Canonical records:

- [Brave playback termination and attribution](../Field-Reports/<redacted-private-path>)
- [Native Shorts correctness closure](../Field-Reports/<redacted-private-path>)

The session began by closing the production-wiring holes left around the §46/§47 abandoned-playback
fix: initial deadline arming, lifecycle-owned late-duration completion, a truly frozen finalized
Watch, process-independent opaque tombstones, and a fail-closed multi-tab selector. Field follow-up
added narrow first-party takeover arbitration, exact foreground-Short lead-in credit, and exact
hashtag/emoji-only title behavior. The detailed Brave record maps every cause to production source
and regression, and preserves the final device rerun boundary instead of using native evidence as a
browser substitute.

The A36 follow-up then found nine native-Short/UI defects: visible 2× evidence was lost after parsing;
Android returned a cached outgoing accessibility footer; an identity-less incoming seekbar crossed
the 750 ms stabilizer boundary; a resumed tab got a new listen token/start; unresolved playback was
rendered as dead History/Not logged text; completed Shorts could take 137 seconds to appear in the
eventually-consistent watch-history feed; immediate PiP discarded the only complete frame while it
was still stabilizing; an identity-less `NONE`/`STOPPED` MediaSession appeared as a Now item; and a
resolved untitled Short still disappeared because Not Logged required the missing surface title.
The native record includes each pre-fix field shape, exact code boundary, focused regressions, the
historical four-item reconciliation, and the final installed-byte gate for the last three fixes.

### Final source gate

The final uncached gate used the repository's configured Android/JBR toolchain and completed:

```text
testDebugUnitTest: 1,132 tests in 109 suites; 0 failures, 0 errors, 0 skips
lintDebug:          37 findings; 0 errors, 0 fatal findings
assembleDebug:      PASS
git diff --check:   clean
```

The test total was read from the JUnit XML and lint severity from the lint XML, not inferred from the
Gradle console. The final post-regression local debug APK has SHA-256
`5938aca8128edb5a98208696f781c3da6d3df64b4f21217f04e40986e1ed930f`, byte-identical to the
installed A36 `base.apk`. The prior physical
acceptance artifact and installed `base.apk` both had SHA-256
`923de00c68a4c5ffc48341422288d2f3d75ae5a732bda83a81a19ed026d4f418`; that older installed hash is
not proof of the immediate-PiP fix.

### Final physical gate

The installed final bytes on Samsung `<redacted-device-model>`/`<redacted-device-id>` produced exactly four broadcasts and
four transaction ids for ordinary 2× → Shorts 2× → Home Short 2× → real pinned PiP 1×. History held
four linked rows; Not logged held one linked same-listen dedup refusal, and its tap opened the exact
Short. The full and acceptance-slice log hashes, percentages, transaction ids, pinned-task evidence,
and screenshots are in the native record.

The host gate did not run `am instrument`: on API 31 that operation had disabled/crashed the
accessibility services whose production behavior was under test. The final A36 sequence therefore
uses host orchestration, real app surfaces, `dumpsys`, persisted UI, and chain transactions. The
SharedPreferences tombstone device test remains source-level instrumentation coverage and is not
misrepresented as part of that native accessibility/PiP proof.

That four-item physical gate is historical. The later immediate-PiP gate also passed on the final
matching bytes. Task `#31` was a real pinned YouTube task. `jTrrozU8AVk` moved from the first
stabilizing frame to acquired proof in 664 ms, finalized at 33/33, produced one payload and
transaction `a5bc2196cabaf053b8bdb68cef5f0f52a629cbea`, and appeared as one linked History row. Both
`api.hive.blog` and `api.deathwing.me` returned the transaction in irreversible block 109061359 and
returned identical operation bytes through block and account-history lookup. Untitled
`Bvffso90yrQ` closed through Samsung's transient PiP X at 30/59, produced no payload, and appeared as
one linked Not Logged row with its canonical recovered title. Both row taps opened their exact ids,
and Now contained no empty `NONE`/`STOPPED` item after close. The final field report carries the
screenshots, hashes, full timeline, and literal gate verdicts.

## 49. Pre-Phase 5 remediation and earned-listen continuation — 2026-08-20

Canonical record:
[2026-08-20 pre-Phase-5 field report](../Field-Reports/<redacted-private-provenance>).
That report owns the full root-cause maps, physical timelines, artifact ledger,
immutable transactions, and current `Safe for Phase 5: NO` boundary.

### Regression shape

A real YouTube Music listen had already earned automatic disposition: 199,165
ms of 299,235 ms (66.6%) against a 60% threshold, exact id
`UxQv0SGRt8g`. When its MediaSession vanished, the generic exact-position carry
path hid it behind the 15-minute human-interruption window. It therefore appeared
in neither History nor Not logged until the window expired, even though no
further playback was required for eligibility.

This was not a Hive, identity, or dedup failure. The old installed bytes
eventually wrote one correct transaction after the 15-minute delay. The defect
was terminal scheduling and user-visible latency.

### Production ownership and red-first coverage

The correction keeps threshold policy in `ScrobbleEngine`. Its
`shouldFinalizeContinuationPromptly()` answer requires monitoring and automatic
scrobbling, a known positive duration, and the active threshold. `SessionProbe`
supplies only measured played time/duration; `TrackProgressCarry` stores the
boolean and selects its existing 60-second replacement grace. Non-earned and
auto-off exact listens retain the 15-minute interruption window.

The regression was added before production code. The first focused compile
failed because `promptFinalization` and
`shouldFinalizeContinuationPromptly()` did not exist. Final tests prove:

- one millisecond below the exact 60% boundary does not shorten the window;
- the exact boundary does;
- automatic scrobbling off never shortens it;
- a routine same-track MediaSession replacement can still claim the listen
  inside 30 seconds; and
- without replacement, the carry expires once at 60 seconds.

The current generated `SessionProbe` mirror was regenerated, and provenance,
old/current parity, end-to-end parity, exclusive-takeover, and metadata-
freshness current-mirror tests all passed.

### Exact source and artifact gate

The local and supporting independent-clone command was:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='<redacted-local-path>/Library/Android/sdk' \
./gradlew --no-daemon --no-build-cache --no-configuration-cache \
  --rerun-tasks testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Parsed results:

```text
testDebugUnitTest:       1,232 tests in 118 suites; 0 failure/error/skip
lintDebug:               0 errors; 35 warnings; 2 informational
assembleDebug:           PASS
assembleDebugAndroidTest PASS
git diff --check:        clean
main APK:                dca2c02eb141a36ce1258e9be4981e593289c6933c6aad116bfcc97956ed2908
test APK:                4e2769a304a19ded570a2bdd08a567ac59c11f0a818b0216c3cec8bec075f983
local/clone equality:    byte-identical main and test APKs
local/installed equality byte-identical main APK
```

An orchestration attempt that invoked the clone's wrapper while still working
from the original repository rebuilt the original tree. Missing clone outputs
exposed the mistake; that run was rejected. Only a build whose process working
directory was the clone counted as clone evidence. A clone wrapper path alone
does not select the clone project.

### Physical automatic-scrobble gate

The reviewed main APK was update-installed with `adb install -r -t`; app data,
encrypted keys, settings, usage access, notification listener, and both
accessibility bindings survived. No package uninstall, data clear, or
instrumentation was used.

`Dear Jessie` / Madonna (`EGYmN-1UQzI`) then played in real YouTube Music while
the launcher was foreground. No seek credit was used. At session end it had
187/275 seconds (68%); production logged the corrected 60-second replacement
grace, finalized once, broadcast once, and confirmed once:

```text
10:22:56 session ended — waiting 60s for replacement
10:23:56 continuation expired — finalized 187s/275s
10:24:07 confirmed — tx 7f50aba481269dd5e6d139ffb77b6763b22772de
```

Both `api.hive.blog` and `api.deathwing.me` returned the exact transaction and
payload from irreversible block `109192394`. RustedWax History showed one
“Madonna — Dear Jessie” row at 68%, confirmed in block, with the same transaction
id. The scoped device log contained one finalization, one broadcast, and one
confirmation.

### Remaining acceptance boundary

This closes the earned-listen regression. It does not inherit the earlier
closure artifact's Phase 5 authorization. The final pre-Phase-5 decision still
requires the plan's literal independent detached-clone audit, a bounded physical
ordinary-replacement and package-isolation smoke on the latest scheduling code,
and an explicit latest-tree re-audit verdict. Until that report exists,
**Safe for Phase 5 remains NO**.

### Documentation-consolidation artifact boundary

After the physical gate, all project documents moved into `Documentation/` and
document-path readers, static links, tools, and source/test comments were
updated. No executable production expression changed, but a fresh uncached
build produced main hash
`8d25819d30d566d9c256d0fcb94788eacd17bc8a27ffd96244c2d7c619b15a64`.
It has the same 16,578,700-byte size and archive entry set as installed
`dca2c0…`; per-entry SHA-256 comparison found only `classes4.dex` different.
The test APK remained byte-identical at `4e2769…`.

The documentation-consolidated tree passed the complete 1,232-test, lint,
main/test assembly and `git diff --check` gate. It was not installed and its
independent clone was not rerun. Therefore the minimized-listen physical/Hive
evidence remains attached to exact installed `dca2c0…`, while the strict final
audit must begin from the latest documentation-consolidated source tree.

## 50. YouTube Music catalog/parser closure and Phase 5 entry — 2026-08-21 to 2026-08-23

Canonical records:

- [art-track identity](../Field-Reports/<redacted-private-provenance>)
- [duplicate catalog uploads](../Field-Reports/<redacted-private-provenance>)
- [catalog parser](../Field-Reports/<redacted-private-provenance>)
- [overnight audit and final closure](../Field-Reports/<redacted-private-provenance>)
- [independent pre-Phase-5 review](../Reports/<redacted-private-provenance>)

The field sequence repaired Song/Video presentation continuity, collaboration
credits, release-aware art-track lookup, bounded album browse recovery,
duplicate distributor ingests, album-less singles, Topic-channel aliases,
unlinked catalog bylines and six measured title/credit grammar shapes. Identity
remains fail closed when no exact id, complete credit or unambiguous catalog row
can be proven.

Independent review tightened two boundaries before acceptance: linked catalog
artists now require complete-credit equality, and deterministic duplicate-row
collapse additionally requires the same catalog release and the same YouTube
Music presentation type. Different releases or Song/Video items remain
ambiguous even when work, artist and length happen to agree.

The final local and no-hardlinks detached-clone command was:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='<redacted-local-path>/Library/Android/sdk' \
./gradlew --no-daemon --no-build-cache --no-configuration-cache \
  --rerun-tasks testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Parsed results:

```text
testDebugUnitTest:       1,270 tests in 120 suites; 0 failure/error/skip
lintDebug:               0 errors; 35 warnings; 2 informational
assembleDebug:           PASS
assembleDebugAndroidTest PASS
git diff --check:        clean
main APK:                030efaee8556378ee4bb9e48a46691ebecd2f1160831062cff7ce713b06dd38c
test APK:                4e2769a304a19ded570a2bdd08a567ac59c11f0a818b0216c3cec8bec075f983
local/clone equality:    byte-identical main and test APKs
local/installed equality byte-identical main APK
```

The main APK was update-installed without uninstall, data clear or
instrumentation. Monitoring, automatic scrobbling, package settings, all nine
preference files, the notification listener and both accessibility services
survived and remained bound.

On those exact installed bytes, YouTube Music published no exact id for
`Question` by `Jamal & Attomatic`. The production catalog route selected
`FcYm_6kR3Eg` after complete-credit, album, duration and presentation-type
agreement. The track played naturally to 164/164 seconds, finalized on the real
successor transition, broadcast once and returned transaction
`a6a5bafda4c482d1bf101773a53d86cf221e52e9`. `api.hive.blog` and
`api.openhive.network` returned identical operation bytes from irreversible
block 109285852 / transaction index 1.

The remaining `Tommy Lee`/`Tommy Lee Sparta` and localized `Et` shapes are
explicit fail-closed omissions; no alias or separator rule was generalized from
one sample. The final decision is **Safe for Phase 5: YES. Phase 5 = READY.**
Phase 5 implementation has not started.

## 51. Phase 7 unified finalization — 2026-08-23

Canonical record:
[<redacted-private-provenance>](../Reports/<redacted-private-provenance>).

`FinalizeTrackUseCase` is now the one orchestration boundary for automatic,
manual, and shadow finalization. Its production wiring supplies explicit
identity, enrichment, classification, eligibility, payload, effects, and
dispatcher services. The manual trigger retains manual-only feedback and
never queues failures; automatic transient dispatch failures may queue the
already-serialized payload. Queue retry is transport-only and has no finalized
track input, so it cannot re-run identity, policy, payload construction, or
terminal-outcome recording.

Red-first wiring tests failed before the split because the use case and shared
entry points did not exist. The accepted suite adds byte-equal automatic/manual
payload and route parity, typed-refusal parity, manual-to-auto collision, claim
release, no-account, transport-only retry, and a fake non-YouTube
adapter-to-reducer-to-recorded-dispatch path.

The final local and no-hardlinks detached-clone command was:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='<redacted-local-path>/Library/Android/sdk' \
./gradlew --no-daemon --no-build-cache --no-configuration-cache \
  --rerun-tasks testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Parsed results:

```text
testDebugUnitTest:       1,310 tests in 127 suites; 0 failure/error/skip
lintDebug:               0 errors; 35 warnings; 2 informational
assembleDebug:           PASS
assembleDebugAndroidTest PASS
git diff --check:        clean
main APK:                75ab278526417928eb94a2d8a84f46c08c3daafcb7504122cf9f19586d68392c
test APK:                7bf99e53b2dd43b59ea89f16f9ee4d122f004f4a42479737e6648f3ec39b7ad3
local/clone equality:    byte-identical main and test APKs
local/installed equality byte-identical main APK
```

The detached clone used `git clone --no-hardlinks --no-local`; a representative
source/clone inode differed. After the report and ledgers were synchronized,
the complete tracked-plus-nonignored-untracked path lists and ordered content
objects compared equal. The report deliberately does not embed a self-referential
whole-dirty-tree hash inside the tree it hashes.

The main APK was installed with `adb install -r`, without uninstall, data
clear, instrumentation, or permission changes. First-install time, app data,
account/key storage, notification access, both accessibility services, usage
access, and automatic scrobbling survived. The pulled installed APK and local
APK were byte-identical.

On those exact bytes, production returned irreversible transactions for
YouTube Music (`089186e4f13dd5eb8ebea45f07780c4d69019b38`), native YouTube
ordinary playback (`b1142a51d7b288770bf3efd4d8c3fa432aec988d`), Brave
(`b10c67ffb4521b4f12f50618cfe89c81ebea0661`), and a foreground Short carried
through real Android pinned PiP (`36ac193a0167f521e038a9b63d8c3f83e14f3c4a`).
The Short retained one identity while combining 22 seconds of direct seekbar
measurement with 34 seconds of bounded PiP inference, then finalized once at
56/60 seconds.

Manual and automatic physical finalization of the same 21-second browser item
both returned the same typed under-30-second refusal and wrote nothing. The
eligible dispatch form is covered by the production-wired replay: manual and
automatic produce byte-identical payloads and routes, and a manual-first
collision yields one recorded dispatch plus one duplicate refusal. This is not
misreported as a second physical transaction.

All seven transactions observed during the bounded live matrix, including
three successor/listen writes, were read back from both `api.hive.blog` and
`api.openhive.network`. For every transaction the block/index, canonical
operation hash, and raw embedded JSON hash matched between nodes. Both nodes
reported last irreversible block `109291867`, later than the newest accepted
transaction block `109291701`.

Verdict: **Phase 7 PASS. Safe for Phase 8: YES. Phase 8 remains unstarted.**

## 52. Architecture Phase 8 dependency-direction acceptance — 2026-08-24

The exact current tree contains the intended acyclic Gradle graph:

```text
:core
:identity-api       -> :core
:youtube-identity   -> :core, :identity-api
:android-sources    -> :core, :identity-api
:hive               -> :core
:app                -> all five lower modules
```

Red-first executable architecture tests enforce core Android/platform
independence, adapter independence, identity UI/MediaSession independence,
source-kind-check confinement, source-neutral shared APIs, storage independence,
the exact acyclic module map, and a fake non-YouTube adapter → reducer →
finalization → recorded-dispatch traversal. The Phase 7 facade and unified
finalization boundary remained at this Phase 8 checkpoint; Phase 9 had not yet
started. Section 53 records its later removal and supersedes this checkpoint as
the current architecture state.

The exact uncached Android gate executed all 157 tasks and produced 1,281 tests
in 125 parsed suites with no failures, errors, or skips. The explicit extracted
module gate added 38 Hive tests in 3 suites, also all green. Lint parsed to zero
errors, 38 warnings, and 2 informational findings. A no-hardlinks detached copy
repeated both gates. Local and detached main/test APKs were byte-identical; the
update-installed Galaxy A36 `base.apk` was pulled back byte-identical to the
main APK at SHA-256
`a1e615788a6aa4d0728d408ab973b567cd3e394f9ec126feb8d1329a4e40943c`.

With automatic scrobbling on, that exact installed APK produced and returned:

- native YouTube `7i_2TJv96Wk` →
  `fc9acea3303ff648c08bf94af111e487eb1cfde2`;
- Brave notification-only `0WtStU8wCE8` →
  `bb4ed34a1091efc15d8cf1b5c9200c70fb477975`.

Both transactions were independently returned by `api.hive.blog` and
`api.openhive.network` from irreversible blocks with identical complete
operation and raw embedded JSON bytes. The Brave run used notification host
proof, the real MediaSession destruction/continuation lifecycle, the production
60-second timer, exact watch-history resolution, one target broadcast, and one
confirmed dispatch; its separately finalized successor was refused as
`source not proven YouTube` and did not broadcast.

Samsung cleared both pre-existing RustedWax accessibility grants during the
otherwise data-preserving update install. They were not restored because the
task forbids toggling established grants. Accordingly, the exact-current-APK
accessibility-dependent Shorts/PiP device condition is **NOT ESTABLISHED**; it
is covered by the green production reducer/replay suite but is not misreported
as a physical PASS.

Checkpoint verdict: **Phase 8 PASS; Phase 9 had not started at this point.** Full evidence:
[`<redacted-private-provenance>`](../Reports/<redacted-private-provenance>).

## 53. Architecture Phase 9 compatibility-removal acceptance — 2026-08-24

Phase 9 removes production compatibility authority only after replacement-path
proof. `SessionProbe.Watch`, the `ScrobbleEngine` forwarding facade, production
`RunScopedEvidence`, and the mutable singleton evidence callbacks/cross-clearing
surface are absent. `MediaSessionDriver` owns reducer state; automatic, manual,
and write-suppressed shadow triggers call one `FinalizeTrackUseCase` directly.
The fake-source architecture gate traverses adapter → reducer → finalization →
recorded dispatch without Android or YouTube source dependencies.

The red-first gate initially failed 4/4 tests. The final focused set passed
11/11. The exact uncached Android gate executed 157/157 tasks; parsed XML
contained 128 app suites / 1,288 tests / zero failures, errors, or skips. The
explicit lower-module gate added 3 Hive suites / 38 tests, also green. Parsed
lint contained zero errors, 38 warnings, and 2 informational findings. A fresh
no-hardlinks detached copy repeated both gates and produced a byte-identical
main APK:
`addc0895d0bf3c722f6651ef2306104ecd14e7427997b95af8cc39c0ff3245f3`.

The authentic 1,414-record sanitized corpus passed. The literal historical
189-row feed is **NOT ESTABLISHED** because its row bodies were never retained;
the amended gate therefore uses that authentic corpus plus a separately labeled
synthetic 189-track pressure soak. A synthetic 180-entry playlist soak also
passed. It does not claim to be the named field playlist, whose other 177 rows
are unavailable.

With automatic scrobbling on, the exact update-installed Galaxy A36 APK
produced:

- native YouTube `7i_2TJv96Wk` →
  `9e311a73e31df0831a9123704fce590d522e40c8`;
- Brave `0WtStU8wCE8` →
  `5db7763f9eaea8014626a10fdeaf259d669429ce`.

Both complete operations and raw embedded payloads were returned identically
from `api.hive.blog` and `api.openhive.network` after their blocks became
irreversible. Local, detached, and pulled installed APKs were whole-file
identical. App data and established notification/accessibility grants were
preserved.

Verdict: **Phase 9 PASS under the amended, non-fabricated corpus gate.** The
missing historical 189 rows, missing 177 real playlist rows, and absence of a
fresh Phase 9 physical Shorts/PiP run remain explicit **NOT ESTABLISHED**
limitations. Full evidence:
[`<redacted-private-provenance>`](../Reports/<redacted-private-provenance>).

## v0.11.1 navigation, settings, theme and log retention

### What is covered by automated tests, and what is not

| Behaviour | Suite | Class of evidence |
| --- | --- | --- |
| Destinations, stale selection, swipe in both directions | `ui/AppNavigationTest` | model |
| `Log`/`Export` visibility follows the event-log switch | `ui/AppNavigationTest` | model |
| Settings order, adjacency of the two account rows, row copy | `ui/SettingsOutlineTest` | model |
| Monitoring status survives the removed card | `ui/SettingsOutlineTest` | model |
| Missing/expired/paused/mismatched/unreadable warning states | `ui/YouTubeConnectionWarningTest` | model |
| Stored theme resolution for every window | `ui/AppThemeTest` | model |
| 12-hour window, 512 KiB cap, undated legacy lines | `detect/LogRetentionTest` | model |
| Prune at startup, across a restart, and on a bound | `detect/EventLogRetentionTest` | production object + real file |
| `shortClipScrobbling` removed, `disableShorts` preserved, schema v2 | `storage/SettingsTest` | production migration |
| Proven-public Shorts admitted, everything else refused | `scrobble/ScrobbleRulesTest` | production rule |
| An unproven Short refused end-to-end through the engine | `replay/ShortsAndPipReplayTest` | replay |
| A `/watch` track is not swept up by the Shorts refusal | `scrobble/ScrobbleRulesTest` | negative control |
| Closed Now-card fields, cache-only category and conservative status | `ui/NowCardTest` | presentation model |
| Seven-tap developer unlock, persisted gate and tier contents | `architecture/UiSettingsLogWiringTest` + `ui/SettingsOutlineTest` | production wiring + model |
| Read-only head block, account and posting-authority validation | `hive/HiveConnectionCheckTest` | injected RPC contract |
| Screens are the models' only caller | `architecture/UiSettingsLogWiringTest` | production wiring |

`UiSettingsLogWiringTest` is a **supplement**, not a substitute. The behaviour suites prove what the
models decide; the wiring test proves the screens actually call them, which is the one failure a
pure model suite cannot see — a correct model that nothing on the device consults.

**No JVM test renders a composable.** The project has no Robolectric and no Compose UI-test
dependency, so the applied theme, the swipe gesture, the visible row order and the warning card's
presence on screen are established **physically**, not automatically. Treat any claim about what the
screen looks like as **NOT ESTABLISHED** unless it is backed by a device screenshot.

### Physical checks

1. **Tab/swipe synchronisation.** Tap each destination; the strip scrolls it fully into view and the
   page follows. Swipe left and right; the strip's selection follows the settled page. Swipe from the
   first and last destinations; neither wraps.
2. **Vertical scrolling and controls still work.** On `Settings`, scroll the list vertically inside
   the pager, then toggle a switch. Neither should be captured by the horizontal pager.
3. **Log visibility.** With `Event log` off there must be no `Log` destination and no `Export`
   button. Turn it on: both appear, and no other destination moves. Turn it off from `Log`: the
   selection lands on `Settings`, not on a neighbour.
4. **Settings order.** `Automatic scrobbling` is the first card. There is no "Watching YouTube in
   Brave and Chrome…" card. The monitoring status is still in the strip above.
5. **Hive account placement.** `Hive account` is the card directly below `YouTube watch history`,
   and `Manage` reveals the identity, `Forget key` and the posting-key disclosure. There is no test
   broadcast and no unaffiliated-software note.
6. **Missing-connection warning.** On an install with no YouTube session, the warning is above the
   destinations and visible from all of them, and its `Sign in` opens the app's own sign-in screen.
7. **Theme.** Set `System`, `Light`, `Dark` in turn and open the sign-in screen from the warning.
   RustedWax's own chrome — the app bar, the disclosure text, both buttons — must follow the choice.
   Google's page inside the WebView is out of scope and is expected to keep its own appearance.
8. **Log retention.** With `Event log` on, `run-as com.rustedwax.app stat -c %s files/rustedwax-log.txt`
   must stay at or below 512 KiB, including between scheduled prunes, and the oldest retained line
   must be within 12 hours. On the first launch after upgrading, a pre-v0.11.1 undated log is
   discarded. Turning the switch off must erase both the file and the in-memory rows.
9. **Now card.** It shows only platform, artist/channel, title, progress/length, category and one
   status. Crossing the percentage threshold says `Threshold reached — final checks run when this
   ends`; it must not promise that finalization will accept the listen. No manual broadcast control
   or diagnostic dump is present.
10. **Developer mode.** In **Settings › About**, seven version taps reveal the developer tier.
    Relocking it hides the tier without changing Event log, Disable Shorts or any established grant.
11. **Read-only Hive check.** With a saved test account, **Test Hive connection** reports the head
    block, account availability and posting-key match without adding a History row or broadcasting
    an operation.

### What a Shorts change still requires

Automated and replay evidence is **not** end-to-end acceptance for §5. A physical Shorts result needs
`Disable Shorts` off, automatic scrobbling on, a real Short crossing its real threshold, a returned
transaction id, and two-node reconciliation after irreversibility — see §"Physical and Hive" in
[<redacted-private-provenance>](../Development/<redacted-private-provenance>). Without an authorized Hive write it
is **NOT ESTABLISHED**.

## v0.11.1c log-retention lockup repair — 2026-08-26

The v0.11.1 retention bound made the app lock up. `LogRetention.prune` trimmed to
*exactly* `MAX_BYTES` while `EventLog.append` prunes whenever
`retainedBytes > MAX_BYTES`, so once a log reached 512 KiB the ceiling re-armed on
**every append** and a whole-file read, reparse and rewrite ran per logged line —
on the main thread this single-process app shares between Compose, the
notification listener and both accessibility services. A prune now trims to
`PRUNE_TO_BYTES` (384 KiB), so the documented 200-line cadence governs again.

Full evidence:
[<redacted-private-provenance>](../Field-Reports/<redacted-private-provenance>).

### Reproducing the defect on a pre-fix build

The condition is **the retained window sitting on the ceiling**, not any
particular content. An empty or small log will not reproduce it, which is why it
survived the v0.11.1 gate: `a long run is pruned without being asked` writes ~220
short lines and never approaches 512 KiB.

```bash
adb shell "run-as com.rustedwax.app stat -c %s files/rustedwax-log.txt"   # want ~524,288
adb logcat -c && adb logcat -v threadtime > repro.txt &
adb shell am start -a android.intent.action.VIEW \
  -d "https://www.youtube.com/watch?v=<id>" -p com.google.android.youtube
# then, with the app's pid:
grep Skipped repro.txt
grep "D RustedWax" repro.txt | awk '$3==PID && $4==PID' | wc -l   # main-thread lines
```

On the pre-fix build this reported `Skipped 302 frames` — five seconds of frozen
UI — with 74 of 78 log lines written on the main thread. Emptying the retained
window and repeating the identical playback produced no skipped frames at all;
that A/B is the causation proof and is worth repeating before believing any
future "the app is slow" report is the same defect.

### Required checks on the fixed artifact

Run the v0.11.1 `Log retention` item above, then:

1. **Headroom is actually reclaimed.** After a launch with a log at the ceiling,
   the file must drop well below 512 KiB rather than stopping on it:
   `adb shell "run-as com.rustedwax.app stat -c %s files/rustedwax-log.txt"`.
   Observed 2026-08-26: 524,263 → 393,294 bytes.
2. **A heavy log burst drops no frames.** Start playback with the window full and
   confirm no `Choreographer: Skipped` line for the app's pid. Observed: 55 log
   lines in one second with zero dropped frames.
3. **The ceiling still binds.** The file must stay at or below 512 KiB including
   between scheduled prunes.
4. **The window still binds.** The oldest retained line must be within 12 hours.

A startup `Skipped ~31 frames` is **baseline Compose startup**, not this defect:
it appears identically with an empty log. Compare before attributing it.

5. **Soak for growth, not just for level.** Sample main-thread jiffies, RSS and
   `files/rustedwax-log.txt` every few minutes with YouTube Music playing, Chrome
   open and RustedWax foreground. A 45-minute run on 2026-08-26 gave 10
   post-startup samples with mean 101.3 jiffies/30 s and its *minimum* at minute
   41; RSS oscillated 216–285 MB without growing and the log stayed 393–409 KB.
   A single high sample that coincides with the `files/musicbrainz` count rising
   is a track change resolving identity, not drift — check that count before
   reading a spike as degradation. Data:
   `debug/ytmusic-lag-2026-08-26/12-soak-45min.tsv`.

### What this does not cover

Automated and replay evidence is not end-to-end acceptance. This round's
transaction proof covers **native YouTube only**
(`46ad2ab16eb255205212758e3cee0c864398cd1f`, block 109353386, two-node
reconciled). `EventLog.append` is source-agnostic and the foreground-Shorts and
browser paths log *more* heavily per second, so the capability-isolation
alternative in the acceptance rule above does **not** apply to them: YouTube
Music, foreground Shorts and the browser path each still need their own device
transaction and are **NOT ESTABLISHED**.

## v0.11.1d status-poll main-thread cost — 2026-08-26

Two costs on the once-a-second poll in `MainActivity.Wired`, both removed
without changing what is scrobbled.

`AndroidSessionBinding.snapshot()` rendered `MetadataDump.dump` for every live
snapshot, once per second per session, for a field only
`FinalizedTrack.from(snapshot)` reads. The dump probes every non-text extra as
text — which makes `android.os.BaseBundle` print a whole `ClassCastException`
stack trace — and marshals every artwork bitmap across Binder. YouTube Music
publishes both; the YouTube app publishes neither. `YouTubeSessionVault.session`
separately decrypted four `EncryptedSharedPreferences` values against the Android
Keystore on every call.

### What to check on device

1. **The stack traces are gone.** With YouTube Music playing, count what the
   process writes:

   ```bash
   adb logcat -c; sleep 12; adb logcat -d -v threadtime \
     | awk -v p=$(adb shell pidof com.rustedwax.app) '$3==p' | grep -c "W Bundle"
   ```

   Must be `0`. Measured before the repair: **1,532**, which was 99% of
   everything the process logged.

2. **Background main-thread CPU.** With YouTube Music playing, Chrome open and
   RustedWax hidden, sample the main thread's jiffies over 30 seconds from
   `/proc/<pid>/task/<pid>/stat` fields 14+15. Measured 132 before, 65–70 after.

3. **Frame timing is warm-run only.** `dumpsys gfxinfo <pkg> reset`, twenty
   scripted swipes, then read `Janky frames`. **Discard at least three warm-up
   runs first**: a cold or freshly reinstalled process absorbs JIT warm-up and
   will read several times worse than the same build warm. An uncontrolled
   first-run measurement produced an 8.65%-vs-0.64% differential during this
   investigation that did not survive a controlled repeat (2.2% vs 1.6%).

4. **The session cache is still correct.** Sign in, confirm the account label
   appears; disconnect, confirm it disappears on the next poll rather than
   persisting. Those are the cache-invalidation paths `YouTubeSessionVaultCacheTest`
   pins as negative controls.

5. **Soak for growth, not just for level.** Sample main-thread jiffies, RSS and
   `files/rustedwax-log.txt` every few minutes with YouTube Music playing, Chrome
   open and RustedWax foreground. A 45-minute run on 2026-08-26 gave 10
   post-startup samples with mean 101.3 jiffies/30 s and its *minimum* at minute
   41; RSS oscillated 216–285 MB without growing and the log stayed 393–409 KB.
   A single high sample that coincides with the `files/musicbrainz` count rising
   is a track change resolving identity, not drift — check that count before
   reading a spike as degradation. Data:
   `debug/ytmusic-lag-2026-08-26/12-soak-45min.tsv`.

### What this does not cover

`snapshot()` is shared by every source, so the acceptance rule's
capability-isolation alternative does not apply. This round's transaction proof
covers **YouTube Music only**
(`8ef5bcec3b1ce8b67c71d770a984ca4b2f0d34d5`, block 109375691, two-node
reconciled). Foreground Shorts and the browser path each still need their own
device transaction on these bytes and are **NOT ESTABLISHED**.

## v0.11.1e stable-state recomposition boundary — 2026-08-26

Use the exact reported arrangement: YouTube Music actively playing, Chrome
retained on `scrobble.life`, RustedWax foreground on Settings, Developer Mode
on, automatic scrobbling on, event logging on, and both RustedWax accessibility
services live. Do not compare different destinations.

1. Record the installed whole-file APK hash and reset `dumpsys gfxinfo` only
   after Settings is settled.
2. Over a 30-second idle window, record main-thread jiffies and the complete
   `gfxinfo` summary. The decisive regression is continuous frames from
   high-frequency state invalidating the otherwise idle screen; a percentage
   calculated from one navigation frame is not meaningful by itself.
3. Open Now twice a few seconds apart and require the real playback percentage
   or position to advance. Deferring the read must not freeze the card.
4. Turn Developer Mode off through its UI, tap the version seven times 250 ms
   apart, and require it to turn on immediately.
5. Let a real YouTube Music listen traverse its natural production duration and
   threshold with automatic scrobbling on. Require the returned transaction and
   identical complete operation/payload bytes from two independent Hive nodes
   after irreversibility.

The production-shape guard is
`RecompositionScopeWiringTest`: `Wired` must retain stable Compose State holders
for the event log, Shorts status and live sessions; the changing values are read
only by `LogList`, the Shorts row and `SessionList`. A stable session-count value
alone may reach the destination strip.

The 2026-08-26 A36 gate measured 181 rendered frames / 177 janky / 171 slow UI
thread frames before the fix and three post-navigation frames in the fixed
30-second window. Main-thread cost fell from approximately 126 to 76 jiffies per
30 seconds. Now advanced 50% to 53% in three seconds, seven taps re-enabled
Developer Mode, and the exact artifact returned the block-confirmed YouTube
Music transaction recorded in the field report.

## 50. Automatic Scrobbling temporal authorization — 2026-08-30

Automatic Scrobbling is a temporal write boundary. A logical target keeps the
authorization generation in which it began across MediaSession recreation,
continuation, `TrackProgressCarry`, and foreground-Short interruption/resume.
The production finalization path must reject stale generations around
asynchronous work and, after eligibility plus the dedup claim, must atomically
order Auto mutation against commitment of the complete ordered payload batch.
OFF winning releases the claim and starts no signing-capable transport. Commit
winning transfers already-authorized transport and a later OFF cannot partly
cancel the batch. Manual finalization and already-serialized queue retry remain
outside this target-authorization decision.

### Automated matrix

| Case | Scenario | Required result |
| --- | --- | --- |
| A | Auto OFF, target begins, Auto ON, direct finalization | One ignored outcome; no broadcast |
| B | Auto OFF, continuation opens, Auto ON, continuation expires | Original disabled generation survives; no broadcast |
| C | Auto ON, target begins, Auto OFF before finalization | One ignored outcome; no broadcast |
| D | Auto ON, automatic finalization enters asynchronous work, Auto OFF before transport commitment | One ignored outcome; no posting-key use or broadcast; claim released |
| E | Auto OFF, old target ends, Auto ON, new target begins | New target is eligible and can reach the recording broadcaster |
| F | MediaSession/continuation carry | Original generation is preserved exactly |
| G | Suppressed stale target | Exactly one typed terminal outcome; no double finalization |
| H | Already-serialized retry, then Auto OFF | Retry remains transport-only and does not re-enter finalization |
| I | Final live check computes true, deterministic pause before commitment, Auto OFF, resume | OFF wins; no key load, broadcaster call or queue entry; claim released; one outcome |
| J | Transport commitment wins, deterministic pause, Auto OFF, resume | Complete committed batch proceeds deterministically; one outcome and retained claim |

`AutomaticScrobbleTemporalAuthorizationTest` owns A-J. Its I/J cases use
latches at the production policy/use-case boundary, never sleeps or network.
Also require:

- `SettingsTest` to prove idempotent assignments do not advance the generation
  and both real toggle directions do;
- `ForegroundShortAuthorizationTest` to prove a resumed Short keeps its stamp
  while a genuinely new Short samples the current generation; and
- `AutomaticScrobbleAuthorizationWiringTest` to prove both production playback
  producers receive the runtime stamp, the real setter and commit share one
  short policy monitor, commitment follows the claim and precedes `Eligible`
  and dispatch, and a miswired automatic dispatch is rejected before any
  signing-capable port.

### Physical acceptance plan — do not execute without separate authorization

1. Record branch/HEAD, the fresh local APK hash, installed `base.apk` hash,
   application ID/version, Automatic Scrobbling state, active MediaSessions,
   queue-file state, and established grants. Update-install only the exact
   reviewed APK and confirm local/installed whole-file hashes match.
2. With Automatic Scrobbling OFF, start the real Brave Short incident shape,
   let it enter a continuation, then turn Auto ON and allow natural expiry.
   Require one suppressed terminal result and no new Hive transaction.
3. With Auto ON, begin a fresh eligible target and use a non-signing diagnostic
   build or test seam to pause immediately before transport commitment. Turn
   Auto OFF and release the work. Require one ignored result, no claim, no key
   use, no queue entry and no Hive write. Do not try to manufacture this timing
   against production nodes with a real posting authority.
4. Begin a genuinely new target after re-enabling. Only with separate explicit
   Hive-write authorization, let it cross its real threshold and require the
   returned transaction plus identical irreversible operation/payload bytes
   from two independent Hive nodes.
5. Confirm playback totals, threshold behavior, continuation timing,
   exactly-once terminal behavior, queue contents, and unrelated source/security
   behavior remain unchanged. Restore only owner-requested UI state; do not
   alter grants, app data, accounts, PIN, cookies, or accessibility services.

Automated/replay proof does not establish device timing or an irreversible
transaction. Until the owner separately authorizes and completes this plan,
physical and Hive acceptance are **NOT ESTABLISHED**.
