#!/bin/bash
# Full app flow in the emulator: Shizuku -> PadTap overlay -> virtual pad -> touches on ProbeActivity
exec > >(tee e2e.log) 2>&1
set -x
adb install -r shizuku.apk
adb install -r padtap.apk
SZ=$(adb shell pm path moe.shizuku.privileged.api | head -1 | sed 's/package://' | tr -d '\r')
adb shell "$(dirname "$SZ")/lib/x86_64/libshizuku.so"
sleep 3
adb shell appops set io.padtap SYSTEM_ALERT_WINDOW allow
adb shell pm grant io.padtap android.permission.POST_NOTIFICATIONS
adb shell wm size 720x1440
adb shell wm density 300
sleep 2
SIZE=$(adb shell wm size | tail -1 | sed 's/.*: //' | tr -d '\r'); W=${SIZE%x*}; H=${SIZE#*x}

adb shell am start -W -n io.padtap/.MainActivity
sleep 3
adb exec-out screencap -p > shot1-main.png
python3 android/ci/ui.py '許可する' --optional
sleep 3
adb exec-out screencap -p > shot2-shizuku-dialog.png
python3 android/ci/ui.py '^(Allow all the time|常に許可)$' --optional
sleep 3
adb exec-out screencap -p > shot3-main-ready.png
python3 android/ci/ui.py '開始する'
sleep 4
adb exec-out screencap -p > shot4-running.png
adb logcat -c
adb shell am start -W -n io.padtap/.ProbeActivity
sleep 2

APK=$(adb shell pm path io.padtap | head -1 | sed 's/package://' | tr -d '\r')
LIB="$(dirname "$APK")/lib/x86_64/libpadtap.so"
adb shell "CLASSPATH=$APK app_process -Dpadtap.lib=$LIB -Dpadtap.mode=pad /system/bin io.padtap.SelfTest" > pad.log 2>&1 &
PADPID=$!
sleep 11
adb exec-out screencap -p > shot5-setup.png
wait $PADPID
cat pad.log
sleep 1
adb exec-out screencap -p > shot6-after.png
# open the menu panel via the floating icon (right edge, 40% height)
adb shell input tap $((W - 40)) $((H * 4 / 10))
sleep 2
adb exec-out screencap -p > shot7-panel.png
adb logcat -d -s PadTapProbe:I > e2e-probe.log
adb logcat -d -s AndroidRuntime:E PadTapSvc:* PadTapInj:* >> e2e.log
cat e2e-probe.log
n=$(grep -c 'touch ACTION_DOWN' e2e-probe.log)
echo "E2E touch downs: $n"
if grep -q 'touch ACTION_MOVE' e2e-probe.log && [ "$n" -ge 2 ]; then echo "E2E PASS"; else echo "E2E FAIL"; fi
