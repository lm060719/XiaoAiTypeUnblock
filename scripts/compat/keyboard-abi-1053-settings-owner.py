"""Give the settings binder its own owner ID in the 0.2.1053 reference (qh.c -> qh.y).

The 0.2.1053 settings composable and preference binder share class qh.c; 0.2.1246 moved the
binder to another class. The settings group therefore names the owner qh.y in every build,
while keyboard.surfaceText keeps qh.c. Run on update-keyboard-abi-reference.ps1 output for
the 0.2.1053 APK:

usage: keyboard-abi-1053-settings-owner.py <keyboard-abi.json>
"""
import json
import sys

GROUP = "keyboard.settings"


def main(path):
    data = json.load(open(path, encoding="utf-8"))
    renamed = {}
    for rid in data["groups"][GROUP]:
        if rid == "c:qh.c" or rid.startswith("m:qh.c#"):
            renamed[rid] = rid.replace("qh.c", "qh.y", 1)
    if not renamed:
        raise SystemExit("already converted")
    data["groups"][GROUP] = [renamed.get(rid, rid) for rid in data["groups"][GROUP]]
    for section in ("rules", "reference"):
        entries = {}
        for rid, value in data[section].items():
            if rid in renamed:
                # Keep the shared ID (surfaceText still uses qh.c) and add the owner ID beside it.
                if any(rid in ids for group, ids in data["groups"].items() if group != GROUP):
                    entries[rid] = value
                entries[renamed[rid]] = value
            else:
                entries[rid] = value
        data[section] = entries
    with open(path, "w", encoding="utf-8") as output:
        # Match the probe's org.json output, which escapes slashes.
        output.write(json.dumps(data, ensure_ascii=False, indent=2).replace("/", "\/"))
    print("renamed:", renamed)


if __name__ == "__main__":
    main(sys.argv[1])
