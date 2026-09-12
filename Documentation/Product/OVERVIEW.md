# Why RustedWax exists

The problem RustedWax solves and the shape of the current v0.11.2 product.

[← Back to the project README](../../README.md)

## Why it exists

Brave and Chrome on Android cannot run the desktop Hive-scrobbling extension
flow. RustedWax supplies the Android pieces instead:

| Desktop extension | RustedWax on Android |
| --- | --- |
| Per-site browser connector | MediaSession observation plus source-specific evidence |
| Browser page identity | Verified browser URL, native metadata, playlist, watch-history, or bounded lookup evidence |
| Hive Keychain transaction signing | Local signing with a saved Hive posting key |

The current public release supports YouTube in Brave and Chrome, the native
YouTube app, and YouTube Music under one **YouTube scrobbling** setting. It
measures played content, requires a verified video ID, applies the documented
scrobbling rules, signs on the device, and shows the resulting transaction or
refusal in the UI.

RustedWax follows the established Hive `hive_scrobble_ai` `custom_json` format
and scrobbling thresholds, with documented mobile-specific identity,
classification, Shorts, picture-in-picture, and deduplication rules.

Title normalization is not always byte-identical to the desktop extension. For
example, RustedWax can normalize presentation markers such as `(Live)` or
`【Guitar Cover】` to the underlying recording. See the [behavior contract](BEHAVIOR_CONTRACT.md)
and [on-chain format](ON_CHAIN_FORMAT.md) for the normative details.
