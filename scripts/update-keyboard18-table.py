"""Build the offline 18-key pinyin.table from pinned dictionary sources.

Weights come from two corpora on different scales, so they are put on one scale here:
- words: the AOSP PinyinIME frequencies (rime-pinyin-simp); rime-ice base words missing from AOSP
  are rank-mapped onto the AOSP distribution, because base.dict carries hand-set bands
  (500000+, 999999, 19260817...) that are not frequencies;
- characters: the AOSP frequency, raised to the rime-ice 8105 character frequency rank-mapped onto
  the AOSP character distribution, so they keep their balance against words. Characters outside the
  8105 standard set (traditional and variant forms) stay selectable but sort last.

Curated modern terms that come from no corpus live in extra.table, not here.
"""
import argparse
import bisect
import gzip
import hashlib
from pathlib import Path
import re
from urllib.request import urlopen

SIMP_REVISION = "0c6861ef7420ee780270ca6d993d18d4101049d0"
ICE_REVISION = "99662ba01e3affa6a0f009174ca530b887bac3b6"
SOURCES = {
    "pinyin_simp.dict.yaml": (f"https://raw.githubusercontent.com/rime/rime-pinyin-simp/{SIMP_REVISION}/pinyin_simp.dict.yaml",
                              "e341598343a0f0f2035bb1aafc34a7f3bb7887deeecb3f60796262aaa2983e6b"),
    "LICENSE": (f"https://raw.githubusercontent.com/rime/rime-pinyin-simp/{SIMP_REVISION}/LICENSE", None),
    "base.dict.yaml": (f"https://raw.githubusercontent.com/iDvel/rime-ice/{ICE_REVISION}/cn_dicts/base.dict.yaml",
                       "551cad93c2e74c7325599e0d23afbf71c2d2e57df9af3f8f6fa16d87e60290a3"),
    "8105.dict.yaml": (f"https://raw.githubusercontent.com/iDvel/rime-ice/{ICE_REVISION}/cn_dicts/8105.dict.yaml",
                       "1f9a42b91dea6982baee2551981780271aeffd78876662b9c9f324e56b37b120"),
}
# base.dict words below this weight are too rare to be worth shipping.
BASE_MIN_WEIGHT = 10000
# Easter-egg weights in base.dict; rows carrying them and found in no other source are dropped.
EASTER_EGGS = {19260817, 12345678, 2312312}
# Characters that must never come up ahead of ordinary ones for a reading.
DEMOTED = set("屄屌肏")
SPELLING = re.compile(r"[a-z]+(?: [a-z]+)*")


def parse(data):
    rows = []
    for line in data.decode("utf-8-sig").split("\n...\n", 1)[1].splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3 or not parts[0]:
            continue
        try:
            weight = int(parts[2])
        except ValueError:
            continue
        # rime-ice spells lüe as lve/nve; the shipped table and the schemes use lue/nue.
        spelling = " ".join({"lve": "lue", "nve": "nue"}.get(s, s) for s in parts[1].split(" "))
        if SPELLING.fullmatch(spelling) and len(spelling.replace(" ", "")) <= 48:
            rows.append((parts[0], spelling, weight))
    return rows


def rank_map(values, targets):
    """Maps each value to the target at the same rank; equal values share one target."""
    targets = sorted(targets, reverse=True)
    result = {}
    for index, value in enumerate(sorted(values, reverse=True)):
        if value not in result:
            result[value] = targets[min(len(targets) - 1, index * len(targets) // len(values))]
    return result


def build(simp, base, chars):
    syllables = {s for _, spelling, _ in simp for s in spelling.split(" ")}
    def valid(spelling):
        return all(s in syllables for s in spelling.split(" "))
    simp = {(t, p): w for t, p, w in simp if valid(p)}
    base = {(t, p): w for t, p, w in base if valid(p) and len(t) > 1}
    chars = {(t, p): w for t, p, w in chars if valid(p) and len(t) == 1}
    standard = {t for t, _ in chars}
    weights = {}

    # Characters: 8105 order on the AOSP scale.
    char_values = list(chars.values())
    char_targets = [w for key, w in simp.items() if key in chars]
    mapped = rank_map(char_values, char_targets)
    best = {}
    for (text, spelling), weight in chars.items():
        # AOSP counts standalone use, which is right for the frequent characters (你 了) but
        # leaves 计/鸡/衣 behind rare ones; 8105 lifts those without lowering the AOSP leaders.
        value = 0 if text in DEMOTED else max(mapped[weight], simp.get((text, spelling), 0))
        weights[text, spelling] = value
        best[text] = max(best.get(text, 0), value)
    for (text, spelling), weight in simp.items():
        if len(text) != 1 or (text, spelling) in weights:
            continue
        # AOSP-only readings of standard characters (嗯 ng) keep their weight below the main
        # reading; characters outside the standard set sort after every standard one.
        weights[text, spelling] = min(weight, best[text]) if text in standard and text not in DEMOTED else 0

    # Words: AOSP frequency, base-only words rank-mapped onto it.
    for key, weight in simp.items():
        if len(key[0]) > 1:
            weights[key] = weight
    overlap = [(base[key], simp[key]) for key in base if key in simp]
    base_sorted = sorted(w for w, _ in overlap)
    simp_sorted = sorted(w for _, w in overlap)
    cap = simp_sorted[int(len(simp_sorted) * 0.99)]
    for key, weight in base.items():
        if key in weights or weight < BASE_MIN_WEIGHT or weight in EASTER_EGGS:
            continue
        rank = bisect.bisect_left(base_sorted, weight) / len(base_sorted)
        weights[key] = min(cap, simp_sorted[min(len(simp_sorted) - 1, int(rank * len(simp_sorted)))])
    return sorted(((w, t, p) for (t, p), w in weights.items()), key=lambda row: (-row[0], row[2], row[1]))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source-dir", type=Path, help="directory holding the files of SOURCES")
    args = parser.parse_args()
    data = {}
    for name, (url, digest) in SOURCES.items():
        content = (args.source_dir / name).read_bytes() if args.source_dir else urlopen(url, timeout=60).read()
        if digest and hashlib.sha256(content).hexdigest() != digest:
            raise ValueError(f"{name}: revision/hash mismatch")
        data[name] = content
    if b"Apache License" not in data["LICENSE"]:
        raise ValueError("Unexpected rime-pinyin-simp license")
    rows = build(parse(data["pinyin_simp.dict.yaml"]), parse(data["base.dict.yaml"]), parse(data["8105.dict.yaml"]))
    target = Path(__file__).resolve().parents[1] / "app/src/main/assets/keyboard18"
    body = "".join(f"{text}\t{spelling}\t{weight}\n" for weight, text, spelling in rows).encode("utf-8")
    with (target / "pinyin.table").open("wb") as output:
        with gzip.GzipFile(fileobj=output, mode="wb", filename="", mtime=0) as compressed:
            compressed.write(body)
    (target / "LICENSE-pinyin.txt").write_bytes(data["LICENSE"])
    (target / "SOURCE-pinyin.txt").write_text(
        f"Source: https://github.com/rime/rime-pinyin-simp\nRevision: {SIMP_REVISION}\n"
        f"Source file: pinyin_simp.dict.yaml\nSource SHA256: {SOURCES['pinyin_simp.dict.yaml'][1]}\n"
        "License: Apache-2.0 (see LICENSE-pinyin.txt)\n"
        "Derived from the Android Open Source Project PinyinIME dictionary.\n\n"
        "Expanded with (GPL-3.0; this project is also GPL-3.0):\n"
        f"Source: https://github.com/iDvel/rime-ice\nRevision: {ICE_REVISION}\n"
        f"- cn_dicts/base.dict.yaml SHA256 {SOURCES['base.dict.yaml'][1]}\n"
        f"- cn_dicts/8105.dict.yaml SHA256 {SOURCES['8105.dict.yaml'][1]}\n"
        "Only dictionary data rows are used; no Rime engine code is bundled.\n\n"
        f"Entries: {len(rows)}. Built by scripts/update-keyboard18-table.py: word weights are AOSP frequencies,\n"
        f"rime-ice base words (weight >= {BASE_MIN_WEIGHT}) missing from AOSP are rank-mapped onto them;\n"
        "characters take the higher of AOSP and the rime-ice 8105 frequency on the AOSP scale; characters outside\n"
        "the 8105 set sort last; lve/nve are spelled lue/nue; UTF-8 TSV, deterministic gzip.\n"
        "Curated modern terms are in extra.table; english.table keeps only cased terms (iPhone, WiFi),\n"
        "mixed Chinese-English terms and a few common Latin abbreviations (ai, ok, qq, app, api, wx, vx).\n",
        encoding="utf-8", newline="\n")
    print(f"Packaged {len(rows)} dictionary entries: {target / 'pinyin.table'}")


if __name__ == "__main__":
    main()
