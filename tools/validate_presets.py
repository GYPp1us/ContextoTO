"""Offline CI validation of dictionary and article-only analysis presets."""
import hashlib
import json
from pathlib import Path
import re
from zipfile import ZipFile

assets = Path(__file__).resolve().parent.parent / "app/src/main/assets"
bank = json.loads((assets / "general_10000.json").read_text(encoding="utf-8"))
assert bank["version"] == 1 and len(bank["words"]) == 10000
assert len(bank["known_words"]) >= 10000 and len(set(bank["known_words"])) == len(bank["known_words"])
assert re.fullmatch(r"[a-f0-9]{64}", bank["source_sha256"])
assert all(re.fullmatch(r"[a-z]+(?:-[a-z]+)*", word) and data["translation"] for word, data in bank["words"].items())
assert all(root in bank["words"] for roots in bank["forms"].values() for root in roots)
articles = json.loads((assets / "articles.json").read_text(encoding="utf-8"))
with ZipFile(assets / "first_two_analyses.zip") as archive:
    assert set(archive.namelist()) == {"manifest.json", "analyses.json"}
    assert sum(item.file_size for item in archive.infolist()) < 32 * 1024 * 1024
    manifest = json.loads(archive.read("manifest.json")); raw = archive.read("analyses.json")
assert manifest["sha256"] == hashlib.sha256(raw).hexdigest()
assert manifest["version"] == 1 and manifest["format"] == "contextoto-analysis-zip"
bundle = json.loads(raw); assert bundle["version"] == 1 and bundle["format"] == "contextoto-analysis"
assert len(bundle["articles"]) == 2
word_total = 0; sentence_total = 0
pattern = re.compile(r"[A-Za-z]+(?:['’][A-Za-z]+)?(?:-[A-Za-z]+)*")
for expected, article in zip(articles[:2], bundle["articles"]):
    for field in ("id", "title", "paragraphs"):
        assert article[field] == expected[field]
    texts = {-1: article["title"], **dict(enumerate(article["paragraphs"]))}
    positions = set()
    for word in article["words"]:
        paragraph = texts[word["paragraph"]]; start = word["start"]; end = word["end"]
        assert paragraph[start:end] == word["text"]
        positions.add((word["paragraph"], start, end)); analysis = word["analysis"]
        assert analysis["lemma"] == analysis["lexical_identity"]["lemma"]
        assert analysis["context_sense"]["zh"] and analysis["common_senses"]
        assert all(re.fullmatch(r"/.+/", analysis["phonetics"][accent]) for accent in ("uk", "us"))
        assert isinstance(analysis["derivatives"], list)
        assert set(analysis) <= {"target", "lemma", "form", "lexical_identity", "context_sense", "common_senses", "derivatives", "phonetics"}
    expected_positions = {(p, match.start(), match.end()) for p, text in texts.items() for match in pattern.finditer(text)}
    assert positions == expected_positions and len(positions) == len(article["words"])
    for sentence in article["sentences"]:
        text = sentence["text"]; paragraph = texts[sentence["paragraph"]]
        assert paragraph[sentence["start"]:sentence["start"] + len(text)] == text
        analysis = sentence["analysis"]; assert analysis["translation_zh"]
        assert set(analysis) == {"translation_zh", "clauses", "glosses"}
        for item in analysis["clauses"] + analysis["glosses"]:
            assert text[item["start"]:item["end"]] == item["quote"]
        assert len(analysis["glosses"]) == len(list(pattern.finditer(text)))
    word_total += len(article["words"]); sentence_total += len(article["sentences"])
assert (word_total, sentence_total) == (2582, 120)
assert not re.search(rb"sk-[a-fA-F0-9]{20,}|user_[A-Za-z0-9]{30,}|focus-reporter/1\.[a-fA-F0-9]{30,}", raw)
print(f"Validated: 10000 lemmas, {sentence_total} sentences, {word_total} word occurrences; article/analysis-only ZIP")
