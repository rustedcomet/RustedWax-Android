# Detection sources

RustedWax observes Android media sessions and adds provider-specific evidence
only where the platform does not expose enough information to identify or
measure the item safely.

## Supported sources

| Source | Base observation | Optional supporting evidence |
| --- | --- | --- |
| YouTube | Android media session | Native accessibility, playlist structure, watch history, public metadata |
| YouTube Music | Android media session | Watch history and public music metadata |
| Brave | Browser media session | Accessibility-scoped address bar and explicit visible ad labels |
| Chrome | Browser media session | Accessibility-scoped address bar and explicit visible ad labels |

Unsupported packages cannot opt themselves into a supported source merely by
publishing similar metadata.

## Notification access

Notification Access lets RustedWax enumerate active media sessions and read
their playback state, position, duration, title, artist, and source package
where Android supplies them. It does not grant access to notification bodies
for arbitrary app behavior.

## Browser evidence

The optional browser accessibility service is restricted by Android to the
configured Brave and Chrome packages. It reads the address bar and the narrow
visible labels needed for explicit YouTube ad evidence.

A browser URL is accepted only when:

- it parses structurally;
- the scheme is HTTPS;
- the host is an exact supported YouTube host;
- the route is a supported watch or Shorts route; and
- the item identifier has the exact expected form.

Foreign notification origin, contradictory item identifiers, or ambiguous
browser tabs cause refusal.

## Native Shorts

The native Shorts accessibility service is restricted to the YouTube package.
It observes the foreground Shorts surface, including title/owner presentation
and visible progress when YouTube exposes them.

Picture-in-picture inference requires a previously latched Short plus paired
evidence that YouTube still has the relevant visible window and media audio.
Inferred time is bounded and cannot be transferred to a different logical
track.

## Watch history

The optional YouTube connection can read the signed-in account's history feed
to identify native playback that lacks a direct item ID. The app reads only the
configured YouTube history route and sends the encrypted session only to
YouTube.

Route health and candidate matching are separate:

- a route fault means the feed cannot currently be trusted;
- a candidate miss means the feed was readable but did not identify this item.

Neither condition permits a guessed identity.

## Lifecycle

Each observation is bound to a source session and generation. Disabling a
source, losing a required grant, replacing the MediaSession, or observing a
contradictory successor invalidates stale evidence. Compatible recreation may
carry measured progress without carrying unrelated identity.

See [Identity](IDENTITY.md) for the verification rules and
[Scrobbling rules](SCROBBLE_RULES.md) for eligibility.
