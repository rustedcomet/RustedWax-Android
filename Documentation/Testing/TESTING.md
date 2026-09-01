# Testing RustedWax

This guide describes current contributor testing. Private device records,
account activity, transaction evidence, and chronological field diaries must
stay outside public Git.

## Evidence classes

Always label evidence by what actually ran:

| Evidence | Establishes |
| --- | --- |
| Unit/model | Pure logic and boundary contracts |
| Replay | Deterministic recorded or synthetic event sequences |
| Production wiring | Real composition reaches the intended implementation |
| Local artifact | A specific locally built APK or bundle |
| Physical device | Behavior observed on a named private test record |
| Irreversible chain | Returned transaction plus independent read-back |

Use **PASS**, **FAIL**, or **NOT ESTABLISHED**. Do not convert a weaker evidence
class into a stronger claim.

## Prerequisites

- JDK 17
- Android SDK with the configured compile/target platform
- `ANDROID_HOME` pointing to the local SDK
- `JAVA_HOME` pointing to the JDK

Keep paths in environment variables or ignored `local.properties`.

## Focused tests

Run the smallest affected module or test class while developing:

```bash
./gradlew :core:test
./gradlew :hive:test
./gradlew :app:testDebugUnitTest --tests 'com.rustedwax.app.SomeTest'
```

Add a red characterization or production-wiring test before fixing a defect.
When the defect is in composition or lifecycle behavior, a helper-only test is
not sufficient.

## Complete local Android gate

```bash
./gradlew --no-daemon --no-build-cache --no-configuration-cache \
  --rerun-tasks testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew :hive:test
```

Run additional lower-module tests when the root task does not include them.

## Parse results

Do not rely only on Gradle's final console line. Parse JUnit XML under each
module's `build/test-results/` tree and report:

- suites;
- tests;
- failures;
- errors; and
- skipped tests.

Parse Android lint XML by severity and review every error.

## Replay fixtures

Fixtures committed to the repository must be synthetic, deliberately public,
or reduced so they cannot identify an account, device, person, or private
listening session.

A replay must state:

- where its events came from;
- whether they are synthetic or reduced;
- which production boundary consumes them;
- what outcome is expected; and
- which side effects are prohibited.

Never commit raw device logs, cookies, preference files, transaction responses,
screenshots with personal state, or copied private history.

## Architecture gates

Relevant executable boundaries include:

- module dependency direction;
- provider-neutral `:core`;
- isolated source adapters;
- typed identity strategies;
- one finalization use case;
- transport-only retry;
- one terminal outcome; and
- shadow write isolation.

Update an architecture test only when the underlying public invariant
intentionally changes.

## Artifact verification

When exact-artifact identity matters:

1. build from the final tree;
2. compute a whole-file SHA-256;
3. copy or clone without hardlinks for a clean rebuild;
4. compare complete APK files, not selected ZIP entries; and
5. if installed, compare the pulled installed base APK to the tested artifact.

Do not treat a source hash or certificate fingerprint as a substitute for the
whole APK hash.

## Physical-device testing

Physical testing requires the device owner's authorization. Preserve app data
and established grants unless the test explicitly requires a disposable
profile.

Record private evidence outside the repository:

- exact source and scenario;
- artifact hash;
- Android/app version;
- required grants before and after;
- decisive observed state;
- logs needed to explain the outcome; and
- whether any account or chain mutation occurred.

Do not publish device serials, local paths, preference hashes, account names,
watch history, media timelines, or transaction identifiers in a public report.

Instrumentation can restart or disrupt accessibility-dependent components.
Use host-side orchestration for a gate whose decisive evidence depends on those
services remaining bound.

## Hive testing

A Hive write is irreversible. Automated tests should use deterministic
throwaway keys and mocked or scripted transports.

Real broadcast testing requires explicit authorization for the named account
and scenario. End-to-end acceptance requires:

1. automatic scrobbling enabled;
2. the exact post-change APK;
3. a real supported source;
4. real threshold and timer behavior;
5. a returned transaction identifier; and
6. matching irreversible read-back from two independent nodes.

Without all six, the irreversible end-to-end result is **NOT ESTABLISHED**.
Store the detailed evidence privately.

## Final review

Before handing off:

```bash
git diff --check
git status --short
```

Review the complete diff, verify documentation links, and state whether
application behavior, device state, credentials, release signing, or remote
repository state changed.
