#!/usr/bin/env bash
set -euo pipefail

apk="${1:?Pass the signed APK to test}"
package="com.yaroslav.habittracker"
activity="$package/.MainActivity"
ui_path="/sdcard/habittracker-startup.xml"
ui_snapshot="$(mktemp)"
trap 'rm -f "$ui_snapshot"' EXIT

adb install -r "$apk"
adb shell am force-stop "$package"
adb shell am start -W -n "$activity"

for _ in {1..5}; do
  adb shell rm -f "$ui_path"
  if adb shell uiautomator dump "$ui_path" && adb pull "$ui_path" "$ui_snapshot" &&
     grep -Fq 'package="com.yaroslav.habittracker"' "$ui_snapshot" &&
     grep -Fq 'text="Сегодня"' "$ui_snapshot"; then
    exit 0
  fi
  sleep 2
done

printf '%s\n' 'The signed release did not render the Today screen.' >&2
adb logcat -d -b crash -t 120
exit 1
