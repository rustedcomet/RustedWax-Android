# How it works

RustedWax is a best-effort Android scrobbler. It observes supported playback,
measures played content, verifies the exact YouTube item, and signs eligible
Hive operations on the device with the user's posting key.

[← Back to the README](../../README.md)

## Sources and Notification Access

| Source | Base observation | Supporting evidence when available |
| --- | --- | --- |
| YouTube | Android media session | Native accessibility, playlist structure, watch history, public metadata |
| YouTube Music | Android media session | Watch history and structured music metadata |
| Brave or Chrome | Browser media session and media notification origin | Optional accessibility address bar and visible YouTube ad labels |

Android **Notification Access is a broad platform permission that can expose
notification content**. RustedWax uses it to read active media-session metadata
and playback state, plus supported browser media-notification fields needed
for source and ad evidence. Its posted-notification handler filters to supported
browser packages and media-session notifications; this is app-level
minimization, not a restriction on what Android grants.

The notification listener hosts observation and rechecks active sessions and
browser media notifications when it starts or reconnects. RustedWax does not
use a foreground service or persistent monitoring notification. Notification
Access does not guarantee that Android keeps the process alive or delivers
every callback. See [Known limitations](LIMITATIONS.md).

Unsupported packages cannot become supported sources by publishing similar
metadata. Optional access and fresh-install defaults are in [Setup](SETUP.md).

## Measuring a listen

RustedWax accumulates content played from observed position changes and
playback rate, then compares it with the verified content duration. Pauses,
seeks, interstitials, and uncorroborated gaps do not earn time. Playback before
RustedWax begins observing is not credited simply because the player reports
an advanced position.

Compatible session recreation or a brief source transition may continue one
logical listen. Observations remain bound to their source session and lifecycle
generation; stale callbacks cannot authorize a successor. Required identity,
duration, and presentation evidence must remain compatible before progress can
be carried. YouTube Music Music/Video switching can still interrupt continuity.

For native Shorts, optional YouTube-only accessibility reads the foreground
surface and progress when available. Picture-in-picture inference requires a
previously latched Short and continuing YouTube window/audio evidence. Inferred
time is bounded, stops when evidence is missing or contradictory, and cannot
be transferred to another track. Shorts scrobbling is off by default.

## Native watch-player ads

YouTube can publish a pre-roll using the upcoming video's metadata. The native
accessibility service checks the watch player for supported ad controls paired
with exact visible ad labels. Labelled intervals earn no playback time; a
presentation observed as an ad from its start earns none of that presentation's
time. This excludes the ad interval without vetoing the organic video that
follows in the same listen.

An absent ad label is evidence only when the full-size player is observable.
The current organic-presentation check requires three seconds of observed
unlabelled playback. An unreadable player is not proof that an ad ended; a
supported ad control on a minimized player still counts as positive evidence.
YouTube Music has no equivalent ad label that RustedWax can read. Duration and
presentation checks still apply; see [Known limitations](LIMITATIONS.md#ads-and-duration-churn).

## Verifying identity

A write requires one verified YouTube video ID and its canonical public link.
A browser URL must parse as HTTPS on an exact supported YouTube host and route,
with a valid item ID that agrees with other exact evidence. The optional browser
accessibility service is scoped to Brave and Chrome. Foreign origins,
contradictory IDs, and unresolved tab ambiguity cause refusal.

Native sessions often omit the ID. Recovery may use a uniquely matching
playlist entry, signed-in history row, public lookup corroborated by watch-page
facts, or structured YouTube Music metadata. Title similarity, uploader name,
length, or search rank alone is insufficient. Missing, ambiguous, or
contradictory identity produces no write. [Identity and verification](IDENTITY.md)
defines those boundaries.

The optional watch-history connection stores its session encrypted on the
device and sends session cookies only to the configured YouTube history route
over HTTPS. A route fault means history is not currently trustworthy; a
candidate miss means readable history did not identify this listen. Neither
permits a guessed ID.

## Metadata and classification

Verified video/work identity and artist quality are separate concerns.
YouTube Music's source-published artist and title are preserved as best-effort
metadata where appropriate, even if a canonical page names a different uploader.
MusicBrainz may enrich generic music metadata; it is not a required independent
performer check for every YouTube Music song. Incorrect artist metadata remains
possible.

Generic metadata that cannot establish a meaningful artist/title split can
produce a `video` entry instead of a `song`, preserving an otherwise eligible
verified listen. Classification and metadata cannot replace an unverified ID.
See the [behavior contract](BEHAVIOR_CONTRACT.md#identity-and-metadata) and
[on-chain format](ON_CHAIN_FORMAT.md) for the rules and field meanings.

## Finalization and delivery

When a listen ends or reaches a supported finalization boundary, the app checks
identity, duration, measured progress, classification, mute state, and
deduplication. Eligible authorized operations are serialized and signed locally.
The shared payload format uses the `hive_scrobble_ai` custom JSON identifier,
with `rustedwax/<version>` identifying this app.

Automatic transport persists the exact signed transaction before sending and
seeks independent block or mempool evidence. Acceptance without confirmation
is reported separately. Retry uses saved transport work, with bounded backoff
and fail-closed reconciliation; it does not guess whether a possibly accepted
transaction failed. Connectivity or app lifecycle events can resume due work
while Android lets the process run. Retry deadlines are not independent
background wakeups. See [Transport](BEHAVIOR_CONTRACT.md#transport).

**History** shows successful or accepted operations. **Not logged** explains
refusals for eligible user-facing targets, including unidentified items without
a guessed link. Unsupported or unproven-source sessions are omitted. These
bounded UI lists are not a complete record of everything played or every
callback Android might have missed.

Private mode is not exposed in current settings. Stored privacy preferences
and the compatible encrypted envelope remain implemented; see
[Private envelope compatibility](ON_CHAIN_FORMAT.md#private-envelope-compatibility).

## Stop and Automatic scrobbling

**Stop** ends observation, clears live evidence, and discards the current track.
**Automatic scrobbling** separately controls authorization: a listen first
observed while it was off cannot become writable by turning it on later.
Turning it off before transport commitment prevents that listen's automatic
write. Already committed batches and queued transport work remain authorized;
neither switch cancels work already handed to transport.
