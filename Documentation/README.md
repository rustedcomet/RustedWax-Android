# RustedWax documentation

This directory contains deliberately public documentation for users and
contributors. Internal device logs, field reports, signing operations,
security reproduction records, and coding-agent work diaries do not belong in
the public tree.

## Users

- [Setup](Product/SETUP.md)
- [How it works](Product/HOW_IT_WORKS.md)
- [Identity and verification](Product/IDENTITY.md)
- [Scrobbling rules](Product/BEHAVIOR_CONTRACT.md#eligibility)
- [On-chain format](Product/ON_CHAIN_FORMAT.md)
- [Known limitations](Product/LIMITATIONS.md)
- [Release verification](Product/RELEASE_VERIFICATION.md)
- [Roadmap](Product/ROADMAP.md)

## Contributors

- [Behavior contract](Product/BEHAVIOR_CONTRACT.md)
- [Architecture](Development/ARCHITECTURE.md)
- [Testing](Testing/TESTING.md)
- [Contributing](../CONTRIBUTING.md)
- [Security policy](../SECURITY.md)

## Authority

Use current shipping source and executable tests as the final description of
what a checkout does. Product documents describe intended current behavior.
Dated private evidence may explain earlier decisions, but it is not a public
specification and is not authoritative for a later tree.

The static project page is [`index.html`](index.html). Its deployment workflow
uses an explicit allowlist containing only the page and reviewed public image
assets.
