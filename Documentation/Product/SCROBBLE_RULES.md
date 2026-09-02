# Scrobble rules

RustedWax turns a measured, verified logical listen into at most the operations
allowed by the rules below.

## Preconditions

An automatic write requires all of the following:

- monitoring is enabled;
- the source is supported and enabled;
- automatic scrobbling was enabled when the logical listen began;
- the same authorization generation commits the transport;
- one exact video identity is confirmed;
- no direct ad evidence applies;
- a usable duration is available;
- measured progress reaches the configured threshold;
- the item is not muted or already claimed; and
- a posting account and readable posting key are available.

Failure of any precondition produces no automatic write.

## Progress

The default threshold is 60%. The setting is constrained to the supported
range in the app.

```text
progress = credited content milliseconds / verified content duration
```

Credited time comes from observed position changes and playback rate. Paused
time, seeks, interstitial time, and uncorroborated gaps do not count.

Ordinary content has a 30-second hard duration floor. Verified Shorts use a
10-second hard floor when Shorts scrobbling is enabled. Content below its floor
cannot qualify regardless of percentage.

## Continuity

Compatible session recreation, picture-in-picture, and brief source handover
may preserve one logical listen. Continuity requires the same source-bound
identity and lifecycle generation. A successor item cannot inherit the
predecessor's progress.

Position reset is treated as a loop only when the reducer observes the required
end-to-start transition for the same logical content. A transport or track
change clears a provisional reset.

## Shorts

Shorts scrobbling is disabled by default.

When enabled:

- only a verified Short gets the 10-second floor;
- picture-in-picture inference requires prior foreground proof and paired
  continuing evidence;
- progress accumulated without a readable surface stays bounded;
- one continuous viewing produces at most one operation; and
- ambiguity, ad evidence, or a lost identity produces no write.

An ordinary `/watch` item does not become a Short merely because it is brief.

## Kinds and operation count

RustedWax classifies a confirmed payload as a supported content kind using
typed metadata and conservative rules.

- Non-song kinds are capped to one operation per logical viewing.
- Songs may produce a second percentage only after a genuine additional listen
  and only when no loop evidence applies.
- A verified Short stays capped to one operation even if its payload kind is
  `song`.

The deduplication ledger prevents concurrent or repeated finalization paths
from emitting the same logical claim.

## Identity and ads

A title, artist, duration, package, brand, or search rank cannot independently
authorize a write.

A literal ad observation vetoes the item. Unknown or changing duration alone
does not prove an ad; instead, the affected interval is quarantined while the
organic measurement remains separate.

## Automatic, manual, and shadow triggers

All triggers use the same identity, enrichment, policy, privacy, and payload
logic.

- **Automatic** requires a committed automatic-write authorization.
- **Manual** requires the user's explicit action and reports an immediate
  result; it does not silently become automatic retry work.
- **Shadow** is write-suppressed and cannot alter durable state.

## Privacy

If Private mode applies to the content category, the payload must be encrypted
before transport. Encryption failure is terminal and plaintext is never used
as a fallback.

## Retry

Retry stores the already serialized payload plus transport metadata. It does
not resolve identity, recalculate progress, rerun eligibility, or rebuild the
payload from a live session.

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

## Evidence

A unit or replay test can establish logic but not a physical or irreversible
chain outcome. End-to-end evidence requires the exact artifact, a real source
crossing its real threshold, a returned transaction, and independent
irreversible read-back. Such evidence must use an explicitly authorized test
account and must not be committed with private device/account details.
