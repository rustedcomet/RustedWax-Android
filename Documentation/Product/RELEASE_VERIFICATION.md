# Release verification

The current public release is **RustedWax v0.12.2** for application ID
`com.rustedwax.app`.

Download the APK from the
[v0.12.2 GitHub Release](https://github.com/rustedcomet/RustedWax-Android/releases/tag/v0.12.2).

## APK checksum

```text
SHA-256  103c6fc8bb8cd5ab556cea9be6c06615838139f7d6707875075d76f044ceec12
File    rustedwax-v0.12.2-release.apk
Version code  64
```

The release also includes `rustedwax-v0.12.2-release.apk.sha256`.

## Signing certificate

```text
Certificate SHA-256  c626ce85cee589066ceddb5856fef84237aafe233404a97ad87ca7433c67f367
Key algorithm         RSA
Key size              4096 bits
```

The signing certificate and its fingerprint are public verification data. The
private signing key, keystore, credentials, backup location, and signing
procedure are intentionally kept outside Git.

This record identifies the published artifact; it is not a description of the
maintainer's private signing environment.

## Update metadata

From the release that ships the update check (Issue #43), every GitHub Release
also carries `rustedwax-update.json`:

```json
{
  "schema": 1,
  "applicationId": "com.rustedwax.app",
  "versionCode": 66,
  "versionName": "0.14.0"
}
```

Generate it from the release checkout with `tools/release/update-metadata.sh`
and attach it to the release with the APK. The app reads it from
`releases/latest/download/rustedwax-update.json` at most once a day and
compares `versionCode` only. It refuses the file when any field is missing or
mistyped, when `applicationId` differs, or when `versionName` disagrees with the
release tag it was served from. Refused or unreachable metadata never produces a
notification.

The file carries no URL. A tap on the notification opens this repository's
latest release page, or the Play listing for a copy installed by Google Play.
Nothing is downloaded or installed automatically.
