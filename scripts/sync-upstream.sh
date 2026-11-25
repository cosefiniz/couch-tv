#!/usr/bin/env bash
# Takes couch-tv's own new changes (Seagey/couch-tv, remote "origin") into both branches:
# the working branch (phone-app) and the public one (salon-tv), by merging origin/main.
# A conflict stops the script with the files to fix; nothing is pushed until both merges are clean.
# Then publish and update the stick with scripts/deploy-all.sh.
#
#   scripts/sync-upstream.sh [--dry-run]
# The stick guard checks for new upstream changes once a week and notifies the phone app.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); cd "$ROOT"
[ -f phone-app/deploy.env ] && . phone-app/deploy.env
WORK=${WORK:-phone-app}; PUBLIC=${PUBLIC:-salon-tv}; PUBLIC_REMOTE=${PUBLIC_REMOTE:-fork}; BACKUP_REMOTE=${BACKUP_REMOTE:-yedek}
DRY=0; [ "${1:-}" = "--dry-run" ] && DRY=1
[ "$(git branch --show-current)" = "$WORK" ] || { echo "switch to $WORK first"; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "commit or stash your changes first"; exit 1; }

git fetch -q origin main
N=$(git rev-list --count "$PUBLIC..origin/main")
if [ "$N" = 0 ]; then echo "couch-tv has nothing new for $PUBLIC."; exit 0; fi
echo "couch-tv has $N new commit(s):"
git log --format='  %h %s' "$PUBLIC..origin/main"
echo "Files they touch that we changed too (watch these):"
comm -12 <(git diff --name-only "$(git merge-base "$PUBLIC" origin/main)" origin/main | sort) \
         <(git diff --name-only origin/main "$PUBLIC" | sort) | sed 's/^/  /' || true
[ "$DRY" = 1 ] && { echo "(dry-run: nothing merged)"; exit 0; }

merge() {   # $1 = branch
  git checkout -q "$1"
  # --no-verify: the incoming commits are couch-tv's own and already public; the privacy hook
  # still checks the push, which skips upstream commits and looks at ours.
  if ! git merge --no-verify --no-edit -m "Merge couch-tv upstream ($(git rev-parse --short origin/main)) into $1" origin/main; then
    echo; echo "Conflict on $1. Fix these files, then: git add <files> && git commit --no-verify && scripts/sync-upstream.sh"
    git diff --name-only --diff-filter=U | sed 's/^/  /'
    exit 1
  fi
}
merge "$WORK"
merge "$PUBLIC"
git checkout -q "$WORK"
echo "Merged into $WORK and $PUBLIC. Rebuild what couch-tv changed and publish with scripts/deploy-all.sh"
echo "(the TV home app itself updates from couch-tv's Releases, asked on the TV)."
