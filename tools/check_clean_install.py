"""Read-only checks for a freshly initialized, isolated release test user."""
import argparse
import json
from pathlib import Path
import sqlite3

parser = argparse.ArgumentParser()
parser.add_argument("database", type=Path)
args = parser.parse_args()
with sqlite3.connect(args.database.resolve().as_uri() + "?mode=ro", uri=True) as db:
    assert db.execute("PRAGMA quick_check").fetchone()[0] == "ok"
    assert db.execute("PRAGMA user_version").fetchone()[0] == 5
    counts = {table: db.execute('SELECT COUNT(*) FROM "' + table + '"').fetchone()[0] for table in
              ("analysis", "analysis_seed_install", "study_word", "occurrence", "review_question", "review_attempt", "question_issue", "activity_event", "word_bank", "imported_article", "lookup_event")}
    assert counts["analysis"] >= 9000 and counts["analysis_seed_install"] == 1
    assert all(value == 0 for table, value in counts.items() if table not in ("analysis", "analysis_seed_install"))
    print(json.dumps({"integrity": "ok", "schema": 5, "counts": counts}))
