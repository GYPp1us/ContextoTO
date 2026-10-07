"""Build a deterministic 10k-lemma ECDICT subset; downloaded source stays off-repo."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument("--source", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
pattern = re.compile(r"[a-z]+(?:-[a-z]+)*")
csv.field_size_limit(1024 * 1024)

def rows():
    with args.source.open(encoding="utf-8-sig", newline="") as handle:
        yield from csv.DictReader(handle)

def exchange(value):
    return dict(part.split(":", 1) for part in value.split("/") if ":" in part)

def rank(row):
    contemporary = int(row.get("frq") or 0)
    traditional = int(row.get("bnc") or 0)
    return contemporary if contemporary > 0 else traditional + 1000000 if traditional > 0 else 9999999

candidates = {}
known = set()
for row in rows():
    word = row["word"].lower().strip()
    if not pattern.fullmatch(word):
        continue
    known.add(word)
    morph = exchange(row.get("exchange", ""))
    roots = [v for v in morph.get("0", "").split(",") if pattern.fullmatch(v)]
    if any(root != word for root in roots) or not row.get("translation") or rank(row) >= 9999999:
        continue
    if word not in candidates or rank(row) < rank(candidates[word]):
        candidates[word] = row
selected = sorted(candidates, key=lambda word: (rank(candidates[word]), word))[:10000]
assert len(selected) == 10000, "Insufficient independent lemmas"
selected_set = set(selected)
forms = {}
for row in rows():
    word = row["word"].lower().strip()
    if not pattern.fullmatch(word):
        continue
    morph = exchange(row.get("exchange", ""))
    for root in morph.get("0", "").split(","):
        if root in selected_set and root != word:
            forms.setdefault(word, set()).add(root)
    if word in selected_set:
        for code in ("p", "d", "i", "3", "s", "r", "t"):
            for inflected in morph.get(code, "").split(","):
                if pattern.fullmatch(inflected) and inflected != word:
                    forms.setdefault(inflected, set()).add(word)
data = {"version": 1, "name": "通用高频 10000", "source": "https://github.com/skywind3000/ECDICT",
        "source_sha256": hashlib.sha256(args.source.read_bytes()).hexdigest(), "license": "MIT",
        "words": {word: {"translation": candidates[word]["translation"].replace("\\n", "\n"), "ipa_uk": candidates[word].get("phonetic", "").strip("/[]"),
                          "rank": rank(candidates[word])} for word in selected},
        "forms": {word: sorted(roots) for word, roots in sorted(forms.items())}, "known_words": sorted(known)}
args.output.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print(f"General bank: {len(selected)} lemmas, {len(forms)} inflections; verification index: {len(known)} spellings")
