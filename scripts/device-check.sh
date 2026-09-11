#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?Pass the explicitly selected, unlocked test device serial}"
adb_command="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
log="artifacts/device-checks-${serial//[^[:alnum:]_.-]/_}.log"
"$adb_command" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_command" -s "$serial" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb_command" -s "$serial" shell run-as com.captionglass.app mkdir -p files/models files/fixtures
tar -C artifacts/models -cf - x-asr-zh-en-480ms pengcheng-8lang-int8 hy-mt2-1.8b-q4-k-m |
  "$adb_command" -s "$serial" exec-in run-as com.captionglass.app tar -xf - -C files/models
tar -C artifacts/fixtures/wav -cf - en.wav zh.wav ja.wav |
  "$adb_command" -s "$serial" exec-in run-as com.captionglass.app tar -xf - -C files/fixtures
"$adb_command" -s "$serial" shell am instrument -w -r -e mode all \
  com.captionglass.app.test/com.captionglass.app.DeviceChecks > "$log" &
runner_pid=$!
# Some OEMs freeze instrumentation before its first Activity can be scheduled.
sleep 2
"$adb_command" -s "$serial" shell am start -n com.captionglass.app/.MainActivity
wait "$runner_pid"
cat "$log"
rg -q 'PASS: all' "$log"
