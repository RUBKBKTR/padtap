#!/usr/bin/env python3
"""tap a UI element by text regex using uiautomator dump: ui.py <regex> [--optional]"""
import re, subprocess, sys, time
pat = re.compile(sys.argv[1])
optional = '--optional' in sys.argv
for attempt in range(8):
    subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml'], capture_output=True)
    xml = subprocess.run(['adb', 'shell', 'cat', '/sdcard/ui.xml'], capture_output=True, text=True).stdout
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if m.group(1) and pat.search(m.group(1)):
            x = (int(m.group(2)) + int(m.group(4))) // 2
            y = (int(m.group(3)) + int(m.group(5))) // 2
            print(f'tap "{m.group(1)}" at {x},{y}')
            subprocess.run(['adb', 'shell', 'input', 'tap', str(x), str(y)])
            sys.exit(0)
    time.sleep(1.5)
print('NOT FOUND:', sys.argv[1])
print(re.findall(r'text="([^"]+)"', xml)[:40])
sys.exit(0 if optional else 1)
