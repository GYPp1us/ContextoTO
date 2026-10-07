"""Read-only comparison of dedicated-emulator SQLite snapshots; prints counts only."""
import argparse
import json
from pathlib import Path
import sqlite3

parser = argparse.ArgumentParser()
parser.add_argument("--before", type=Path, required=True)
parser.add_argument("--after", type=Path, required=True)
args = parser.parse_args()
before = sqlite3.connect(args.before.resolve().as_uri() + "?mode=ro", uri=True)
after = sqlite3.connect(args.after.resolve().as_uri() + "?mode=ro", uri=True)
assert before.execute("PRAGMA user_version").fetchone()[0] == 4
assert after.execute("PRAGMA user_version").fetchone()[0] == 5
tables = ("study_word", "occurrence", "review_question", "review_attempt", "question_issue", "activity_event", "word_bank", "imported_article", "word_alias", "lookup_event", "reading_place", "bookmark")
available = {row[0] for row in before.execute("SELECT name FROM sqlite_master WHERE type='table'")}
counts = {}
for table in tables:
    if table not in available:
        continue
    original = before.execute('SELECT * FROM "' + table + '"').fetchall()
    current = after.execute('SELECT * FROM "' + table + '"').fetchall()
    assert set(original) == set(current), f"Private-state rows changed: {table}"
    counts[table] = len(original)

def supplemented(old, new):
    if isinstance(old, dict):
        return isinstance(new, dict) and all(key in new and supplemented(value, new[key]) for key, value in old.items())
    if old in (None, "", []):
        return True
    return old == new

cache = dict(after.execute("SELECT cache_key,payload FROM analysis"))
old_cache = list(before.execute("SELECT cache_key,payload FROM analysis"))
for key, value in old_cache:
    assert key in cache and supplemented(json.loads(value), json.loads(cache[key])), "Existing analysis was lost or overwritten"
print(json.dumps({"schema": "4 -> 5", "private_tables_unchanged": counts, "old_analyses_preserved": len(old_cache), "new_analysis_count": len(cache)}, ensure_ascii=False))
before.close(); after.close()
