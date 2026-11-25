#!/usr/bin/env bash
# One command on the PC that brings everything up to date and says how it all is:
#   1. publish: new commits on the working branch (phone-app) are cherry-picked to the public
#      branch (salon-tv) and pushed (privacy hooks check every push); the working branch goes to
#      the private backup remote.
#   2. stick: git pull, then every installed copy (systemd units, the Wi-Fi watchdog, apt and
#      systemd drop-ins) is compared with the repo and replaced where it differs; the services whose
#      files changed are restarted.
#   3. web remote: rebuilt and copied when remote/ changed since the copy on the stick.
#   4. TV apps (tv-helper, tv-browser): built and installed when the repo's versionCode is newer than
#      the TV's.
#   5. phone app: built and published when version.properties is newer than the stick's, with
#      phone-app/release-notes.txt as the notes the phones show.
#   6. the stick's newest nightly backup is copied to ../yedek-stick (outside git; it holds secrets).
#   7. the status page, as text.
#
#   scripts/deploy-all.sh [--dry-run] [--no-publish]
# Settings come from phone-app/deploy.env (git-ignored): STICK=user@<stick>, TV_IP=<tv>, optionally
# WORK=phone-app PUB_BRANCH=salon-tv PUB_REMOTE=fork BACKUP_REMOTE=yedek.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); cd "$ROOT"
[ -f phone-app/deploy.env ] && . phone-app/deploy.env
STICK=${STICK:?set STICK=user@your-stick in phone-app/deploy.env}
TV_IP=${TV_IP:-}
WORK=${WORK:-phone-app}; PUB_BRANCH=${PUB_BRANCH:-salon-tv}; PUB_REMOTE=${PUB_REMOTE:-fork}; BACKUP_REMOTE=${BACKUP_REMOTE:-yedek}
DRY=0; PUBLISH=1
for a in "$@"; do case "$a" in --dry-run) DRY=1 ;; --no-publish) PUBLISH=0 ;; *) echo "unknown: $a"; exit 2 ;; esac; done
say() { printf '\n== %s\n' "$*"; }
do_() { if [ "$DRY" = 1 ]; then echo "  (dry-run) $*"; else eval "$@"; fi; }
# -n: ssh must not read this script's stdin (it would swallow the rest of a while-read loop).
on_stick() { ssh -n -o BatchMode=yes -o ConnectTimeout=10 "$STICK" "$@"; }
to_stick() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$STICK" "$@"; }   # stdin goes to the stick

say "0. checks"
[ "$(git branch --show-current)" = "$WORK" ] || { echo "switch to $WORK first"; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "commit or stash your changes first"; exit 1; }
on_stick true || { echo "the stick ($STICK) does not answer"; exit 1; }
[ -n "$TV_IP" ] || TV_IP=$(on_stick "cd ~/couch-tv && .venv/bin/python -c \"import json;c=json.load(open('config.json'));t=c.get('tvs') or [{}];print(t[0].get('ip',''))\"")
echo "  stick $STICK, TV $TV_IP"

if [ "$PUBLISH" = 1 ]; then
  say "1. publish"
  # git config salon.published = the last commit of $WORK already on $PUB_BRANCH (set here after
  # each publish; set it once by hand when starting: git config salon.published <commit>).
  MARK=$(git config --get salon.published || true)
  [ -n "$MARK" ] || { echo "set the last published $WORK commit first: git config salon.published <commit>"; exit 1; }
  NEW=$(git rev-list --reverse "$MARK..$WORK")
  if [ -n "$NEW" ]; then
    echo "  to $PUB_BRANCH: $(echo "$NEW" | wc -l) commit(s)"
    do_ git checkout -q "$PUB_BRANCH"
    for c in $NEW; do
      # a commit with nothing new for the public branch is skipped instead of stopping everything
      do_ "git cherry-pick --allow-empty $c > /dev/null && { git diff --quiet HEAD~1 HEAD && git reset -q --hard HEAD~1 || true; }"
    done
    do_ git push -q "$PUB_REMOTE" "$PUB_BRANCH"
    do_ git checkout -q "$WORK"
    do_ git config salon.published "$(git rev-parse "$WORK")"
  else
    echo "  $PUB_BRANCH is up to date"
  fi
  do_ git push -q "$BACKUP_REMOTE" "$WORK"
fi

say "2. stick"
OLD=$(on_stick "cd ~/couch-tv && git rev-parse HEAD")
do_ 'on_stick "cd ~/couch-tv && git pull -q --ff-only"'
NEW_HEAD=$(on_stick "cd ~/couch-tv && git rev-parse HEAD")
CHANGED=$(on_stick "cd ~/couch-tv && git diff --name-only $OLD $NEW_HEAD" || true)
[ -n "$CHANGED" ] && echo "$CHANGED" | sed 's/^/  changed: /' || echo "  code unchanged ($(echo "$NEW_HEAD" | cut -c1-7))"
# Installed copies: repo file | installed path | mode | what to run when it changes.
SYNC='stick-guard/systemd/couch-tv-bridge.service|/etc/systemd/system/couch-tv-bridge.service|644|reload bridge
stick-guard/systemd/couch-tv-adb-guard.service|/etc/systemd/system/couch-tv-adb-guard.service|644|reload guard
stick-guard/systemd/wifi-watchdog.service|/etc/systemd/system/wifi-watchdog.service|644|reload wifi
stick-guard/systemd/couch-tv-backup.service|/etc/systemd/system/couch-tv-backup.service|644|reload backup
stick-guard/systemd/couch-tv-backup.timer|/etc/systemd/system/couch-tv-backup.timer|644|reload backup
stick-guard/wifi-watchdog.sh|/usr/local/sbin/wifi-watchdog|755|wifi
stick-guard/systemd/10-hw-watchdog.conf|/etc/systemd/system.conf.d/10-hw-watchdog.conf|644|reexec
stick-guard/systemd/20-io-accounting.conf|/etc/systemd/system.conf.d/20-io-accounting.conf|644|reexec
stick-guard/systemd/90-less-writes.apt.conf|/etc/apt/apt.conf.d/90-less-writes|644|aptclean'
ACTIONS=""
while IFS='|' read -r src dst mode act; do
  if ! on_stick "cmp -s ~/couch-tv/$src $dst"; then
    echo "  install $dst"
    do_ "on_stick \"sudo mkdir -p \$(dirname $dst) && sudo install -m $mode ~/couch-tv/$src $dst\""
    ACTIONS="$ACTIONS $act"
  fi
done <<< "$SYNC"
echo "$CHANGED" | grep -q '^stick-guard/guard.py$' && ACTIONS="$ACTIONS guard"
echo "$CHANGED" | grep -q '^bridge/' && ACTIONS="$ACTIONS bridge"
case "$ACTIONS" in *reexec*) do_ 'on_stick "sudo systemctl daemon-reexec"' ;; *reload*) do_ 'on_stick "sudo systemctl daemon-reload"' ;; esac
case "$ACTIONS" in *aptclean*) do_ 'on_stick "sudo apt-get clean"' ;; esac
case "$ACTIONS" in *backup*) do_ 'on_stick "sudo systemctl enable --now couch-tv-backup.timer"' ;; esac
case "$ACTIONS" in *wifi*) do_ 'on_stick "sudo systemctl restart wifi-watchdog"' ;; esac
case "$ACTIONS" in *bridge*) do_ 'on_stick "sudo systemctl restart couch-tv-bridge"' ;; esac
case "$ACTIONS" in *guard*) do_ 'on_stick "sudo systemctl restart couch-tv-adb-guard"' ;; esac
[ -z "$ACTIONS" ] && echo "  installed files match the repo"

say "3. web remote"
WANT=$(git log -1 --format=%H -- remote/)
HAVE=$(on_stick "cat ~/couch-tv/remote/dist/.built-from 2> /dev/null" || true)
if [ "$WANT" != "$HAVE" ]; then
  echo "  rebuilding (remote/ at ${WANT:0:7}, stick has ${HAVE:0:7})"
  do_ '(cd remote && npm run -s build > /dev/null) && echo "$WANT" > remote/dist/.built-from'
  do_ '(cd remote && tar czf - dist) | to_stick "cd ~/couch-tv/remote && rm -rf dist.new && mkdir dist.new && tar xzf - -C dist.new && rm -rf dist && mv dist.new/dist dist && rmdir dist.new"'
else
  echo "  up to date"
fi

say "4. TV apps"
for app in tv-helper tv-browser; do
  pkg=$(sed -n 's/.*applicationId = "\(.*\)".*/\1/p' "$app/app/build.gradle.kts")
  want=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$app/app/build.gradle.kts")
  have=$(on_stick "adb -s $TV_IP:5555 shell dumpsys package $pkg 2> /dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1" || true)
  if [ -z "$have" ] || [ "$want" -gt "$have" ]; then
    echo "  $pkg: repo $want, TV ${have:-none} -> install"
    do_ "cmd //c \"\$(cygpath -w \"$ROOT/$app/build.cmd\")\" > /dev/null"
    do_ "scp -q $app/app/build/outputs/apk/release/app-release.apk $STICK:/tmp/$app.apk"
    do_ "on_stick \"adb -s $TV_IP:5555 install -r /tmp/$app.apk && rm /tmp/$app.apk\""
  else
    echo "  $pkg: $have (up to date)"
  fi
done

say "5. phone app"
want=$(sed -n 's/^code=//p' phone-app/version.properties | tr -d '\r')
have=$(on_stick "python3 -c \"import json;print(json.load(open('/home/'+__import__('getpass').getuser()+'/couch-tv-apphub/version.json'))['versionCode'])\"" 2> /dev/null || echo 0)
if [ "$want" -gt "$have" ]; then
  echo "  repo $want, stick $have -> publish"
  NOTES=$(cat phone-app/release-notes.txt 2> /dev/null || true)
  do_ 'bash phone-app/deploy.sh "$NOTES" > /dev/null'
else
  echo "  $have (up to date)"
fi

say "6. backup copy"
DEST="$ROOT/../yedek-stick"
LAST=$(on_stick "ls -1t ~/yedek/stick-*.tgz 2> /dev/null | head -1" || true)
if [ -n "$LAST" ]; then
  mkdir -p "$DEST"
  [ -f "$DEST/$(basename "$LAST")" ] && echo "  $(basename "$LAST") already here" || { do_ "scp -q $STICK:$LAST $DEST/"; echo "  $(basename "$LAST") -> ../yedek-stick"; }
  ls -1t "$DEST"/stick-*.tgz 2> /dev/null | tail -n +8 | xargs -r rm -f
else
  echo "  no backup on the stick yet"
fi

say "7. status"
on_stick "curl -s http://127.0.0.1:5401/status" | python -X utf8 -c "
import json, sys
raw = sys.stdin.read()
try:
    s = json.loads(raw)
except ValueError:
    print('  no status page on the stick yet (guard older than this script?)'); sys.exit(0)
mark = {'ok': '  ok ', 'warn': '  !! ', 'bad': '  XX '}
print('  overall:', s['state'])
for i in s['items']:
    line = mark.get(i['state'], '  ?  ') + i['group'] + ' / ' + i['label'] + ': ' + i['detail']
    print(line + ('  -> ' + i['hint'] if i.get('hint') and i['state'] != 'ok' else ''))
"
