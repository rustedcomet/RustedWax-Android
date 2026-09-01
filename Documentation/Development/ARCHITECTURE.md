# Architecture

RustedWax separates playback measurement, provider observation, identity,
policy, and Hive transport so each boundary can fail closed and be tested
without a device.

## Modules

```text
:core
:identity-api       -> :core
:youtube-identity   -> :core, :identity-api
:android-sources    -> :core, :identity-api
:hive               -> :core
:app                -> all lower modules
```

- `:core` contains provider-neutral playback and identity-domain types.
- `:identity-api` defines typed identity outcomes and strategy contracts.
- `:youtube-identity` implements YouTube-specific identity strategies.
- `:android-sources` contains Android source-adapter contracts.
- `:hive` builds, signs, broadcasts, and verifies Hive operations.
- `:app` owns Android wiring, UI, storage, enrichment, and production effects.

Lower modules must not depend on `:app`. Core APIs must not require a YouTube
video ID, URL, Android class, or UI concept.

## Playback ownership

`MediaSessionDriver` owns reducer state. Android callbacks are translated into
typed observations, then reduced deterministically. Bindings execute reducer
effects but do not maintain a second playback state machine.

Source-specific lifecycle behavior belongs in a `SourceAdapter` or an
application gateway with a capability that proves why the exception applies.
Adapters do not finalize, broadcast, mutate another adapter, or control shared
state directly.

## Identity

Provider identity is evaluated behind typed strategies. A strategy may confirm,
refuse, report ambiguity, or report contradiction. Ambiguity and contradiction
are terminal failures for a write; diagnostic text never controls the result.

Evidence is bound to the source session, logical track, and lifecycle
generation. Stale asynchronous work cannot modify a successor or a frozen
listen.

## Finalization and transport

Automatic, manual, and write-suppressed shadow triggers use the same
`FinalizeTrackUseCase` for identity, enrichment, eligibility, payload
construction, and terminal outcome recording.

Shadow execution cannot broadcast, queue, persist, retain a deduplication
claim, or write durable diagnostics. Queue retry accepts already serialized
transport work and must not rerun identity, rules, finalization, or payload
construction.

Every finalized target receives one typed terminal outcome.

## Extending a source

A new provider normally needs:

1. registration at the application composition edge;
2. an isolated adapter or gateway;
3. typed identity/enrichment behavior;
4. explicit capabilities;
5. synthetic fixtures and negative controls;
6. production-wiring and architecture tests.

Provider exceptions must not be added to the shared reducer merely because the
first provider needs them.
