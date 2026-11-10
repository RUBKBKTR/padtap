#!/bin/bash
# Runs inside the emulator job. Produces selftest.log, probe.log, test-status.txt
adb install -r padtap.apk
APK=$(adb shell pm path io.padtap | head -1 | sed 's/package://' | tr -d '\r')
LIB="$(dirname "$APK")/lib/x86_64/libpadtap.so"
SIZE=$(adb shell wm size | tail -1 | sed 's/.*: //' | tr -d '\r')
W=${SIZE%x*}; H=${SIZE#*x}
echo "apk=$APK lib=$LIB size=${W}x${H}"
adb shell ls -l "$LIB"
adb shell id
adb logcat -c
adb shell am start -W -n io.padtap/.ProbeActivity
sleep 3
adb shell "CLASSPATH=$APK app_process -Dpadtap.lib=$LIB -Dpadtap.tap=$((W/2)),$((H/2)) /system/bin io.padtap.SelfTest" 2>&1 | tee selftest.log
sleep 1
adb logcat -d -s PadTapProbe:I > probe.log
adb logcat -d -s PadTapSvc:* PadTapInj:* AndroidRuntime:* >> selftest.log
cat probe.log

fail=0
chk() { if eval "$1"; then echo "PASS $2"; else echo "FAIL $2"; fail=1; fi; }
{
  chk "grep -q 'SELFTEST fails=0' selftest.log" "service self-test"
  chk "grep -q 'touch ACTION_DOWN' probe.log" "injected touch down reached app"
  chk "grep -q 'ACTION_POINTER_DOWN' probe.log" "injected 2nd finger reached app"
  chk "grep -q 'touch ACTION_UP' probe.log" "injected touch up reached app"
  chk "grep -q 'ACTION_MOVE' probe.log" "injected drag reached app"
  chk "! grep -q 'KEYCODE_BUTTON_B' probe.log" "grabbed B hidden from Android"
  chk "grep -q 'KEYCODE_BUTTON_X' probe.log" "ungrabbed X visible to Android"
} | tee test-status.txt
echo "overall fail=$fail" | tee -a test-status.txt
exit $fail
