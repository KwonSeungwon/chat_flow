#!/usr/bin/env bash
# One-screen project status. Designed to be the first thing a new
# session (human or agent) runs to orient itself.
#
# Outputs:
#   - current branch + commits ahead/behind vs develop and origin
#   - working-tree dirtiness
#   - last develop-build.yml run (status + conclusion + sha)
#   - last 5 commits on current branch
#   - tracked TODO/FIXME counts (rough work-in-flight signal)
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

bold() { printf "\033[1m%s\033[0m\n" "$*"; }
dim()  { printf "\033[2m%s\033[0m\n" "$*"; }

bold "== branch =="
BR="$(git branch --show-current)"
echo "current:    $BR"
if git rev-parse --verify --quiet origin/"$BR" >/dev/null; then
  AHEAD=$(git rev-list --count origin/"$BR"..HEAD 2>/dev/null || echo 0)
  BEHIND=$(git rev-list --count HEAD..origin/"$BR" 2>/dev/null || echo 0)
  echo "vs origin:  $AHEAD ahead, $BEHIND behind"
fi
if [ "$BR" != "develop" ] && git rev-parse --verify --quiet origin/develop >/dev/null; then
  AHEAD_D=$(git rev-list --count origin/develop..HEAD 2>/dev/null || echo 0)
  BEHIND_D=$(git rev-list --count HEAD..origin/develop 2>/dev/null || echo 0)
  echo "vs develop: $AHEAD_D ahead, $BEHIND_D behind"
fi

DIRTY=$(git status --porcelain | wc -l | tr -d ' ')
if [ "$DIRTY" != "0" ]; then
  echo "tree:       $DIRTY uncommitted files"
else
  echo "tree:       clean"
fi
echo

bold "== last commits (current branch) =="
git log --oneline -5
echo

bold "== develop-build.yml (latest run) =="
if command -v gh >/dev/null 2>&1; then
  gh run list --workflow=develop-build.yml --limit 1 \
    --json status,conclusion,headBranch,headSha,createdAt,displayTitle \
    --jq '.[0] | "status:     \(.status)\nconclusion: \(.conclusion // "—")\nsha:        \(.headSha[0:7])\ntitle:      \(.displayTitle)\ncreated:    \(.createdAt)"' 2>&1 || true
else
  dim "(gh CLI not available)"
fi
echo

bold "== work in flight (rough) =="
TODO_COUNT=$(git grep -InE 'TODO|FIXME|XXX' -- '*.java' '*.dart' 2>/dev/null | wc -l | tr -d ' ' || echo 0)
echo "TODO/FIXME markers: $TODO_COUNT"
echo

bold "== uncommitted (top 10) =="
git status --porcelain | head -10
