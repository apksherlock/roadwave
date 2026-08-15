#!/usr/bin/env bash
#
# Roadwave — Android Auto head-unit diagnostics
#
# Run this on YOUR machine with the test phone connected over USB (adb), then
# plug the phone into the Opel Intellilink head unit while it is running.
# The sandbox Claude works in has no adb and no USB access, so this has to run locally.
#
#   ./tools/diagnose-android-auto.sh          # full capture
#   ./tools/diagnose-android-auto.sh preflight  # static checks only, no car needed
#
set -uo pipefail

PKG="com.apksherlock.roadwave"
OUT="${OUT:-android-auto-diagnostics.log}"

command -v adb >/dev/null 2>&1 || { echo "adb not on PATH — add \$ANDROID_HOME/platform-tools"; exit 1; }

section() { printf '\n\n===== %s =====\n' "$1" | tee -a "$OUT"; }

preflight() {
  : > "$OUT"

  section "Devices"
  adb devices -l 2>&1 | tee -a "$OUT"

  section "Installed Roadwave packages (watch for debug/release coexistence)"
  adb shell pm list packages -f "$PKG" 2>&1 | tee -a "$OUT"

  section "Signing certificate of the installed build"
  # A release APK installed over a previously debug-signed one, or vice versa,
  # leaves Android Auto's trust state confused. Confirm only ONE signer is present.
  adb shell dumpsys package "$PKG" 2>&1 \
    | grep -iE 'versionCode|signatures|pkgFlags|firstInstallTime|lastUpdateTime' \
    | tee -a "$OUT"

  section "Declared car metadata on the installed build"
  # Verifies the APK on the phone actually carries the manifest you think it does.
  adb shell dumpsys package "$PKG" 2>&1 \
    | grep -iE 'minCarApiLevel|car\.app|MEDIA_TEMPLATES|CarAppService|TintableAttribution' \
    | tee -a "$OUT"

  section "Android Auto host version on the phone"
  # This is the component that decides the Car App API level — NOT the head unit.
  adb shell dumpsys package com.google.android.projection.gearhead 2>&1 \
    | grep -iE 'versionName|versionCode' | head -5 | tee -a "$OUT"

  section "Android Auto developer settings"
  # Both of these must be enabled for a sideloaded, non-Play-approved build to
  # appear on a REAL head unit. DHU is more permissive, which is exactly the
  # asymmetry you are seeing.
  adb shell "settings get secure android_auto_dev_mode" 2>&1 | tee -a "$OUT"
  adb shell "dumpsys package com.google.android.projection.gearhead | grep -i unknown" 2>&1 | tee -a "$OUT"
  echo "NOTE: verify manually in Android Auto > Settings > Developer settings:" | tee -a "$OUT"
  echo "  - 'Unknown sources'          must be ON" | tee -a "$OUT"
  echo "  - 'Enable CAL Beta Features' must be ON (templated media apps are in beta)" | tee -a "$OUT"

  section "Does the host resolve our CarAppService?"
  adb shell "cmd package query-services --components -a androidx.car.app.CarAppService" 2>&1 \
    | tee -a "$OUT"
  echo "--- filtered to MEDIA category ---" | tee -a "$OUT"
  adb shell "cmd package query-services -a androidx.car.app.CarAppService -c androidx.car.app.category.MEDIA" 2>&1 \
    | tee -a "$OUT"
}

capture() {
  section "Live logcat — connect the phone to the head unit NOW (Ctrl-C to stop)"
  adb logcat -c
  # RoadwaveCar   : our own diagnostics (carAppApiLevel, token registration)
  # CarApp/CarHost: the Android Auto host's app-filtering decisions
  adb logcat -v time \
    | grep --line-buffered -iE 'RoadwaveCar|carapp|CarAppService|CarHost|gearhead|projection|roadwave|template|validat|reject|denied|not supported|unknown source' \
    | tee -a "$OUT"
}

preflight
if [ "${1:-}" != "preflight" ]; then
  capture
fi

echo
echo "Saved to $OUT"
echo
echo "What to look for:"
echo "  1. 'carAppApiLevel' in the RoadwaveCar block. Compare it to the value you"
echo "     saw under DHU — both run the SAME phone-side host, so if they match,"
echo "     API-level filtering is NOT the cause."
echo "  2. Any host line mentioning reject / denied / not allowlisted / unknown source."
echo "  3. Whether onCreateSession() is logged at all. If it never appears, the host"
echo "     filtered the app before starting it; if it does appear, the app is"
echo "     reaching the car and the problem is in template rendering instead."
