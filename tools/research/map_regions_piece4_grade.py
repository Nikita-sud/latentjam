#!/usr/bin/env python3
"""Grade the piece-4 region-identity check log against the frozen grading map (docs/map-refresh-checks.md,
piece 4, review round 3, item 5). Usage: python3 -I map_regions_piece4_grade.py <p4-check.log> [delta] [libraries]
PASS needs every expected cell present and passing: per library 6 cases x 2 paths (R1, R3, R4, R6 both paths,
R2 average linkage only, R5 novel only) plus the chain (R7 both paths, R8/R9 average linkage only) = 60 cells."""
import re, sys

log, delta = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "0.2")
libraries = int(sys.argv[3]) if len(sys.argv) > 3 else 6
PER_ROW = {"R1": 12, "R2": 6, "R3": 12, "R4": 12, "R5": 2, "R6": 12, "R7": 2, "R8": 1, "R9": 1}
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

missing = 0
for row in [f"R{i}" for i in range(1, 10)]:
    rs = [c for c in cells if c[0] == row]
    bad = [c for c in rs if c[2] != "PASS"]
    expected = PER_ROW[row] * libraries
    missing += max(0, expected - len(rs))
    print(f"{row}: {len(rs) - len(bad)}/{len(rs)} pass, {expected - len(rs)} of {expected} expected missing" +
          ("" if not bad else "  not pass: " + "; ".join(f"{w} ({x})" for _, w, x in bad)))
fails = [c for c in cells if c[2] == "FAIL"]
nas = [c for c in cells if c[2] == "n/a"]
expected = sum(PER_ROW.values()) * libraries
print(f"cells={len(cells)} of {expected} expected (missing={missing}) FAIL={len(fails)} n/a={len(nas)}")
# n/a never counts as a pass, and a missing cell is not a pass either (round 3, item 1).
print("PIECE 4 VERDICT:", "PASS" if not fails and not nas and missing == 0 else "FAIL")
