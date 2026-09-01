# RustedWax documentation

This directory is the single home for RustedWax product documentation,
engineering contracts, test evidence, field records, plans, and historical
reports. The repository root intentionally keeps only the project
[`README.md`](../README.md); legal and build files remain at the root because
they are not project documentation.

## Start here

| Need | Authoritative starting point |
| --- | --- |
| What RustedWax is | [Project README](../README.md) |
| Intended current behavior | [Behavior contract](Product/BEHAVIOR_CONTRACT.md) |
| How playback becomes a Hive entry | [How it works](Product/HOW_IT_WORKS.md) |
| Identity and fail-closed rules | [Identity contract](Product/IDENTITY.md) |
| What qualifies for scrobbling | [Scrobble rules](Product/SCROBBLE_RULES.md) |
| How to run and interpret verification | [Testing](Testing/TESTING.md) |
| How an AI agent should implement features | [AI feature workflow](Development/<redacted-private-provenance>) |
| Latest independent feature audit | [Claude feature architecture audit — 2026-08-25](Reports/<redacted-private-provenance>) |
| Latest consolidated engineering record | [Phase 9 compatibility-removal report](Reports/<redacted-private-provenance>) |
| Latest feature record | [Now-card simplification and developer mode](Reports/<redacted-private-provenance>) |
| Prior feature record | [UI, settings and log-retention report](Reports/<redacted-private-provenance>) |
| Latest field session | [2026-08-26 app lockup](Field-Reports/<redacted-private-provenance>) |
| Prior field session | [2026-08-25 Now card and developer mode](Field-Reports/<redacted-private-provenance>) |
| Planned product expansion | [Product roadmap](Product/ROADMAP.md) |

## Authority and interpretation

These files serve different purposes. They must not be treated as equally
current simply because they are all documentation.

1. Shipping source and generated test/device evidence establish what a specific
   tree or APK actually does.
2. `Product/BEHAVIOR_CONTRACT.md` defines intended product behavior.
3. The focused Product documents explain the current public and engineering
   contract for their named topic.
4. `CLAUDE.md`, `.claude/rules/`, and `Development/` define how coding work is
   performed and reported. They do not override Product behavior contracts.
5. Field reports preserve measured evidence, failures, fixes, and immutable
   transactions for the date and artifact they name.
6. Reports record an audit verdict for an exact tree or artifact. A later source
   change makes its hashes and authorization historical unless a newer report
   explicitly re-establishes them.
7. Plans describe required work and acceptance gates; they are not proof that
   the work passed.
8. Phase documents are chronological design records. They do not override the
   current behavior contract.

When two documents appear to disagree, first compare their dates, named commit
or APK hash, evidence class, and explicit supersession notices. Preserve an old
measurement; correct only a claim that incorrectly presents it as current.

## Directory map

### `Product/`

Current user-facing and normative engineering behavior:

- [Overview](Product/OVERVIEW.md)
- [Setup](Product/SETUP.md)
- [How it works](Product/HOW_IT_WORKS.md)
- [Detection sources](Product/DETECTION.md)
- [Identity contract](Product/IDENTITY.md)
- [Scrobble rules](Product/SCROBBLE_RULES.md)
- [On-chain format](Product/ON_CHAIN_FORMAT.md)
- [Limitations](Product/LIMITATIONS.md)
- [Roadmap](Product/ROADMAP.md)
- [Behavior contract](Product/BEHAVIOR_CONTRACT.md)

### `Architecture/`

The architecture audit and chronological phase records. The phase files explain
why boundaries were introduced and what each migration step was intended to
preserve; they are not a substitute for current product contracts.

- [Architecture audit and migration plan](Architecture/<redacted-private-provenance>)
- [Post-fix architecture conformance review — 2026-08-28](Architecture/<redacted-private-provenance>)
- [Phase records](Architecture/Phases/)

### `Testing/`

Test execution, evidence classification, replay provenance, and acceptance
gates:

- [Testing RustedWax](Testing/TESTING.md)
- [Replay trace provenance](Testing/<redacted-private-provenance>)

### `Development/`

Current contributor and coding-agent workflow:

- [AI-assisted feature implementation workflow](Development/<redacted-private-provenance>)
- repository-wide Claude instructions live in [`CLAUDE.md`](../CLAUDE.md);
- path-scoped executable-work rules live in [`.claude/rules/`](../.claude/rules/).

### `Field-Reports/`

Dated device investigations. Each report records the reported symptom, actual
production cause, rejected hypotheses, implementation boundary, regressions,
physical evidence, immutable Hive transactions where authorized, and remaining
risks. [The 2026-08-20 report](Field-Reports/<redacted-private-provenance>)
is the earlier consolidated pre-Phase-5 handoff. The latest field session is
[the 2026-08-26 app-lockup report](Field-Reports/<redacted-private-provenance>);
the ones before it are
[the 2026-08-25 Now-card and developer-mode report](Field-Reports/<redacted-private-provenance>)
and [the 2026-08-25 Shorts-title report](Field-Reports/<redacted-private-provenance>).

### `Plans/`

Historical and proposed forward-work contracts, including the development
history, watch-history breaker contract, and pre-Phase-5 gates. Architecture
Phases 0–9 are complete; no plan in this directory is an active architecture
phase merely because it exists. A plan's presence does not mean its gate passed.

### `Reports/`

Independent audits and closure reports tied to the exact commits and APK hashes
inside each report. Read any current-state notice before relying on a verdict.

### `History/`

- [Field result summary](History/<redacted-private-provenance>)
- [Release and specification history](History/<redacted-private-provenance>)

### `Assets/`

The logo and screenshots referenced by the root README and the static project
page. `index.html` and `.nojekyll` remain at the `Documentation/` root so the
static page and its assets stay together. GitHub Pages publishes this directory
through [the Pages workflow](../.github/workflows/pages.yml); the legacy branch
publisher supports only `/` or `/docs` and must not be pointed at the removed
`/docs` path.

## Current checkpoint: 2026-08-26 (round 4)

The remaining lag was a false deferral in the Compose wiring. `Wired` collected
the event log and Shorts status with delegated `by`, so it still read every new
value in the parent and each lambda captured the replacement value. Live
MediaSession snapshots also still crossed into `MainScreen` by value once a
second. In the exact reported arrangement — YouTube Music playing, Chrome on
scrobble.life, RustedWax Settings foreground, Developer Mode and event logging
on — the installed pre-fix APK continuously rendered 181 frames in the sample,
177 janky and 171 slow on the UI thread.

The changing values now remain behind stable Compose State holders and are read
only by the Log page, Shorts row and Now list; the tab strip receives only a
stable session count. The exact fixed APK rendered only three post-navigation
frames in a 30-second idle Settings interval, while Now advanced 50% to 53% in
three seconds and seven version taps immediately restored Developer Mode.

Local and independent no-hardlinks-copy gates each parse 144 suites / 1,476
tests with no failures, errors or skips and zero lint errors. Their main and
test APKs are byte-identical at SHA-256
`99f9a40ef90a013ca4ffdf2692926c4727a0adbc770a560463a54cb5ad9e35bd`
and `c1c4570dd74ed3f1127794422d73ff5b0734ed256b92210ab5240fcbf2df165a`.
The installed A36 main APK matches. With automatic scrobbling on, YouTube Music
played `Trust` by Buju Banton for 202/202 seconds and returned transaction
`5ae9e3eefba4bfbcc5d2dc70154101f8e150b5b3`; `api.deathwing.me` and
`api.openhive.network` returned identical operation and payload bytes from
irreversible block 109378999. A full-day soak remains **NOT ESTABLISHED**.

## Prior checkpoint: 2026-08-26 (round 2)

A second field round on the same day found two main-thread costs on the
once-a-second status poll, reported as RustedWax lagging while YouTube Music
played with Chrome open. A live `SessionProbe` snapshot was rendering the
diagnostic metadata dump that only a *finalized* snapshot consumes — 1,532
`W/Bundle` stack traces in twelve seconds, 99% of everything the process logged
— and `YouTubeSessionVault.session` was decrypting the stored session against
the Android Keystore every second, 58.88% of the poll coroutine by `simpleperf`.
Both are repaired; the vault caches only the display label and timestamps and
never the cookie.

Background main-thread CPU with YouTube Music playing fell from 132 to 65–70
jiffies per 30 seconds on alternating installs. Foreground frame timing improved
from roughly 2.2% to 1.6% janky frames — a real but modest gain, and the larger
differential quoted mid-investigation was withdrawn as JIT-warm-up-confounded.

The gate parses 143 suites / 1,471 tests with zero failures, errors or skips and
zero lint errors. Workspace, rebuild and installed A36 APKs are byte-identical at
SHA-256
`841a1ddc0c09138f5ef3698d901b12dfb07d1f5c719b80eb62e01559d72882cc`. With
automatic scrobbling on, that artifact scrobbled a real **YouTube Music** listen
at 100% and returned transaction
`8ef5bcec3b1ce8b67c71d770a984ca4b2f0d34d5`; `api.deathwing.me` and
`api.openhive.network` returned identical operation bytes from irreversible
block `109375691`. Native YouTube re-confirmed on the same bytes as
`4a38dfe58e844cc879073674602d6d16ba12a419` and
`79b754fd22d9fabfbf1daf8ea1fde556853ca243`. That closes the YouTube Music gate
round 1 left open.

A 45-minute soak in the reported arrangement — YouTube Music playing, Chrome
open, RustedWax foreground — found main-thread cost flat (10 post-startup
samples, mean 101.3 jiffies/30 s, its minimum at minute 41), RSS oscillating
without growth, and the event log orbiting its 384 KiB prune target. A static
review found every in-memory store bounded. That excludes a leak; it does not
cover a full day.

Foreground Shorts and the browser path remain **NOT ESTABLISHED** on these bytes,
as does the reported degradation across a whole day. Full evidence and two recorded
evidence corrections:
[2026-08-26 app lockup](Field-Reports/<redacted-private-provenance>).

## Prior checkpoint: 2026-08-26 (round 1)

The 2026-08-25 log-retention bound shipped a degenerate steady state: pruning
trimmed to exactly its 512 KiB ceiling, so the ceiling re-armed on every append
and a whole-file rewrite ran per logged line, on the main thread the UI and the
detection services share. Measured on the A36 at 524,263 bytes across 4,497
lines: **302 skipped frames** — five seconds of frozen UI — at playback start,
and none at all once the retained window was emptied. A prune now reclaims to
384 KiB, so the documented 200-line cadence governs; the 12-hour and 512 KiB
promises are unchanged.

The gate parses 140 suites / 1,456 tests with zero failures, errors or skips —
the 2026-08-25 total plus this task's four tests — and zero lint errors.
Workspace, gate, independent no-hardlinks-copy and installed A36 APKs are
byte-identical at SHA-256
`3343043585f1a599a9fb34d3f3e45efe16024c4ea9e25753b473de8a2f9e161f`. With
automatic scrobbling on, that exact artifact absorbed 55 log lines in one second
with **zero** dropped frames and wrote transaction
`46ad2ab16eb255205212758e3cee0c864398cd1f`; `api.deathwing.me` and
`api.openhive.network` returned identical operation bytes from irreversible block
`109353386`.

YouTube Music, foreground Shorts and the browser path remain explicitly **NOT
ESTABLISHED**: an authorized update-install caused Android to drop both
accessibility grants, and they were not re-granted from the shell. Full evidence
and the owner action required:
[2026-08-26 app lockup](Field-Reports/<redacted-private-provenance>).

## Prior checkpoint: 2026-08-25

The combined Shorts-title, UI/settings/log, and Now-card/developer-mode feature
tree passed an independent architecture audit after four latent contract
violations were repaired. Final workspace and no-hardlinks-copy gates each
parsed 140 suites / 1,452 tests with zero failures, errors or skips and zero lint
errors. Workspace, independent and installed app APKs are byte-identical at
SHA-256 `6fbd5438ff7913d087ca4f36cf43e35bb3d4e25b3b1383c6fb1f589a0e55c057`.

With automatic scrobbling on, the exact installed artifact exercised a native
seekbar-less Short and produced transaction
`641d7bdb0c6960e1012dc5f637c8f658464d3462`. `api.deathwing.me` and
`api.openhive.network` returned identical operation and payload bytes from
irreversible block `109351572`. The exact physical repeated-title-blink timing
condition was not provoked and remains explicitly **NOT ESTABLISHED**; its
production-boundary regression is green. Full evidence:
[Claude feature architecture audit — 2026-08-25](Reports/<redacted-private-provenance>).

## Prior checkpoint: 2026-08-24

Architecture Audit Phases 0–9 are complete. Phase 9 is accepted under the
amended, non-fabricated corpus gate. Six Gradle modules enforce the acyclic
dependency map; architecture tests enforce platform, adapter, identity,
storage, source-neutral API, reducer-ownership, direct-finalization, and
compatibility-removal boundaries.

- Local and final no-hardlinks-copy Android gates: 128 app suites / 1,288 tests,
  zero failures, errors, or skips. The explicit Hive gate adds 3 suites / 38
  tests, also green. Lint has zero errors, 38 warnings, and 2 informational
  findings.
- Local, detached, and installed main APKs are byte-identical at SHA-256
  `addc0895d0bf3c722f6651ef2306104ecd14e7427997b95af8cc39c0ff3245f3`.
- With automatic scrobbling on, the exact artifact produced native YouTube
  transaction `9e311a73e31df0831a9123704fce590d522e40c8` and Brave transaction
  `5db7763f9eaea8014626a10fdeaf259d669429ce`; both were read identically from two
  Hive nodes after irreversibility.
- The unrecoverable historical 189 feed rows, 177 unpublished rows of the named
  180-entry playlist, and a fresh Phase 9 physical Shorts/PiP run remain
  explicitly **NOT ESTABLISHED** rather than being fabricated or inferred.

The complete evidence and remaining boundaries are in the
[Phase 9 report](Reports/<redacted-private-provenance>). New work now
follows the [AI feature workflow](Development/<redacted-private-provenance>); there is
no active Phase 10.

## Prior checkpoint: 2026-08-21

The mixed pre-Phase-5 tree now includes the device-proven YouTube Music
Song-mode art-track repair and its corrected independent closure.

- Local and no-hardlinks detached-clone gates: 120 suites, 1,251 tests, zero
  failures, errors, or skips; lint has zero errors.
- Local and clone app APKs are byte-identical at SHA-256
  `037f2922e5be7f3609bbda0c33d0c3c71e0e73629c9470dd1c8ff4cd7d4f3034`;
  the update-installed Galaxy A36 `base.apk` has the same hash.
- Automatic scrobbling remained on for two exact-artifact Song-mode runs:
  `Party` produced transaction `2d9d2106e9ba654e4a2ffe2e82b107dda3120b20`,
  and `Diamonds and Gold` from album `Safe` produced transaction
  `5a4cd0a5d99f592f09aceb449ee1000a090c24b0`.
- The Music catalog and music-client-only finalization exceptions remain
  package-gated to YouTube Music; ordinary YouTube/browser/Shorts/PiP behavior
  remains behind its existing adapters and green Phase 1–4 suites.

The corrected closure report records **Safe for Phase 5: YES**. Phase 5 itself
has not begun.

## Historical checkpoint: 2026-08-20

The latest unstaged source tree includes the pre-Phase-5 A-D remediation,
YouTube Music cache-only preview correction, and the earned-auto-scrobble
continuation fix.

- The behavior-fix tree passed local and independent no-hardlinks clone gates:
  118 suites, 1,232 tests, zero failures, errors, or skips.
- Lint: zero errors, 35 warnings, and 2 informational findings.
- Physically tested behavior-fix main APK SHA-256:
  `dca2c02eb141a36ce1258e9be4981e593289c6933c6aad116bfcc97956ed2908`.
- Test APK SHA-256:
  `4e2769a304a19ded570a2bdd08a567ac59c11f0a818b0216c3cec8bec075f983`.
- Local and independent-clone behavior-fix APKs were byte-identical; the
  installed A36 main APK was pulled back and matched `dca2c0…`.
- A real minimized YouTube Music listen finalized after the corrected 60-second
  replacement grace at 68%, wrote transaction
  `7f50aba481269dd5e6d139ffb77b6763b22772de`, and appeared confirmed in History.
  Two Hive RPC nodes independently returned the same operation in block
  `109192394`.

The documentation consolidation then changed only documentation, executable
documentation readers, and source/test comments that name document paths. Its
fresh full gate remains 118 suites / 1,232 tests with the same lint totals, and
the rebuilt current main APK is
`8d25819d30d566d9c256d0fcb94788eacd17bc8a27ffd96244c2d7c619b15a64`.
The strict final audit rebuilt it in a genuine `--no-local --no-hardlinks`
detached clone, proved local/clone whole-file equality, update-installed it on
the A36 and pulled it back byte-identical. The unchanged test APK still has the
hash above and also matches across all three copies.

The required exact-byte ordinary-replacement and package-isolation smoke also
passed with automatic scrobbling off. The closure report therefore records
**Safe for Phase 5: YES**. Phase 5 itself has not begun and still requires
separate owner authorization.

## Documentation maintenance rule

New documentation belongs in the narrowest directory above, not in the
repository root. A useful engineering record must identify:

- the reported symptom and user impact;
- the production root cause and why earlier coverage missed it;
- the owner and invariant of the correction;
- red-first and final regression evidence;
- modeled, production-path, physical, and unavailable evidence separately;
- exact hashes, transaction IDs, and device facts when they are acceptance
  gates;
- remaining risk, superseded claims, and the next authorized boundary.

Scratch notes without ownership, evidence, or project direction should not be
promoted into the permanent documentation set.
