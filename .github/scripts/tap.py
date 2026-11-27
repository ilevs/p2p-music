"""Taps the first on-screen element whose text contains the argument. Usage: tap.py <text>."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], check=True, capture_output=True)
xml = subprocess.run(["adb", "shell", "cat", "/sdcard/ui.xml"], check=True, capture_output=True).stdout
for node in ET.fromstring(xml).iter("node"):
    if sys.argv[1] in node.get("text", "") + node.get("content-desc", ""):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        subprocess.run(["adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)], check=True)
        sys.exit(0)
print("No element with text: " + sys.argv[1])
sys.exit(1)
