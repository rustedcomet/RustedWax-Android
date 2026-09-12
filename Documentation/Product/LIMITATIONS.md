# Known limitations

RustedWax is constrained by what Android and YouTube expose to third-party
apps. It refuses uncertain writes instead of attempting to match the desktop
browser extension feature for feature.

## YouTube interfaces

Some identity and metadata routes use unsupported YouTube web pages or response
shapes. A markup change can temporarily prevent native identity recovery or
metadata enrichment.

Google can also restrict sign-in inside embedded browsers. The optional watch
history route may stop working even when other playback sources continue.

## Native identity

Android media sessions often omit the exact video ID. Outside a playlist,
native playback may require the optional signed-in watch-history route or a
unique public lookup. Two indistinguishable uploads produce no write.

## Shorts and picture-in-picture

YouTube can hide the Shorts progress surface while the item continues playing.
RustedWax uses bounded inference only after a foreground Short has been latched
and continuing YouTube audio/window evidence agrees. Missing or contradictory
evidence stops inference.

Shorts scrobbling is disabled by default. It also depends on optional Android
access that can be revoked or disrupted by the operating system.

## Android background reliability

For reliable background monitoring, set RustedWax's Android **Battery usage** to
**Unrestricted**, particularly on Samsung devices. Battery restrictions can
suspend the process and delay playback callbacks or finalization. This setting
does not guarantee uninterrupted delivery or identification of every listen.

## Ads and duration churn

Native apps may reuse one media session across content, ads, and interstitial
screens. In the YouTube app, time under the watch player's own ad label is not
measured, but only while the native accessibility service can see the player
(see [Detection](DETECTION.md#native-watch-player-ads)). Otherwise RustedWax
quarantines material duration replacements and excludes interstitial intervals.
An unresolved duration conflict can cause a legitimate listen to be skipped.
YouTube Music exposes no ad label that RustedWax can read.

## Background browser playback

Browser metadata and accessibility state can become stale across tab changes,
backgrounding, or browser process recreation. RustedWax binds evidence to a
session and refuses when the URL and media metadata cannot be reconciled.

## Hive transport

Public Hive RPC nodes can be stale, unavailable, or disagree temporarily.
RustedWax checks node freshness and seeks independent confirmation. If an
automatic transaction may have been accepted but independent status remains
unavailable, it waits fail-closed; delivery can be delayed indefinitely rather
than risk a newly signed duplicate.

## Local security

The current sign-in WebView and local storage configuration have defense-in-
depth work remaining. Review [the security policy](../../SECURITY.md) before
using a high-value account or a device with an elevated threat model.

## Not provided

RustedWax does not provide:

- a desktop browser extension;
- per-chapter scrobbling;
- a guarantee that every YouTube presentation can be identified;
- editing or deleting an immutable Hive entry;
- protection against a fully compromised Android device.
