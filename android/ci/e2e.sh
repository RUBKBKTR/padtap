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
adb shell input tap $((W - 30)) $((H * 4 / 10))
sleep 2
adb exec-out screencap -p > shot7-panel.png
python3 android/ci/ui.py 'ボタン配置を設定' --optional
sleep 2
adb exec-out screencap -p > shot8-setup-from-menu.png
adb logcat -d -s PadTapProbe:I > e2e-probe.log
adb logcat -d -s AndroidRuntime:E PadTapSvc:* PadTapInj:* >> e2e.log
cat e2e-probe.log
n=$(grep -c 'touch ACTION_DOWN' e2e-probe.log)
echo "E2E touch downs: $n"
if grep -q 'touch ACTION_MOVE' e2e-probe.log && [ "$n" -ge 2 ]; then echo "E2E PASS"; else echo "E2E FAIL"; fi

# ---------- part 2: sticks, turbo, editors (config imported via adb) ----------
# close the menu panel (outside tap) and make sure the probe is in front
adb shell input tap 20 $((H - 30))
sleep 1
adb shell am start -W -n io.padtap/.ProbeActivity
sleep 1
CFG='{"profiles":[{"name":"E2E","btns":{"304":{"t":0,"x":0.5,"y":0.3,"m":1,"i":100,"s":64},"307":{"t":0,"x":0.5,"y":0.45,"m":0,"s":96}},"sticks":[{"m":2,"cx":0.25,"cy":0.75,"r":0.15,"d":0.1},{"m":3,"cx":0.75,"cy":0.75,"r":0.15,"u":true,"d":0.1,"sp":1}]}]}'
B64=$(printf '%s' "$CFG" | base64 -w0)
adb shell am broadcast -a io.padtap.IMPORT_CONFIG -n io.padtap/.ConfigReceiver --es b64 "$B64"
sleep 1
adb logcat -c
adb shell "CLASSPATH=$APK app_process -Dpadtap.lib=$LIB -Dpadtap.mode=pad2 /system/bin io.padtap.SelfTest" > pad2.log 2>&1 &
PADPID=$!
sleep 11
adb exec-out screencap -p > shot9-zones.png
sleep 0.5
adb shell input tap 339 338
sleep 1.3
adb exec-out screencap -p > shot9b-stick-editor.png
sleep 3.0
adb exec-out screencap -p > shot10-btn-editor.png
wait $PADPID
cat pad2.log
adb logcat -d -s PadTapProbe:I > e2e2-probe.log
cat e2e2-probe.log
python3 - <<'PY' | tee -a e2e.log
import re
W, H = 720, 1440
ev = []
for line in open('e2e2-probe.log'):
    m = re.search(r'touch (ACTION_\w+)(?:\(\d+\))? n=\d+ x=(-?\d+) y=(-?\d+)', line)
    if m: ev.append((m.group(1), int(m.group(2)), int(m.group(3))))
def near(a, b, tol=12): return abs(a - b) <= tol
ok = 0
joy_down = any(e[0] == 'ACTION_DOWN' and near(e[1], 0.25*W) and near(e[2], 0.75*H) for e in ev)
joy_move = any(e[0] == 'ACTION_MOVE' and near(e[1], 0.25*W + 0.15*W, 15) and near(e[2], 0.75*H) for e in ev)
joy_up = [e for e in ev if e[0] == 'ACTION_UP' and near(e[2], 0.75*H) and e[1] < 0.5*W]
joy_settled = bool(joy_up) and near(joy_up[0][1], 0.25*W + 0.15*W, 6)
sw_down = any(e[0] == 'ACTION_DOWN' and near(e[1], 0.75*W) and near(e[2], 0.75*H) for e in ev)
sw_up = any(e[0] == 'ACTION_MOVE' and near(e[1], 0.75*W) and e[2] < 0.75*H - 0.15*W - 20 for e in ev)
turbo = sum(1 for e in ev if e[0] == 'ACTION_DOWN' and near(e[1], 0.5*W) and near(e[2], 0.3*H))
res = [('joystick touch-down at center', joy_down), ('joystick moves to circle edge', joy_move), ('joystick settles at edge (%s)' % (joy_up[0][1] if joy_up else '-'), joy_settled),
       ('swipe touch-down at center', sw_down), ('swipe goes beyond radius (unlimited)', sw_up),
       ('turbo repeats (%d taps in ~0.8s hold)' % turbo, 6 <= turbo <= 10)]
for name, r in res: print(('E2E2 PASS ' if r else 'E2E2 FAIL ') + name)
print('E2E2 ' + ('ALL PASS' if all(r for _, r in res) else 'SOME FAIL'))
PY
