#!/bin/bash
# Wi-Fi watchdog for the stick: keeps it on the home Wi-Fi without anyone pulling the plug.
#
# Seen on 2026-11 (Debian trixie, NetworkManager 1.52, msm8916 stick): after a power cut the stick
# came up but never joined the Wi-Fi again (only the red power LED, no blue Wi-Fi LED) until it was
# unplugged. The logs were lost (the journal lives in RAM), so the cause is not known. Likely ones:
# the router came up later than the stick, or its Wi-Fi refused the first handshakes while booting,
# and NetworkManager blocked the connection (it can take a failed handshake for a wrong password and
# then waits for someone to type it); or the Wi-Fi chip/driver got stuck. So this escalates:
#
#   no answer from the router for 30 s   -> "nmcli connection up" every minute (clears any block)
#   5 minutes                            -> note the state, restart wpa_supplicant + NetworkManager
#   15 minutes                           -> reboot, at most once an hour (a router that is off for
#                                           hours must not keep the stick rebooting)
#
# "Online" means the default gateway on the Wi-Fi answers a ping: an internet outage with the router
# up is not the stick's problem and changes nothing here. Notes go to /var/lib/wifi-watchdog/ (on
# flash, a few lines per outage), so the next time there is something to read.
set -u
IFACE=${WIFI_IF:-wlan0}
CON=${WIFI_CON:-}                       # NetworkManager connection; empty: the Wi-Fi one it has
STATE=/var/lib/wifi-watchdog
LOG=$STATE/log
SNAP=$STATE/snapshots
mkdir -p "$STATE"

log() {
  echo "$*"
  echo "$(date '+%F %T') $*" >> "$LOG"
  tail -n 400 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
}

uptime_s() { cut -d. -f1 /proc/uptime; }   # the stick has no clock chip: wall time can jump

con() {
  if [ -n "$CON" ]; then echo "$CON"; return; fi
  nmcli -t -f NAME,TYPE connection show | awk -F: '$2 == "802-11-wireless" { print $1; exit }'
}

online() {
  local gw
  gw=$(ip -4 route show default dev "$IFACE" 2>/dev/null | awk '{ print $3; exit }')
  [ -n "$gw" ] || return 1
  ping -c1 -W2 -I "$IFACE" "$gw" >/dev/null 2>&1 || ping -c1 -W3 -I "$IFACE" "$gw" >/dev/null 2>&1
}

# NetworkManager is in the middle of connecting (states 40..90): let it finish
activating() {
  local s
  s=$(nmcli -t -f GENERAL.STATE device show "$IFACE" 2>/dev/null | cut -d: -f2 | awk '{ print $1 }')
  [ -n "$s" ] && [ "$s" -ge 40 ] && [ "$s" -lt 100 ]
}

snapshot() {
  {
    echo "=== $(date '+%F %T') $1"
    nmcli -t device
    nmcli -t -f GENERAL.STATE,GENERAL.REASON device show "$IFACE"
    nmcli -t -f NAME,AUTOCONNECT,ACTIVE connection show
    echo "-- networks in range:"
    timeout 20 nmcli -t -f SSID,SIGNAL,CHAN device wifi list --rescan yes 2>&1 | head -12
    ip -br addr show "$IFACE"
    echo "-- kernel:"
    dmesg | grep -iE "wcn|wlan|wcnss|remoteproc|ieee80211|firmware" | tail -10
    echo "-- NetworkManager / wpa_supplicant:"
    journalctl -u NetworkManager -u wpa_supplicant -n 40 --no-pager -o short-iso 2>/dev/null
  } >> "$SNAP" 2>&1
  tail -n 1500 "$SNAP" > "$SNAP.tmp" && mv "$SNAP.tmp" "$SNAP"
}

down_since=0 tried=0 restarted=0 busy_since=0
log "basladi ($IFACE, baglanti: $(con))"
while true; do
  up=$(uptime_s)
  if online; then
    if [ "$down_since" -gt 0 ]; then
      log "Wi-Fi geri geldi ($(( up - down_since )) sn kopuktu)"
    fi
    down_since=0 tried=0 restarted=0 busy_since=0
    sleep 20
    continue
  fi
  if [ "$down_since" -eq 0 ]; then
    down_since=$up
    log "Wi-Fi yok: modem cevap vermiyor"
  fi
  gone=$(( up - down_since ))

  # 1) ask NetworkManager to connect, unless it is already busy connecting (for at most 90 s)
  if activating; then
    [ "$busy_since" -eq 0 ] && busy_since=$up
  else
    busy_since=0
  fi
  if [ "$gone" -ge 30 ] && [ $(( up - tried )) -ge 60 ] &&
     { [ "$busy_since" -eq 0 ] || [ $(( up - busy_since )) -ge 90 ]; }; then
    tried=$up
    c=$(con)
    nmcli radio wifi on >/dev/null 2>&1
    out=$(nmcli --wait 45 connection up "$c" ifname "$IFACE" 2>&1 | tail -n 1)
    log "yeniden baglan ($c): ${out:0:160}"
  fi

  # 2) five minutes: write down the state, restart the Wi-Fi services
  if [ "$gone" -ge 300 ] && [ "$restarted" -eq 0 ]; then
    restarted=1
    snapshot "5 dk kopuk"
    log "wpa_supplicant ve NetworkManager yeniden baslatiliyor"
    systemctl restart wpa_supplicant NetworkManager
    sleep 20
  fi

  # 3) fifteen minutes: reboot, no more than once an hour
  if [ "$gone" -ge 900 ]; then
    last=$(cat "$STATE/last-reboot" 2>/dev/null || echo 0)
    now=$(date +%s)
    if [ $(( now - last )) -ge 3600 ] || [ "$now" -lt "$last" ]; then
      snapshot "15 dk kopuk: yeniden baslatma"
      echo "$now" > "$STATE/last-reboot"
      log "15 dk kopuk: stick yeniden baslatiliyor"
      sync
      systemctl reboot
      sleep 120
    fi
  fi
  sleep 15
done
