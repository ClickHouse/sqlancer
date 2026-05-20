# SQLancer fork — operational notes

## Running a ClickHouse head instance for perf

- Image: `clickhouse/clickhouse-server:head` — pull fresh each session, current head is `26.5.1.779`. Port 18124 was already taken by `ch-querylog` so use a fresh container name/port.
- Required env vars on first run: without `CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1` + `CLICKHOUSE_SKIP_USER_SETUP=1` the entrypoint disables network access for the `default` user (`Authentication failed: password is incorrect`). Logs print `neither CLICKHOUSE_USER nor CLICKHOUSE_PASSWORD is set, disabling network access` — that's the signal.
- Working command:
  ```
  CFG="$(pwd)/.claude/clickhouse-config"
  docker run --ulimit nofile=262144:262144 --name clickhouse-server-perf -p18124:8123 -d \
    --cpus=6 -m=8g \
    -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 -e CLICKHOUSE_SKIP_USER_SETUP=1 \
    -v "$CFG/log_level.xml:/etc/clickhouse-server/config.d/sf_log_level.xml:ro" \
    -v "$CFG/trace_log_disabled.xml:/etc/clickhouse-server/config.d/sf_trace_log_disabled.xml:ro" \
    -v "$CFG/system_log_ttl.xml:/etc/clickhouse-server/config.d/sf_system_log_ttl.xml:ro" \
    clickhouse/clickhouse-server:head
  ```
  The three `-v` flags mount disk-pressure mitigation overrides (`log_level.xml` drops logger to
  `warning`; `trace_log_disabled.xml` removes the trace_log table entirely via the
  `remove="remove"` attribute; `system_log_ttl.xml` caps `processors_profile_log` retention at 1
  hour). Files must be mounted directly into config.d — ClickHouse's config processor scans only
  flat `*.xml` files there, not subdirectories. The `sf_` prefix on each filename keeps them
  sorted next to the entrypoint-generated `docker_related_config.xml` for easy inspection. With 6
  sqlancer threads these three together hold the data dir + file logs under ~150 MB during a
  15-minute run versus ~1 GB without them. Drop a `-v` flag (or all three) if you specifically
  want trace_log / verbose server logs for a debugging session.
- Readiness probe: `until curl -sf http://127.0.0.1:18124/ping; do sleep 1; done`.
- Between runs: `.claude/clickhouse-disk-cleanup.sh` truncates the system observability tables and
  in-container file logs and drops orphan sqlancer databases. Idempotent; ~87% reduction on a
  populated container in benchmarks.
- HTTP-vs-SET trap:
  - `wait_end_of_query` is **HTTP-only** (SET returns `UNKNOWN_SETTING` and suggests `http_wait_end_of_query`).
  - `http_response_buffer_size` is consumed at the moment the server commits to a chunked response — SETting after a query starts is too late, so it must also stay on the URL.
  - Everything else (`max_execution_time`, `allow_experimental_analyzer`, `allow_suspicious_low_cardinality_types`) works via SET after connection.

## Running sqlancer

- Built jar: `target/sqlancer-2.0.0.jar` (~3.4 MB) after `mvn -B package -DskipTests=true -Djacoco.skip=true`. **Must include `-Djacoco.skip=true`** — JaCoCo 0.8.12 fails on class file major version 69 (Java 25).
- Maven: vendored under `tmp/apache-maven-3.9.9/` (not on `$PATH` by default).
- Argument order is positional: global options (`--num-threads`, `--host`, `--port`, `--username`, `--password`, etc.) must come **before** the DBMS subcommand (`clickhouse`); DBMS-specific options come after. Putting `--host` after `clickhouse` gives `Was passed main parameter '--host' but no main parameter was defined in your arg class`.
- Run-to-stop knobs: `--num-tries 999999 --timeout-seconds 180 --use-connection-test false --print-progress-summary true`. Without a huge `--num-tries` you stop after the first 100 found errors.
- **Raise heap for long runs**: invoke as `java -Xmx4g -jar target/sqlancer-2.0.0.jar ...`. The default heap fills mid-run on dense reproducer dumps and 37 of 38 saved `logs/clickhouse/database*.log` files in the 2026-05-19 48-minute baseline were OOM-truncated (the AssertionError reproducer wrote the schema + INSERTs successfully but the JVM died before serialising the failing query). 4 GiB is enough for a 25-oracle composite × 6 threads × multi-hour run.
- Default oracle for ClickHouse is `TLPWhere`.
- `--log-each-select=true` is default and is required for AssertionError reproducer files; turning it off is invasive.
- The default `--num-threads=16` is too high for a `--cpus=6` CH server (CH becomes the bottleneck); 6 sqlancer threads matched the 6 CPU cores cleanly.
- Progress line interpretation: `Threads shut down: N` means `N` of `--num-threads` workers have died via `AssertionError` (real bug or unhandled error) and are gone for the rest of the run; throughput drops proportionally.

## Local-patched clickhouse-jdbc driver

The clickhouse-jdbc 0.9.8 jar in `target/lib/` and in `~/.m2/repository/com/clickhouse/
clickhouse-jdbc/0.9.8/` is a **locally patched** build, not the upstream artifact. Patch
source lives at `/tmp/clickhouse-java` on branch `fix/resultset-close-swallow-stream-errors`
(open as draft PR https://github.com/ClickHouse/clickhouse-java/pull/2857). The fix
downgrades `ConnectionClosedException: Premature end of chunk coded message body`
from a thrown SQLException to a debug log inside `ResultSetImpl.close()`. Without it,
sqlancer's 25-oracle composite would lose ~7 oracle iterations per 5-minute window to
close-time noise (server send_timeout firing before the terminating zero-length chunk
is written); each lost iteration costs a full database rebuild including CERT's 50k-row
`INSERT … SELECT … FROM numbers(50000)` bulk-load, dragging steady-state throughput
from ~90 q/s down to 0 q/s by minute 5.

- Affected file: `jdbc-v2/src/main/java/com/clickhouse/jdbc/ResultSetImpl.java`. The
  patch adds a package-private `isStreamDrainException(Throwable)` classifier gating
  the two `e = re;` assignments in `close()`.
- Rebuilding the patched jar (after editing `/tmp/clickhouse-java`):
  ```
  unset JAVA_TOOL_OPTIONS; unset ASAN_OPTIONS
  cd /tmp/clickhouse-java
  javac -cp /home/nik/work/sqlancer-fork/target/lib/clickhouse-jdbc-0.9.8-all.jar:\
  /home/nik/work/sqlancer-fork/target/lib/slf4j-api-2.0.6.jar \
        -d /tmp/repro-out \
        jdbc-v2/src/main/java/com/clickhouse/jdbc/ResultSetImpl.java
  cp /home/nik/work/sqlancer-fork/target/lib/clickhouse-jdbc-0.9.8-all.jar /tmp/patched/
  cd /tmp/patched
  jar uvf clickhouse-jdbc-0.9.8-all.jar \
      -C /tmp/repro-out com/clickhouse/jdbc/ResultSetImpl.class \
      -C /tmp/repro-out com/clickhouse/jdbc/ResultSetImpl\$1.class
  cp clickhouse-jdbc-0.9.8-all.jar /home/nik/work/sqlancer-fork/target/lib/
  cp clickhouse-jdbc-0.9.8-all.jar /home/nik/.m2/repository/com/clickhouse/clickhouse-jdbc/0.9.8/
  ```
  Both the local `target/lib/` copy and the maven cache copy must be updated, otherwise
  `mvn package` will overwrite the local jar from the cache on the next sqlancer build.
- Verify the patch is live: `unzip -p target/lib/clickhouse-jdbc-0.9.8-all.jar
  com/clickhouse/jdbc/ResultSetImpl.class | strings | grep isStreamDrainException`
  should print the classifier method name.
- Backup of the stock 0.9.8 jar (no patch) is at
  `target/lib/clickhouse-jdbc-0.9.8-all.jar.bak`. Restore if comparing against unpatched
  behaviour: `cp target/lib/clickhouse-jdbc-0.9.8-all.jar.bak target/lib/clickhouse-jdbc-0.9.8-all.jar`.
- Upstream PR getting merged with a different fix would require re-patching against a new
  driver version; until then, keep the local patch in place.

## Environment quirks

- `JAVA_TOOL_OPTIONS` is poisoned in this user's shell: `-Djdk.attach.allowAttachSelf=trueASAN_OPTIONS=malloc_context_size=10 verbosity=1 ...`. **Every `java`/`mvn`/`jfr` invocation must start with `unset JAVA_TOOL_OPTIONS; unset ASAN_OPTIONS`** or the JVM refuses to start with `Unrecognized option: verbosity=1`.
- JDK 25 (`openjdk version "25.0.2"`). The project's `pom.xml` targets source 25.
