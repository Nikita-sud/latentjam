#!/bin/bash
# framestats.sh <key|tap|swipe|none> <n> <outfile> : gfxinfo framestats per skip (env NEXT_XY="x y", SWIPE="x1 y1 x2 y2")
D=emulator-5556; P=io.github.nikitasud.latentjam.kmp
mode=$1; n=$2; out=$3; : > $out
for i in $(seq 1 $n); do
  adb -s $D shell dumpsys gfxinfo $P reset >/dev/null
  sleep 0.3
  case $mode in
    key) adb -s $D shell input keyevent KEYCODE_MEDIA_NEXT;;
    tap) adb -s $D shell input tap $NEXT_XY;;
    none) ;;
    swipe) adb -s $D shell input swipe $SWIPE 150;;
  esac
  sleep 1.4
  adb -s $D shell dumpsys gfxinfo $P framestats | awk -F, -v run=$i '/---PROFILEDATA---/{p=!p; next} p && $1=="0" {print run, ($18-$3)/1e6, ($6-$3)/1e6, ($14-$6)/1e6}' >> $out
done
