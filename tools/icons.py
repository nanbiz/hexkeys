"""Writes the icons the keyboard draws as Android vector drawables, from
Google's Material Symbols (Apache-2.0), outlined style, at a pinned commit.

Usage: python3 tools/icons.py   (writes android/app/src/main/res/drawable/icon_*.xml)
"""

import pathlib
import re
import urllib.request

COMMIT = "49d4db35df873165d6bd6ba09b063c7dafbac2f4"
ICONS = [
    "settings", "mood", "mic", "key", "backspace", "keyboard_return", "shift", "schedule",
    "stop_circle", "timer", "error", "send", "search", "arrow_forward", "arrow_back", "check", "keyboard_tab", "close",
    "emoji_people", "pets", "restaurant", "directions_car", "sports_soccer", "lightbulb", "emoji_symbols", "flag",
    "content_paste", "push_pin", "info",
]
OUT = pathlib.Path(__file__).resolve().parent.parent / "android/app/src/main/res/drawable"

for name in ICONS:
    url = (
        "https://raw.githubusercontent.com/google/material-design-icons/"
        f"{COMMIT}/symbols/web/{name}/materialsymbolsoutlined/{name}_24px.svg"
    )
    svg = urllib.request.urlopen(url).read().decode()
    paths = re.findall(r'<path d="([^"]+)"', svg)
    body = "\n".join(f'        <path android:fillColor="#FFFFFFFF" android:pathData="{d}" />' for d in paths)
    # Most symbols draw in a 960 unit box starting at y = -960, a few in their 24 by 24 pixels.
    box = re.search(r'viewBox="([^"]+)"', svg)
    x, y, w, h = (float(v) for v in box.group(1).split()) if box else (0, 0, 24, 24)
    OUT.joinpath(f"icon_{name}.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- Material Symbols \"{name}\" (Apache-2.0), written by tools/icons.py. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp" android:height="24dp"\n'
        f'    android:viewportWidth="{w:g}" android:viewportHeight="{h:g}">\n'
        f'    <group android:translateX="{-x:g}" android:translateY="{-y:g}">\n'
        f"{body}\n"
        "    </group>\n"
        "</vector>\n"
    )
    print(name)
