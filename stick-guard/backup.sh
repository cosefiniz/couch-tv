#!/bin/sh
# Nightly backup of everything that is not in git: the bridge's settings and data (pairing keys,
# history), the guard's settings, the ADB key the TV trusts, the app hub's published version and
# the system units. Keeps the last 7 in ~/yedek (BACKUP_DIR). The PC copies the newest one with
# scripts/deploy-all.sh. Restore: stick-guard/RESTORE.md. Run by couch-tv-backup.timer, as the user.
set -eu
HOME_DIR=${HOME:-/home/user}
DIR=${BACKUP_DIR:-$HOME_DIR/yedek}
KEEP=${BACKUP_KEEP:-7}
mkdir -p "$DIR"
chmod 700 "$DIR"
OUT="$DIR/stick-$(date +%Y%m%d-%H%M).tgz"
LIST=$(mktemp)
trap 'rm -f "$LIST"' EXIT
cd "$HOME_DIR"
for f in couch-tv/config.json couch-tv/.env couch-tv/bridge/data couch-tv/stick-guard/config.json \
         couch-tv/stick-guard/tvplus-index couch-tv/stick-guard/ntfy-topic couch-tv/tv-home/tvs \
         couch-tv-apphub/version.json couch-tv-apphub/events.jsonl .android/adbkey .android/adbkey.pub; do
  [ -e "$f" ] && echo "$f" >> "$LIST"
done
for f in /etc/systemd/system/couch-tv-bridge.service /etc/systemd/system/couch-tv-adb-guard.service \
         /etc/systemd/system/couch-tv-backup.service /etc/systemd/system/couch-tv-backup.timer \
         /etc/systemd/system/wifi-watchdog.service /etc/systemd/system.conf.d/10-hw-watchdog.conf \
         /etc/systemd/system.conf.d/20-io-accounting.conf /usr/local/sbin/wifi-watchdog \
         /etc/NetworkManager/system-connections; do
  [ -e "$f" ] && echo "$f" >> "$LIST"
done
# NetworkManager's Wi-Fi profiles are root-only; read them through sudo when allowed, else skip.
if sudo -n true 2> /dev/null; then
  sudo tar czf "$OUT" -C "$HOME_DIR" --files-from "$LIST" 2> /dev/null || true
  sudo chown "$(id -u):$(id -g)" "$OUT"
else
  grep -v '^/etc/NetworkManager' "$LIST" > "$LIST.u"; mv "$LIST.u" "$LIST"
  tar czf "$OUT" -C "$HOME_DIR" --files-from "$LIST" 2> /dev/null || true
fi
chmod 600 "$OUT"
# keep the newest $KEEP
ls -1t "$DIR"/stick-*.tgz 2> /dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f
echo "backup: $OUT ($(du -h "$OUT" | cut -f1))"
