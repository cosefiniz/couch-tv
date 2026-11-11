#!/usr/bin/env bash
# Build the phone app on this PC and publish it on the stick's app hub (port 5401).
# Phones running the app see the new version within 15 minutes (or when they open the app),
# get a notification and install it from inside the app.
#
#   ./deploy.sh ["notes"]        publish the current version.properties as it is
#   ./deploy.sh --bump "notes"   raise the version (code +1, name +0.1) first, with release notes
#
# STICK is the ssh login of the computer running the bridge and the stick guard (its app hub folder
# is ~/couch-tv-apphub there). Put it in deploy.env (git-ignored), for example STICK=user@my-stick.
set -euo pipefail
cd "$(dirname "$0")"
[ -f deploy.env ] && . ./deploy.env
STICK=${STICK:?set STICK=user@your-stick, in deploy.env or the environment}
NOTES=${1:-}

if [ "${1:-}" = "--bump" ]; then
  NOTES=${2:-}
  python - <<'EOF'
import re
p = "version.properties"
s = open(p).read()
code = int(re.search(r"code=(\d+)", s).group(1)) + 1
major, minor = re.search(r"name=(\d+)\.(\d+)", s).groups()
s = re.sub(r"code=\d+", f"code={code}", s)
s = re.sub(r"name=[\d.]+", f"name={major}.{int(minor) + 1}", s)
open(p, "w").write(s)
print("version ->", code, f"{major}.{int(minor) + 1}")
EOF
fi

cmd //c "$(cygpath -w "$PWD/build.cmd")"
APK=app/build/outputs/apk/release/app-release.apk
CODE=$(sed -n 's/^code=//p' version.properties | tr -d '\r')
NAME=$(sed -n 's/^name=//p' version.properties | tr -d '\r')
SHA=$(sha256sum "$APK" | cut -d' ' -f1)

PYTHONUTF8=1 NOTES="$NOTES" CODE="$CODE" NAME="$NAME" SHA="$SHA" python - <<'EOF' > version.json
import json, os, time
print(json.dumps({"versionCode": int(os.environ["CODE"]), "versionName": os.environ["NAME"],
                  "sha256": os.environ["SHA"], "notes": os.environ["NOTES"],
                  "published": time.strftime("%Y-%m-%dT%H:%M:%S")}))
EOF

ssh -o BatchMode=yes "$STICK" 'mkdir -p ~/couch-tv-apphub'
scp -q "$APK" "$STICK":couch-tv-apphub/couch-tv-kumanda.apk.tmp
scp -q version.json "$STICK":couch-tv-apphub/version.json.tmp
# APK first, then the version that points at it, so a phone never sees a version without its file.
ssh -o BatchMode=yes "$STICK" 'cd ~/couch-tv-apphub && mv couch-tv-kumanda.apk.tmp couch-tv-kumanda.apk && mv version.json.tmp version.json'
echo "published $NAME ($CODE) sha256 $SHA"
echo "download page: http://${STICK#*@}:5401/"
