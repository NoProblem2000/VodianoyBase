#!/usr/bin/env bash
# Starts the built pg-git-vc.jar, checks that the UI and the REST API are served, initializes _pgvc through the API.
set -euo pipefail
workdir=$(mktemp -d)
cp target/pg-git-vc-*.jar "$workdir/pg-git-vc.jar"
cd "$workdir"
java -jar pg-git-vc.jar --server.port=18765 > app.log 2>&1 &
pid=$!
trap 'kill $pid 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  curl -sf http://127.0.0.1:18765/api/environments > /dev/null && break
  sleep 1
done

result=()
index=$(curl -sf http://127.0.0.1:18765/)
echo "$index" | grep -q '<div id="root">' && result+=("UI index.html served")
asset=$(echo "$index" | grep -o '/assets/[^"]*\.js' | head -1)
curl -sf "http://127.0.0.1:18765$asset" > /dev/null && result+=("JS bundle $asset served")

curl -sf -X POST http://127.0.0.1:18765/api/environments -H 'Content-Type: application/json' \
  -d "{\"name\":\"Smoke\",\"jdbcUrl\":\"$PGVC_TEST_JDBC_URL\",\"username\":\"$PGVC_TEST_USER\",\"password\":\"$PGVC_TEST_PASSWORD\"}" > /dev/null
curl -sf -X POST http://127.0.0.1:18765/api/environments/smoke/test | grep -q '"success":true' && result+=("connection test ok")
curl -sf -X POST http://127.0.0.1:18765/api/environments/smoke/pgvc/initialize | grep -q '"initialized":true' && result+=("_pgvc initialized via API")
[ -f pgvc-environments.json ] && grep -q '"activeEnvironmentId" : "smoke"' pgvc-environments.json && result+=("pgvc-environments.json written next to the jar")

printf '%s\n' "${result[@]}"
if [ ${#result[@]} -ne 5 ]; then
  echo "::error title=Jar smoke test::only ${#result[@]}/5 checks passed%0A$(printf '%s%%0A' "${result[@]}")$(tail -30 app.log | sed 's/%/%25/g' | paste -sd '\n' | sed ':a;N;$!ba;s/\n/%0A/g')"
  exit 1
fi
echo "::notice title=Jar smoke test::$(printf '%s%%0A' "${result[@]}")"
