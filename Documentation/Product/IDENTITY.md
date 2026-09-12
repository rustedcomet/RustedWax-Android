# Identifying the video

RustedWax writes a Hive entry only after it has one verified YouTube video ID
and a canonical public link.

## Typed outcomes

Identity strategies return one of four meaningful outcomes:

- **Confirmed** — one item satisfies the route's evidence contract.
- **Missing** — no supported route supplied a candidate.
- **Ambiguous** — more than one plausible item remains.
- **Contradiction** — exact evidence disagrees.

Only **Confirmed** may continue to an on-chain write. Missing, ambiguous, and
contradictory outcomes fail closed.

## Browser identity

Brave and Chrome can expose a URL through the optional accessibility service.
The URL is parsed structurally; substring matches are never authority.

Supported forms include canonical HTTPS watch and Shorts routes on exact
YouTube hosts. The parsed item ID must agree with every other exact item ID
bound to the session. A foreign origin or disagreement vetoes the candidate.

## Native YouTube identity

Native media sessions often omit the video ID. RustedWax may use:

- a stable item ID already bound to the source session;
- a uniquely matching playlist entry;
- a uniquely matching signed-in watch-history row;
- a public search candidate corroborated by watch-page facts;
- a structured YouTube Music catalog candidate.

Each route has negative controls. A title similarity, channel name, duration,
or ranking position alone is insufficient.

When a browser omits duration, a recovery route may use an exact title plus
canonical channel only if one item survives and the canonical page supplies
the required duration. Playlist-sequence recovery requires two preceding
verified IDs adjacent and in order, and one uniquely matching immediate
successor across the candidate playlists. These bounded routes cannot choose
between multiple plausible uploads.

## Corroboration

A candidate may be compared using normalized title, owner/channel, duration,
playlist position, content kind, and direct item-ID evidence.

Normalization handles presentation differences such as Unicode forms,
punctuation, whitespace, and known display suffixes. It must not collapse two
distinct plausible recordings into one.

If two uploads of the same recording remain plausible, RustedWax refuses
rather than choosing the most popular or first result.

## Ads and interstitials

Ad status requires direct evidence such as an explicit observed ad UI or a
typed source transition. RustedWax does not infer an ad from title, brand,
duration, package, popularity, or unfamiliar metadata.

Interstitial duration and position changes do not replace the organic
identity. Attributed organic progress is retained only where continuity evidence
permits it; unrelated provisional progress is not rescued by a later identity
match. Native watch-player ad labels exclude the ad presentation rather than
vetoing the organic video that follows.

## Metadata refinement

After identity is confirmed, public metadata can improve title, artist,
duration, thumbnail, or content kind. Refinement cannot change the confirmed
video ID or turn ambiguity into confirmation.

Unsupported YouTube pages and metadata interfaces can change without notice.
Markup failure can prevent a write or degrade optional metadata; it never
permits a fabricated ID. Refusals are shown for eligible user-facing targets.

Artist/performer quality is distinct from verified video/work identity.
YouTube Music's source-published credits may remain best-effort; MusicBrainz
and canonical-performer corroboration are not universal write prerequisites.
Uploader differences alone do not veto a verified music-video row. Exact ID,
work, duration, and presentation checks still apply, as defined by the
[behavior contract](BEHAVIOR_CONTRACT.md#identity-and-metadata).

## Canonical link

The public payload and UI history use the confirmed item ID to build the
canonical link. A row without a verified link is not presented as a successful
video scrobble.

See [How it works](HOW_IT_WORKS.md#sources-and-notification-access) for source
evidence and [Eligibility](BEHAVIOR_CONTRACT.md#eligibility) for the next
decision stage.
