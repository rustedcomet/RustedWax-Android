#!/bin/sh
# Exercise process death and listener reconnect from the host while recording
# state and grant hashes before and after. The script avoids force-stop, data
# clearing, uninstall, installation, and settings writes.
#
# Usage: tools/device/process-restart.sh [serial]
# ANDROID_SERIAL may supply the serial. ADB or ANDROID_HOME may locate adb.
set -eu

serial="${1:-${ANDROID_SERIAL:-}}"
adb="${ADB:-}"
pkg="com.rustedwax.app"
prefs="rustedwax_keys.xml rustedwax_settings.xml rustedwax_youtube_session.xml"

say() { printf '%s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

[ -n "$serial" ] || fail "provide a device serial or set ANDROID_SERIAL"
if [ -z "$adb" ] && [ -n "${ANDROID_HOME:-}" ]; then
	adb="$ANDROID_HOME/platform-tools/adb"
fi
if [ -z "$adb" ]; then
	adb=$(command -v adb || true)
fi
[ -x "$adb" ] || fail "adb is unavailable; set ADB or ANDROID_HOME"

state() {
	for f in $prefs; do
		printf '%s ' "$f"
		"$adb" -s "$serial" shell "run-as $pkg sha256sum shared_prefs/$f 2>/dev/null" |
			tr -d '\r' | awk '{print $1}'
	done
	printf 'listener '
	"$adb" -s "$serial" shell settings get secure enabled_notification_listeners |
		tr ':' '\n' | grep -c "$pkg" | tr -d '\r'
	# Accessibility is three separate facts and all matter: the services may be
	# listed while the master flag is off, in which case Android binds neither.
	# A prior `am force-stop` implementation turned `accessibility_enabled`
	# from 1 to 0, silently disabling the native/PiP evidence path. An earlier
	# version of this script only hashed preferences and the listener grant, so it
	# reported "unchanged" across exactly that loss.
	printf 'a11y-listed '
	"$adb" -s "$serial" shell settings get secure enabled_accessibility_services |
		tr ':' '\n' | grep -c "$pkg" | tr -d '\r'
	printf 'a11y-enabled '
	"$adb" -s "$serial" shell settings get secure accessibility_enabled | tr -d '\r'
	printf 'a11y-bound '
	"$adb" -s "$serial" shell dumpsys accessibility |
		sed -n '/bound services:{/,/crashed services:/p' |
		grep -c "id=$pkg/" | tr -d '\r' || true
}

pid_of() { "$adb" -s "$serial" shell pidof "$pkg" | tr -d '\r' | awk '{print $1}'; }

say "== device =="
"$adb" -s "$serial" shell getprop ro.product.model | tr -d '\r'

say "== state before =="
before=$(state)
printf '%s\n' "$before"
printf '%s' "$before" | grep -qE 'listener [1-9]' ||
	fail "notification access is not granted; there is no listener to reconnect"
printf '%s' "$before" | grep -q 'a11y-enabled 1' ||
	fail "accessibility master is not enabled before the process test"
printf '%s' "$before" | grep -q 'a11y-bound 2' ||
	fail "both accessibility services are not bound before the process test"

pid_before=$(pid_of)
[ -n "$pid_before" ] || fail "the app process is not running; nothing to restart"
say "pid before: $pid_before"

"$adb" -s "$serial" logcat -c

say "== ActivityManager crash (process death without force-stop) =="
case "$pid_before" in
	*[!0-9]*|'') fail "unsafe pid: $pid_before" ;;
esac
"$adb" -s "$serial" shell am crash "$pkg" >/dev/null

say "== waiting for the system to rebind the listener =="
connected=""
i=0
while [ "$i" -lt 60 ]; do
	if "$adb" -s "$serial" logcat -d |
		grep -q "Notification listener connected — media sessions readable"; then
		connected="yes"
		break
	fi
	sleep 2
	i=$((i + 1))
done
[ -n "$connected" ] || fail "the listener never reconnected within 120s"

pid_after=$(pid_of)
[ -n "$pid_after" ] || fail "no app process after the rebind"
[ "$pid_before" != "$pid_after" ] ||
	fail "the process never died: pid unchanged at $pid_before"
say "pid after: $pid_after (process really was replaced)"

say "== waiting for both accessibility services to rebind =="
i=0
while [ "$i" -lt 30 ]; do
	bound=$("$adb" -s "$serial" shell dumpsys accessibility |
		sed -n '/bound services:{/,/crashed services:/p' |
		grep -c "id=$pkg/" | tr -d '\r' || true)
	master=$("$adb" -s "$serial" shell settings get secure accessibility_enabled | tr -d '\r')
	[ "$master" = "1" ] && [ "$bound" = "2" ] && break
	sleep 1
	i=$((i + 1))
done
[ "${master:-}" = "1" ] && [ "${bound:-}" = "2" ] ||
	fail "accessibility did not recover: master=${master:-?}, bound=${bound:-?}"

say "== production reconnect evidence =="
"$adb" -s "$serial" logcat -d |
	grep -E "notification listener service connected: ComponentInfo\{$pkg|Notification listener connected|Start proc .*$pkg.* for service \{$pkg" |
	tail -5

say "== state after =="
after=$(state)
printf '%s\n' "$after"

[ "$before" = "$after" ] ||
	fail "app data or grant changed across the restart:
before:
$before
after:
$after"

say "PASS: process replaced; listener and both accessibility services rebound; preferences and grants unchanged"
