#!/usr/bin/env bash
set -uo pipefail

# Collect visual/live-site evidence even when a test fails, then preserve Gradle's status.
timeout --signal=TERM --kill-after=30s 420s ./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace "-Pandroid.testInstrumentationRunnerArguments.liveWebsites=${LIVE_WEBSITES:-false}"
test_status=$?
report_dir=app/build/reports/webview
mkdir -p "$report_dir"
timeout 15s adb logcat -d -s OpenTabsLiveWeb:I > "$report_dir/live-sites.txt" || true
if [ "$test_status" -ne 0 ]; then
  timeout 15s adb logcat -d -t 3000 > "$report_dir/device-errors.txt" || true
  timeout 15s adb shell dumpsys activity activities > "$report_dir/activity-state.txt" || true
fi
for screenshot in webview-songsterr.png webview-ultimate_guitar.png; do
  if timeout 10s adb shell run-as org.opentabs.app test -f "cache/$screenshot"; then
    timeout 15s adb exec-out run-as org.opentabs.app cat "cache/$screenshot" > "$report_dir/$screenshot" || true
  fi
done
exit "$test_status"
