#!/bin/sh
# MapsToTom start-up hook for the TomTom GO 950 (NavCore 9.541).
# /etc/rc runs this file before the navigation program (also after every USB connection) and
# waits, with no time limit, for start_ttn_after_ttntools. This script swaps /bin/ttn (in the
# RAM disk, nothing is flashed) for the patched copy next to it.
#   Undo for good: delete the ttntools folder.
#   Skip: create a file named "off" in this folder (made automatically after 3 failed power-ups).
#   Program log: create a file named "ttnlog" (the navigation program's messages go to ttn.log).
#   Keep the built-in modem visible: create a file named "keepmodem" (by default it is hidden, like on the ONE v4).
# Safety: a timer sets the start flag after 90 s whatever happens, so the unit cannot stay on the logo
# because of this script. 3 quick restarts (< 2 min apart, same power-up) -> TomTom's own program until
# the next power-up. 3 power-ups in a row that end within 90 s (e.g. a crash reboot loop) -> "off" file.

D=${1:-/mnt/sdcard/ttntools/}
D=${D%/}
FLAG=$D/start_ttn_after_ttntools
LOG=$D/log.txt
RUN=/var/run/m2t
# your unit's own program; empty when ttn.orig is missing, and then nothing is swapped
STOCK=$(md5sum "$D/ttn.orig" 2>/dev/null | cut -d' ' -f1)
mkdir -p $RUN
CLAIM=$RUN/claim.$$   # whoever creates this first (script or guard) sets the one start flag of this run

log() { read u x < /proc/uptime; echo "$u $*" >> "$LOG"; return 0; }
late() { [ -d $CLAIM ]; }   # the guard has started the navigator: change nothing any more

# rc deletes the flag once it has seen it, so one found now is left over and would make rc start the
# navigator without waiting for this script.
STALE=; [ -f "$FLAG" ] && { rm -f "$FLAG"; STALE=1; }

# the guard: runs outside the card (cd /, no open files there)
( cd /; sleep 90; mkdir $CLAIM 2>/dev/null && { touch "$FLAG"; echo "guard: started the navigator after 90 s" >> "$LOG"; } ) </dev/null >/dev/null 2>&1 &

go() {
  if mkdir $CLAIM 2>/dev/null; then touch "$FLAG"; log "flagged"; else log "done (the guard had already flagged)"; fi
  exit 0
}

log "start load $(cat /proc/loadavg) $(awk '/^(MemFree|Cached|SwapFree):/ {printf "%s%s ", $1, $2}' /proc/meminfo)"
[ -n "$STALE" ] && log "old start flag found and removed (this start may not have waited)"
if [ "$(wc -c < "$LOG")" -gt 65536 ]; then tail -n 200 "$LOG" > "$LOG.new" && mv "$LOG.new" "$LOG"; fi

# quick restarts within one power-up (rc main loop)
read now x < /proc/uptime; now=${now%.*}
last=$(cat $RUN/last 2>/dev/null); last=${last:--1000}
quick=$(cat $RUN/quick 2>/dev/null); quick=${quick:-0}
if [ $((now - last)) -lt 120 ]; then quick=$((quick + 1)); else quick=0; fi
echo $now > $RUN/last
echo $quick > $RUN/quick

# power-ups that end within 90 s (counted on the card, so a reboot loop is seen too)
if [ ! -f $RUN/booted ]; then
  touch $RUN/booted
  boots=$(cat "$D/boots" 2>/dev/null); boots=${boots:-0}; boots=$((boots + 1))
  echo $boots > "$D/boots"
  ( cd /; sleep 90; rm -f "$D/boots" ) </dev/null >/dev/null 2>&1 &
  if [ $boots -ge 3 ] && [ ! -f "$D/off" ]; then
    touch "$D/off"; rm -f "$D/boots"; log "3 power-ups in a row ended within 90 s: off file made (delete it to try again)"
  fi
else
  rm -f "$D/boots"   # a second run in this power-up (after USB): it did not crash
fi
log "counters done (restart $quick)"

# undo the log wrapper first, so the checks below see the real program (same disk: renames are atomic)
if [ -f /bin/ttn.real ]; then mv /bin/ttn.real /bin/ttn && log "log wrapper removed"; fi

PATCHED=$(cut -c1-32 "$D/ttn.md5" 2>/dev/null | tr -d '\r\n')
log "checking the program"
cur=$(md5sum /bin/ttn | cut -d' ' -f1)
log "program now ${cur%${cur#????????}}"

# the built-in modem: hidden for the patched program (rc starts gprscomd after this script only if
# /dev/gprsmodem exists; the program asks the HAL, which reads /proc/barcelona/gprsmodem)
modem_hide() {
  if [ -L /dev/gprsmodem ] || [ -e /dev/gprsmodem ]; then mv /dev/gprsmodem $RUN/gprsmodem && log "modem device hidden"; fi
  if grep -q "barcelona/gprsmodem" /proc/mounts; then log "modem flag already 0"
  else echo 0 > $RUN/gprs0; mount -o bind $RUN/gprs0 /proc/barcelona/gprsmodem 2>> "$LOG"; log "modem flag now $(cat /proc/barcelona/gprsmodem)"; fi
}
modem_show() {
  if { [ -L $RUN/gprsmodem ] || [ -e $RUN/gprsmodem ]; } && [ ! -e /dev/gprsmodem ] && [ ! -L /dev/gprsmodem ]; then
    mv $RUN/gprsmodem /dev/gprsmodem && log "modem device back"
  fi
  if grep -q "barcelona/gprsmodem" /proc/mounts; then umount /proc/barcelona/gprsmodem && log "modem flag back"; fi
}

# put = copy $1 to /bin/ttn.new, check md5 $2, then rename over /bin/ttn (never leaves /bin/ttn missing)
# returns 2 when the guard has already started the navigator
put() {
  rm -f /bin/ttn.new
  late && return 2
  cp "$1" /bin/ttn.new && chmod 755 /bin/ttn.new && log "copied" && \
    [ "$(md5sum /bin/ttn.new | cut -d' ' -f1)" = "$2" ] && log "copy checked" && ! late && mv /bin/ttn.new /bin/ttn && return 0
  rm -f /bin/ttn.new
  late && return 2
  return 1
}

stock() {
  late || modem_show
  if [ "$cur" = "$STOCK" ]; then log "$1: TomTom's own program"
  else put "$D/ttn.orig" "$STOCK"; r=$?
    case $r in 0) log "$1: TomTom's own program put back";; 2) log "$1: too late, the navigator had already started";;
      *) log "$1: could not put TomTom's own program back";; esac
  fi
  go
}

[ -f "$D/off" ] && stock "off file"
[ -f $RUN/fellback ] && stock "fell back earlier in this power-up"
[ $quick -ge 3 ] && { touch $RUN/fellback; stock "3 quick restarts"; }

if [ "$cur" = "$STOCK" ] && [ -n "$PATCHED" ] && [ -f "$D/ttn" ]; then
  put "$D/ttn" "$PATCHED"; r=$?
  case $r in 0) cur=$PATCHED; log "patched program in place";; 2) log "too late, the navigator had already started: TomTom's own program this time";;
    *) log "patched copy wrong or failed, TomTom's own program kept";; esac
elif [ "$cur" = "$PATCHED" ]; then log "patched program already in place"
else log "unexpected program or no patched copy, left alone"
fi

late && go
if [ "$cur" = "$PATCHED" ] && [ ! -f "$D/keepmodem" ]; then modem_hide; else modem_show; fi

# program log: wrapper written next to the program, then swapped in by renames on the same disk
if [ -f "$D/ttnlog" ] && [ "$cur" = "$PATCHED" ] && ! late; then
  if [ -f "$D/ttn.log" ] && [ "$(wc -c < "$D/ttn.log")" -gt 20000000 ]; then
    tail -n 5000 "$D/ttn.log" > "$D/ttn.log.new" && mv "$D/ttn.log.new" "$D/ttn.log"
  fi
  rm -f /bin/ttn.w /bin/ttn.real
  if printf '#!/bin/sh\nread u x < /proc/uptime\necho "=== start $u" >> %s/ttn.log\nexec /bin/ttn.real "$@" >> %s/ttn.log 2>&1\n' "$D" "$D" > /bin/ttn.w \
     && chmod 755 /bin/ttn.w && ln /bin/ttn /bin/ttn.real && ! late && mv /bin/ttn.w /bin/ttn; then
    log "program log on"
  else
    rm -f /bin/ttn.w /bin/ttn.real   # /bin/ttn is still the program itself here
    log "program log could not be switched on"
  fi
fi
go
