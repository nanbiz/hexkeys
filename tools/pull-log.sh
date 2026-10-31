#!/usr/bin/env bash
# Pulls the debug build's gesture log from the phone and merges it into an
# archive on this machine, which keeps every record the phone has since cut
# from its own file. Usage: tools/pull-log.sh [ARCHIVE], by default
# ~/.local/share/hexkeys/gestures.jsonl. Prints how many records are new.
set -euo pipefail
archive="${1:-$HOME/.local/share/hexkeys/gestures.jsonl}"
mkdir -p "$(dirname "$archive")"
touch "$archive"
serial="$(adb-phone --wait)"
pulled="$(mktemp)"
trap 'rm -f "$pulled"' EXIT
adb -s "$serial" exec-out run-as io.github.tieo.hexkeys cat files/gestures.jsonl > "$pulled"
python3 - "$archive" "$pulled" <<'EOF'
import json, sys
archive, pulled = sys.argv[1], sys.argv[2]
seen = set(open(archive, encoding="utf-8").read().splitlines())
new = []
for line in open(pulled, encoding="utf-8").read().splitlines():
    if line and line not in seen:
        try:
            json.loads(line)
        except ValueError:
            continue
        seen.add(line)
        new.append(line)
with open(archive, "a", encoding="utf-8") as out:
    out.writelines(line + "\n" for line in new)
print(f"{len(new)} new records, {len(seen)} in {archive}")
EOF
