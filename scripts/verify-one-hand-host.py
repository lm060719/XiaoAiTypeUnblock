"""Verify the one-hand hook against trusted local indexes from compat/abi_match/index_dex.py.

Usage: python scripts/verify-one-hand-host.py index-1053.pkl index-1246.pkl index-1358.pkl
Only pass indexes generated locally: pickle is executable input, not an exchange format.
This validates DEX structure and call sites, not device rendering or touch behaviour.
"""
import argparse
import pickle
from pathlib import Path


def verify(path):
    methods = {
        name.replace(" ", ""): value
        for name, value in pickle.loads(path.read_bytes())["methods"].items()
    }
    matches = [name for name, value in methods.items()
               if "KeyboardDimensionAdjustment(heightDelta=" in value["strs"]]
    assert len(matches) == 1, (path, "dimension fingerprint", matches)
    owner = matches[0].split("->")[0]
    constructor = owner + "-><init>(IIIIII)V"
    assert constructor in methods, (path, "six-int constructor")
    writes = methods[constructor]["fld"]
    # The first reference index stored field descriptors directly; newer indexes add read/write tags.
    writes = [item[1] if isinstance(item, tuple) else item for item in writes
              if not isinstance(item, tuple) or item[0] == "w"]
    assert len(writes) == 6 and len(set(writes)) == 6, (path, "six dimension fields", writes)
    assert all(field.endswith(":I") for field in writes), (path, writes)
    callers = [name for name, value in methods.items()
               if constructor in [call.replace(" ", "") for call in value["inv"]]
               and {"qwerty", "rect"}.issubset(value["strs"])
               and "Lcom/mi/ime/MiInputMethodService;" in name]
    assert len(callers) == 1, (path, "scoped dimension layout", callers)
    print(f"PASS {path.name}: {owner} {constructor} caller={callers[0]}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("indexes", nargs="+", type=Path)
    for index in parser.parse_args().indexes:
        verify(index)
