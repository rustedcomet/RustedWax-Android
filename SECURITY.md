# Security policy

## Reporting a vulnerability

Do not publish credentials, personal data, exploit payloads, or detailed
reproduction steps in a public issue.

Private vulnerability reporting is the preferred channel when it is enabled
for this repository. If it is unavailable, open a minimal public issue asking
the maintainer to establish a private contact channel; include no sensitive
details in that issue.

## Supported release

The latest GitHub Release is the supported public build. Verify its checksum
and signing certificate against
[the release-verification record](Documentation/Product/RELEASE_VERIFICATION.md).

Private Android signing material and owner credentials are never part of the
repository. A public signing-certificate fingerprint is verification data, not
a secret.

## Security model

- Hive transactions are signed locally with a posting key stored through
  Android encrypted storage.
- Video identity fails closed when evidence is ambiguous or contradictory.
- Automatic scrobbling is an explicit opt-in.
- Privileged Android components are non-exported except the launcher activity.
- App backup is disabled.
- Diagnostic logging is optional, bounded, and cleared when disabled.

## Known security limitations

RustedWax depends on Android facilities and third-party network services whose
behavior can change. Defensive hardening remains ongoing across network,
embedded sign-in, transaction-delivery, and local data-sharing boundaries.
When required evidence or a security-sensitive operation cannot be completed
safely, the app is designed to refuse or defer the operation.

Security-sensitive implementation details and reproduction information are
handled through private vulnerability reporting. Users should install the
latest GitHub Release, keep Android current, grant only the access needed for
the sources they use, and prefer a separately revocable Hive posting-authority
key.
