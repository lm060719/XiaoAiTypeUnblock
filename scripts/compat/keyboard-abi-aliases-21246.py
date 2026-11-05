"""Map keyboard ABI reference IDs onto host 0.2.1246 for KeyboardAbiReferenceProbe.

IDs keep the 0.2.1053 labels used by the bridges. Each target was matched by
bytecode similarity and then reviewed against JADX output of both builds;
`projected` holds the reviewed automatic matches, OVERRIDES the manual ones.

usage: keyboard-abi-aliases-21246.py <keyboard-abi.json> <projected.json> <output.json>

The output used for keyboard-abi-21246.json is kept as keyboard-abi-aliases-21246.json:
  update-keyboard-abi-reference.ps1 -TargetApk <0.2.1246 apk> -Aliases scripts/compat/keyboard-abi-aliases-21246.json
      -OutputFile app/src/main/resources/xatype/keyboard-abi-21246.json
"""
import json
import re
import sys

# Settings binds its single-choice preference through a static helper that R8
# moved out of the class holding the surface-text composable in 0.2.1246.
SETTINGS_OWNER = "qh.y"

OVERRIDES = {
    "c:z8.u": "La9/u;",
    "c:y8.b": "Lz8/e;",
    "m:z8.p#l(I)V": "La9/p;->o(I)V",
    "f:ka.c#p": "Lta/d;->p:Ljava/lang/Integer;",
    "f:ka.c#q": "Lta/d;->q:Z",
    "m:c2.f0#c()J": "Lc2/f0;->g()J",
    "c:qh.c": "Lvh/b;",
    "c:" + SETTINGS_OWNER: "Lo5/s;",
    # Releases a one-shot shift after an uppercase commit.
    "m:ab.b1#h()V": "Ljb/t0;->h()V",
}

# IDs whose signature changed: ID (or (group, ID)) -> (new label ID, target).
CHANGED = {
    # shiftState parameter removed.
    "m:ab.p1#e(Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/String;ZLla/a;)Lka/g;":
        ("m:ab.p1#e(Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/String;Z)Lka/g;",
         "Ljb/g1;->e(Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/String;Z)Lta/h;"),
    # Key renderer gained a slot/flag pair before its layout parameters.
    "m:z9.w5#a(Lka/c;Lma/k;Le1/o;FFLz9/l5;FFZZLbd/b;Lkotlin/jvm/functions/Function0;Lbd/b;Lbd/b;Lbd/b;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lbd/b;ZLkotlin/jvm/functions/Function0;ZLs0/p;III)V":
        (None, "Lja/h8;->a(Lta/d;Lva/j;Le1/o;FFLja/u7;ZLja/v7;FFZZLqd/b;Lkotlin/jvm/functions/Function0;Lqd/b;Lqd/b;Lqd/b;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Lqd/b;ZLkotlin/jvm/functions/Function0;ZLs0/p;III)V"),
    # Clipboard chip gained a Modifier parameter.
    "m:z9.u7#i0(Ljava/lang/String;Lkotlin/jvm/functions/Function0;FFLs0/p;II)V":
        (None, "Lja/ia;->Y(Ljava/lang/String;Lkotlin/jvm/functions/Function0;Le1/o;FFLs0/p;II)V"),
    # Composing text row: the item parameter became an onCommit callback.
    "m:qh.c#h(Ljava/lang/String;Laa/c;Lkotlin/jvm/functions/Function0;Lfa/j;Lkotlin/jvm/functions/Function0;Le1/o;Ls0/p;I)V":
        (None, "Lvh/b;->r(Ljava/lang/String;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Loa/h;Lkotlin/jvm/functions/Function0;Le1/o;Ls0/p;I)V"),
    # Moved to its own owner ID, see SETTINGS_OWNER.
    ("keyboard.settings", "m:qh.c#y(Ldg/u;Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Lbd/b;)V"):
        ("m:%s#y(Ldg/u;Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Lbd/b;)V" % SETTINGS_OWNER, None),
    ("keyboard.settings", "c:qh.c"): ("c:" + SETTINGS_OWNER, None),
}

# Members added in 0.2.1246 for the "全部/单字" candidate filter, named so they
# never collide with existing callbacks.
ADDED = {
    "keyboard.engine": {
        "m:z8.p#singleCharFilterSupported()Z": "La9/p;->k()Z",
        "m:z8.p#filterSingleChar(Z)Lz8/r;": "La9/p;->n(Z)La9/r;",
    },
    "keyboard.layout": {"f:ka.c#s": "Lta/d;->s:Z"},
}


def main(reference_file, projected_file, output_file):
    reference = json.load(open(reference_file, encoding="utf-8"))
    targets = dict(json.load(open(projected_file, encoding="utf-8")))
    targets.update(OVERRIDES)
    targets["m:%s#y(Ldg/u;Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Lbd/b;)V" % SETTINGS_OWNER] = \
        "Lo5/s;->o(Lyg/t;Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;Lqd/b;)V"
    labels = {}  # target class -> label, for translating new signatures
    for rid, target in targets.items():
        if rid.startswith("c:") and rid != "c:" + SETTINGS_OWNER:
            labels[target] = "L" + rid[2:].replace(".", "/") + ";"

    def label(descriptor):
        return re.sub(r"L[^;]+;", lambda m: labels.get(m.group(0), m.group(0)), descriptor)

    groups, refs, out = {}, {}, {}
    for group, ids in reference["groups"].items():
        keys = []
        for rid in ids:
            change = CHANGED.get((group, rid)) or CHANGED.get(rid)
            new_id, target = change or (rid, None)
            new_id = new_id or rid
            target = target or targets.get(new_id) or targets.get(rid)
            if target is None:
                raise SystemExit("unmapped " + rid)
            if new_id.startswith("m:") and change and change[1]:
                owner, rest = new_id[2:].split("#", 1)
                name = rest.split("(", 1)[0]
                new_id = "m:%s#%s(%s" % (owner, name, label(target).split("(", 1)[1])
            keys.append(new_id)
            out[new_id] = target
            old = reference["reference"][rid]
            if new_id == rid:
                refs[new_id] = old
            elif new_id.startswith("c:"):
                refs[new_id] = old
            else:
                owner = old.split("->", 1)[0]
                refs[new_id] = owner + "->" + new_id.split("#", 1)[1]
        for rid, target in ADDED.get(group, {}).items():
            keys.append(rid)
            out[rid] = target
            owner = "L" + rid[2:].split("#")[0].replace(".", "/") + ";"
            member = rid.split("#", 1)[1]
            refs[rid] = owner + "->" + (member if rid.startswith("m:") else member + ":" + target.split(":")[-1])
        groups[group] = keys
    json.dump({"groups": groups, "targets": out, "reference": refs}, open(output_file, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1, sort_keys=True)
    print("aliases:", len(out), {g: len(k) for g, k in groups.items()})


if __name__ == "__main__":
    main(*sys.argv[1:4])
