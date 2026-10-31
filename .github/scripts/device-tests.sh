#!/usr/bin/env bash
set -uo pipefail

# Collect visual/live-site evidence even when a test fails, then preserve Gradle's status.
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace
test_status=$?
report_dir=app/build/reports/webview
mkdir -p "$report_dir"
adb logcat -d -s OpenTabsLiveWeb:I > "$report_dir/live-sites.txt" || true
for screenshot in webview-songsterr.png webview-ultimate_guitar.png; do
  if adb shell run-as org.opentabs.app test -f "cache/$screenshot"; then
    adb exec-out run-as org.opentabs.app cat "cache/$screenshot" > "$report_dir/$screenshot" || true
  fi
done
exit "$test_status"
