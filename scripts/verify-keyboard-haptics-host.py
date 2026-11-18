"""Check the haptic binding against trusted local DEX indexes (no device required).

Usage: python scripts/verify-keyboard-haptics-host.py <host-index.pkl> [...]
Accepts indexes from scripts/compat/abi_match/index_dex.py and the older local format.
Only load indexes generated locally from trusted APKs; pickle is not an interchange format.
"""
import pickle
import re
import sys
from pathlib import Path


def verify(path):
    raw = pickle.loads(Path(path).read_bytes())
    modern = "classes" in raw and "methods" in raw
    classes = raw["classes"] if modern else raw
    methods = raw["methods"] if modern else {
        f"{owner}->{m['name']}{m['desc'].replace(' ', '')}": m
        for owner, entry in classes.items() for m in entry["methods"]
    }
    service = "Lcom/mi/ime/MiInputMethodService;"
    getters = [m for m in methods if m.startswith(service + "->getHapticFeedbackManager$") and "()" in m]
    assert len(getters) == 1, getters
    manager = getters[0].split(")")[1]
    contexts = [f for f in classes[manager]["fields"] if f[1] == "Landroid/content/Context;"]
    assert len(contexts) == 1, contexts
    dispatchers = []
    for descriptor, method in methods.items():
        if not descriptor.startswith(manager + "->") or not descriptor.endswith(")V"):
            continue
        params = re.findall(r"\[*L[^;]+;|\[*[ZBCSIJFD]", descriptor.split("(")[1].split(")")[0])
        if len(params) != 2 or params[0] not in classes:
            continue
        event = classes[params[0]]
        if event["sup"] != "Ljava/lang/Enum;":
            continue
        names = methods[params[0] + "-><clinit>()V"]["strs"]
        if "KEY_PRESS" in names:
            dispatchers.append(descriptor)
            assert {"KEY_PRESS", "LONG_PRESS", "ERROR", "SUCCESS", "BUTTON_LARGE"} <= set(names), names
            assert "vibration_enabled" in method["strs"], descriptor
    assert len(dispatchers) == 1, dispatchers
    dispatch = methods[dispatchers[0]]
    readers = {call.replace(" ", "") for call in dispatch.get("inv", dispatch.get("calls", []))
               if call.replace(" ", "").endswith("(ILjava/lang/String;)I")}
    assert len(readers) == 1, readers
    # Mirrors the string-anchored DexKit reader lookup without relying on obfuscated names.
    anchors = [m for m, entry in methods.items()
               if {"vibration_enabled", "vibration_mode", "vibration_intensity"} <= set(entry["strs"])
               and m.endswith(")V") and len(re.findall(r"\[*L[^;]+;|\[*[ZBCSIJFD]",
                   m.split("(")[1].split(")")[0])) == 2]
    assert anchors == dispatchers, anchors
    owner = "Landroidx/preference/SeekBarPreference;"
    constructor = owner + "-><init>(Landroid/content/Context;Landroid/util/AttributeSet;)V"
    assert constructor in methods, constructor
    assert classes[owner]["sup"] == "Landroidx/preference/Preference;"
    assert 100 in methods[constructor]["consts"], "native default maximum"
    pages = [m for m, entry in methods.items() if "kf_vibration_intensity" in entry["strs"]]
    assert len(pages) == 1, pages
    print(f"PASS {path}: {next(iter(readers))}; native SeekBarPreference constructor and vibration page")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    for index in sys.argv[1:]:
        verify(index)
