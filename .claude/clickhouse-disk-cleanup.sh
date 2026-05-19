#!/usr/bin/env bash
# ClickHouse disk-pressure cleanup for sqlancer dev iterations.
#
# The container is ~98% observability data after a sqlancer run, not user
# tables. Two distinct stores need clearing:
#
#   1. /var/lib/clickhouse/store -- the system.*_log tables (text_log,
#      query_log, processors_profile_log, trace_log, metric_log,
#      asynchronous_metric_log, part_log, query_metric_log, etc.) live here.
#      Each is a MergeTree; TRUNCATE drops all parts but leaves the table
#      structure. They refill on the next query, so this is the right unit
#      of cleanup between sqlancer runs.
#
#   2. /var/log/clickhouse-server/clickhouse-server.log  (.err.log) -- file
#      log written by the server's Logger. Defaults: trace level,
#      rotates at 100 MB, keeps 10 files = up to ~1 GB. `truncate -s 0` on the
#      live files reclaims immediately without restarting the server.
#
# Plus: any orphan `database*` schemas left by killed sqlancer runs. Each
# carries metadata + data parts; SYNC ensures the storage is reclaimed
# synchronously rather than asynchronously.
#
# This script is idempotent and safe to run between sqlancer invocations.
# Container name and host port match the project's CLAUDE.md.

set -euo pipefail
CONTAINER=${CONTAINER:-clickhouse-server-perf}
HOST=${HOST:-127.0.0.1}
PORT=${PORT:-18124}

echo "=== Disk before ==="
docker exec "$CONTAINER" du -sh /var/lib/clickhouse /var/log/clickhouse-server

# 1. Drop orphan sqlancer databases. Drop in parallel; SYNC waits per-drop.
echo "=== Dropping orphan sqlancer databases ==="
mapfile -t DROPS < <(curl -s --data "SELECT 'DROP DATABASE IF EXISTS \`' || name || '\` SYNC' FROM system.databases WHERE name LIKE 'database%' FORMAT TabSeparatedRaw" "http://$HOST:$PORT/" --user "default:")
echo "found ${#DROPS[@]} orphan databases"
for d in "${DROPS[@]}"; do
    curl -s --data "$d" "http://$HOST:$PORT/" --user "default:" -o /dev/null &
done
wait

# 2. Truncate system observability tables.
echo "=== TRUNCATE system.*_log tables ==="
curl -s --data "SYSTEM FLUSH LOGS" "http://$HOST:$PORT/" --user "default:" -o /dev/null
for tbl in text_log query_log processors_profile_log trace_log metric_log asynchronous_metric_log part_log error_log query_views_log query_thread_log session_log opentelemetry_span_log query_metric_log backup_log background_schedule_pool_log; do
    curl -s --data "TRUNCATE TABLE IF EXISTS system.$tbl" "http://$HOST:$PORT/" --user "default:" -o /dev/null
done

# 3. Truncate the server file logs in place.
echo "=== Truncating server file logs in place ==="
docker exec "$CONTAINER" bash -c 'truncate -s 0 /var/log/clickhouse-server/clickhouse-server.log /var/log/clickhouse-server/clickhouse-server.err.log; rm -f /var/log/clickhouse-server/clickhouse-server.log.[0-9]* /var/log/clickhouse-server/clickhouse-server.err.log.[0-9]*'

echo "=== Disk after ==="
docker exec "$CONTAINER" du -sh /var/lib/clickhouse /var/log/clickhouse-server
