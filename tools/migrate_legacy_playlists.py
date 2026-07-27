"""Migrate playlists from the legacy LatentJam app into the new KMP app.

Path: legacy user_music.db gives playlist name -> song UIDs. The app's own
music_graph_debug.dot maps song UID -> title. Titles are matched against the
emulator's MediaStore to get the ids the new app uses. Purely data-level: no
legacy source is consulted, only files the app itself wrote.
"""
import os
import re
import sqlite3
import subprocess
import sys
from collections import OrderedDict

ADB = os.environ.get("ADB", "adb")
EMU = os.environ.get("ANDROID_SERIAL", "emulator-5554")

# Directory holding the legacy app's pulled state: graph.dot and user_music.db go in,
# playlists.txt comes out. Pass it as the first argument.
if len(sys.argv) < 2:
    sys.exit(f"usage: {sys.argv[0]} <legacy-state-dir>")
LEGACY = sys.argv[1].rstrip("/")
FIELD = ""

# --- 1. UID -> title, from the app's debug graph -------------------------
uid_to_title = {}
label_re = re.compile(r'label="(.*?)\\nUID: (u[a-z]{2}[0-9a-f-]+)"', re.S)
with open(f"{LEGACY}/graph.dot", encoding="utf-8") as fh:
    for title, uid in label_re.findall(fh.read()):
        uid_to_title[uid] = title.strip()
print(f"graph: {len(uid_to_title)} uid->title entries")

# --- 2. playlists from the legacy database -------------------------------
db = sqlite3.connect(f"{LEGACY}/user_music.db")
playlists = OrderedDict()
for puid, name in db.execute("SELECT playlistUid, name FROM PlaylistInfo"):
    playlists[puid] = {"name": name, "uids": []}
for puid, suid in db.execute(
    "SELECT playlistUid, songUid FROM PlaylistSongCrossRef ORDER BY id"
):
    if puid in playlists:
        playlists[puid]["uids"].append(suid)
print(f"legacy: {len(playlists)} playlists, "
      f"{sum(len(p['uids']) for p in playlists.values())} memberships")

# --- 3. the emulator's library: title -> MediaStore id --------------------
out = subprocess.run(
    [ADB, "-s", EMU, "shell", "content", "query", "--uri",
     "content://media/external/audio/media", "--projection", "_id:title"],
    capture_output=True, text=True, check=True).stdout
title_to_id = {}
for line in out.splitlines():
    m = re.search(r"_id=(\d+), title=(.*)$", line.strip())
    if m:
        title_to_id.setdefault(m.group(2).strip().lower(), m.group(1))
print(f"emulator: {len(title_to_id)} titles indexed")


def match(title):
    key = title.lower()
    if key in title_to_id:
        return title_to_id[key]
    # Debug labels are truncated at ~50 chars, so fall back to a prefix hit.
    if len(key) >= 12:
        for candidate, tid in title_to_id.items():
            if candidate.startswith(key) or key.startswith(candidate):
                return tid
    return None


# --- 4. resolve and emit our format --------------------------------------
lines, report = [], []
now = 1784385000000
for index, (puid, data) in enumerate(playlists.items()):
    ids, missing = [], 0
    for suid in data["uids"]:
        title = uid_to_title.get(suid)
        tid = match(title) if title else None
        if tid and tid not in ids:
            ids.append(tid)
        elif not tid:
            missing += 1
    lines.append(FIELD.join([
        f"pl-legacy-{index}", data["name"], str(now + index), ",".join(ids)
    ]))
    report.append((data["name"], len(ids), missing))

for name, found, missing in report:
    print(f"  {name:<18} {found:>3} matched, {missing:>3} unmatched")
total_found = sum(r[1] for r in report)
total_missing = sum(r[2] for r in report)
print(f"TOTAL: {total_found} matched, {total_missing} unmatched")

with open(f"{LEGACY}/playlists.txt", "w", encoding="utf-8") as fh:
    fh.write("\n".join(lines))
print(f"wrote {LEGACY}/playlists.txt")
sys.exit(0 if total_found else 1)
