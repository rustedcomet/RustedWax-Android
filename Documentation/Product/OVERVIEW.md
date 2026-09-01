# Why RustedWax exists

The problem it solves and the shape of the solution.

[← Back to the README](../../README.md)

---

## Why it exists

Brave on Android supports no extensions, so the desktop flow — content script → Hive Keychain →
`custom_json` — cannot run on a phone at all. RustedWax replaces the two pieces that depended on the
browser being a desktop browser:

| Desktop extension | RustedWax |
| --- | --- |
| Per-site DOM connectors | Android `MediaSessionManager` — one universal source |
| Hive Keychain signs the transaction | The app signs locally with your posting key |

The on-chain format, the scrobble thresholds and the dedup rules follow the established ones.

> **Not byte-identical to desktop any more.** Phase 4 normalizes titles to the original recording —
> `(Live)`, `(Instrumental)` and `【Guitar Cover】` are stripped, so a cover lands on the same entry
> as the studio track instead of scattering play counts. The extension keeps those markers. Same
> schema, same id, same rules; the title field can differ. See [PHASE4.md](../Architecture/Phases/PHASE4.md) decision D7.

> **Default scope: YouTube in Brave and Chrome.** v0.9 can additionally read the exact native
> YouTube or YouTube Music package after its separate opt-in is enabled; both default off. A browser
> media session still has to prove the site. A native package proves only its origin, not a specific
> video, so no native entry is broadcast without a verified id and canonical link. Everything else
> is skipped rather than guessed.

---
