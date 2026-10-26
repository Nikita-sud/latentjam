#!/usr/bin/env python3
"""Grade the piece-4 region-identity check log against the frozen grading map (docs/map-refresh-checks.md,
piece 4, review round 3, item 5). Usage: python3 -I map_regions_piece4_grade.py <p4-check.log> [delta]"""
import re, sys

log, delta = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "0.2")
cells, verdicts = [], {}

def cell(row, where, value):
    cells.append((row, where, value))

for line in open(log):
    if not line.startswith("R4 "):
        continue
    v = dict(re.findall(r"(\w+)=(\S+)", line))
    if line.startswith("R4 chain "):
        if v.get("delta") != delta:
            continue
        where = f"{v['lib']} chain switch={v['switch']}"
        flags = re.findall(r"(\w+)=(PASS|FAIL)", line.split("verdict", 1)[1])
        f = dict(flags)
        cell("R7", where, f["churn"])
        if v["switch"] == "false":
            cell("R8", where, f["purity"]); cell("R9", where, f["coverage"])
        continue
    m = re.match(r"R4 lib=(\S+) (\S+) switch=(\w+) delta=(\S+) ", line)
    if not m or m.group(4) != delta:
        continue
    lib, case, switch, _ = m.groups()
    where = f"{lib} {case} switch={switch}"
    f = dict(re.findall(r"(\w+)=(PASS|FAIL|n/a)", line.split("verdict", 1)[1]))
    cell("R1", where, f["churn"])
    if switch == "false":
        cell("R2", where, f["purity"])
    cell("R3", where, f["coverage"])
    cell("R4", where, f["placed"])
    if case == "novel":
        cell("R5", where, f["own"])
    cell("R6", where, f["minSize"])

for row in [f"R{i}" for i in range(1, 10)]:
    rs = [c for c in cells if c[0] == row]
    bad = [c for c in rs if c[2] != "PASS"]
    print(f"{row}: {len(rs) - len(bad)}/{len(rs)} pass" + ("" if not bad else "  not pass: " + "; ".join(f"{w} ({x})" for _, w, x in bad)))
fails = [c for c in cells if c[2] == "FAIL"]
nas = [c for c in cells if c[2] == "n/a"]
print(f"cells={len(cells)} FAIL={len(fails)} n/a={len(nas)}")
print("PIECE 4 VERDICT:", "PASS" if not fails and cells else "FAIL")
