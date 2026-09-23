"""Build compact app assets from the two supplied, versioned source archives."""

from __future__ import annotations

import csv
import io
import json
import re
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
ARTICLE_IDS = [f"n{i:02d}" for i in range(1, 31)] + [f"g{i:02d}" for i in range(1, 19)]


def article_body(markdown: str) -> list[str]:
    lines = markdown.splitlines()
    separators = [i for i, line in enumerate(lines) if line.strip() == "---"]
    if len(separators) < 2:
        raise ValueError("article markdown does not contain two body delimiters")
    text = "\n".join(lines[separators[0] + 1 : separators[1]]).strip()
    paragraphs = [p.strip().replace("\n", " ") for p in re.split(r"\n\s*\n", text) if p.strip()]
    if not paragraphs or any(len(p) < 20 for p in paragraphs):
        raise ValueError("invalid paragraph structure")
    return paragraphs


def read_articles() -> list[dict]:
    with tarfile.open(ROOT / "kaoyan-corpus-48.tar.gz", "r:gz") as archive:
        members = {Path(m.name).name: m for m in archive if Path(m.name).stem in ARTICLE_IDS}
        if len(members) != 48:
            raise ValueError(f"expected 48 article files, got {len(members)}")
        articles = []
        for article_id in ARTICLE_IDS:
            source = archive.extractfile(members[article_id + ".md"])
            assert source is not None
            markdown = source.read().decode("utf-8")
            heading = markdown.splitlines()[0].removeprefix("# ")
            title = heading.split(" · ", 1)[-1].strip()
            articles.append(
                {
                    "id": article_id,
                    "kind": "改写" if article_id.startswith("n") else "定向",
                    "title": title,
                    "paragraphs": article_body(markdown),
                }
            )
    if sum(len(a["paragraphs"]) for a in articles) != 323:
        raise ValueError("unexpected corpus paragraph count")
    return articles


def read_wordlists() -> dict[str, dict]:
    with tarfile.open(ROOT / "kaoyan-wordlists.tar.gz", "r:gz") as archive:
        tables = {}
        for member in archive:
            filename = Path(member.name).name
            if filename.endswith(".csv"):
                source = archive.extractfile(member)
                assert source is not None
                tables[filename] = list(csv.DictReader(io.StringIO(source.read().decode("utf-8-sig"))))
    if {name: len(rows) for name, rows in tables.items()} != {
        "final-3000.csv": 3000,
        "kaoyan-exam-freq.csv": 4801,
        "gap-319.csv": 319,
    }:
        raise ValueError("wordlist row count mismatch")
    words: dict[str, dict] = {}
    for row in tables["kaoyan-exam-freq.csv"]:
        words[row["word"].lower()] = {
            "translation": row["translation"],
            "band": row["band"],
            "exam_freq": row["exam_freq"],
        }
    for row in tables["final-3000.csv"]:
        item = words[row["word"].lower()]
        item["rank"] = row["rank"]
        item["guaranteed"] = row["guaranteed"] == "1"
    for row in tables["gap-319.csv"]:
        words[row["word"].lower()]["gap"] = True
    return words


def main() -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    articles = read_articles()
    words = read_wordlists()
    (ASSETS / "articles.json").write_text(
        json.dumps(articles, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )
    (ASSETS / "lexicon.json").write_text(
        json.dumps(words, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )
    print(f"Imported {len(articles)} articles, {sum(len(a['paragraphs']) for a in articles)} paragraphs, {len(words)} lexemes")


if __name__ == "__main__":
    main()
