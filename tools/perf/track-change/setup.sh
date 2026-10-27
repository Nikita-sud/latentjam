#!/bin/bash
# setup.sh <apk> : install as update, compile with its profile, relaunch, start playing, open the player
D=emulator-5556; P=io.github.nikitasud.latentjam.kmp; S=$(dirname $0)
adb -s $D install -r "$1" | tail -1
adb -s $D shell am start -n $P/io.github.nikitasud.latentjam.app.MainActivity >/dev/null; sleep 5
adb -s $D shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE $P/androidx.profileinstaller.ProfileInstallReceiver >/dev/null
adb -s $D shell cmd package compile -m speed-profile -f $P
adb -s $D shell am force-stop $P
adb -s $D shell am start -n $P/io.github.nikitasud.latentjam.app.MainActivity >/dev/null; sleep 6
[ "$(adb -s $D shell cmd media_session volume --stream 3 --get 2>&1 | tail -1 | grep -c 'volume is 0')" = 1 ] || { echo NOT MUTED; exit 1; }
adb -s $D shell input tap 1036 504; sleep 1      # alphabet rail: A
adb -s $D shell input tap 320 740; sleep 2       # second row: starts the in-order queue near its top
adb -s $D shell input tap 400 2160; sleep 3      # mini player: open the full player
# SMART mode: the session's custom action then offers "Turn shuffle off"
for i in 1 2 3; do
  adb -s $D shell dumpsys media_session | grep -q "mName='Turn shuffle off" && break
  adb -s $D shell input tap 952 1804; sleep 1.5
done
adb -s $D shell dumpsys media_session | grep -q "mName='Turn shuffle off" || echo "NOT SMART"
sleep 3
adb -s $D exec-out screencap -d 4619827259835644672 -p > $S/setup.png; sips -Z 600 $S/setup.png --out $S/setup_s.png >/dev/null
