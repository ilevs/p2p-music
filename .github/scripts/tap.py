"""Taps the first on-screen element whose text contains the argument. Usage: tap.py <text>."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET



def tap(text):
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], check=True, capture_output=True)
    xml = subprocess.run(["adb", "shell", "cat", "/sdcard/ui.xml"], check=True, capture_output=True).stdout
    for node in ET.fromstring(xml).iter("node"):
        if text in node.get("text", "") + node.get("content-desc", ""):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            subprocess.run(["adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)], check=True)
            return True
    return False


# Slow CI emulators sometimes show "System UI / Launcher isn't responding" over the app; wait it out.
for _ in range(3):
    if tap(sys.argv[1]):
        sys.exit(0)
    if not tap("Wait"):
        break
print("No element with text: " + sys.argv[1])
sys.exit(1)
