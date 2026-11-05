"""Resolve remaining alias targets by aligning operands of matched callers.

usage: resolve.py <old.pkl> <new.pkl> <xmap.pkl> <aliases-21246.json> <out projected.json> [overrides.json]

For a target member T of the old build, every matched caller pair (C, C') has
its invoke (or field) sequence aligned with difflib on canonical descriptors;
the operand of C' aligned with T votes. A target is accepted only when one
candidate wins at least two votes and no other candidate has any.
"""
import difflib
import json
import pickle
import re
import sys
from collections import Counter, defaultdict

from match import Build, Matcher, split_field, split_method

TYPE = re.compile(r"L[^;]+;")


def main(old, new, xmap, aliases_file, out, overrides_file=None):
    a, b = Build(old), Build(new)
    m = Matcher(a, b)
    x = pickle.load(open(xmap, "rb"))
    m.cm, m.mm, m.fm = x["cm"], x["mm"], x["fm"]
    m.cmr = {v: k for k, v in m.cm.items()}
    m.mmr = {v: k for k, v in m.mm.items()}
    m.fmr = {v: k for k, v in m.fm.items()}
    aliases = json.load(open(aliases_file, encoding="utf-8"))
    overrides = json.load(open(overrides_file, encoding="utf-8")) if overrides_file else {}

    callers = defaultdict(set)
    readers = defaultdict(set)
    for d, meth in a.methods.items():
        for i in meth["inv"]:
            callers[i].add(d)
        for k, f in meth["fld"]:
            if k != "t":
                readers[f].add(d)

    def canon_list(desc, side, kind):
        build = a if side == 0 else b
        meth = build.methods[desc]
        if kind == "m":
            return meth["inv"], [m.canon_member(i, side, "m") for i in meth["inv"]]
        ops = [f for k, f in meth["fld"] if k != "t"]
        return ops, [m.canon_member(f, side, "f") for f in ops]

    def coarse(desc, side, kind):
        """Name-free canonical form used to check aligned candidates."""
        if kind == "m":
            o, n, s = split_method(desc)
            return m.canon_sig(s, side)
        o, n, t = split_field(desc)
        return m.canon_type(t, side)

    def vote(target, kind):
        votes = Counter()
        pool = callers if kind == "m" else readers
        for c in pool.get(target, ()):
            c2 = m.mm.get(c)
            if not c2:
                continue
            ra, ca = canon_list(c, 0, kind)
            rb, cb = canon_list(c2, 1, kind)
            sm = difflib.SequenceMatcher(None, [s.replace("M", "", 1) if s.startswith("M") else s for s in ca],
                                         [s.replace("M", "", 1) if s.startswith("M") else s for s in cb],
                                         autojunk=False)
            for tag, i1, i2, j1, j2 in sm.get_opcodes():
                if tag not in ("equal", "replace") or i2 - i1 != j2 - j1:
                    continue
                for k in range(i2 - i1):
                    if ra[i1 + k] == target:
                        cand = rb[j1 + k]
                        if coarse(target, 0, kind) == coarse(cand, 1, kind):
                            votes[cand] += 1
        return votes

    def resolve_class(target):
        votes = Counter()
        for d, v in list(m.mm.items()) + list(m.fm.items()):
            if d.split("->")[0] == target:
                votes[v.split("->")[0]] += 1
        for d, v in m.mm.items():
            for tx, ty in zip(TYPE.findall(split_method(d)[2]), TYPE.findall(split_method(v)[2])):
                if tx.lstrip("[") == target:
                    votes[ty.lstrip("[")] += 1
        return votes

    projected, report = {}, {}
    for rid, t in sorted(aliases["targets"].items()):
        if rid in overrides:
            projected[rid] = overrides[rid]
            report[rid] = "override"
            continue
        kind = rid[0]
        direct = {"c": m.cm, "m": m.mm, "f": m.fm}[kind].get(t)
        if direct:
            projected[rid] = direct
            report[rid] = "matched"
            continue
        votes = resolve_class(t) if kind == "c" else vote(t, kind)
        top = votes.most_common(3)
        if top and top[0][1] >= 2 and len(top) == 1:
            projected[rid] = top[0][0]
            report[rid] = "voted %d" % top[0][1]
        else:
            report[rid] = "UNRESOLVED %s" % top
    json.dump(projected, open(out, "w", encoding="utf-8"), indent=1, sort_keys=True)
    for rid, r in sorted(report.items()):
        if r != "matched":
            print(r.ljust(12), rid, "->", projected.get(rid))
    print(len(projected), "of", len(aliases["targets"]))


if __name__ == "__main__":
    main(*sys.argv[1:])
