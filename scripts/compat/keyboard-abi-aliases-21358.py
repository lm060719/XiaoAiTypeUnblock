"""Map keyboard ABI reference IDs onto host 0.2.1358 for KeyboardAbiReferenceProbe.

0.2.1358 moved the keyboard engine behind a coroutine session: key handling,
commits, refreshes and engine switches became suspend functions, and engines
run on a dedicated thread behind a handle. IDs keep the 0.2.1053 labels; members
whose signature changed get new IDs, and the session members get new labels
(engine.*, candidate.*, flow.*).

`projected` (keyboard-abi-projected-21358.json) holds the automatic matches against
0.2.1246 from abi_match/ (index_dex.py both builds, match.py, then resolve.py),
reviewed against JADX output of both builds; TARGETS holds the manual ones.

usage: keyboard-abi-aliases-21358.py <aliases-21246.json> <projected.json> <output.json>
  keyboard-abi-aliases-21358.py scripts/compat/keyboard-abi-aliases-21246.json
      scripts/compat/keyboard-abi-projected-21358.json scripts/compat/keyboard-abi-aliases-21358.json

The output used for keyboard-abi-21358.json is kept as keyboard-abi-aliases-21358.json:
  update-keyboard-abi-reference.ps1 -TargetApk <0.2.1358 apk> -Aliases scripts/compat/keyboard-abi-aliases-21358.json
      -OutputFile app/src/main/resources/xatype/keyboard-abi-21358.json
"""
import json
import re
import sys

# Reviewed manually: the callbacks interface, renamed engine methods and UI owners.
TARGETS = {
    "c:e1.g": "Lb1/d;",
    "c:qh.c": "Lkb/e;",
    "c:qh.y": "Lr5/d;",
    "m:ab.b1#A()Ljava/lang/String;": "Lfb/n1;->C()Ljava/lang/String;",
    "m:ab.b1#F()V": "Lfb/n1;->E()V",
    "m:ab.b1#J()V": "Lfb/n1;->H()V",
    "m:ab.b1#M()I": "Lfb/n1;->N()I",
    "m:ab.b1#d()Z": "Lfb/n1;->b()Z",
    "m:ab.b1#f()Ljava/lang/String;": "Lfb/n1;->c()Ljava/lang/String;",
    "m:ab.b1#m()Lz8/g;": "Lfb/n1;->j()Lv8/i1;",
    "m:ab.b1#n()Z": "Lfb/n1;->k()Z",
    "m:ab.b1#o()Z": "Lfb/n1;->l()Z",
    "m:ab.b1#h()V": "Lfb/n1;->e()V",
    "m:androidx.compose.foundation.layout.a#a(Le1/o;Le1/g;)Le1/o;":
        "Landroidx/compose/foundation/layout/a;->a(Lb1/l;Lb1/d;)Lb1/l;",
    "m:z8.p#l(I)V": "Lv8/u1;->p(I)V",
    "m:z8.p#p(I)V": "Lv8/u1;->u(I)V",
    "m:z8.q#d(Ljava/lang/String;)Lz8/r;": "Lv8/v1;->b(Ljava/lang/String;)Lv8/w1;",
    "m:z8.q#e()Z": "Lv8/v1;->c()Z",
    "m:z8.q#i(I)Lz8/r;": "Lv8/v1;->h(I)Lv8/w1;",
    "m:z8.q#o(Ljava/lang/String;)Lz8/r;": "Lv8/v1;->s(Ljava/lang/String;)Lv8/w1;",
}

# Changed signatures: old ID -> (new ID in label space, target).
CHANGED = {
    "m:ab.b1#K(Ljava/lang/String;)V":
        ("m:ab.b1#K(Ljava/lang/String;Led/c;)Ljava/lang/Object;",
         "Lfb/n1;->x(Ljava/lang/String;Led/c;)Ljava/lang/Object;"),
    "m:ab.b1#y(Lz8/r;)V":
        ("m:ab.b1#y(Lz8/r;Lcd/c;)Ljava/lang/Object;", "Lfb/n1;->J(Lv8/w1;Lcd/c;)Ljava/lang/Object;"),
    "m:ab.b1#u()Lz8/q;": ("m:ab.b1#u()Lengine/Handle;", "Lfb/n1;->I()Lv8/h;"),
    "m:ab.f1#c(Lka/c;Lab/b1;)V":
        ("m:ab.f1#c(Lka/c;Lab/b1;Led/c;)Ljava/lang/Object;", "Lfb/z1;->c(Lpa/c;Lfb/n1;Led/c;)Ljava/lang/Object;"),
    # Time/date expansion is now inlined into candidate display.
    "m:ab.s0#l(Lz8/q;)V":
        ("m:ab.s0#l(Lengine/Handle;Lz8/r;Landroid/view/inputmethod/EditorInfo;Lbd/b;"
         "Lkotlin/jvm/functions/Function0;Lmd/d;)V",
         "Lfb/x0;->r(Lv8/h;Lv8/w1;Landroid/view/inputmethod/EditorInfo;Lmd/b;Lkotlin/jvm/functions/Function0;Lmd/d;)V"),
    # The engine factory became static and takes the manager first.
    "m:ab.x0#b(Lz8/g;)Lz8/q;":
        ("m:ab.x0#b(Lab/x0;Lz8/g;Lt7/e;)Lz8/q;", "Lfb/j1;->a(Lfb/j1;Lv8/i1;Lt7/e;)Lv8/v1;"),
    "m:com.mi.ime.MiInputMethodService#switchEngine$app_iflytekFullRelease(Lz8/g;)V":
        ("m:com.mi.ime.MiInputMethodService#switchEngine$app_iflytekFullRelease(Lz8/g;Lcd/c;)Ljava/lang/Object;",
         "Lcom/mi/ime/MiInputMethodService;->switchEngine$app_iflytekFullRelease(Lv8/i1;Lcd/c;)Ljava/lang/Object;"),
    "m:p0.n#b(Ll2/e;Le1/o;JJJJZIILjava/util/Map;Lbd/b;Ll2/h0;Ls0/p;I)V":
        ("m:p0.n#b(Lh2/d;Le1/o;JJJJIZIILjava/util/Map;Lbd/b;Lh2/g0;Ls0/p;III)V",
         "Lm0/n;->b(Lh2/d;Lb1/l;JJJJIZIILjava/util/Map;Lmd/b;Lh2/g0;Lp0/r;III)V"),
    "m:z9.u7#S(Ld9/a;Le1/o;FFLkotlin/jvm/functions/Function0;Ls0/p;I)V":
        ("m:z9.u7#S(Lz8/a;Le1/o;FFLb1/c;Lkotlin/jvm/functions/Function0;Ls0/p;I)V",
         "Lfa/ta;->H(Lz8/a;Lb1/l;FFLb1/c;Lkotlin/jvm/functions/Function0;Lp0/r;I)V"),
    "m:z9.u7#i0(Ljava/lang/String;Lkotlin/jvm/functions/Function0;Le1/o;FFLs0/p;II)V":
        ("m:z9.u7#i0(Ljava/lang/String;Lkotlin/jvm/functions/Function0;Le1/o;FFFLh2/g0;Lb1/c;Ls0/p;II)V",
         "Lfa/ta;->Y(Ljava/lang/String;Lkotlin/jvm/functions/Function0;Lb1/l;FFFLh2/g0;Lb1/c;Lp0/r;II)V"),
}

# Removed from a group: the onModeSelected lambda is read by its captured service,
# and candidate selection by index now needs the handler's private continuation.
REMOVED = {
    "keyboard.picker": {"c:gb.u0"},
    "keyboard.actions": {"m:ab.b1#b(I)V"},
}

SESSION = {
    "c:engine.Handle": "Lv8/h;",
    "f:engine.Handle#session": "Lv8/h;->a:Lv8/c1;",
    "c:engine.Session": "Lv8/c1;",
    "f:engine.Session#engine": "Lv8/c1;->n:Lv8/v1;",
    "c:engine.Command": "Lv8/k0;",
    "m:engine.Handle#send(Lengine/Command;Lcd/c;)Ljava/lang/Object;": "Lv8/h;->f(Lv8/k0;Lcd/c;)Ljava/lang/Object;",
    "c:engine.Key": "Lv8/y;",
    "c:engine.Backspace": "Lv8/p;",
    "c:engine.ReplaceRaw": "Lv8/f0;",
    "c:nc.m": "Lyc/m;",
    "f:nc.m#a": "Lyc/m;->a:Lyc/m;",
}

ADDED = {
    "keyboard.engine": dict(SESSION, **{
        "m:z8.q#insertAtCursor(CI)Lz8/r;": "Lv8/v1;->A(CI)Lv8/w1;",
        "m:z8.q#cursorEditing()Z": "Lv8/v1;->B()Z",
        "m:z8.q#engineName()Ljava/lang/String;": "Lv8/v1;->getName()Ljava/lang/String;",
        "m:z8.q#deleteAtCursor(I)Lz8/r;": "Lv8/v1;->y(I)Lv8/w1;",
        "m:z8.p#segmentState()I": "Lv8/u1;->t()I",
        "f:ab.s0#macro": "Lfb/x0;->g:Lae/t0;",
        "c:flow.State": "Lae/t0;",
        "m:flow.State#setValue(Ljava/lang/Object;)V": "Lae/t0;->i(Ljava/lang/Object;)V",
    }),
    "keyboard.actions": {
        "c:engine.Handle": "Lv8/h;",
        "m:engine.Handle#clear(Led/c;)Ljava/lang/Object;": "Lv8/h;->a(Led/c;)Ljava/lang/Object;",
        "c:candidate.Select": "Lt7/y0;",
        "m:candidate.Select#select(Lcom/mi/ime/MiInputMethodService;IZLed/c;)Ljava/lang/Object;":
            "Lt7/y0;->j(Lcom/mi/ime/MiInputMethodService;IZLed/c;)Ljava/lang/Object;",
    },
}

FIELD = re.compile(r"^f:([^#]+)#(.+)$")


def main(aliases_file, projected_file, output_file):
    previous = json.load(open(aliases_file, encoding="utf-8"))
    projected = json.load(open(projected_file, encoding="utf-8"))
    groups, targets, refs = {}, {}, {}

    def put(rid, target, ref):
        if targets.get(rid, target) != target:
            raise SystemExit("conflicting target " + rid)
        targets[rid], refs[rid] = target, ref

    def own_ref(rid, target):
        if rid.startswith("c:"):
            return "L" + rid[2:].replace(".", "/") + ";"
        owner = rid[2:].split("#", 1)[0]
        member = rid.split("#", 1)[1]
        if rid.startswith("m:"):
            return "L" + owner.replace(".", "/") + ";->" + member
        # Field references carry the type's label when the target type is labelled.
        ftype = target.split(":", 1)[1]
        label = next((k[2:] for k, v in list(targets.items()) + list(SESSION.items())
                      if k.startswith("c:") and v == ftype), None)
        return "L%s;->%s:%s" % (owner.replace(".", "/"), member,
                                "L" + label.replace(".", "/") + ";" if label else ftype)

    for group, ids in previous["groups"].items():
        keys = []
        for rid in ids:
            if rid in REMOVED.get(group, ()):
                continue
            if rid in CHANGED:
                new_id, target = CHANGED[rid]
                old = previous["reference"][rid]
                put(new_id, target, old.split("->", 1)[0] + "->" + new_id.split("#", 1)[1])
                keys.append(new_id)
                continue
            target = TARGETS.get(rid) or projected.get(rid)
            if target is None:
                raise SystemExit("unmapped " + rid)
            put(rid, target, previous["reference"][rid])
            keys.append(rid)
        for rid, target in ADDED.get(group, {}).items():
            if rid not in keys:
                keys.append(rid)
            if rid not in targets:
                put(rid, target, None)
        groups[group] = keys
    for rid, target in targets.items():
        if refs[rid] is None:
            refs[rid] = own_ref(rid, target)
    json.dump({"groups": groups, "targets": targets, "reference": refs}, open(output_file, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1, sort_keys=True)
    print("aliases:", len(targets), {g: len(k) for g, k in groups.items()})


if __name__ == "__main__":
    main(*sys.argv[1:4])
