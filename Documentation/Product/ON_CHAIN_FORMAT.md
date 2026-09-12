# On-chain format

RustedWax uses the shared Hive scrobbling schema. Matching field names and
meaning matters for indexers; Android classification and metadata normalization
need not be identical to the desktop extension.

[← Back to the README](../../README.md)

## Hive operation

- Operation: `custom_json`.
- ID: `hive_scrobble_ai`, shared with Hive Scrobbler.
- Authority: `required_auths: []`, `required_posting_auths: [<username>]`.
- `json`: the serialized scrobble payload below.
- `app`: `rustedwax/<version>` identifies RustedWax independently of other clients.

The shared [payload type](https://github.com/Holozing1/hivescrobble/blob/master/src/core/scrobbler/hive/hive.types.ts)
provides interoperability context. Current RustedWax output is described here;
not every optional field or feature of another client is implemented.

## Public payload fields

| Field | RustedWax meaning |
| --- | --- |
| `app` | `rustedwax/<version>` build attribution |
| `kind` | `song`, `video`, `movie`, or `episode`, according to current classification |
| `title` | Usable source or refined title; music parsing depends on source and kind |
| `timestamp` | Logical listen start, formatted as ISO-8601 UTC with milliseconds |
| `artist` | Best-effort artist credit for music; normally the uploader/channel for generic non-song entries |
| `album` | Song release metadata when available; omitted for non-song kinds |
| `duration` | Content duration as `m:ss`, with total minutes permitted above 59 |
| `percent_played` | Integer percentage for this operation, capped at 100 |
| `platform` | `youtube` for the currently supported sources |
| `url` | `https://www.youtube.com/watch?v=<verified-video-id>`, including for Shorts |

`app`, `kind`, `title`, and `timestamp` are required payload fields. Optional
metadata is omitted when unavailable. The current YouTube write path also
requires verified identity, canonical URL, and usable duration; the schema's
optional fields do not bypass these eligibility checks. `now_playing` is never
broadcast on-chain.

## Kinds, credits, and operation counts

YouTube Music supplies dedicated artist/title metadata, which RustedWax
preserves where appropriate. MusicBrainz or canonical-page performer
corroboration is not a universal write requirement. An uploader mismatch does
not by itself veto a verified music-video row; exact ID, work, duration, and
presentation checks still apply. Metadata can be wrong even when identity is
correct.

For generic sources, `Artist - Track` and related title parsing applies to
`song` formatting. Description credits and optional MusicBrainz matches can
refine that pair. If no meaningful split is established, an otherwise eligible
listen can fall back to `video`. Generic non-song entries retain the whole
cleaned title and channel credit. Native YouTube Music can retain its own
artist/title fields even for a non-song result.

Title cleaning can remove trailing hashtags and presentation markers; it is
not guaranteed to match desktop normalization byte for byte. Song albums may
come from native session metadata or structured Art Track metadata, rather
than guesses from arbitrary descriptions.

Music classification considers source context, structured music metadata,
format evidence, category, optional MusicBrainz evidence, and conservative
title/channel rules. Native YouTube Music is strong music context, but explicit
podcast or non-music evidence can still produce `video`. A catalogue miss is
not proof of non-music, and weak title formatting alone is insufficient for
Shorts or long-form content. Without music evidence, the default is `video`.

The current builder also recognizes structural movie and episode evidence.
These kinds do not add `imdb_id`, `wikipedia_url`, `series_*`, or `poster_url`.
There is no dedicated `podcast` classification path, although the schema and
private-category mapping retain that kind for compatibility.

All current kinds use the configured progress threshold (60% by default);
there is no separate 80% movie/episode threshold. Non-song kinds and continuous
Shorts viewings are capped at one operation. Songs can yield a second operation
at one full duration plus the configured threshold (160% at the default), only
with corroborated position and no loop evidence. Each operation's percentage
is the remaining listened fraction, rounded and capped at 100. Duration floors,
Shorts public-page proof, and deduplication remain mandatory; see
[Eligibility](BEHAVIOR_CONTRACT.md#eligibility).

## Private envelope compatibility

**Private mode is not currently exposed in the app's settings.** Per-category
preferences remain stored, default off, and are still honored by finalization.
The envelope and cipher remain implemented for compatibility; this is not a
currently offered setup feature.

When a stored privacy preference applies, the public payload becomes:

```json
{ "app": "rustedwax/<version>", "kind": "song", "timestamp": "<UTC timestamp>", "private": "<base64 blob>", "v": 1 }
```

`app`, `kind`, and `timestamp` remain public. The encrypted JSON contains title
and any available artist, album, duration, percent, platform, and URL fields,
using their public payload field names. Music, videos, movies/TV, and podcasts
have separate stored categories; `movie` and `episode` share movies/TV, and an
unknown kind uses the music category.

The v1 blob is AES-256-GCM: standard padded base64 of a fresh 12-byte IV followed
by ciphertext and a 128-bit authentication tag. The 32-byte secret is SHA-256 of
the raw deterministic compact signature bytes over the SHA-256 digest of the
UTF-8 challenge `zingit:privacy-key:v1`, signed with the posting key. These
formats and the challenge must remain compatible with clients reading v1
entries; encrypted JSON key order is not a contract.

If derivation or encryption fails, nothing is sent. Plaintext is never a
fallback. Retain the applicable posting key to decrypt its earlier entries;
a different posting key derives a different secret.
