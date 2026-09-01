# Roadmap

These are possible future directions, not current support claims or release
commitments. Current behavior is documented in the other product guides.

[← Back to the README](../../README.md)

---

## Additional Android media sources

The provider-neutral core can support additional source adapters. Candidates
include:

- **Spotify**;
- **Netflix**;
- **SoundCloud**.

No implementation order is committed. Each source needs its own identity rules,
synthetic replay fixtures, production lifecycle tests, and device validation.

The source-neutral core is in place. Adding a provider should mean registering
source-specific adapters, identity and enrichment strategies, capabilities, and
tests. It must not require rewriting playback measurement, finalization,
eligibility, deduplication, dispatch, or shared UI history. See the public
[architecture overview](../Development/ARCHITECTURE.md).

---

## Onboarding and language

Future onboarding may guide users through Android's Notification Access,
Accessibility, and Usage Access screens while keeping optional grants skippable.
Localization requires moving remaining user-facing diagnostic strings into
Android resources before translations are added.

---

## Guest accounts

Guest-account support depends on a documented mobile authentication and ingest
contract from the service operator. RustedWax will not extract web-session
tokens or depend on an undocumented private endpoint to provide it.

---

## Per-chapter scrobbling

The current app can parse chapter metadata for diagnostics, but one video still
produces one logical viewing. Per-chapter writes would require independent
chapter progress, identity, deduplication, and multi-operation policy before
they could be offered safely.
