#!/bin/sh
# Write rustedwax-update.json, the release asset the app's update check reads
# (Issue #43), from the version this checkout builds. Attach the file to the
# GitHub Release it describes, after the APK; the app reads it from
# releases/latest/download/rustedwax-update.json and refuses it when its
# versionName disagrees with that release's tag.
#
# Usage: tools/release/update-metadata.sh [output-file]
# Writes to stdout without an argument. Changes nothing in the repository.
set -eu

root="$(cd "$(dirname "$0")/../.." && pwd)"
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

code="$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\)[[:space:]]*$/\1/p' "$root/app/build.gradle.kts")"
name="$(sed -n 's/^[[:space:]]*version = "\([0-9]*\.[0-9]*\.[0-9]*\)"[[:space:]]*$/\1/p' "$root/build.gradle.kts")"
[ "$(printf '%s\n' "$code" | wc -l | tr -d ' ')" = 1 ] && [ -n "$code" ] || fail "exactly one numeric versionCode not found"
[ "$(printf '%s\n' "$name" | wc -l | tr -d ' ')" = 1 ] && [ -n "$name" ] || fail "exactly one x.y.z version not found"

json="$(printf '{\n  "schema": 1,\n  "applicationId": "com.rustedwax.app",\n  "versionCode": %s,\n  "versionName": "%s"\n}\n' "$code" "$name")"
if [ $# -ge 1 ]; then printf '%s\n' "$json" > "$1"; else printf '%s\n' "$json"; fi
