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
- Run-to-stop knobs: `--num-tries 999999 --timeout-seconds <run-seconds> --use-connection-test false --print-progress-summary true`. **`--timeout-seconds` is the total wall-clock cap** for the whole sqlancer run (it backs `execService.awaitTermination` in `Main.java:738`), not a per-statement timeout. Pass the desired duration in seconds (e.g. `1800` for 30 min); `-1` disables the cap entirely. Without a huge `--num-tries` you also stop after the first 100 found errors.
- **Raise heap for long runs**: invoke as `java -Xmx8g -jar target/sqlancer-2.0.0.jar ...`. The default heap fills mid-run on dense reproducer dumps and 37 of 38 saved `logs/clickhouse/database*.log` files in the 2026-05-19 48-minute baseline were OOM-truncated (the AssertionError reproducer wrote the schema + INSERTs successfully but the JVM died before serialising the failing query). 8 GiB is the floor for 25-oracle × 6-thread runs; oracles materialise full result-sets into Java strings before TLPWhere can compare them, and large `Date`/`DateTime` columns × multi-row reads blow past 4 GiB. The JoinAlgorithm oracle previously OOMed on cartesian self-joins even at 12 GiB; each algorithm-sweep query now carries server-side caps (`max_result_rows=1_000_000`, `result_overflow_mode='throw'`, `max_bytes_in_join=256 MiB`, `max_memory_usage=1 GiB`) so the unsupported corner trips a tolerated error instead of materialising 2.5B rows into the JVM. **Do not raise heap to compensate for cartesian-join OOMs** — investigate why the cap is being bypassed instead.
- Default oracle for ClickHouse is `TLPWhere`.
- `--log-each-select=true` is default and is required for AssertionError reproducer files; turning it off is invasive.
- The default `--num-threads=16` is too high for a `--cpus=6` CH server (CH becomes the bottleneck); 6 sqlancer threads matched the 6 CPU cores cleanly.
- Progress line interpretation: `Threads shut down: N` means `N` of `--num-threads` workers have died via `AssertionError` (real bug or unhandled error) and are gone for the rest of the run; throughput drops proportionally.

## Wire transports

Two interchangeable transports, both requesting `RowBinaryWithNamesAndTypes` and parsed via
client-v2's `RowBinaryWithNamesAndTypesFormatReader` through the thin adapter
`ClickHouseRowBinaryParser`:

- `--transport client` (default): backed by `com.clickhouse.client.api.Client` (clickhouse-java
  client-v2 0.9.8). Brings httpclient5 + connection pooling. Server-side settings
  (`max_execution_time`, `wait_end_of_query`, `http_response_buffer_size`,
  `allow_experimental_analyzer`, `allow_suspicious_low_cardinality_types`) are attached per-query
  via `QuerySettings.serverSetting` so pooled connections all carry them.
- `--transport http`: raw `HttpURLConnection`, zero extra deps. Useful as a fallback when an
  Apache HC regression appears under client-v2.

`jdbc-v2` (clickhouse-jdbc 0.9.8) was the historical transport and is dropped. Both wins from
that move stand:

- UInt64 → `long` overflow (`ArithmeticException`) in PQS' `fetchPivotRow` is gone: the reader
  exposes `getString(int)` which routes UInt64 through `BigInteger`, and oracle-side code calls
  `getString` and decides how to use it.
- `java.time.DateTimeException: Instant exceeds minimum or maximum` from JDBC's
  `getTimestamp()` is gone: the reader's `getString` formats DateTime values via CH's own text
  renderer rather than collapsing through `java.sql.Timestamp`.

### RowBinaryWithNamesAndTypes specifics

- The reader requires a timezone: `RowBinaryWithNamesAndTypesFormatReader`'s ctor refuses to
  build without `QuerySettings.setUseTimeZone(...)` or `setUseServerTimeZone(true)`. We pass
  `UTC`; sqlancer cares only about textual values, so any zone yields consistent rendering.
- The reader uses `Guava 31.1+`'s `ImmutableMap.Builder.buildKeepingLast()`. The transitive
  guava from `auto-service:1.0.1` is `31.0.1-jre` which lacks it, so `pom.xml` pins an
  explicit `com.google.guava:guava:33.4.0-jre` dependency. Without that pin every binary
  SELECT raises `NoSuchMethodError` deep inside the reader.
- `getString(int)` is **1-based** (CH/JDBC convention) and returns Java `null` for SQL NULL
  iff `hasValue(idx)` returns false; an empty string is `""` and `hasValue` is true. Empty vs
  NULL are structurally distinct on the wire, unlike TSV's `''` vs `\N`.
- Format rendering by `getString(int)` matches CH's TSV serialiser for the value types we
  exercise (verified on probe: empty strings, UTF-8 multi-byte, UInt64 above Long.MAX_VALUE,
  Float NaN/Infinity, Decimal(38,15)) with one cosmetic difference -- the reader emits
  `"NaN"` and `"Infinity"` where TSV emits `"nan"` and `"inf"`. Oracle string compares operate
  on values that all flow through the same reader, so they stay self-consistent.

The previous `ClickHouseTsvParser` was removed as part of this change. It silently dropped
single-column empty-string rows because of a misunderstanding of `BufferedReader.readLine`'s
trailing-newline semantics, manifesting as NoREC `(N-K vs N)` false positives whenever a
column held K empty values. Going binary eliminates the hand-rolled escape parser entirely.

## Environment quirks

- `JAVA_TOOL_OPTIONS` is poisoned in this user's shell: `-Djdk.attach.allowAttachSelf=trueASAN_OPTIONS=malloc_context_size=10 verbosity=1 ...`. **Every `java`/`mvn`/`jfr` invocation must start with `unset JAVA_TOOL_OPTIONS; unset ASAN_OPTIONS`** or the JVM refuses to start with `Unrecognized option: verbosity=1`.
- JDK 25 (`openjdk version "25.0.2"`). The project's `pom.xml` targets source 25.
