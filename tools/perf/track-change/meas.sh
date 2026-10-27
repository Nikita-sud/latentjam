#!/bin/bash
# meas.sh <tag> <mapping> : 12 key skips under simpleperf, report
S=$(dirname $0); cd $S
./prof.sh key 12 $1 && python3 ~/Library/Android/sdk/ndk/28.2.13676358/simpleperf/report_sample.py -i runs/$1.data --proguard-mapping-file $2 > runs/$1.txt 2>/dev/null && python3 an.py runs/$1.txt 10 0 | head -1
