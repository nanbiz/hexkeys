#!/usr/bin/env python3
"""Why deleting happens, from the debug build's gesture log archive.

Usage: tools/deletes.py [ARCHIVE] [--list KIND], by default
~/.local/share/hexkeys/gestures.jsonl as tools/pull-log.sh keeps it.

Every run of deleting (backspace, delete slides and flicks, one after another
with nothing typed between) is read against the text before the cursor as the
next touches logged it: what went, and what was typed in its place up to the
next space. Each run is put down to one reason:

  swipe       a swiped word taken back, at once or after more was typed
  correction  an autocorrect taken back
  overshoot   more went than was meant: the start of what went is typed again
  punctuation a space taken back to put punctuation right after the word
  joined      a space taken back to go on with the word
  space       a space forgotten: what went comes again after a space
  typo        a key missed: what went lies next to the key typed instead
  spelling    letters in a word typed again otherwise, not next to each other
  word        a word begun and swapped for another one
  rewrite     whole words gone and others written
  nothing     what went was not typed again at all (the end of a message, a field cleared)

Prints how many runs each reason has, and for typos which key was typed for
which, the pairs most often first. --list KIND prints every run of one reason.
Fields that take passwords are not in the log, nor is text where the log kept
none (suggestions off).
"""

import collections
import json
import os
import sys

archive = os.path.expanduser("~/.local/share/hexkeys/gestures.jsonl")
listing = None
args = sys.argv[1:]
while args:
    arg = args.pop(0)
    if arg == "--list":
        listing = args.pop(0)
    else:
        archive = arg

DELETING = {"delete", "backspace"}

# Where the letter keys' centres are, in key widths, on the layout with space
# in the middle: two keys whose centres are about one key apart touch.
ROWS = ["wetzio", "qargulp", "..fh.?", "ysdnmjk", "xcvb"]
SHIFTS = [1, 0.5, 1, 0.5, 1]
CENTRES = {}
for r, (keys, shift) in enumerate(zip(ROWS, SHIFTS)):
    for k, key in enumerate(keys):
        if key != ".":
            CENTRES.setdefault(key, (shift + k, r * 0.87))


def next_to(a, b):
    a, b = CENTRES.get(a.lower()), CENTRES.get(b.lower())
    return a is not None and b is not None and (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 <= 1.2**2
# Two touches further apart than this, in ms, are in different typing.
GAP_MS = 60_000


def kinds(record):
    return {action["kind"] for action in record.get("actions", [])}


def common(a, b):
    n = 0
    for x, y in zip(a, b):
        if x != y:
            break
        n += 1
    return n


def distance(a, b):
    """Levenshtein distance."""
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        previous, row[0] = row[0], i
        for j, y in enumerate(b, 1):
            previous, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, previous + (x != y))
    return row[-1]


records = []
with open(archive, encoding="utf-8") as lines:
    for line in lines:
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if "points" in record:
            records.append(record)
records.sort(key=lambda r: r["time"])

runs = []
i = 0
while i < len(records):
    record = records[i]
    if not kinds(record) & DELETING or "before" not in record:
        i += 1
        continue
    start = i
    while i + 1 < len(records) and kinds(records[i + 1]) & DELETING and records[i + 1]["time"] - records[i]["time"] < GAP_MS:
        i += 1
    end = i
    i += 1
    after = records[end + 1] if end + 1 < len(records) else None
    if after is None or "before" not in after or after["time"] - records[end]["time"] > GAP_MS:
        continue
    before = record["before"]
    left = after["before"]
    kept = common(before, left)
    # The log keeps the last part of the text only: a run that took more than it shows is skipped.
    if kept < len(left) and not before.startswith(left):
        continue
    gone = before[kept:]
    if not gone:
        continue
    # What was typed in its place: from the text later touches saw, up to the next space
    # past as much as went.
    typed = ""
    for later in records[end + 1:end + 40]:
        if "before" not in later or later["time"] - records[end]["time"] > GAP_MS:
            break
        text = later["before"]
        if not text.startswith(before[:kept]):
            break
        typed = text[kept:]
        if len(typed) > len(gone) and typed[len(gone):].count(" ") >= 1:
            break
    previous = records[start - 1] if start > 0 else None
    runs.append({"gone": gone, "typed": typed, "previous": previous, "first": record, "count": end - start + 1})


def reason(run):
    gone, typed, previous = run["gone"], run["typed"], run["previous"]
    previous_kinds = kinds(previous) if previous else set()
    notes = [a for a in previous.get("actions", []) if a["kind"] in ("corrected", "amended")] if previous else []
    if "swipe" in previous_kinds or any(a["kind"] == "words" for a in run["first"].get("actions", [])):
        return "swipe"
    if notes and any(gone.strip() and gone.strip() in note["text"].split("->")[-1] for note in notes):
        return "correction"
    if not typed.strip():
        return "nothing"
    if gone == " " and typed[:1] in ",.?!:;)":
        return "punctuation"
    if gone == " " and typed[:1].isalnum():
        return "joined"
    if typed.startswith(gone) or len(gone) > 1 and (typed.startswith(gone[:-1]) or typed.startswith(gone[: len(gone) // 2 + 1])):
        return "overshoot"
    if typed.startswith(" ") and gone.strip() and typed.strip().startswith(gone.strip()):
        return "space"
    if " " in gone.strip():
        return "rewrite"
    context = run["first"]["before"][: len(run["first"]["before"]) - len(gone)]
    at_word_start = not context or not context[-1].isalnum()
    replacement = typed.split(" ")[0][: len(gone)]
    # A word begun with two letters or more that share nothing with what follows is a new word.
    if at_word_start and len(gone.strip()) >= 2 and distance(gone.strip().lower(), replacement.lower()) >= len(gone.strip()):
        return "word"
    if gone[:1] and replacement[:1] and next_to(gone[0], replacement[0]):
        return "typo"
    return "word" if at_word_start else "spelling"


counts = collections.Counter()
swaps = collections.Counter()
for run in runs:
    run["reason"] = reason(run)
    counts[run["reason"]] += 1
    if run["reason"] == "typo":
        word = run["typed"].split(" ")[0]
        g = run["gone"]
        if len(g) == 1 and word:
            swaps[(g, word[0])] += 1

total = sum(counts.values())
print(f"{total} runs of deleting with text around them")
for kind, n in counts.most_common():
    bar = "█" * round(40 * n / max(total, 1))
    print(f"  {kind:<11} {n:>5} {n / total:6.1%} {bar}")
if swaps:
    print("typos of one letter, typed → meant:")
    for (wrong, meant), n in swaps.most_common(15):
        print(f"  {wrong!r} → {meant!r}  {n}")
if listing:
    for run in runs:
        if run["reason"] == listing:
            context = run["first"]["before"][-30 : len(run["first"]["before"]) - len(run["gone"])]
            print(f"{context!r} | gone {run['gone']!r} | typed {run['typed'][:30]!r} | {run['count']} touches")
