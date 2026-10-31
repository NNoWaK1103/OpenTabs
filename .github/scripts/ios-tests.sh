#!/usr/bin/env bash
set -euo pipefail

# Select an available iPhone by UDID rather than assuming a particular runtime/model.
simulator_id=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
for devices in json.load(sys.stdin)["devices"].values():
    for device in devices:
        if device["name"].startswith("iPhone"):
            print(device["udid"])
            sys.exit(0)
raise SystemExit("No available iPhone simulator. Install an iOS runtime in Xcode.")
')
xcodebuild test \
  -project iosApp/OpenTabs.xcodeproj \
  -scheme OpenTabs \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$simulator_id" \
  -derivedDataPath iosApp/DerivedData \
  -resultBundlePath iosApp/TestResults.xcresult \
  CODE_SIGNING_ALLOWED=NO
