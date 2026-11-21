#!/bin/bash
# Privacy check for what is about to leave this computer, run as git hooks:
#
#   pre-commit   scripts/staged-privacy-check.sh                 the staged lines and file names
#   commit-msg   scripts/staged-privacy-check.sh --msg <file>    the message and the author/committer
#   pre-push     scripts/staged-privacy-check.sh --push <remote> every commit being pushed (git gives
#                                                                the refs on stdin), when <remote> is
#                                                                in git config privacy.publicRemotes
#
# Unlike scripts/privacy-scan.sh (the whole folder and the whole history, for a one-off check), this
# looks only at what goes into a commit or a push, so git-ignored local files (config.json, signing
# keys, addresses) never block a commit. Same rules as privacy-scan.sh, plus your own word list:
# git config privacy.denylist /path/outside/the/repo/words.txt (one word or phrase per line).
# Install: scripts/install-privacy-hooks.sh. It prints where each finding is and which rule, never
# the matched text. Bypass once, only if you are sure: git commit --no-verify / git push --no-verify.
set -u
ROOT=$(git rev-parse --show-toplevel) || exit 2
cd "$ROOT" || exit 2

TMP=$(mktemp -d "${TMPDIR:-/tmp}/staged-privacy.XXXXXX") || exit 2
trap 'rm -rf "$TMP"' EXIT

DENY=$(git config --get privacy.denylist || true)
DENYF=""
if [ -n "$DENY" ] && [ -s "$DENY" ]; then
  DENYF="$TMP/deny"
  # Lower case here and in what is searched: grep -i -F -f crashes in some builds (Git for Windows).
  tr -d '\r' < "$DENY" | grep -v '^[[:space:]]*$' | tr '[:upper:]' '[:lower:]' > "$DENYF"
  [ -s "$DENYF" ] || DENYF=""
fi

RULES=(
  "private-ip-192.168|(^|[^0-9.])192\.168\.[0-9]{1,3}\.[0-9]{1,3}"
  "private-ip-10.x|(^|[^0-9.])10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}"
  "private-ip-172.16-31|(^|[^0-9.])172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3}"
  "cgnat-or-vpn-ip-100.64-127|(^|[^0-9.])100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]{1,3}\.[0-9]{1,3}"
  "email|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}"
  "phone-intl|\+[1-9][0-9]{0,2}[ -]?\(?[0-9]{2,4}\)?[ -]?[0-9]{3,4}[ -]?[0-9]{3,4}"
  "mac-address|(^|[^0-9A-Fa-f:])([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}([^0-9A-Fa-f:]|$)"
  "home-folder-path|(/Users/[A-Za-z0-9._-]+/|[Cc]:[\\/]+Users[\\/]+[A-Za-z0-9._-]+)"
  "local-hostname|[A-Za-z0-9-]+\.local([^A-Za-z0-9]|$)"
  "home-network-hostname|[A-Za-z0-9-]+\.(lan|home\.arpa|ts\.net)([^A-Za-z0-9.-]|$)"
  "secret-like|(api[_-]?key|secret|passw(or)?d|token)[\"' ]*[:=][\"' ]*[A-Za-z0-9/+_-]{12,}"
)
# Allowed text (placeholders, public noreply addresses, package scopes, the ADB service type).
ALLOW='users\.noreply\.github\.com|noreply@anthropic\.com|@example\.(com|org)|your-computer\.local|homeassistant\.local|my-computer\.local|example\.local|<your-computer>\.local|x@y\.z|@types/|@vitejs/|@rolldown/|@oxc-project/|@emnapi/|@napi-rs/|@tybys/|@babel/|@esbuild/|@rollup/|@img/|\.local\.(plist|service)|_tcp\.local\.|192\.0\.2\.|storePassword|keyPassword'
FORBIDDEN='(\.apk$|\.aab$|\.pem$|\.jks$|\.keystore$|(^|/)adbkey|(^|/)pair-code|(^|/)token|(^|/)config\.json$|(^|/)\.env$|(^|/)bridge/data/|(^|/)signing\.properties$|(^|/)bridge\.properties$|(^|/)deploy\.env$|(^|/)ntfy-topic$|\.sqlite$|\.log$)'

FOUND=0
note() { echo "  privacy: $*" >&2; FOUND=$((FOUND + 1)); }

# 0 when the text on stdin holds a word from the list, 1 when not. A grep crash counts as a finding.
has_deny() {
  local r
  tr '[:upper:]' '[:lower:]' | grep -qaF -f "$DENYF"
  r=$?
  if [ "$r" -gt 1 ]; then note "word list check failed (grep exit $r)"; return 1; fi
  return "$r"
}

# $1: a file of "where: text" lines. Reports "rule in <label> <where>".
check_lines() {
  local f=$1 label=$2 rule name re hits h
  for rule in "${RULES[@]}"; do
    name="${rule%%|*}"; re="${rule#*|}"
    hits=$(grep -aE "$re" "$f" | grep -vE "$ALLOW" | cut -d: -f1 | sort -u)
    if [ -n "$hits" ]; then while IFS= read -r h; do note "$name in $label $h"; done <<< "$hits"; fi
  done
  if [ -n "$DENYF" ]; then
    # "where: " stays as it is; the text after it is lower-cased for the search.
    awk '{i = index($0, ": "); if (i) print substr($0, 1, i + 1) tolower(substr($0, i + 2)); else print tolower($0)}' "$f" > "$f.lc"
    grep -aF -f "$DENYF" "$f.lc" > "$f.hits"
    [ $? -gt 1 ] && note "word list check failed in $label"
    hits=$(cut -d: -f1 "$f.hits" | sort -u)
    if [ -n "$hits" ]; then while IFS= read -r h; do note "denylist in $label $h"; done <<< "$hits"; fi
  fi
}

# $1: a file of file names.
check_names() {
  local n
  while IFS= read -r n; do
    [ -z "$n" ] && continue
    if grep -qE "$FORBIDDEN" <<< "$n"; then note "forbidden file $n"; fi
    if [ -n "$DENYF" ] && has_deny <<< "$n"; then note "denylist in file name $n"; fi
  done < "$1"
}

# $1: a file of "Name <email>" lines.
check_ident() {
  local l
  while IFS= read -r l; do
    grep -qE '<[^>]*>' <<< "$l" || continue
    if ! grep -qE '<[^>]*(users\.noreply\.github\.com|noreply@anthropic\.com)>' <<< "$l"; then
      note "author/committer email is not a GitHub noreply address"
    fi
    if [ -n "$DENYF" ] && has_deny <<< "$l"; then note "denylist in author/committer name"; fi
  done < "$1"
}

# Added lines of a diff on stdin, tagged with their file: "path: text".
tag_added() { awk '/^\+\+\+ b\//{f=substr($0,7); next} /^\+/ && !/^\+\+\+ /{print f ": " substr($0,2)}'; }
SKIP=(-- . ':!scripts/privacy-scan.sh' ':!scripts/staged-privacy-check.sh' ':!remote/package-lock.json')
ZERO=0000000000000000000000000000000000000000

case "${1:-}" in
  --msg)
    sed '/^#/d' "$2" | awk '{print "message: " $0}' > "$TMP/msg"
    check_lines "$TMP/msg" "commit"
    { git var GIT_AUTHOR_IDENT; git var GIT_COMMITTER_IDENT; } | sed 's/>.*/>/' > "$TMP/ident"
    check_ident "$TMP/ident"
    ;;
  --push)
    remote=${2:-}
    git config --get-all privacy.publicRemotes | grep -qxF "$remote" || exit 0
    while read -r lref lsha rref rsha; do
      [ "$lsha" = "$ZERO" ] && continue
      if [ "$rsha" = "$ZERO" ]; then
        base=$(git rev-list "$lsha" --not --remotes="$remote" 2> /dev/null | tail -1)
        [ -z "$base" ] && continue
        range="$base^..$lsha"
      else
        range="$rsha..$lsha"
      fi
      git log -p --no-color --no-ext-diff --format='+++ b/commit-message%n+%s%n+%b' "$range" "${SKIP[@]}" | tag_added > "$TMP/push"
      check_lines "$TMP/push" "push to $rref:"
      git log --no-renames --name-only --format= "$range" | sort -u > "$TMP/names"
      check_names "$TMP/names"
      git log --format='%an <%ae>%n%cn <%ce>' "$range" | sort -u > "$TMP/ident"
      check_ident "$TMP/ident"
    done
    ;;
  *)
    git diff --cached -U0 --no-color --no-ext-diff "${SKIP[@]}" | tag_added > "$TMP/staged"
    check_lines "$TMP/staged" "staged"
    git diff --cached --name-only --diff-filter=ACMR > "$TMP/names"
    check_names "$TMP/names"
    ;;
esac

if [ "$FOUND" -gt 0 ]; then
  echo "privacy check: $FOUND finding(s). Nothing was committed or pushed. Fix them, or if you are sure: --no-verify." >&2
  exit 1
fi
exit 0
