#!/usr/bin/env bash
# Publishes the important lines of failed builds as GitHub annotations, so failures are readable
# through the checks API without downloading the raw logs.
set -u

emit() { # title, file
  local title="$1" file="$2"
  [ -s "$file" ] || return 0
  local chunk=0
  while [ $chunk -lt 8 ]; do
    local body
    body=$(sed -n "$((chunk * 25 + 1)),$((chunk * 25 + 25))p" "$file")
    [ -n "$body" ] || break
    body="${body//'%'/'%25'}"
    body="${body//$'\r'/}"
    body="${body//$'\n'/'%0A'}"
    echo "::error title=${title} (part $((chunk + 1)))::${body}"
    chunk=$((chunk + 1))
  done
}

tmp=$(mktemp)
for log in typecheck.log npm.log; do
  if [ -f "$log" ]; then
    grep -E "error|ERR!" "$log" | head -60 > "$tmp"
    emit "$log" "$tmp"
  fi
done

if [ -f build.log ]; then
  grep -E "^\[ERROR\]|COMPILATION ERROR|Tests run:.*Fail|FAILURE!|expected|Caused by|Exception|overridden by rollback|ERROR: " build.log \
    | grep -vE "^\[ERROR\] *$|Re-run Maven|For more information|Help 1|full stack trace|-e switch|-X switch|^\[ERROR\] -> \[Help" \
    | head -150 > "$tmp"
  emit "maven" "$tmp"
fi

for report in target/surefire-reports/*.txt; do
  [ -f "$report" ] || continue
  if grep -qE "FAILURE|ERROR" "$report"; then
    head -60 "$report" > "$tmp"
    emit "$(basename "$report")" "$tmp"
  fi
done
exit 0
