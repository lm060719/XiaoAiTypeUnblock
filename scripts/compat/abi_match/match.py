"""Match classes, methods and fields between two indexed host builds.

usage: match.py <old.pkl> <new.pkl> <out.pkl>

Seeds are methods whose bytecode, strings, constants and name-independent
signature are unique and equal in both builds. Matches then propagate through
positional invoke/field operands of matched pairs and through class membership.
Every accepted pair must be unique in both directions.
"""
import pickle
import re
import sys
from collections import Counter, defaultdict

TYPE = re.compile(r"L[^;]+;")


def load(path):
    d = pickle.load(open(path, "rb"))
    return d["methods"], d["classes"]


def split_method(desc):
    owner, rest = desc.split("->", 1)
    name = rest[:rest.index("(")]
    sig = rest[rest.index("("):]
    return owner, name, sig


def split_field(desc):
    owner, rest = desc.split("->", 1)
    name, ftype = rest.split(":", 1)
    return owner, name, ftype


class Build:
    def __init__(self, path):
        self.methods, self.classes = load(path)
        self.fields = {}
        for cname, c in self.classes.items():
            for fname, ftype, acc in c["fields"]:
                self.fields["%s->%s:%s" % (cname, fname, ftype)] = acc

    def obfuscated(self, t):
        base = t.lstrip("[")
        if base not in self.classes:
            return False
        simple = base[1:-1].rsplit("/", 1)[-1]
        return len(simple.split("$")[0]) <= 3


class Matcher:
    def __init__(self, a, b):
        self.a, self.b = a, b
        self.cm, self.cmr = {}, {}
        self.mm, self.mmr = {}, {}
        self.fm, self.fmr = {}, {}
        self.conflicts = Counter()

    # -- canonical forms ------------------------------------------------------
    def canon_type(self, t, side):
        build = self.a if side == 0 else self.b
        dims = len(t) - len(t.lstrip("["))
        base = t[dims:]
        if not build.obfuscated(base):
            return t
        if side == 0:
            return "[" * dims + (base if base in self.cm else "*")
        return "[" * dims + (self.cmr.get(base, "*"))

    def canon_sig(self, sig, side):
        return TYPE.sub(lambda m: self.canon_type(m.group(0), side), sig)

    def canon_member(self, desc, side, kind):
        build = self.a if side == 0 else self.b
        if kind == "m":
            owner, name, sig = split_method(desc)
            mapped = (self.mm if side == 0 else self.mmr)
            if side == 0 and desc in mapped:
                return "M" + desc
            if side == 1 and desc in mapped:
                return "M" + mapped[desc]
            co = self.canon_type(owner, side)
            keep_name = not build.obfuscated(owner) or len(name) > 3 or name.startswith("<")
            return "%s->%s%s" % (co, name if keep_name else "*", self.canon_sig(sig, side))
        owner, name, ftype = split_field(desc)
        mapped = (self.fm if side == 0 else self.fmr)
        if side == 0 and desc in mapped:
            return "F" + desc
        if side == 1 and desc in mapped:
            return "F" + mapped[desc]
        co = self.canon_type(owner, side)
        keep_name = not build.obfuscated(owner) or len(name) > 3
        return "%s->%s:%s" % (co, name if keep_name else "*", self.canon_sig(ftype, side))

    def key(self, desc, side, deep):
        build = self.a if side == 0 else self.b
        m = build.methods[desc]
        owner, name, sig = split_method(desc)
        keep_name = not build.obfuscated(owner) or len(name) > 3 or name.startswith("<")
        parts = [m["acc"], name if keep_name else "*", self.canon_sig(sig, side),
                 self.canon_type(owner, side) if deep else "", tuple(m["ops"]), tuple(m["strs"]),
                 tuple(m["consts"])]
        if deep:
            parts.append(tuple(self.canon_member(x, side, "m") for x in m["inv"]))
            parts.append(tuple((k, self.canon_member(x, side, "f") if k != "t" else self.canon_type(x, side))
                               for k, x in m["fld"]))
        return tuple(parts)

    # -- accepting pairs ------------------------------------------------------
    def add_class(self, x, y):
        if x == y and not self.a.obfuscated(x):
            pass
        if x in self.cm or y in self.cmr:
            if self.cm.get(x) != y:
                self.conflicts["class"] += 1
            return False
        if x not in self.a.classes or y not in self.b.classes:
            return False
        self.cm[x], self.cmr[y] = y, x
        return True

    def add_method(self, x, y):
        if x in self.mm or y in self.mmr:
            if self.mm.get(x) != y:
                self.conflicts["method"] += 1
            return False
        if x not in self.a.methods or y not in self.b.methods:
            return False
        ox, _, sx = split_method(x)
        oy, _, sy = split_method(y)
        if len(TYPE.findall(sx)) != len(TYPE.findall(sy)):
            return False
        self.mm[x], self.mmr[y] = y, x
        return True

    def add_field(self, x, y):
        if x in self.fm or y in self.fmr:
            if self.fm.get(x) != y:
                self.conflicts["field"] += 1
            return False
        if x not in self.a.fields or y not in self.b.fields:
            return False
        self.fm[x], self.fmr[y] = y, x
        return True

    # -- passes ---------------------------------------------------------------
    def seed_stable_classes(self):
        n = 0
        for c in self.a.classes:
            if not self.a.obfuscated(c) and c in self.b.classes:
                n += self.add_class(c, c)
        return n

    def unique_pass(self, deep):
        ka = defaultdict(list)
        kb = defaultdict(list)
        for d in self.a.methods:
            if d not in self.mm:
                ka[self.key(d, 0, deep)].append(d)
        for d in self.b.methods:
            if d not in self.mmr:
                kb[self.key(d, 1, deep)].append(d)
        n = 0
        for k, xs in ka.items():
            ys = kb.get(k)
            if ys and len(xs) == 1 and len(ys) == 1:
                n += self.add_method(xs[0], ys[0])
        return n

    def propagate(self):
        n = 0
        votes = defaultdict(Counter)
        for x, y in list(self.mm.items()):
            ma, mb = self.a.methods[x], self.b.methods[y]
            ox, _, sx = split_method(x)
            oy, _, sy = split_method(y)
            votes[ox][oy] += 1
            for tx, ty in zip(TYPE.findall(sx), TYPE.findall(sy)):
                votes[tx][ty] += 1
            same_code = ma["ops"] == mb["ops"]
            if same_code and len(ma["inv"]) == len(mb["inv"]):
                for ix, iy in zip(ma["inv"], mb["inv"]):
                    oix, nix, six = split_method(ix)
                    oiy, niy, siy = split_method(iy)
                    if (not self.a.obfuscated(oix) or len(nix) > 3) and nix != niy:
                        continue
                    if self.canon_sig(six, 0).count("L") != self.canon_sig(siy, 1).count("L"):
                        continue
                    votes[oix][oiy] += 1
                    for tx, ty in zip(TYPE.findall(six), TYPE.findall(siy)):
                        votes[tx][ty] += 1
                    if ix in self.a.methods and iy in self.b.methods:
                        n += self.add_method(ix, iy)
            fa = [v for v in ma["fld"]]
            fb = [v for v in mb["fld"]]
            if same_code and len(fa) == len(fb):
                for (kx, vx), (ky, vy) in zip(fa, fb):
                    if kx != ky:
                        continue
                    if kx == "t":
                        votes[vx.lstrip("[")][vy.lstrip("[")] += 1
                        continue
                    ofx, _, tfx = split_field(vx)
                    ofy, _, tfy = split_field(vy)
                    votes[ofx][ofy] += 1
                    votes[tfx.lstrip("[")][tfy.lstrip("[")] += 1
                    n += self.add_field(vx, vy)
        for x, y in list(self.fm.items()):
            ox, _, tx = split_field(x)
            oy, _, ty = split_field(y)
            votes[ox][oy] += 1
            votes[tx.lstrip("[")][ty.lstrip("[")] += 1
        for x, counter in votes.items():
            if x in self.cm or x not in self.a.classes:
                continue
            (best, count), *rest = counter.most_common(2) + [(None, 0)]
            second = rest[0][1] if rest else 0
            if best in self.b.classes and best not in self.cmr and count >= 2 * second + 1:
                n += self.add_class(x, best)
        return n

    def class_members(self):
        """Inside matched classes, pair members unique by canonical signature."""
        n = 0
        for x, y in list(self.cm.items()):
            ca, cb = self.a.classes.get(x), self.b.classes.get(y)
            if not ca or not cb:
                continue
            # superclass / interfaces
            if ca["sup"] and cb["sup"]:
                n += self.add_class(ca["sup"], cb["sup"]) if self.a.obfuscated(ca["sup"]) else 0
            if len(ca["ifaces"]) == len(cb["ifaces"]):
                for ix, iy in zip(ca["ifaces"], cb["ifaces"]):
                    if self.a.obfuscated(ix):
                        n += self.add_class(ix, iy)
            ga, gb = defaultdict(list), defaultdict(list)
            for d in ca["methods"]:
                if d not in self.mm:
                    o, name, sig = split_method(d)
                    keep = not self.a.obfuscated(o) or len(name) > 3 or name.startswith("<")
                    ga[(name if keep else "*", self.canon_sig(sig, 0), self.a.methods[d]["acc"])].append(d)
            for d in cb["methods"]:
                if d not in self.mmr:
                    o, name, sig = split_method(d)
                    keep = not self.b.obfuscated(o) or len(name) > 3 or name.startswith("<")
                    gb[(name if keep else "*", self.canon_sig(sig, 1), self.b.methods[d]["acc"])].append(d)
            for k, xs in ga.items():
                ys = gb.get(k)
                if ys and len(xs) == 1 and len(ys) == 1 and "*" not in k[1]:
                    n += self.add_method(xs[0], ys[0])
            fa, fb = defaultdict(list), defaultdict(list)
            for fname, ftype, acc in ca["fields"]:
                d = "%s->%s:%s" % (x, fname, ftype)
                if d not in self.fm:
                    fa[(self.canon_type(ftype, 0), acc)].append(d)
            for fname, ftype, acc in cb["fields"]:
                d = "%s->%s:%s" % (y, fname, ftype)
                if d not in self.fmr:
                    fb[(self.canon_type(ftype, 1), acc)].append(d)
            for k, xs in fa.items():
                ys = fb.get(k)
                if ys and len(xs) == 1 and len(ys) == 1 and "*" not in k[0]:
                    n += self.add_field(xs[0], ys[0])
        return n

    def run(self):
        print("stable classes", self.seed_stable_classes(), flush=True)
        for round_ in range(12):
            g = self.unique_pass(False)
            g += self.unique_pass(True)
            g += self.propagate()
            g += self.class_members()
            print("round", round_, "+%d" % g, "classes", len(self.cm), "methods", len(self.mm),
                  "fields", len(self.fm), dict(self.conflicts), flush=True)
            if g == 0:
                break


def main(old, new, out):
    m = Matcher(Build(old), Build(new))
    m.run()
    pickle.dump(dict(cm=m.cm, mm=m.mm, fm=m.fm), open(out, "wb"))


if __name__ == "__main__":
    main(*sys.argv[1:4])
