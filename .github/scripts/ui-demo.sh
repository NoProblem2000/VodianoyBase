#!/usr/bin/env bash
# Starts the built jar against three demo databases (dev / stage / prod) with a small history, so the UI shows
# environment badges on different commits, a working tree with uncommitted changes and a DDL audit log.
set -euo pipefail
export PGPASSWORD="$PGVC_TEST_PASSWORD"
PSQL="psql -h localhost -U $PGVC_TEST_USER -v ON_ERROR_STOP=1 -qX"
for db in demo_dev demo_stage demo_prod demo_qa demo_customer; do
  $PSQL -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
done
$PSQL -d demo_dev -f src/test/resources/fixtures/rich-schema.sql
# QA already has the same schema and gets its own baseline: its history is not connected to dev (link scenario)
$PSQL -d demo_qa -f src/test/resources/fixtures/rich-schema.sql
# an isolated customer installation: its own table first (other OIDs), then the same initial schema; no _pgvc
$PSQL -d demo_customer -c "CREATE TABLE public.customer_only (id int PRIMARY KEY, note text)"
$PSQL -d demo_customer -f src/test/resources/fixtures/rich-schema.sql

workdir=$(mktemp -d)
cp target/pg-git-vc-*.jar "$workdir/pg-git-vc.jar"
(cd "$workdir" && nohup java -jar pg-git-vc.jar --server.port=18766 > app.log 2>&1 &)
echo "$workdir" > /tmp/ui-demo-workdir
API=http://127.0.0.1:18766/api
for _ in $(seq 1 60); do curl -sf $API/environments > /dev/null && break; sleep 1; done

json() { curl -sf -H 'Content-Type: application/json' "$@"; }
add_env() { json -X POST $API/environments -d "{\"name\":\"$1\",\"colorBadge\":\"$2\",\"jdbcUrl\":\"jdbc:postgresql://localhost:5432/$3\",\"username\":\"$PGVC_TEST_USER\",\"password\":\"$PGVC_TEST_PASSWORD\"}" > /dev/null; }
add_env Dev '#22c55e' demo_dev
add_env Stage '#f59e0b' demo_stage
add_env Prod '#ef4444' demo_prod
add_env QA '#38bdf8' demo_qa
add_env Customer '#a855f7' demo_customer

json -X POST $API/environments/dev/pgvc/initialize > /dev/null
json -X POST $API/environments/dev/vc/commit -d '{"message":"Baseline: sales, billing and partitioned events","author":"alice"}' > /dev/null
json -X POST $API/vc/push -d '{"sourceEnvironmentId":"dev","targetEnvironmentId":"prod"}' > /dev/null
json -X POST $API/environments/qa/pgvc/initialize > /dev/null
json -X POST $API/environments/qa/vc/commit -d '{"message":"QA baseline","author":"qa"}' > /dev/null
json -X POST $API/environments/dev/vc/execute -d '{"message":"Add customer notes","author":"bob","upSql":"CREATE TABLE sales.notes (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, customer_id bigint REFERENCES sales.customers(id), body text NOT NULL);"}' > /dev/null
json -X POST $API/environments/dev/vc/execute -d '{"message":"Index notes by customer","author":"bob","upSql":"CREATE INDEX notes_customer_idx ON sales.notes (customer_id);"}' > /dev/null
json -X POST $API/vc/push -d '{"sourceEnvironmentId":"dev","targetEnvironmentId":"stage"}' > /dev/null
json -X POST $API/environments/dev/vc/execute -d '{"message":"Add February partition","author":"carol","upSql":"CREATE TABLE billing.events_2026_02 PARTITION OF billing.events FOR VALUES FROM ('"'"'2026-02-01'"'"') TO ('"'"'2026-03-01'"'"');"}' > /dev/null

# a hotfix made directly on prod: its history diverges from dev (second lane in the graph)
json -X POST $API/environments/prod/vc/execute -d '{"message":"Prod hotfix: index orders by status","author":"ops","upSql":"CREATE INDEX orders_status_hotfix_idx ON sales.orders (status);"}' > /dev/null

# uncommitted changes made "in an external IDE" -> working tree + DDL audit log
$PSQL -d demo_dev -c "ALTER TABLE sales.orders RENAME COLUMN note TO remark" \
  -c "ALTER TABLE sales.customers ADD COLUMN phone varchar(32)" \
  -c "CREATE INDEX customers_phone_idx ON sales.customers (phone)"
echo "demo ready"
