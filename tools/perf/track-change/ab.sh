#!/bin/bash
# ab.sh <rounds> <name>:<dir> ... : alternate builds; 2 measurements of 12 SMART skips per build per round
S=$(dirname $0); rounds=$1; shift
for r in $(seq 1 $rounds); do
  for spec in "$@"; do
    name=${spec%%:*}; dir=${spec#*:}
    $S/setup.sh $S/$dir/androidApp-arm64-v8a-release.apk >/dev/null
    for k in a b; do $S/meas.sh ab_${name}_r${r}$k $dir/mapping.txt >/dev/null 2>&1; python3 $S/absum.py $name >/dev/null; [ -n "$KEEP" ] || rm -f $S/runs/ab_${name}_r${r}$k.txt $S/runs/ab_${name}_r${r}$k.data; done
    echo "round $r $name done"
  done
done
