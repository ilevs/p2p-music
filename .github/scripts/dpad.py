"""Moves the D-pad focus until the focused element shows <target>, pressing <key> (UP, DOWN, LEFT, RIGHT) up to <max>
times. <target> is a text or content description the focused element or its children contain, or "class:<name>".
Prints what is focused at each step. Usage: dpad.py <target> <key> [max], or dpad.py press <key>...: presses keys."""
import subprocess
import sys
import xml.etree.ElementTree as ET

# Linux key codes of the emulator's hardware keyboard, which Android reads as a D-pad like a TV remote's.
# "adb shell input" keys come from a virtual device, and Compose text fields ignore D-pad keys from those.
CODES = {"UP": 103, "DOWN": 108, "LEFT": 105, "RIGHT": 106, "CENTER": 232, "BACK": 158}


def press(key):
    code = CODES[key]
    subprocess.run(["adb", "emu", "event", "send", f"EV_KEY:{code}:1", "EV_SYN:0:0", f"EV_KEY:{code}:0", "EV_SYN:0:0"],
                   check=True, capture_output=True)


def labels(node):
    return [n.get("text", "") + " " + n.get("content-desc", "") for n in node.iter("node")]


def focused():
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], check=True, capture_output=True)
    xml = subprocess.run(["adb", "shell", "cat", "/sdcard/ui.xml"], check=True, capture_output=True).stdout
    # The innermost focused node: a window's root view also reports focus.
    nodes = [n for n in ET.fromstring(xml).iter("node") if n.get("focused") == "true"]
    return nodes[-1] if nodes else None


def matches(node, target):
    if node is None:
        return False
    if target.startswith("class:"):
        return node.get("class") == target[6:]
    return any(target in label for label in labels(node))


def main():
    if sys.argv[1] == "press":
        for key in sys.argv[2:]:
            press(key)
        return 0
    target, key = sys.argv[1], sys.argv[2]
    presses = int(sys.argv[3]) if len(sys.argv) > 3 else 10
    for i in range(presses + 1):
        node = focused()
        shown = "nothing" if node is None else node.get("class", "") + " " + " | ".join(filter(str.strip, labels(node)))[:120]
        print(f"focus: {shown}")
        if matches(node, target):
            return 0
        if i < presses:
            press(key)
    print(f"No focus on {target} after {presses} presses of {key}")
    # Which device and source the last keys came from, and whether the on-screen keyboard took them.
    dump = subprocess.run(["adb", "shell", "dumpsys", "input"], capture_output=True, text=True).stdout
    print("\n".join(line.strip()[:200] for line in dump.splitlines() if "KeyEvent" in line or "mInputShown" in line)[-3000:])
    ime = subprocess.run(["adb", "shell", "dumpsys", "input_method"], capture_output=True, text=True).stdout
    print("\n".join(line.strip() for line in ime.splitlines() if "mInputShown" in line or "mCurMethodId" in line))
    return 1


# Self-check of the matcher, so a typo here does not read as an app that cannot be reached by remote.
_row = ET.fromstring('<node class="android.view.View" focused="true"><node text="Pink Floyd · 1973" /></node>')
assert matches(_row, "Pink Floyd · 1973") and not matches(_row, "Найти") and matches(_row, "class:android.view.View")
sys.exit(main())
