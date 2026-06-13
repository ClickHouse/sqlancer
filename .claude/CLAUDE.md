# SQLancer fork — operational notes

> **NEVER add comments to code.** Java sources in this repo are kept comment-free
> (see commit `chore: strip all comments from Java sources`). Do not write `//` or
> `/* */` comments, Javadoc, or explanatory inline notes in any code you add or
> edit — make the code self-explanatory through naming instead. Put rationale in
> commit messages, PR descriptions, or this CLAUDE.md, never in the source.

> **NEVER run sqlancer or ClickHouse locally for this repo.** All fuzz runs,
> smoke tests, and bug reproduction happen on the **dev-vm** (see "Running on the
> dev VM" below and the `dev-vm` skill). Do not start a local
> `clickhouse-server` container or run the sqlancer jar against `127.0.0.1`. The
> local-container recipe below is retained only as reference for the config-file
> set and env vars that `run-sqlancer.sh` mounts **on the dev-vm** — not an
> invitation to run locally.

## Running a ClickHouse head instance for perf (dev-vm only — see banner above)

- Image: `clickhouse/clickhouse-server:head` — **ALWAYS `docker pull` it fresh before every run AND every reproduction**, no exceptions. `head` is a mutable tag that advances ~daily and ClickHouse does **not** retain per-build version tags (e.g. `26.6.1.658` becomes unpullable once head moves to `.694`), so a stale local image silently fuzzes an old build and makes any finding impossible to re-confirm later. `run-sqlancer.sh` enforces this: the pull is unconditional (the old `--no-pull` flag is now a deprecated no-op), and the resolved `SELECT version()` + image `RepoDigest` are stamped at the top of every `logs/runs/sqlancer-*.log` and printed in the run summary so reproducers stay attributable after head advances. **Always record the exact version next to a saved reproducer** — if a finding doesn't replay on current head it may simply be fixed (or, since the prior build is unpullable, an unconfirmable build-specific transient). Port 18124 was already taken by `ch-querylog` so use a fresh container name/port.
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
- **Required config set (mounted by `run-sqlancer.sh` on the dev-vm).** Five files, not three:
  the three disk-pressure overrides above **plus** `async_insert_off.xml` (config.d) and
  `alter_mutation_sync.xml` (**users.d**, sets `alter_sync=2` + `mutations_sync=2`). The
  `mutations_sync=2` mount is load-bearing for oracle soundness: without it, async `ALTER … DELETE`
  mutations issued during DB generation run in the background and can complete *between* the two
  reads of any two-query oracle, producing false-positive mismatches (observed 2026-06-02 when a
  stray non-dev-vm container lacked the mount: an EET reverse∘reverse identity reported "16 rows
  vs 0" purely because a `DELETE WHERE <truthy>` landed mid-iteration). `run-sqlancer.sh` always
  mounts all five, so any dev-vm run is correct by construction — this is one more reason to run
  there and never stand up an ad-hoc container. (`async_insert_off.xml` →
  `/etc/clickhouse-server/config.d/`, `alter_mutation_sync.xml` →
  `/etc/clickhouse-server/users.d/`; profile settings load from the users tree, not config.d.)
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
- **Raise heap for long runs**: invoke as `java -Xmx24g -jar target/sqlancer-2.0.0.jar ...`. The default heap fills mid-run on dense reproducer dumps and 37 of 38 saved `logs/clickhouse/database*.log` files in the 2026-05-19 48-minute baseline were OOM-truncated (the AssertionError reproducer wrote the schema + INSERTs successfully but the JVM died before serialising the failing query). 8 GiB is the bare floor for 25-oracle × 6-thread runs; oracles materialise full result-sets into Java strings before TLPWhere can compare them, and large `Date`/`DateTime` columns × multi-row reads blow past 4 GiB. **`ClickHouseProvider` now pins `max_result_rows=1_000_000` + `result_overflow_mode='throw'` on every connection** (both client-v2 and http transports), and `ClickHouseErrors` tolerates `"Limit for result exceeded"` / `"TOO_MANY_ROWS_OR_BYTES"` globally — that universal cap, not the JoinAlgorithm-specific one, is what eliminated the OOM-thread-death family across all oracles. With the cap in place, **8 threads × 24 GiB heap = 3 GiB/thread** is the recommended budget for multi-hour runs; an earlier 8/16 configuration completed the 2026-05-23 dev-VM attempt-3 cleanly (1.58M queries, 0 OOMs) but left less headroom for the heaviest oracle iterations and trimmed GC margin. Before the cap, the same 8/16 config GC-thrashed to a halt after 8 minutes. **Do not raise heap to compensate for OOMs** — first check that the universal cap is in place (`max_result_rows` should appear in `ClickHouseProvider.createDatabase{Http,Client}`'s `settings` map), then check what's bypassing it.
- Default oracle for ClickHouse is `TLPWhere`.
- `--log-each-select=true` is default and is required for AssertionError reproducer files; turning it off is invasive.
- The default `--num-threads=16` is too high for a `--cpus=6` CH server (CH becomes the bottleneck); 6 sqlancer threads matched the 6 CPU cores cleanly.
- Progress line interpretation: `Threads shut down: N` means `N` of `--num-threads` workers have died via `AssertionError` (real bug or unhandled error). `Main`'s `ThreadPoolExecutor` replaces dead workers, so the **counter is cumulative across the run** (M deaths over time, not the current live count) and throughput stays steady even as the counter climbs. Compare against the saved `logs/clickhouse/database*.log` reproducer count for the real picture.
- **`run-sqlancer.sh` exit code: `255` = "reproducers were found", `0` = none.** It is NOT a crash — a clean 30-min run that surfaced 1+ reproducers exits 255 (the JVM exits non-zero when any worker died with an AssertionError). Don't mistake exit 255 for an aborted run; check the `==> Summary` block's `reproducers:` count and `Threads shut down:` instead. (A genuine degraded run looks different: throughput collapsing to single-digit q/s with the query counter flat-lining — that's GC thrash from a heavy seed materialising large result sets, independent of the exit code.)

## Running on the dev VM (Graviton ARM, CH HEAD)

The dev-vm skill (`~/.claude/skills/dev-vm/`) owns connection details and lifecycle. The sqlancer-specific bootstrap that worked in the 2026-05-23 3-hour run:

```bash
# 1. Bootstrap (≈3 min on a fresh c7g.4xlarge)
ssh ubuntu@nik-fomichev-dev-vm-1 'sudo apt-get update -qq && \
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -qq -y docker.io openjdk-25-jdk-headless rsync && \
  sudo usermod -aG docker ubuntu && sudo docker pull clickhouse/clickhouse-server:head'

# 2. Sync source tree (NEVER include database*.tmp/ — those are 7+ GiB of HSQLDB scratch
#    from other DBMS oracles; sqlancer doesn't need them)
rsync -az --exclude='target/' --exclude='target-root-old/' --exclude='logs/' \
  --exclude='.git/' --exclude='database*.tmp/' --exclude='database*.properties' \
  --exclude='database*.script' --exclude='database*.data' --exclude='database*.log' \
  ./ ubuntu@nik-fomichev-dev-vm-1:~/sqlancer-fork/

# 3. Build the jar on the VM (vendored Maven works fine on aarch64 — Maven is Java)
ssh ubuntu@nik-fomichev-dev-vm-1 'cd ~/sqlancer-fork && \
  unset JAVA_TOOL_OPTIONS; unset ASAN_OPTIONS; \
  tmp/apache-maven-3.9.9/bin/mvn -B package -DskipTests=true -Djacoco.skip=true -q'
```

Scaling on c7g.4xlarge (16 vCPU / 32 GiB): cap CH at 8 cpu / 6 GiB (`--cpus=8 -m=6g`) and run sqlancer with `--num-threads 8 -Xmx24g`. Totals out at ~30 GiB used, leaving ~2 GiB for the OS and container daemon. CH-side `MEMORY_LIMIT_EXCEEDED` is now globally tolerated (commit `15b8a901`), so the squeezed `-m=6g` cap surfaces as harmless `IgnoreMe`s rather than worker deaths — that's the trade for the bigger JVM heap. The earlier 8/16 split (CH at 10 cpu / 12 GiB, sqlancer at 8/16) also worked but left less GC margin for the heaviest iterations. CH at 12 cpu / 14 GiB + sqlancer at 12 threads / 12 GiB heap **overshoots** (per-thread heap drops below the 1.3 GiB floor) — attempt-1 of the 3h run died in 13 minutes that way.

## Filed ClickHouse bugs — reproducer → issue (open only)

Bugs SQLancer found here that are filed and still OPEN upstream. Minimal repros so a future run can
recognise an already-filed bug instead of re-investigating it. **Re-verify against current head
before acting** — when an issue is fixed/closed, delete its entry from this list. (Check state:
`gh issue view <N> --repo ClickHouse/ClickHouse --json state -q .state`.)

- **[#106649](https://github.com/ClickHouse/ClickHouse/issues/106649)** — `LOGICAL_ERROR "Column identifier <c> is already registered"` (Code 49) when a mutation's WHERE has an `IN (subquery)` whose inner SELECT joins two subquery-wrapped derived tables projecting the **same column name** (26.6 regression from PR #98884 routing mutations through the new analyzer; fix in flight as PR #106025). Mutation form required; empty tables suffice (analysis-time). **PINNED** via the substring `"is already registered"` in `ClickHouseErrors.getKnownOpenMutationAnalyzerBugs()` (consumed only by the mutation generator + `MutationAnalyzer` oracle) — **remove the pin when #106025 merges and head no longer reproduces.** Verified reproducing on head 26.6.1.399 (2026-06-10).
  ```sql
  CREATE TABLE a (k Int32, m Int64) ENGINE=MergeTree ORDER BY k;
  CREATE TABLE b (k Int32) ENGINE=MergeTree ORDER BY k;
  ALTER TABLE a UPDATE m = 1 WHERE k IN (SELECT x.k FROM (SELECT k FROM b) AS x
    JOIN b AS e ON e.k = x.k JOIN (SELECT k FROM b) AS y ON y.k = e.k);  -- Code 49 LOGICAL_ERROR
  ```
- **[#106419](https://github.com/ClickHouse/ClickHouse/issues/106419)** — `WHERE toStartOf{Year,Month,Quarter}(Date32) < const` returns 0 rows after a merge when the column has pre-1970 values (Date32→Date narrowing overflows; monotonic-filter range poisoned). Needs a **merge-formed part**. **GATED** (2026-06-11): the `ExtendedDatetime` oracle deliberately constructs this exact surface (private Date32 table, pre-1970 outlier part, optional `OPTIMIZE FINAL`); its setting=0 arm on a merged+pre-1970 table is skipped unless `--extended-datetime-known-overflow-arm` is true, and the non-merged pre-1970 arm pins part topology with `SYSTEM STOP MERGES` so a background merge can't re-form the filed shape behind the gate. Set the flag true to re-confirm; **REMOVE the gate when #106419 is fixed on head.**
  ```sql
  CREATE TABLE t (c1 Date32) ENGINE=MergeTree ORDER BY tuple();
  INSERT INTO t SELECT toDate32('1971-01-01')+toIntervalDay(number%18000) FROM numbers(9991);
  INSERT INTO t SELECT toDate32('1905-01-01')+toIntervalDay(number*30)    FROM numbers(9);
  OPTIMIZE TABLE t FINAL;
  SELECT count() FROM t WHERE toStartOfYear(c1) < toStartOfYear(toDate('2021-06-15'));  -- 0 WRONG
  SELECT countIf(toStartOfYear(c1) < toStartOfYear(toDate('2021-06-15'))) FROM t;       -- 9991 correct
  ```
- **[#106426](https://github.com/ClickHouse/ClickHouse/issues/106426)** — `LOGICAL_ERROR "Join restriction violated"` in `JoinOrderOptimizer::solveGreedy` on comma-join + LEFT JOIN with `IS NULL` in `ON` + cross-relation WHERE. Trigger = cardinality asymmetry (large comma table vs 1-row joined tables). `count()` masks it.
  ```sql
  CREATE TABLE t0 (c0 UInt64) ENGINE=MergeTree ORDER BY tuple();
  CREATE TABLE t1 (c0 Int64, c1 String) ENGINE=MergeTree ORDER BY tuple();
  CREATE TABLE t3 (c1 UInt64, c2 String) ENGINE=MergeTree ORDER BY tuple();
  INSERT INTO t0 SELECT number FROM numbers(1000); INSERT INTO t1 VALUES (1,'a'); INSERT INTO t3 VALUES (1,'a');
  SELECT * FROM t1, t3, t0 JOIN t3 AS right_0 ON (t1.c1=right_0.c2)
    LEFT OUTER JOIN t3 AS right_1 ON (t1.c1=right_1.c2) AND (right_1.c2 IS NULL)
    WHERE t1.c0 < t3.c1;   -- Code 49 LOGICAL_ERROR
  ```
- **[#106262](https://github.com/ClickHouse/ClickHouse/issues/106262)** — `col = const` equality drops rows when the ORDER BY key is a NaN-producing function (`sqrt`/`log` of negatives): the range KeyCondition becomes `[nan, nan]`. `IN (const)` works; range predicates work.
  ```sql
  CREATE TABLE t (c0 Int32) ENGINE=MergeTree ORDER BY sqrt(c0) SETTINGS allow_suspicious_indices=1, index_granularity=4;
  INSERT INTO t SELECT number-50 FROM numbers(100);
  SELECT countIf(c0=-30) FROM t;             -- 1 (exists)
  SELECT count() FROM t WHERE c0=-30;        -- 0 WRONG
  ```
- **[#106124](https://github.com/ClickHouse/ClickHouse/issues/106124)** — partition pruning with `intDiv`/divide by a **negative** constant drops rows for range predicates (decreasing fn flips the inequality in the partition KeyCondition). *(Fix PR may be in flight — re-check state.)*
  ```sql
  CREATE TABLE t (c1 UInt32) ENGINE=MergeTree() ORDER BY tuple() PARTITION BY intDiv(c1,-683);
  INSERT INTO t VALUES (0),(1),(700),(5000),(9976);
  SELECT count() FROM t WHERE c1 < 1000;     -- 1 WRONG (expected 3)
  ```
- **[#106125](https://github.com/ClickHouse/ClickHouse/issues/106125)** — SummingMergeTree FINAL drops a present row when the query reads only a summation column that is 0 for that row (read-in-order + column pruning). **Measure with row output, not `count()`** (count() masks it).
  ```sql
  CREATE TABLE s (c0 UInt32, v_keep Int32, v_zero UInt32) ENGINE=SummingMergeTree ORDER BY c0;
  INSERT INTO s VALUES (0,1,0),(1,1,1),(2,1,2);  INSERT INTO s VALUES (3,1,3),(4,1,4),(5,1,5);
  SELECT c0 FROM s FINAL WHERE v_zero >= 0 ORDER BY c0;   -- c0=0 MISSING (5 rows, expected 6)
  ```
- **[#106099](https://github.com/ClickHouse/ClickHouse/issues/106099)** — `LOGICAL_ERROR "Duplicate column name in row policy actions output"` (Code 49) when a permissive row policy's `USING` is a bare physical column ref. 26.x regression. Any wrapper (`c0+0`, `c0!=0`, `materialize(c0)`) avoids it.
  ```sql
  CREATE TABLE t (c0 Int32) ENGINE=MergeTree ORDER BY tuple(); INSERT INTO t VALUES (1),(2),(3);
  CREATE ROW POLICY pol ON t USING c0 TO ALL;
  SELECT c0 FROM t;   -- Code 49 (reading the policy column)
  ```

## Reproducing findings

- **Version-pin everything.** CH HEAD moves fast; bugs the fuzzer caught on 26.6.x do not always reproduce on 26.5.x. The 2026-05-23 `database48` finding (DISTINCT NaN coalescence) is 26.6-exclusive — single in-pass DISTINCT collapses different NaN bit patterns starting in 26.6, while the UNION-ALL+DISTINCT path doesn't; on 26.5 both paths kept them apart consistently. Record the CH version next to every saved reproducer; if a finding doesn't replay against the local 26.5 container, pull `clickhouse/clickhouse-server:head` (or query the dev-VM's container) before declaring it a flake.
- **Replay flags.** Sqlancer's saved `database*.log` reproducers include all CREATE TABLE attempts (some failing with `BAD_ARGUMENTS` because the generator over-decorates the schema before settling on the one that succeeds), and the CERT-oracle filler uses `INSERT … FROM numbers(N)` against tables that have `PARTITION BY c0` (which trips `TOO_MANY_PARTS` without an explicit raise). The replay command must be:
  ```bash
  docker exec -i clickhouse-server-perf clickhouse-client \
    --multiquery --ignore-error \
    --max_partitions_per_insert_block=100000 \
    < /tmp/databaseN-full.sql
  ```
  Without `--ignore-error` you stop on the first failed CREATE; without raising `max_partitions_per_insert_block` the CERT filler silently no-ops and your replay table has only the VALUES seed (~10-50 rows instead of 50k).
- **Multi-INSERT history matters.** The `database10` LEFT ANTI JOIN bug only fires after a multi-part table is merged via `OPTIMIZE FINAL` (or natural background merge). A fresh single-INSERT table with the same data and schema does not reproduce, even though every other surface (data, schema, settings) is identical. When a reproducer doesn't fire on a clean `CREATE … ; INSERT …` setup, try `INSERT VALUES (…); INSERT VALUES (…); OPTIMIZE TABLE … FINAL;` to mimic the run's part history before concluding it's a flake.

## Triaging a run's reproducers

Most `database*.log` files in a long-run output are **not** wrong-result bugs. Categorise by the `Caused by:` line, not the AssertionError message (the AssertionError text is just the offending SQL):

```bash
# Bucket reproducers by their root cause
for f in logs/clickhouse/database*.log; do
  case "$f" in *-cur.log) continue;; esac
  CAUSE=$(grep -m1 "Caused by:.*Code:" "$f" | grep -oE "Code: [0-9]+.*\([A-Z_]+\)" || \
          head -1 "$f" | grep -oE "^(java\.[a-zA-Z.]+(Error|Exception))")
  printf "%-22s %s\n" "${f##*/}" "${CAUSE:-(unknown)}"
done | sort -k2 | uniq -c -f1 | sort -rn
```

Typical noise families (now tolerated globally in `ClickHouseErrors`, so a fresh run after `15b8a901` shouldn't surface them at all):

- `Code: 241 (MEMORY_LIMIT_EXCEEDED)` — CH process hit its `-m=…` cgroup cap; the operator was just whatever was allocating at the moment. **Not a wrong-result bug.**
- `Code: 27 (CANNOT_PARSE_INPUT_ASSERTION_FAILED)` — generator emitted a string like `'i'` or `'N-<.'` and CH tried to parse it as Float64 (`'i'` looks like the start of `'inf'`, etc.). **Sqlancer-side gap, not a CH bug.**
- `java.lang.NullPointerException` in `ComparableTimSort` — `Collections.sort` on a list containing Java `null` for SQL NULL. **Sqlancer-side bug.**
- `SQLException: Failed to read value for column <x>` from `ClickHouseClientV2Transport` on a query whose projection mixes integer arithmetic with a `Time64` constant (e.g. `Int64*Int64 + CAST(... AS Time64(2))`) — the sum unifies to a Time-typed value far outside the renderable range and client-v2's RowBinary decoder throws while reading it. **Sqlancer-side transport/reader gap** (same class as the Variant decode trap), observed ~1/30-min via CODDTest's constant-folding probe. Follow-up: either render Time/Time64 through raw text in the reader or CAST-wrap mixed time arithmetic at emission, per the multiIf precedent.

- **SEMI/ANTI eliminated-side column reads are non-deterministic BY DESIGN** (ClickHouse#107073,
  closed 2026-06 by @vdimir): for a SEMI/ANTI join the preserved-side row set is well-defined, but
  any column read from the *eliminated* side (when not fixed by the ON keys) is ANY-like — filled
  from whichever matching row arrives first. Any legal plan change (join reorder side-swap,
  default-on since `query_plan_optimize_join_order_limit=10`), or just a different physical row
  order, flips the value, and every outcome is a correct answer. A differential reproducer whose
  ON/projection reads a SEMI/ANTI-dropped alias is therefore **not a bug** — the `JoinReorder`
  oracle's `liveAliasesBeforeJoin` restriction (permanent, `--join-reorder-allow-dropped-key-ref`
  to override) exists exactly for this. Real-world demos of the legal flip (funnel query flipping
  0↔1 after unrelated table growth; LEFT ANY JOIN lookup flipping run-to-run):
  `tmp/107073-real-use-case.md`.

Genuine bug-shape signal usually comes from `ClickHouseTLPSetOpOracle` (real INTERSECT/UNION_DISTINCT divergence) or `ComparatorHelper.assumeResultSetsAreEqual:127` (row-count mismatch). For the latter, **TLP+`GROUP BY` queries** are a known TLP oracle limitation that produces false positives — the same group key can appear in multiple WHERE-partition branches and inflate the UNION ALL count. If you see a row-count mismatch on a query with `GROUP BY`, replay the same query without it before filing; if the non-`GROUP BY` version matches, it's an oracle artifact.

- **Attributing a reproducer to a specific generator/oracle change: grep ONLY the failing query, not the whole file.** A `database*.log` is the *entire lifecycle log* of that database id — every CREATE/INSERT/ALTER/SELECT from many oracle iterations, not just the failing one. Grepping the whole file for a construct (e.g. `toISOWeek`, `multiIf`, `optimize_use_projections`) gives **false attribution**: the marker appears in dozens of *succeeded* statements from unrelated iterations even when the one failing query doesn't use it (cost me a false "my-change" flag on 2026-06-02). The failing query is the AssertionError + offending SQL in the **first ~6 lines** only. Scan those: `head -6 "$f" | grep -oE '<your markers>'`. The actual oracle is on the stack-trace lines just below (e.g. `ClickHouseTLPSetOpOracle.checkExcept`, `NoRECOracle.extractCounts`).
- **Float false-positive families that are NOT new-code bugs**, recurring across runs: TLPSetOp `EXCEPT ALL` over float math (`radians`/`erf`/`log` producing values like `6.8e16`), TLPGroupBy with a float projection (`-erf(abs(c0))`), and any differential aggregate oracle running `sum(Float)` (order-sensitive: partial-aggregate path vs full rescan round differently — the #99109 class). When authoring a new differential/aggregate oracle, restrict to **exact-integer aggregates (sum/min/max/count) and non-float GROUP BY keys**, or the run drowns in float noise (learned building `ProjectionToggle`).

## Preserving artefacts between attempts

Long-run iterations on the same machine clobber each other's `logs/runs/` and `logs/clickhouse/database*.log`. The convention from the 2026-05-23 3h sequence:

```bash
ATTEMPT_DIR=logs/attempt${N}-${THREADS}thr-${HEAP}-${STATUS}
mkdir -p "$ATTEMPT_DIR/clickhouse"
mv logs/runs/all-oracles-*h-*.log "$ATTEMPT_DIR/"
for f in logs/clickhouse/database*.log; do
  case "$f" in *-cur.log) ;; *) mv "$f" "$ATTEMPT_DIR/clickhouse/" ;; esac
done
```

`-cur.log` files are live transcripts, not saved reproducers; leave them in place so the next attempt's workers can overwrite them per database id.

## Wire transport

Single transport: `ClickHouseClientV2Transport`, backed by `com.clickhouse.client.api.Client`
(clickhouse-java client-v2 0.9.8). Requests `RowBinaryWithNamesAndTypes` and parses it via
client-v2's `RowBinaryWithNamesAndTypesFormatReader` through the thin adapter
`ClickHouseRowBinaryParser`. Brings httpclient5 + connection pooling. Server-side settings
(`max_execution_time`, `wait_end_of_query`, `http_response_buffer_size`,
`allow_experimental_analyzer`, `allow_suspicious_low_cardinality_types`) are attached per-query
via `QuerySettings.serverSetting` so pooled connections all carry them.

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
- **The reader CANNOT decode `Variant(...)` columns** -- it throws
  `IndexOutOfBoundsException: Index -1 out of bounds for length N` and the worker dies. This is
  the same reason `Variant`/`Dynamic`/`JSON` columns are kept out of the generated schema. The
  **non-obvious trap (2026-06-02):** an n-ary conditional whose branches do NOT losslessly unify
  produces a Variant *common type* even when no column is Variant. e.g.
  `multiIf(cond, intExpr, cond2, int64Expr, float32Expr)` settles on `Variant(Float32, Int64)`
  on CH 26.6 (`toTypeName` confirms). **Authoring rule: any generator emission whose result type
  is a multi-branch/union of dissimilar numeric types must be wrapped in a concrete cast** --
  `CAST((…) AS Nullable(Float64))` is the safe default (preserves NULLs, reads cleanly, keeps
  multiset semantics; `AS Float64` errors on NULL rows). This is why `generateMultiIf` /
  `renderMultiIfAndNestedIf` wrap their output. Verify a new conditional/union emission with
  `SELECT toTypeName(<expr>) FROM t` -- if it says `Variant(...)`, add the cast.

The previous `ClickHouseTsvParser` was removed as part of this change. It silently dropped
single-column empty-string rows because of a misunderstanding of `BufferedReader.readLine`'s
trailing-newline semantics, manifesting as NoREC `(N-K vs N)` false positives whenever a
column held K empty values. Going binary eliminates the hand-rolled escape parser entirely.

## Engine pool (2026-05-27)

Engine pool is schema-aware (per `ClickHouseTableGenerator.pickEngine(cols)`),
roll 0-99 (updated 2026-05-31, WS3):
- 78% plain MergeTree (always eligible)
- 8% ReplacingMergeTree -- only with a viable ver-column (UInt*/Date*/DateTime*)
- 6% SummingMergeTree -- only with a viable sum-column (numeric)
- 4% Collapsing/VersionedCollapsing -- only with an Int8 Sign column (+ ver for
  Versioned); see U2.1
- 4% AggregatingMergeTree (WS3/U3.2) -- only when the column list has a
  SimpleAggregateFunction column AND a bare-key column for ORDER BY
Each "only when ..." engine falls back to plain MergeTree when its gate fails.

Dedupe / AggregatingMergeTree engines without an eligible differentiator column
collapse all rows into one "dedupe by ORDER BY key" shape, which produces non-
deterministic visible cardinality across SELECTs (the 2026-05-20 false-positive
cluster). The fallback-to-MergeTree avoids the degenerate case. Additionally:
- For these engines, ORDER BY must be a *bare key column* (not a function-of-
  numeric, not a composite/state-typed column). Enforced via
  `isValidOrderByForDedupe` (now requires `isBareKeyColumn`); the dedupe fallback
  ORDER BY also picks the first bare-key column rather than `columns.get(0)`.

`SimpleAggregateFunction(func, T)` columns (WS3/U3.2) are emitted by the type
picker at ~1%. They read as the plain inner type and insert as a plain literal,
so every oracle handles them transparently. **`sum` requires T to be the widened
accumulator type (Int64/UInt64), NOT a narrow int** -- CH rejects
`SimpleAggregateFunction(sum, UInt32)` with Code 36 "Incompatible data types
between aggregate function". min/max are type-preserving (any scalar T). Full
`AggregateFunction(...)` columns are deliberately NOT emitted (opaque state bytes
render unstably through the generic read path, same reason JSON/Variant/Dynamic
stay out).

With these engines in the pool, `ClickHouseTable.supportsFinal()` is true a good
fraction of the time, and `FinalMerge` / `AggregateStateRoundtrip` exercise them.

WS3 oracles (in run-sqlancer.sh `--oracles all`): `AggregateStateRoundtrip`
(`finalizeAggregation(arrayReduce('sumState', groupArray(c))) == sum(c)`, runs on
numeric incl. SimpleAggregateFunction columns) and `MaterializedViewConsistency`
(self-contained: fresh src + Aggregating/Summing MV, numbers()-fed multi-block
inserts, asserts source aggregate == MV-maintained aggregate). The MV oracle has
a **totals-consistency precondition**: if the MV's total row count != the source's
it abandons the iteration -- under a memory-starved CH (`-m=6g`) an INSERT's MV
push can partially fail while the source commits and the INSERT still returns
success, leaving src > MV; that is an environment artifact, not a wrong-result
(proven: the same case replays identical on an unloaded CH).

## MutationAnalyzer oracle (2026-06-10)

`MutationAnalyzer` deterministically exercises the PR #98884 surface (mutation
analysis routed through the new analyzer in 26.6): per iteration one cell of
{ALTER UPDATE/DELETE, lightweight UPDATE/DELETE, MATERIALIZE COLUMN} ×
{#106649 joined-derived-tables IN-subquery, self-referencing IN-subquery
(deadlock-avoidance path — `max_execution_time` timeout here is a FINDING, the
oracle deliberately does not tolerate TIMEOUT_EXCEEDED), plain IN, alias-column
predicate, virtual-column predicate (crash-only arm)} ×
`validate_mutation_query` 0/1, against private AtomicLong-suffixed tables.
Narrow tolerance per the PatchPartConsistency precedent (no global expression
list), plus an affected-rows consistency assertion (sentinel `countIf` for
UPDATE, count-delta for DELETE; integer counts only). The general fleet reaches
the same bug class via the mutation generator's predicate-grade WHEREs (forced
#106649 arm ~10%, `generatePredicate()` arm ~35%) — both delivery vehicles are
intentional, breadth + depth.

## Text-index / full-text-search oracles (2026-06-13)

Four oracles plus a general-fleet predicate injection cover the ClickHouse text
(inverted) index surface. **Text-search-function soundness across tokenizers is
NON-OBVIOUS and bit hard** — a naive "index-on == use_skip_indexes=0 scan"
differential is unsound for several function×tokenizer combinations because the
two paths tokenize the *needle* differently. Empirically verified on head
26.6.1.734 (probe these again if head moves):

- `startsWith` / `endsWith` / `multiSearchAny`: **index==scan on ALL tokenizers**
  (incl. `array`). Safe to emit on a column of unknown tokenizer.
- `hasToken(fullword)`: index==scan on splitByNonAlpha + ngrams(N) + sparseGrams +
  asciiCJK + splitByString, but **DIVERGES on `array`** (array indexes the whole
  value as one token, so `hasToken(s,'word')` via index = [] while the scan
  whole-word-tokenizes → matches). By design, not a bug.
- `hasAllTokens` / `hasAnyTokens` with a **multi-word string needle**: sound on
  splitByNonAlpha, **DIVERGE on ngrams** (the needle's space-spanning N-grams are
  absent from non-adjacent data; the scan path tokenizes the needle into whole
  words instead). By design ("results may differ" territory), not a bug.
- `hasToken(short-fragment < N)` on ngrams(N): diverges (fragment is itself an
  N-gram). Irrelevant if the corpus/needles are full vocabulary words (≥4 chars).
- `LIKE`/`ILIKE`: sound on splitByNonAlpha + ngrams (the original `TextIndexLike`).

Consequences baked into the code:
- **`generateTextSearchPredicate`** (general fleet, `--text-search-predicate-emission`,
  default on) emits ONLY `startsWith`/`endsWith`/`multiSearchAny` — the column's
  index tokenizer is unknown, so only the all-tokenizer-safe trio is allowed.
- **`TextIndexLifecycle`** controls its own tokenizer: LIKE + `hasToken` always;
  `hasAllTokens`/`hasAnyTokens` only on the splitByNonAlpha arm.
- **`renderSkipIndex`** must NOT emit a `preprocessor` in the general schema —
  with a preprocessor, `hasToken` index-path ≠ scan-path is documented, which
  would make NoREC false-positive. Preprocessor coverage lives ONLY in the
  dedicated `TextIndexPreprocessor` oracle (private tables).

The oracles:
- `TextIndexLike` — LIKE/ILIKE over splitByNonAlpha|ngrams, arms DEFAULT /
  `ignore_data_skipping_indices` / `use_text_index_like_evaluation_by_dictionary_scan=0`
  / DIRECT_READ_OFF, plus a Java `contains` ground truth, plus an optional
  lightweight-DELETE(+OPTIMIZE FINAL) topology arm whose ground truth counts over
  live rows (the #107309 delete-masked-part class).
- `TextIndexPreprocessor` — `INDEX(s) preprocessor=lower(s)`, asserts a forced
  direct read (`force_data_skipping_indices` + `direct_read=1, add_hint=0`) over a
  mixed-case corpus equals a Java `lower()`-token-membership ground truth. NB the
  doc's `INDEX(lower(s))` "equivalent" form CANNOT be force-engaged for
  `hasToken(s,…)` on 26.6.1.734 (raises INDEX_NOT_USED) — that's why the oracle
  compares against Java ground truth rather than a second table.
- `TextIndexContainer` — `Array(String)`+`array` tokenizer (`has`/`hasAny`/`hasAll`
  vs exact Java `List` ground truth) and `Map(String,String)` key-vs-value
  isolation (`mapContainsKey`/`mapContainsValue`), across index-on/ignored/scan.
- `TextIndexLifecycle` — CREATE-with-index == (index-free + `ALTER ADD INDEX` +
  `MATERIALIZE INDEX SETTINGS mutations_sync=2`) == `use_skip_indexes=0` scan.

Validated: 2026-06-13 dev-vm, head 26.6.1.734, 1h full-fleet (167k queries) =
0 false positives from any FTS unit. Remaining uncovered (optional follow-ups):
`unicodeWord` tokenizer, `hasPhrase` order-sensitivity, JSON-subcolumn text index,
`tokens()`/`mergeTreeTextIndex` ground-truth oracles.

## TLPGroupBy oracle correctness

TLPGroupBy is fundamentally hard to make sound when fetch columns are arbitrary
expressions over the column set -- the projection's `any()`-per-group value can
collide across distinct groups (NaN-on-float, identity-collisions), so LHS row
count = distinct-group-tuple count while RHS DISTINCT row count = distinct-
projection-value count.

The fix that produces 0 false positives (committed `bbe5ed17`): project the
group-by keys themselves, not arbitrary fetch columns. Then row identity =
group identity, and the TLP partition invariant holds structurally. The
`--tlp-groupby-strict` flag still routes back to UNION ALL with multiset
semantics for periodic adversarial sweeps.

TLPAggregate has a separate residual false-positive class (JOIN+WHERE+SUM with
NaN-producing functions in the SUM argument) that the SUM-of-SUM-of-partitions
identity doesn't hold under. Not addressed in this session; ~23 reproducers /
5 min remain.

**Authoring rule for value-equivalence oracles: compare two expression forms as
two columns of ONE query, not as two separate queries.** A value-equivalence
oracle ("expr A == expr B on every row") that issues `SELECT A FROM t` and
`SELECT B FROM t` as two statements is exposed to a mutation race -- an async
`ALTER … DELETE` (or merge) landing between the reads makes A and B see
different snapshots and reports a spurious mismatch. Issue
`SELECT (A) AS a, (B) AS b FROM t` and compare the two columns **positionally**
(same rows, same order, no sort) -- both forms then evaluate against one
snapshot, the race is gone, and you halve the query count. The EET oracle's
ALGEBRAIC_ID / EXPR_REWRITE / MULTIIF_EQUIV modes were converted to this on
2026-06-02 (see `ClickHouseEETOracle.assertSingleSnapshotEquivalent`). NB: on
the dev-vm the race is also masked by `mutations_sync=2` (see the config-parity
note up top), but the single-snapshot form is correct everywhere and is the
pattern to copy for new equivalence oracles.

## Environment quirks

- `JAVA_TOOL_OPTIONS` is poisoned in this user's shell: `-Djdk.attach.allowAttachSelf=trueASAN_OPTIONS=malloc_context_size=10 verbosity=1 ...`. **Every `java`/`mvn`/`jfr` invocation must start with `unset JAVA_TOOL_OPTIONS; unset ASAN_OPTIONS`** or the JVM refuses to start with `Unrecognized option: verbosity=1`.
- JDK 25 (`openjdk version "25.0.2"`). The project's `pom.xml` targets source 25.
