"""Index every class/method of a set of DEX files for cross-build matching.

usage: index_dex.py <output.pkl> <classes.dex> [<classes2.dex> ...]

methods[descriptor] = {acc, ops, strs, inv, fld, consts}
classes[descriptor] = {acc, sup, ifaces, fields: [(name, type, acc)], methods: [descriptor]}
Descriptors use smali form without spaces, e.g. La/b;->c(ILjava/lang/String;)V.
"""
import pickle
import sys

from loguru import logger

logger.remove()
from androguard.core.dex import DEX  # noqa: E402


def norm(desc):
    return desc.replace(" ", "")


def main(out, *paths):
    methods, classes = {}, {}
    for path in paths:
        dex = DEX(open(path, "rb").read())
        for c in dex.get_classes():
            name = c.get_name()
            mlist = []
            for m in c.get_methods():
                desc = "%s->%s%s" % (name, m.get_name(), norm(m.get_descriptor()))
                ops, strs, inv, fld, consts = [], [], [], [], []
                if m.get_code():
                    for ins in m.get_instructions():
                        op = ins.get_op_value()
                        ops.append(op)
                        n = ins.get_name()
                        if n.startswith("const-string"):
                            strs.append(ins.get_raw_string())
                        elif n.startswith("invoke"):
                            inv.append(norm(ins.get_translated_kind()))
                        elif n.startswith(("iget", "iput", "sget", "sput")):
                            k = ins.get_translated_kind()
                            fld.append(("w" if "put" in n else "r", k.replace(" ", ":", 1)))
                        elif n.startswith("const") and not n.startswith("const-class"):
                            try:
                                consts.append(int(ins.get_literals()[0]))
                            except Exception:
                                pass
                        elif n.startswith(("new-instance", "const-class", "check-cast", "instance-of")):
                            fld.append(("t", ins.get_translated_kind()))
                mlist.append(desc)
                methods[desc] = dict(acc=m.get_access_flags(), ops=ops, strs=strs, inv=inv, fld=fld, consts=consts)
            classes[name] = dict(
                acc=c.get_access_flags(), sup=c.get_superclassname(), ifaces=list(c.get_interfaces()),
                fields=[(f.get_name(), f.get_descriptor(), f.get_access_flags()) for f in c.get_fields()],
                methods=mlist, dex=path)
        print(path, len(classes), flush=True)
    pickle.dump(dict(methods=methods, classes=classes), open(out, "wb"))
    print("done", len(classes), len(methods))


if __name__ == "__main__":
    main(*sys.argv[1:])
