# RustedWax behavior contract

This document defines intended current scrobbling behavior. History Snaps,
replies, and Likes are separate explicit social actions; see
[How it works](HOW_IT_WORKS.md#history-snaps-and-conversations). Shipping source
and executable tests remain authoritative for a particular checkout.

## Safety priorities

1. Never broadcast without an explicit current authorization.
2. Never publish a listen governed by a stored privacy preference as plaintext.
3. Never guess a video identity.
4. Never count playback that lacks the required source evidence.
5. Never emit more than one terminal outcome for one finalized target.

When availability conflicts with these rules, RustedWax refuses or defers the
operation.

## Observation

- Monitoring and automatic scrobbling are separate switches.
- Monitoring off means supported media is not processed.
- Automatic scrobbling is off until the user opts in.
- An automatic listen is stamped with the authorization generation active when
  the logical listen starts. A stale or disabled generation cannot authorize a
  later automatic transport commit.
- Provider and accessibility evidence is accepted only from configured package
  boundaries.

## Playback measurement

- Played time is derived from observed position changes and playback rate.
- Paused, stopped, seeking, missing, or contradictory intervals are not
  credited unless a narrowly defined source capability supplies corroborating
  evidence.
- Session recreation, picture-in-picture, and brief source transitions may
  continue one logical listen only when identity and lifecycle evidence remain
  compatible.
- Material duration replacement is quarantined until the reducer can determine
  whether it belongs to the content or an interstitial.
- Attributed organic progress may survive an interstitial when continuity
  evidence remains valid; interstitial time is not credited. A later identity
  match cannot retroactively authorize unrelated provisional progress.
- A logical track is finalized once.

## Identity and metadata

- A Hive write requires one verified provider item identity and a canonical
  link.
- A direct browser URL is authority only after structural HTTPS parsing, an
  exact supported host and route, and an exact item identifier.
- Native YouTube identity may use playlist structure, signed-in watch history,
  public search, watch-page data, or music-catalog evidence. Each route must
  independently satisfy its typed contract.
- Multiple plausible candidates are ambiguous and produce no write.
- Contradictory exact evidence produces no write.
- A track-bound literal ad observation is a veto. Native watch-player ad
  labels exclude the labelled presentation interval without vetoing the organic
  video that follows. Title, brand, duration, popularity, or package alone is
  not ad authority.
- Metadata may refine a confirmed identity; it cannot replace it with a
  different item.

RustedWax is a best-effort mechanical scrobbler. Verified video/work identity
and best-effort performer metadata are separate concerns:

- Exact video ID, canonical URL, and the route's work, duration, and presentation
  checks remain mandatory. Artist metadata alone cannot authorize a write.
- Preserve YouTube Music's source-published artist/title where appropriate.
  Incorrect or incomplete artist credits remain possible after identity is
  verified.
- Do not require independent MusicBrainz or canonical-performer corroboration
  merely because YouTube Music metadata is imperfect. For a verified music-video
  row, the uploader/distributor is not performer authority and cannot alone
  authorize or veto the item. Optional enrichment must not become a universal
  performer gate.
- A generic source without a meaningful artist/title split may degrade from
  `song` to `video` while retaining an otherwise eligible verified listen.

See [Identity](IDENTITY.md) for route constraints and
[On-chain format](ON_CHAIN_FORMAT.md) for credit and kind semantics.

## Eligibility

An automatic write requires monitoring and the supported source to be enabled,
a current authorization from the listen's start through transport commitment,
one confirmed exact item, usable title and duration, sufficient credited
progress, no applicable ad veto, no mute or existing deduplication claim, and an
available posting account/key.

- Progress is credited content milliseconds divided by verified duration.
  The configured threshold defaults to 60% and is constrained to the supported
  settings range. Seeks, pauses, and uncorroborated gaps do not count.
- Ordinary content needs a duration of at least 30 seconds.
- Shorts scrobbling is disabled by default. When enabled, a Short must resolve
  to a verified public/listed item; an unlisted or unresolved Shorts page, or
  missing listing status, is refused. Only a verified Short gets the 10-second
  duration floor. A brief ordinary watch video is not automatically a Short.
- Shorts picture-in-picture time requires prior foreground proof and paired
  continuing evidence. Time without a readable surface stays bounded.
- A Short remains capped to one operation for one continuous viewing.
- Songs may produce at most two operations: the second requires another full
  duration plus the configured threshold, corroborated position, and no loop
  evidence. At the default threshold this is 160%. Non-song kinds remain
  capped to one operation, using the same configured threshold.
- End-to-start loop evidence caps the viewing to one operation without erasing
  an earned first viewing. A transport or track change clears provisional reset
  evidence; a successor cannot inherit the predecessor's progress.
- Muted items, applicable ad vetoes, missing duration, insufficient progress,
  and unverifiable identities are refused. The deduplication ledger prevents
  competing finalization paths from emitting the same logical claim.

## Finalization

The implementation supports automatic, manual, and shadow triggers through the
same finalization use case. They share identity, enrichment, classification,
eligibility, payload, and deduplication rules. The shipping UI publishes
scrobbles through automatic playback finalization; the connection check is
read-only.

- Automatic dispatch requires a committed automatic-write authorization.
- Manual dispatch requires an explicit separate action and reports its result;
  it does not silently become automatic retry work.
- Shadow dispatch executes decision logic without any external or durable
  effect.
- Exactly one typed terminal outcome is recorded for each finalized target.

## Privacy

- Only a Hive posting key is accepted for posting operations.
- Posting material and the optional YouTube session are stored using Android
  encrypted storage.
- Private mode is not exposed in current settings. Stored per-category
  preferences remain implemented and default off. If a stored preference
  applies, the payload must be encrypted before broadcast; construction failure
  sends nothing, with no plaintext fallback. See
  [Private envelope compatibility](ON_CHAIN_FORMAT.md#private-envelope-compatibility).
- Secrets, raw cookies, and posting keys never belong in diagnostics.
- Event logging is optional, bounded, and erased when disabled.

## Transport

Payload construction occurs once during finalization. Retry stores the already
serialized payload plus transport metadata. It does not resolve identity,
recalculate progress, rerun eligibility, or rebuild the payload from a live
session.

- Permanent rejection is removed from retry.
- Network failure remains eligible for bounded backoff.
- Returning usable connectivity retries entries whose backoff has already
  elapsed, without requiring the user to open the app. It selects nothing that
  the backoff does not already consider due and does not extend the attempt
  ceiling.
- That retry is a process-lifetime observation, not a scheduled background job.
  It runs while the application process is runnable, and may be delivered once a
  suspended process is allowed to run again. A retry deadline that elapses while
  connectivity is unchanged is not woken independently; it waits for the next
  connectivity change or application lifecycle event. The entry remains
  persisted meanwhile.
- Before any automatic broadcast, the queue atomically records a stable operation
  id and the exact signed transaction as `IN_FLIGHT`. If that write fails,
  nothing is broadcast.
- An `IN_FLIGHT` operation is reconciled by transaction id and its saved
  expiration after restart. Known block or mempool evidence settles it;
  unavailable evidence leaves it waiting fail-closed. Independent
  `expired_irreversible` responses are required to prove absence. Bare
  `unknown`, `too_old`, `expired_reversible`, malformed, mixed, or unavailable
  responses cannot authorize replacement. Only proven absence permits an
  expired transaction to be replaced, and the replacement is persisted before
  network I/O.
- Settlement and cleanup failures leave either durable `SETTLED` state or the
  exact durable `IN_FLIGHT` transaction. Neither state can become an ordinary
  payload eligible to be signed blindly, and live state is not age/count pruned.
- Accepted but confirmation-unavailable is treated as potentially successful
  and is not retried.
- If independent status remains unavailable, the operation can remain waiting
  indefinitely rather than risk a newly signed duplicate.

A result records the returned transaction identifier and the strongest
available block or mempool evidence; acceptance without independent
confirmation is distinguished from block confirmation.

## UI outcomes

- **Now** describes the currently observed logical listen.
- **History** contains successful or accepted operations with a verified link.
- **History** and **Not logged** are scoped to the connected Hive account;
  History cards no longer display transaction IDs in normal presentation.
- **Not logged** contains one understandable terminal refusal for an eligible
  user-facing target. A verified link is a requirement of broadcasting, not of
  recording a refusal: a refused target whose exact item was never proven is
  still listed, without a link, and no identifier is inferred to supply one.
- These bounded lists are not a complete playback record. Unsupported or
  unproven-source sessions are omitted, and Android can miss observations.
- Diagnostic prose explains a typed decision but never controls it.

## Evidence

Unit, replay, production-wiring, local-artifact, physical-device, and
irreversible-chain evidence are distinct. A stronger claim requires the
corresponding evidence class; one cannot be substituted for another.
