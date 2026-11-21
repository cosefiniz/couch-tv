#!/bin/sh
# Installs scripts/staged-privacy-check.sh as this clone's pre-commit, commit-msg and pre-push hooks.
#   scripts/install-privacy-hooks.sh [/path/outside/the/repo/words.txt] [public-remote ...]
# The word list (names, network names, anything private) stays outside the repo; its path is kept in
# git config privacy.denylist. Pushes to the remotes named here are checked commit by commit.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
HOOKS=$(git -C "$ROOT" rev-parse --git-path hooks)
case "$HOOKS" in /*|?:*) ;; *) HOOKS="$ROOT/$HOOKS" ;; esac
mkdir -p "$HOOKS"
[ -n "${1:-}" ] && git -C "$ROOT" config privacy.denylist "$1"
[ $# -gt 0 ] && shift
for r in "$@"; do git -C "$ROOT" config --add privacy.publicRemotes "$r"; done
CHECK="\"\$(git rev-parse --show-toplevel)/scripts/staged-privacy-check.sh\""
printf '#!/bin/sh\nexec bash %s\n' "$CHECK" > "$HOOKS/pre-commit"
printf '#!/bin/sh\nexec bash %s --msg "$1"\n' "$CHECK" > "$HOOKS/commit-msg"
printf '#!/bin/sh\nexec bash %s --push "$1"\n' "$CHECK" > "$HOOKS/pre-push"
chmod +x "$HOOKS/pre-commit" "$HOOKS/commit-msg" "$HOOKS/pre-push"
echo "Installed pre-commit, commit-msg and pre-push in $HOOKS"
echo "Word list: $(git -C "$ROOT" config --get privacy.denylist || echo none)"
echo "Public remotes: $(git -C "$ROOT" config --get-all privacy.publicRemotes | tr '\n' ' ')"
