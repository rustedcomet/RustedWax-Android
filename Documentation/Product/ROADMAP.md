# Planned, not yet implemented

Work that is designed but not built. Nothing here is in the app today.

[← Back to the README](../../README.md)

---

## Additional Android media sources — architecture-ready, not yet implemented

RustedWax is not intended to remain YouTube-only. Native Android support is planned for at least:

- **Spotify**;
- **Netflix**;
- **SoundCloud**.

These applications are roadmap commitments, not current support claims, and their implementation
order is not decided here. Each will require source-specific device research, identity rules,
replay fixtures, production lifecycle tests, and on-device verification.

Architecture Audit Phases 0–9 are complete. The accepted implementation in
[`<redacted-private-provenance>`](../Architecture/<redacted-private-provenance>) now has the
required source-neutral core. Adding one of these applications should mean
registering a source adapter, identity/enrichment strategies, capabilities, and
tests. It must not require rewriting playback measurement, finalization,
eligibility, deduplication, dispatch, or shared UI history.

The fake non-YouTube source already proves adapter → reducer → finalization →
recorded dispatch without provider branches in shared core. Every new provider
must keep that fixture and the Phase 8/9 architecture tests green. Implementation
should follow
[`Development/<redacted-private-provenance>`](../Development/<redacted-private-provenance>).

---

## Onboarding and language — planned, not yet implemented

`<redacted-private-path>` §7, and deliberately the last phase: onboarding instructions and translation
are release-facing work for a finished app, so the scrobbling behaviour was built and field-tested
first.

**First-run setup.** Android does not permit an app to grant itself Notification Access,
Accessibility or Usage Access — a disclaimer cannot enable them. The most any app may do is
deep-link to the exact system screen and detect the result on return, so that is the shape: a
disclaimer page, then one page per grant, with optional grants genuinely skippable. First install
only; anyone with a key already saved goes straight to the app.

**English and Spanish.** The UI labels are the easy half. The hard half is that several skip
reasons are long English strings assembled in Kotlin (`ScrobbleRules.progressSurfaceLostReason` and
neighbours) — they are user-facing, they are the best part of the UX, and they have to move to
string resources with placeholders before they can be translated. The event log stays English: it is
the primary debugging artifact and exported diagnostics should not vary by locale.

Two things are open before this can start: who supplies the Spanish translation, and whether the
disclaimer wording is drafted here or provided (`<redacted-private-path>` §9.1, §9.2).

---

## Guest accounts — blocked on an external dependency

Hive Scrobbler's guest path POSTs to `{origin}/api/ingest/scrobble` with a Bearer token that a
content script captures after the user signs in with Google on scrobble.life. **An Android app has
no content script and cannot obtain that token the same way.**

Building it by harvesting the token out of a WebView would be undocumented use of someone else's
server, so it is not being built. The decided path is to ask the scrobble.life maintainer for a
documented mobile auth flow first — a natural follow-up to the `app`-field conversation in
`<redacted-private-path>` §9.3.

Status: **pending that conversation.** Not to be implemented before it happens.

---

## Per-chapter scrobbling — parser built, the rest is not

Chapter parsing landed in v0.10.0 and is recorded in the event log; the video still scrobbles once,
as it always has. Nothing on chain can be amended, so a chapter list read after the fact cannot
retroactively split an entry — the near-term value is diagnostic.

The remaining work is the risky part, and all of it touches code that several field rounds have been
spent hardening: per-chapter play measurement from seekbar position, per-chapter dedup keys, and N
transactions from one video.

---

## Shipped since this file last listed it

- **Privacy mode** — shipped in v0.10.0. Per-kind toggles, the extension's exact envelope, and a
  key derived from the locally held posting key with no prompt. See
  [ON_CHAIN_FORMAT.md](ON_CHAIN_FORMAT.md#private-scrobbles).
- **Films and episodes** — classified since v0.10.0. Classification only: `imdb_id`,
  `wikipedia_url`, `series_*` and `poster_url` stay absent, because the extension fills them from a
  page DOM and a Wikidata lookup this app has no equivalent for.
- **An app list instead of per-app switches** — v0.10.0.
