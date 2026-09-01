# RustedWax behavior contract

This document defines intended current behavior. Shipping source and executable
tests remain authoritative for a particular checkout.

## Safety priorities

1. Never broadcast without an explicit current authorization.
2. Never publish a private-mode listen as plaintext.
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
- Organic progress earned before an interstitial is preserved; interstitial
  time is not credited.
- A logical track is finalized once.

## Identity

- A Hive write requires one verified provider item identity and a canonical
  link.
- A direct browser URL is authority only after structural HTTPS parsing, an
  exact supported host and route, and an exact item identifier.
- Native YouTube identity may use playlist structure, signed-in watch history,
  public search, watch-page data, or music-catalog evidence. Each route must
  independently satisfy its typed contract.
- Multiple plausible candidates are ambiguous and produce no write.
- Contradictory exact evidence produces no write.
- A literal observed ad is a veto. Title, brand, duration, popularity, or
  package alone is not ad authority.
- Metadata may refine a confirmed identity; it cannot replace it with a
  different item.

## Eligibility

- The configured progress threshold defaults to 60% and is constrained to the
  supported settings range.
- Ordinary content needs a verified duration of at least 30 seconds.
- Verified Shorts use a 10-second hard floor when Shorts scrobbling is enabled.
- A Short remains capped to one operation for one continuous viewing.
- Songs may produce a second percentage only for a genuine additional listen
  with no loop evidence; non-song kinds remain capped to one operation.
- Muted items, explicit ads, missing required duration, insufficient progress,
  and unverifiable identities are refused.

## Finalization

Automatic, manual, and shadow triggers enter the same finalization use case.
They share identity, enrichment, classification, eligibility, payload, and
deduplication rules.

- Automatic dispatch requires a committed automatic-write authorization.
- Manual dispatch is an explicit separate user action.
- Shadow dispatch executes decision logic without any external or durable
  effect.
- Exactly one typed terminal outcome is recorded for each finalized target.

## Privacy

- Only a Hive posting key is accepted for posting operations.
- Posting material and the optional YouTube session are stored using Android
  encrypted storage.
- Private categories are serialized into an encrypted envelope before
  broadcast.
- If private-envelope construction fails, nothing is sent.
- Secrets, raw cookies, and posting keys never belong in diagnostics.
- Event logging is optional, bounded, and erased when disabled.

## Transport

- Payload construction occurs once during finalization.
- Retry stores serialized transport work and never re-enters identity,
  eligibility, finalization, or payload construction.
- Permanent chain rejection is not retried.
- An accepted transaction that cannot be independently queried is not retried
  merely because confirmation is unavailable.
- A successful result records the returned transaction identifier and the
  strongest available block or mempool evidence.

The rare accepted-but-independently-not-found state remains a known transport
limitation described in [SECURITY.md](../../SECURITY.md).

## UI outcomes

- **Now** describes the currently observed logical listen.
- **History** contains successful or accepted operations with a verified link.
- **Not logged** contains one understandable terminal refusal for an eligible
  user-facing target.
- Diagnostic prose explains a typed decision but never controls it.

## Evidence

Unit, replay, production-wiring, local-artifact, physical-device, and
irreversible-chain evidence are distinct. A stronger claim requires the
corresponding evidence class; one cannot be substituted for another.
