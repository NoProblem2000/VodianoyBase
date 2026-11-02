#!/usr/bin/env bash
# CLI mode against the running demo (same pgvc-environments.json): exit codes are checked, outputs are kept as
# CI artifacts (cli/*.txt, cli/*.md).
set -uo pipefail
out=/tmp/ui-artifacts/cli
mkdir -p "$out"
cd "$(cat /tmp/ui-demo-workdir)"
fail=0
run() { # name expected-exit-codes args...
  local name=$1 expected=$2; shift 2
  java -jar pg-git-vc.jar --cli "$@" > "$out/$name.txt" 2>&1
  local code=$?
  echo "exit=$code" >> "$out/$name.txt"
  if [[ " $expected " != *" $code "* ]]; then
    echo "::error::CLI $name: exit code $code, expected $expected"
    fail=1
  else
    echo "::notice::CLI $name: exit code $code"
  fi
}
run usage 1
run unknown-env 1 --action=check-drift --env=nope
# dev has uncommitted changes made "in an external IDE"
run check-drift-dev 2 --action=check-drift --env=dev --output="$out/drift-dev.md"
run check-drift-stage "0 2" --action=check-drift --env=stage
run diff-report-stage "0 2" --action=diff-report --env=stage --snapshot=/tmp/ui-artifacts/screenshots/reference.pgsnap.json \
  --output="$out/pr-comment.md" --fail-on=dangerous
run diff-report-working-tree 0 --action=diff-report --env=dev --output="$out/working-tree.md"
for f in working-tree.md drift-dev.md; do
  grep -q '| Change | Object | Risk |' "$out/$f" || { echo "::error::no change table in $f"; fail=1; }
done
exit $fail
