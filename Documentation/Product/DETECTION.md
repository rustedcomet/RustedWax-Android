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

## Native watch-player ads

Native YouTube publishes a pre-roll under the upcoming video's own title and
channel, with the ad's length, so the media session cannot tell the ad from the
video. While an ordinary video is playing, the same native service looks at the
watch player about once a second for YouTube's own ad controls. A reading counts
only when both the view id is one of the player's ad controls
(`ad_progress_text`, `skip_ad_button_container`, `player_learn_more_button`)
and the label is one of the exact ad labels ("Sponsored", "Skip ad", "Visit
advertiser").

- While a label is showing, no playback time is measured.
- A presentation labelled from its first seconds is an advertisement. None of
  its time is credited, and the presentation that follows it starts the
  measurement, whatever its length.
- Absence counts only when the full-size watch player was on screen with no ad
  control drawn. A presentation becomes organic once it has been seen playing
  unlabelled for three seconds.
- When the player cannot be observed, no absence is inferred. A previously read
  ad label continues to hold the current presentation's clock until an observed
  absence or a presentation change permits measurement. A supported ad control
  still visible on the minimized player remains positive ad evidence.

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
