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

The current source has several unresolved hardening items:

- Some production HTTP/RPC clients read a response without a shared byte
  ceiling. This is primarily an availability risk from an oversized response.
- An automatic transaction accepted by one Hive node but not observed by
  independent nodes can remain in fail-closed retry state indefinitely. This
  favors duplicate-write safety over prompt delivery while node evidence is
  unavailable.
- The embedded Google/YouTube sign-in WebView does not yet enforce a complete
  navigation allowlist and explicit legacy file/content-access policy.
- Android device-transfer exclusions are not explicitly defined for every
  sensitive local artifact, and the internal FileProvider mapping is broader
  than the single log export it currently serves.

These items should be fixed in separate, test-driven changes because they touch
network, authentication, transport, or storage boundaries. They are not
credentials embedded in the repository.
