#!/bin/sh
# Ordinary native YouTube PiP, proved entirely from the host.
#
# No instrumentation, install, force-stop of RustedWax, settings write, or app
# data write occurs. Android proves the pinned task; the debug build's structured
# Phase3Telemetry proves the exact immutable SessionSnapshot before and after.
# Usage: tools/device/native-pip.sh [serial]
set -eu

serial="${1:-R58R215V2SA}"
adb="<redacted-local-path>/Library/Android/sdk/platform-tools/adb"
pkg="com.rustedwax.app"
youtube="com.google.android.youtube"
video="tSi6Dn1H36Y"
prefs="rustedwax_keys.xml rustedwax_settings.xml rustedwax_youtube_session.xml"

say() { printf '%s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
field() { printf '%s\n' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p" | tail -1; }
telemetry() {
	$adb -s "$serial" logcat -d -s RustedWaxPhase3:I '*:S' 2>/dev/null |
		sed -n 's/^.*kind=/kind=/p'
}
snapshots() { telemetry | grep '^kind=snapshot ' || true; }
state() {
	for f in $prefs; do
		printf '%s ' "$f"
		$adb -s "$serial" shell "run-as $pkg sha256sum shared_prefs/$f 2>/dev/null" |
			tr -d '\r' | awk '{print $1}'
	done
	printf 'listener '
	$adb -s "$serial" shell settings get secure enabled_notification_listeners |
		tr ':' '\n' | grep -c "$pkg" | tr -d '\r' || true
	printf 'a11y-listed '
	$adb -s "$serial" shell settings get secure enabled_accessibility_services |
		tr ':' '\n' | grep -c "$pkg" | tr -d '\r' || true
	printf 'a11y-master '
	$adb -s "$serial" shell settings get secure accessibility_enabled | tr -d '\r'
	printf 'a11y-bound '
	$adb -s "$serial" shell dumpsys accessibility |
		sed -n '/[Bb]ound services:{/,/[Cc]rashed services:/p' |
		grep -c "id=$pkg/" | tr -d '\r' || true
}
wait_snapshot() {
	token="${1:-}"
	i=0
	while [ "$i" -lt 60 ]; do
		line=$(snapshots | grep "package=$youtube " | grep 'playing=true' |
			grep -v 'titleHash=-' | grep 'finalized=false' |
			{ [ -z "$token" ] && cat || grep "token=$token "; } | tail -1)
		[ -n "$line" ] && { printf '%s\n' "$line"; return; }
		sleep 1
		i=$((i + 1))
	done
	return 1
}

say "== preconditions =="
before=$(state)
printf '%s\n' "$before"
printf '%s' "$before" | grep -qE 'listener [1-9]' || fail "notification access is absent"
printf '%s' "$before" | grep -q 'a11y-master 1' || fail "accessibility master is not enabled"
printf '%s' "$before" | grep -q 'a11y-bound 2' || fail "both RustedWax accessibility services are not bound"
if $adb -s "$serial" shell run-as "$pkg" cat shared_prefs/rustedwax_settings.xml 2>/dev/null |
	grep -q 'name="autoScrobble" value="true"'; then
	fail "auto-scrobble is on; refusing to drive playback that could publish"
fi

$adb -s "$serial" logcat -c
$adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
$adb -s "$serial" shell am start -W -a android.intent.action.VIEW \
	-d "https://www.youtube.com/watch?v=$video" "$youtube" >/dev/null
baseline=$(wait_snapshot "") || fail "production telemetry never published a native snapshot"
token=$(field "$baseline" token)
[ "$token" != "-1" ] || fail "native snapshot has no track token: $baseline"
[ "$(field "$baseline" playing)" = "true" ] || fail "native snapshot is not playing: $baseline"
foreground_activity=$($adb -s "$serial" shell dumpsys activity activities | tr -d '\r')
printf '%s\n' "$foreground_activity" | grep -q "$video" ||
	fail "the foreground YouTube task is not $video"
say "baseline: $baseline"

$adb -s "$serial" shell input keyevent KEYCODE_HOME
sleep 3
activity=$($adb -s "$serial" shell dumpsys activity activities | tr -d '\r')
printf '%s\n' "$activity" | grep -q 'mode=pinned' || fail "Android has no mode=pinned task"
printf '%s\n' "$activity" | grep -q 'rootPinnedTask=Task=' || fail "Android has no rootPinnedTask"
printf '%s\n' "$activity" | grep -q "$video" || fail "the pinned task is not $video"
say "Android: mode=pinned, rootPinnedTask present, item=$video"

sleep 20
after_line=$(wait_snapshot "$token") || fail "the PiP snapshot disappeared or changed token"
say "after:    $after_line"
[ "$(field "$after_line" titleHash)" = "$(field "$baseline" titleHash)" ] ||
	fail "the same token changed title in PiP"

elapsed=$(( $(field "$after_line" elapsed) - $(field "$baseline" elapsed) ))
normal_delta=$(( $(field "$after_line" normal) - $(field "$baseline" normal) ))
inferred_delta=$(( $(field "$after_line" inferred) - $(field "$baseline" inferred) ))
played_delta=$(( $(field "$after_line" played) - $(field "$baseline" played) ))
rate=$(field "$after_line" rate)
[ "$rate" != "-" ] || rate=1.0
[ "$normal_delta" -gt 10000 ] || fail "normal measurement grew only ${normal_delta}ms"
[ "$inferred_delta" -eq 0 ] || fail "ordinary PiP incorrectly inferred ${inferred_delta}ms"
[ "$played_delta" -eq "$normal_delta" ] || fail "normal and total growth disagree"
awk -v d="$played_delta" -v e="$elapsed" -v r="$rate" \
	'BEGIN { exit !(d <= (e * r) + 1500) }' ||
	fail "${played_delta}ms credited across ${elapsed}ms at ${rate}x (rate ceiling exceeded)"

# A pause and a wait beyond the native stopped-replacement grace must not turn
# ordinary PiP into a false finalization or a delayed duplicate.
$adb -s "$serial" shell input keyevent KEYCODE_MEDIA_PAUSE >/dev/null 2>&1 || true
sleep 12
final_count=$(snapshots | grep "token=$token " | grep -c 'finalized=true' || true)
[ "$final_count" -eq 0 ] || fail "ordinary PiP falsely finalized $final_count time(s)"

after=$(state)
[ "$before" = "$after" ] || fail "preferences or grants changed:\nbefore:\n$before\nafter:\n$after"
say "PASS: pinned item $video kept token $token; normal +${normal_delta}ms, inferred +0ms, elapsed ${elapsed}ms at ${rate}x; no finalization or delayed duplicate"
