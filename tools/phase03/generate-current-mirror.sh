#!/bin/sh
# Regenerate the current-implementation mirror used by the old/new parity gate.
#
# The parity gate needs both sides to be real. This script makes the shipping
# implementation executable on a JVM, so the comparison is
#
#     recorded pre-migration Watch   vs   current Android binding + MediaSessionDriver
#
# driven by one script through identical MediaController callbacks — rather than
# the old machine against a hand-written translation of what the new one is
# assumed to receive.
#
# The header transform is the whole edit, and it is the same mechanical one:
#
#   1. the package moves out of `detect`, so the file cannot be shipped;
#   2. the nine `import android.*` lines are dropped and the identically-named
#      stand-ins in `reference/phase01/Android.kt` imported in their place;
#   3. `import com.rustedwax.app.detect.*` is added, because the production
#      helpers the file reached by same-package resolution are now imports.
#
# `CurrentMirrorProvenanceTest` re-checks body equality against the *live*
# production file on every run, so the mirror cannot drift from what ships and
# cannot be hand-edited into agreement with the reference.
#
# Usage: tools/phase03/generate-current-mirror.sh
set -eu

root=$(cd "$(dirname "$0")/../.." && pwd)
original="$root/app/src/main/java/com/rustedwax/app/detect/SessionProbe.kt"
out="$root/app/src/test/java/com/rustedwax/app/replay/reference/current"

mkdir -p "$out"

{
	echo 'package com.rustedwax.app.replay.reference.current'
	echo
	echo '// GENERATED — do not edit. See tools/phase03/generate-current-mirror.sh.'
	echo '// Body below is byte-identical to the shipping detect/SessionProbe.kt.'
	echo
	echo 'import com.rustedwax.app.detect.*'
	echo 'import com.rustedwax.core.*'
	echo 'import com.rustedwax.app.replay.reference.phase01.ComponentName'
	echo 'import com.rustedwax.app.replay.reference.phase01.Context'
	echo 'import com.rustedwax.app.replay.reference.phase01.Handler'
	echo 'import com.rustedwax.app.replay.reference.phase01.Looper'
	echo 'import com.rustedwax.app.replay.reference.phase01.MediaController'
	echo 'import com.rustedwax.app.replay.reference.phase01.MediaMetadata'
	echo 'import com.rustedwax.app.replay.reference.phase01.MediaSessionManager'
	echo 'import com.rustedwax.app.replay.reference.phase01.PlaybackState'
	echo 'import com.rustedwax.app.replay.reference.phase01.PlaybackStateDump'
	echo 'import com.rustedwax.app.replay.reference.phase01.SystemClock'
	echo 'import com.rustedwax.app.replay.reference.phase01.UrlWatcherService'
	grep '^import kotlinx' "$original" || true
	echo
	# Every declaration in the file, copied unchanged. Import counts change as
	# ownership moves, so anchor on the first declaration KDoc rather than a line.
	awk 'BEGIN { body = 0 } /^\/\*\*/ { body = 1 } body { print }' "$original"
} > "$out/SessionProbe.kt"

echo "regenerated:"
echo "  $out/SessionProbe.kt"
