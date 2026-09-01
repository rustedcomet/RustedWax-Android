#!/bin/sh
# Real process death and real listener reconnect, orchestrated from the host.
#
# ## Why this is a script and not a test
#
# `RustedWaxListenerService.onListenerDisconnected` / `onListenerConnected` are
# process/binding events. An instrumented test lives inside the very process the
# event destroys, so it cannot provoke one and survive to assert on it — measured
# on <redacted-device-model> / API 31, the only rebind observed during an instrumentation run
# was the one caused by the runner's own process dying, after the assertions had
# gone with it. `NotificationListenerService.requestRebind` is documented for use
# after `requestUnbind()` and is a no-op while the listener is still bound; 20 s
# of polling produced nothing.
#
# So the boundary is crossed from outside the process, which is what
# `<redacted-private-path>` asks for: "orchestrate it from a host-side `adb`
# script using non-destructive process death/rebind steps, with state/grant
# hashes recorded before and after."
#
# ## Non-destructive by construction
#
# ActivityManager injects a process crash without putting the package into
# Android's force-stopped state. The distinction is load-bearing on this API-31
# device: `am force-stop` clears the accessibility master. A same-UID SIGKILL
# would have been narrower, but SELinux correctly denies the `run-as_app` domain
# permission to signal the app process even though both have the same Linux UID.
# There is no setting write, `pm clear`, uninstall or install.
#
# Usage: tools/device/process-restart.sh [serial]
set -eu

serial="${1:-R58R215V2SA}"
adb="<redacted-local-path>/Library/Android/sdk/platform-tools/adb"
pkg="com.rustedwax.app"
prefs="rustedwax_keys.xml rustedwax_settings.xml rustedwax_youtube_session.xml"

say() { printf '%s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

state() {
	for f in $prefs; do
		printf '%s ' "$f"
		$adb -s "$serial" shell "run-as $pkg sha256sum shared_prefs/$f 2>/dev/null" |
			tr -d '\r' | awk '{print $1}'
	done
	printf 'listener '
	$adb -s "$serial" shell settings get secure enabled_notification_listeners |
		tr ':' '\n' | grep -c "$pkg" | tr -d '\r'
	# Accessibility is three separate facts and all matter: the services may be
	# listed while the master flag is off, in which case Android binds neither.
	# Measured 2026-08-13: the old `am force-stop` implementation turned `accessibility_enabled`
	# from 1 to 0, silently disabling the native/PiP evidence path. An earlier
	# version of this script only hashed preferences and the listener grant, so it
	# reported "unchanged" across exactly that loss.
	printf 'a11y-listed '
	$adb -s "$serial" shell settings get secure enabled_accessibility_services |
		tr ':' '\n' | grep -c "$pkg" | tr -d '\r'
	printf 'a11y-enabled '
	$adb -s "$serial" shell settings get secure accessibility_enabled | tr -d '\r'
	printf 'a11y-bound '
	$adb -s "$serial" shell dumpsys accessibility |
		sed -n '/bound services:{/,/crashed services:/p' |
		grep -c "id=$pkg/" | tr -d '\r' || true
}

pid_of() { $adb -s "$serial" shell pidof "$pkg" | tr -d '\r' | awk '{print $1}'; }

say "== device =="
$adb -s "$serial" shell getprop ro.product.model | tr -d '\r'

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

$adb -s "$serial" logcat -c

say "== ActivityManager crash (process death without force-stop) =="
case "$pid_before" in
	*[!0-9]*|'') fail "unsafe pid: $pid_before" ;;
esac
$adb -s "$serial" shell am crash "$pkg" >/dev/null

say "== waiting for the system to rebind the listener =="
connected=""
i=0
while [ "$i" -lt 60 ]; do
	if $adb -s "$serial" logcat -d |
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
	bound=$($adb -s "$serial" shell dumpsys accessibility |
		sed -n '/bound services:{/,/crashed services:/p' |
		grep -c "id=$pkg/" | tr -d '\r' || true)
	master=$($adb -s "$serial" shell settings get secure accessibility_enabled | tr -d '\r')
	[ "$master" = "1" ] && [ "$bound" = "2" ] && break
	sleep 1
	i=$((i + 1))
done
[ "${master:-}" = "1" ] && [ "${bound:-}" = "2" ] ||
	fail "accessibility did not recover: master=${master:-?}, bound=${bound:-?}"

say "== production reconnect evidence =="
$adb -s "$serial" logcat -d |
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
