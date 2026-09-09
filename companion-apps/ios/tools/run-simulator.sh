#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
python3 tools/generate-project.py
if ! xcodebuild -project YanoCompanion.xcodeproj -target YanoCompanion \
  -sdk iphonesimulator -configuration Debug \
  SYMROOT="$PWD/build/Products" OBJROOT="$PWD/build/Intermediates" \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- \
  CODE_SIGN_ENTITLEMENTS=tools/simulator.entitlements build > build/simulator-build.log 2>&1; then
  tail -60 build/simulator-build.log
  exit 1
fi
companion_simulator_id="${1:-}"
if [ -z "$companion_simulator_id" ]; then
  companion_simulator_id=$(xcrun simctl list devices available -j | python3 -c '
import sys,json
all_devices=json.load(sys.stdin)["devices"]
phones=[d for runtime,devices in all_devices.items() if "iOS" in runtime for d in devices if d.get("isAvailable") and "iPhone" in d["name"]]
if not phones: sys.exit("Install an iPhone simulator runtime in Xcode Components first.")
print(next((d for d in phones if d["state"] == "Booted"), phones[-1])["udid"])
')
fi
xcrun simctl bootstatus "$companion_simulator_id" -b
xcrun simctl install "$companion_simulator_id" build/Products/Debug-iphonesimulator/YanoCompanion.app
xcrun simctl launch "$companion_simulator_id" com.bloxbean.yano.companion
printf 'Built and launched Yano Companion on simulator %s\n' "$companion_simulator_id"
