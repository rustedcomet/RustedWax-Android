# Contributing to RustedWax

RustedWax welcomes focused bug fixes, tests, documentation improvements, and
well-scoped features. Open an issue before a large architectural or behavior
change so the intended contract can be agreed first.

## Local setup

Install Android Studio, JDK 17, and an Android SDK. Keep machine-specific paths
in environment variables or ignored `local.properties`; never commit them.

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:assembleDebug
```

## Development expectations

1. Start with a test that reaches the affected production boundary.
2. Make the smallest change that satisfies the documented behavior.
3. Run focused tests, then the proportionate regression gate.
4. Update current product documentation when behavior intentionally changes.
5. Run `git diff --check` and review the complete diff.

Do not commit credentials, signing material, device exports, logs, screenshots,
transaction responses, local paths, account data, or private field evidence.
Use synthetic fixtures or deliberately public test vectors.

## Architecture

The module direction is:

```text
:core
:identity-api       -> :core
:youtube-identity   -> :core, :identity-api
:android-sources    -> :core, :identity-api
:hive               -> :core
:app                -> all lower modules
```

Keep `:core` Android-independent and provider-neutral. Provider observation
belongs in source adapters; provider identity belongs behind typed identity
strategies. `MediaSessionDriver` owns reducer state, and all finalization
triggers enter the same `FinalizeTrackUseCase`. Retry is transport-only and
must not rerun identity, eligibility, or payload construction.

See [the architecture guide](Documentation/Development/ARCHITECTURE.md).

## Verification

For a complete local Android gate:

```bash
./gradlew --no-daemon --no-build-cache --no-configuration-cache \
  --rerun-tasks testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew :hive:test
```

Parse the generated JUnit and lint XML rather than relying only on console
summaries. See [Testing RustedWax](Documentation/Testing/TESTING.md).

Physical-device evidence is separate from automated evidence. Never clear an
owner's app data, alter grants, handle a real posting key, or broadcast a Hive
transaction without explicit authorization from the person who controls the
device and account.

## Pull requests

Describe the problem, the behavior before and after, the production path
affected, tests run, and remaining risks. Keep unrelated cleanup out of the
change.
