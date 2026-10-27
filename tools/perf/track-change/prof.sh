#!/bin/bash
# usage: prof.sh <mode> <n> <tag> : simpleperf while doing n skips every 1.5 s
D=emulator-5556; P=io.github.nikitasud.latentjam.kmp; S=$(dirname $0)
mode=$1; n=$2; tag=$3; dur=$(( n*3/2 + 2 ))
adb -s $D shell simpleperf record --app $P -g -e cpu-clock -f 4000 --duration $dur -o /data/local/tmp/perf.data >/dev/null 2>&1 &
sleep 1.5
for i in $(seq 1 $n); do
  case $mode in
    key) adb -s $D shell input keyevent KEYCODE_MEDIA_NEXT;;
    tap) adb -s $D shell input tap $NEXT_XY;;
    swipe) adb -s $D shell input swipe $SWIPE 150;;
    none) ;;
  esac
  sleep 1.5
done
wait
adb -s $D pull /data/local/tmp/perf.data $S/runs/$tag.data >/dev/null
