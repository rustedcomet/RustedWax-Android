#!/bin/sh
# Real foreground Short -> PiP inference -> exactly one finalization.
#
# The foreground latch must contain hashed title + owner and readable progress;
# the same token must then lose its progress surface while separate audio and
# YouTube-window evidence are both true. No instrumentation targets RustedWax.
# Usage: tools/device/native-shorts-pip.sh [serial] [shortVideoId] [launch|already-open]
set -eu

serial="${1:-${ANDROID_SERIAL:-}}"
video="${2:-4x_q2gBomZI}"
entry="${3:-launch}"
adb="<redacted-local-path>/Library/Android/sdk/platform-tools/adb"
pkg="com.rustedwax.app"
youtube="com.google.android.youtube"
prefs="rustedwax_keys.xml rustedwax_settings.xml rustedwax_youtube_session.xml"

say() { printf '%s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
field() { printf '%s\n' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p" | tail -1; }
telemetry() {
	$adb -s "$serial" logcat -d -s RustedWaxPhase3:I '*:S' 2>/dev/null |
		sed -n 's/^.*kind=/kind=/p'
}
snapshots() { telemetry | grep '^kind=snapshot ' || true; }
wait_for_stable_rustedwax_bindings() {
	# On this API-31 Samsung build, force-stopping YouTube also makes Android
	# recycle package-scoped accessibility bindings in other processes. The
	# RustedWax process survives, but its native-source epoch notification can be
	# queued behind YouTube startup. Launching before that queue drains lets the
	# old invalidation discard a newly acquired Short and creates a false gate
	# failure before HOME is even pressed.
	stable=0
	i=0
	while [ "$i" -lt 30 ]; do
		accessibility=$($adb -s "$serial" shell dumpsys accessibility)
		bound=$(printf '%s\n' "$accessibility" |
			sed -n '/bound services:{/,/crashed services:/p' |
			grep -c "id=$pkg/" | tr -d '\r' || true)
		crashed=$(printf '%s\n' "$accessibility" |
			sed -n '/crashed services:{/,/client list:/p' |
			grep -c "id=$pkg/" | tr -d '\r' || true)
		if [ "$bound" -eq 2 ] && [ "$crashed" -eq 0 ]; then
			stable=$((stable + 1))
			[ "$stable" -ge 4 ] && return 0
		else
			stable=0
		fi
		sleep 1
		i=$((i + 1))
	done
	return 1
}
state() {
	for f in $prefs; do
		printf '%s ' "$f"
		$adb -s "$serial" shell "run-as $pkg sha256sum shared_prefs/$f 2>/dev/null" |
			tr -d '\r' | awk '{print $1}'
	done
	printf 'listener '
	$adb -s "$serial" shell settings get secure enabled_notification_listeners |
		tr ':' '\n' | grep -c "$pkg" | tr -d '\r' || true
	printf 'usage '
	$adb -s "$serial" shell appops get "$pkg" GET_USAGE_STATS | tr -d '\r' |
		grep -c 'allow' || true
	printf 'a11y-master '
	$adb -s "$serial" shell settings get secure accessibility_enabled | tr -d '\r'
	printf 'a11y-bound '
	$adb -s "$serial" shell dumpsys accessibility |
		sed -n '/bound services:{/,/crashed services:/p' |
		grep -c "id=$pkg/" | tr -d '\r' || true
}

before=$(state)
printf '%s\n' "$before"
printf '%s' "$before" | grep -qE 'listener [1-9]' || fail "notification access is absent"
printf '%s' "$before" | grep -qE 'usage [1-9]' || fail "Usage Access is absent"
printf '%s' "$before" | grep -q 'a11y-master 1' || fail "accessibility master is off"
printf '%s' "$before" | grep -q 'a11y-bound 2' || fail "both RustedWax accessibility services are not bound"
if $adb -s "$serial" shell run-as "$pkg" cat shared_prefs/rustedwax_settings.xml 2>/dev/null |
	grep -q 'name="autoScrobble" value="true"'; then
	fail "auto-scrobble is on; refusing to drive playback that could publish"
fi

case "$entry" in
	launch|already-open) ;;
	*) fail "entry mode must be launch or already-open" ;;
esac

if [ "$entry" = "launch" ]; then
	# A VIEW intent does not replace YouTube's in-app minimized player reliably.
	# End only the source process so the exact Short starts without a different
	# video's retained mini-player; no YouTube data/grant or RustedWax state is
	# cleared. Android may recycle the package-scoped RustedWax accessibility
	# bindings, so wait for them and their source-epoch callback to settle before
	# the exact Short is allowed to latch.
	$adb -s "$serial" shell am force-stop "$youtube"
	wait_for_stable_rustedwax_bindings ||
		fail "RustedWax accessibility bindings did not settle after YouTube stopped"
	$adb -s "$serial" logcat -c
	$adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
	$adb -s "$serial" shell am start -W -a android.intent.action.VIEW \
		-d "https://www.youtube.com/shorts/$video" "$youtube" >/dev/null
else
	$adb -s "$serial" logcat -c
	activity=$($adb -s "$serial" shell dumpsys activity activities | tr -d '\r')
	printf '%s\n' "$activity" | grep -q "mResumedActivity:.*$youtube" ||
		fail "already-open mode requires foreground YouTube"
fi

i=0
foreground=""
while [ "$i" -lt 60 ]; do
	foreground=$(snapshots | grep "package=$youtube " |
		grep 'proof=NATIVE_FOREGROUND_SHORT' | grep 'progressLost=false' |
		grep 'finalized=false' | tail -1)
	if [ -n "$foreground" ] && [ "$(field "$foreground" titleHash)" != "-" ] &&
		[ "$(field "$foreground" ownerHash)" != "-" ] &&
		[ "$(field "$foreground" duration)" -gt 0 ] &&
		[ "$(field "$foreground" position)" -ge 0 ]; then
		break
	fi
	sleep 1
	i=$((i + 1))
done
[ -n "$foreground" ] || fail "no real foreground Short latched with title/owner/progress"
token=$(field "$foreground" token)
[ "$token" != "-1" ] || fail "foreground Short has no token"
say "foreground: $foreground"

# Package-level audio/window evidence cannot distinguish a latched Short from a
# different video left in YouTube's own minimized player. Refuse that ambiguous
# state using the observer's own live capture. `uiautomator dump` is deliberately
# forbidden here: it temporarily takes over accessibility on this device and
# invalidates the very RustedWax token the gate is meant to follow.
surface=$(telemetry | grep '^kind=short-surface ' |
	grep 'roots=1 players=1 ' | grep 'minimized=0' | tail -1)
[ -n "$surface" ] ||
	fail "observer did not prove exactly one Shorts player with no in-app mini-player"
say "surface:    $surface"

$adb -s "$serial" shell input keyevent KEYCODE_HOME
sleep 3
activity=$($adb -s "$serial" shell dumpsys activity activities | tr -d '\r')
printf '%s\n' "$activity" | grep -q 'mode=pinned' || fail "the Short did not enter mode=pinned"
printf '%s\n' "$activity" | grep -q 'rootPinnedTask=Task=' || fail "Android has no rootPinnedTask"
printf '%s\n' "$activity" | grep -q 'A=.*:com.google.android.youtube' ||
	fail "the pinned task does not belong to YouTube"

i=0
pip=""
while [ "$i" -lt 45 ]; do
	pip=$(snapshots | grep "token=$token " | grep 'progressLost=true' |
		grep 'finalized=false' | tail -1)
	paired=$(telemetry | grep '^kind=pip-evidence ' | grep 'audio=true window=true paired=true' |
		tail -1)
	if [ -n "$pip" ] && [ -n "$paired" ] &&
		[ "$(field "$pip" inferred)" -gt "$(field "$foreground" inferred)" ]; then
		break
	fi
	sleep 1
	i=$((i + 1))
done
[ -n "$pip" ] || fail "same-token progress-loss snapshot never appeared"
[ -n "${paired:-}" ] || fail "paired audio + YouTube-window evidence never appeared"
say "PiP:        $pip"
say "evidence:   $paired"
[ "$(field "$pip" titleHash)" = "$(field "$foreground" titleHash)" ] || fail "Short title changed"
[ "$(field "$pip" ownerHash)" = "$(field "$foreground" ownerHash)" ] || fail "Short owner changed"

elapsed=$(( $(field "$pip" elapsed) - $(field "$foreground" elapsed) ))
normal_delta=$(( $(field "$pip" normal) - $(field "$foreground" normal) ))
inferred_delta=$(( $(field "$pip" inferred) - $(field "$foreground" inferred) ))
played_delta=$(( $(field "$pip" played) - $(field "$foreground" played) ))
[ "$inferred_delta" -gt 0 ] || fail "inferred growth was not positive"
[ "$normal_delta" -le 1500 ] || fail "normal measurement also grew ${normal_delta}ms while the progress surface was lost"
[ "$played_delta" -eq $((normal_delta + inferred_delta)) ] || fail "normal + inferred did not equal total growth"
[ "$played_delta" -le $((elapsed + 1500)) ] ||
	fail "${played_delta}ms credited across ${elapsed}ms at the 1x PiP rate ceiling"

# End the source, not RustedWax. The tracker must emit exactly one finalized
# snapshot for the latched token, then remain quiet beyond both short proof and
# native replacement graces.
$adb -s "$serial" shell am force-stop "$youtube"
sleep 8
first_count=$(snapshots | grep "token=$token " | grep -c 'finalized=true' || true)
[ "$first_count" -eq 1 ] || fail "expected one finalization, observed $first_count"
sleep 12
late_count=$(snapshots | grep "token=$token " | grep -c 'finalized=true' || true)
[ "$late_count" -eq 1 ] || fail "a delayed duplicate arrived; finalization count is $late_count"

after=$(state)
[ "$before" = "$after" ] || fail "preferences or grants changed:\nbefore:\n$before\nafter:\n$after"
say "PASS: Short $video kept token $token; foreground title/owner/progress latched; pinned progress loss had paired audio/window evidence; inferred +${inferred_delta}ms, normal +${normal_delta}ms; exactly one finalization and no delayed duplicate"
