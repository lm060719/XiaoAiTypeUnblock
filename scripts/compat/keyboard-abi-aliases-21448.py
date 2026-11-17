"""Project reviewed 0.2.1358 keyboard ABI IDs onto 0.2.1448.

Usage: python keyboard-abi-aliases-21448.py <21358-aliases.json>
    <projected-21448.json> <output.json>

The eight overrides below were checked against the 21448 DEX class and method
signatures. The two Compose methods replaced a callback parameter with boolean.
"""
import json
import sys


OVERRIDES = {
    "m:z8.p#l(I)V": "Lv8/v1;->q(I)V",
    "m:z8.p#p(I)V": "Lv8/v1;->w(I)V",
    "m:z8.q#cursorEditing()Z": "Lv8/w1;->D()Z",
    "m:z8.q#deleteAtCursor(I)Lz8/r;": "Lv8/w1;->A(I)Lv8/x1;",
    "m:z8.q#e()Z": "Lv8/w1;->c()Z",
    "m:z8.q#i(I)Lz8/r;": "Lv8/w1;->h(I)Lv8/x1;",
    "m:z9.u7#S(Lz8/a;Le1/o;FFLb1/c;Lkotlin/jvm/functions/Function0;Ls0/p;I)V":
        "Lfa/za;->H(Lz8/a;Lb1/l;FFZLkotlin/jvm/functions/Function0;Lp0/r;I)V",
    "m:z9.u7#i0(Ljava/lang/String;Lkotlin/jvm/functions/Function0;Le1/o;FFFLh2/g0;Lb1/c;Ls0/p;II)V":
        "Lfa/za;->Y(Ljava/lang/String;Lkotlin/jvm/functions/Function0;Lb1/l;FFFLh2/g0;ZLp0/r;II)V",
}

ADDED = {
    "m:z8.q#inputAtCursor(IC)Lz8/r;": "Lv8/w1;->p(IC)Lv8/x1;",
    "m:z8.p#rawCursor()I": "Lv8/v1;->r()I",
}


def main(source, projection, output):
    old = json.load(open(source, encoding="utf-8"))
    mapped = json.load(open(projection, encoding="utf-8"))
    assert set(old["targets"]) == set(mapped) | set(OVERRIDES)
    mapped.update(OVERRIDES)
    reference = old["reference"].copy()
    for key in list(OVERRIDES)[-2:]:
        reference[key] = reference[key].replace("Lb1/c;", "Z")
    groups = {name: list(ids) for name, ids in old["groups"].items()}
    for key, target in ADDED.items():
        mapped[key] = target
        reference[key] = "L" + key[2:].replace(".", "/").replace("#", ";->")
        groups["keyboard.engine"].append(key)
    result = {"groups": groups, "targets": mapped, "reference": reference}
    with open(output, "w", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=1, sort_keys=True)
    print(f"Mapped {len(mapped)} keyboard ABI entries")


if __name__ == "__main__":
    main(*sys.argv[1:4])
