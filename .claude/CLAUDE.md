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
    -v "$CFG/system_logs_disabled.xml:/etc/clickhouse-server/config.d/sf_system_logs_disabled.xml:ro" \
    clickhouse/clickhouse-server:head
  ```
  The three `-v` flags mount disk-pressure mitigation overrides (`log_level.xml` drops logger to
  `warning`; `trace_log_disabled.xml` removes the trace_log table entirely via the
  `remove="remove"` attribute; `system_logs_disabled.xml` removes the rest of the heavy
  non-diagnostic system logs the same way — `metric_log`, `asynchronous_metric_log`,
  `query_metric_log`, `processors_profile_log` (moved here from the former `system_log_ttl.xml`,
  full remove beats a TTL cap), `query_thread_log`, `query_views_log`, `opentelemetry_span_log`,
  `latency_log`, `blob_storage_log`, `backup_log`, `text_log` (added 2026-06-17 — the
  `database*.log` reproducers already carry the failing query + stack, so text_log is redundant for
  triage and pure disk/IO overhead under the fuzzer statement rate; it is recreated only on server
  start, so `DROP TABLE system.text_log` disables it on a live container without a restart);
  `query_log`/`part_log`/`error_log`/`crash_log` stay enabled for reproducer triage). Files must be mounted directly into config.d —
  ClickHouse's config processor scans only flat `*.xml` files there, not subdirectories. The `sf_`
  prefix on each filename keeps them sorted next to the entrypoint-generated
  `docker_related_config.xml` for easy inspection. With 6 sqlancer threads these together hold the
  data dir + file logs under ~150 MB during a 15-minute run versus ~1 GB without them. Drop a `-v`
  flag (or all three) if you specifically want trace_log / metric_log / verbose server logs for a
  debugging session.
- **Required config set (mounted by `run-sqlancer.sh` on the dev-vm).** Five files, not three:
  the three disk-pressure overrides above (`log_level.xml`, `trace_log_disabled.xml`,
  `system_logs_disabled.xml`) **plus** `async_insert_off.xml` (config.d) and
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

## P0 coverage batch, 2026-08-15 (`docs/plans/2026-08-15-001-...-4month-coverage-gap-plan.md`)

Items 0a, 0b, 1–6 of the 4-month coverage-gap audit. Validated on dev-vm head **26.8.1.1470**:
a 30-minute full-fleet run over all 94 oracles except `TextIndexDirectRead` (its #107186 flood
drowns everything else) finished **exit 0, 137,380 queries, 0 reproducers, 0 threads shut down**.
A separate 12-minute run of the changed and new oracles (CodecRoundtrip,
DistributedPlanEquivalence, JoinReorder, LimitRanking, ReplacingDedup, FinalMerge,
EngineEquivalence, PartitionMirror) did 36k queries with a single reproducer, and that one was the
known unfiled `NOT (NOT` bug below.

**0a — LIMIT BY cap is now asserted server-side.** `ClickHouseLimitRankingOracle.checkLimitByCap`
used to pull the key column into Java through `ComparatorHelper.getResultSetFirstColumnAsString`,
which routes every value through `trimTrailingDotZeros`; that helper rewrites `'0.0'` into `'0'`,
so a String key holding both looked like one key appearing twice (the 2026-08-04/08-07 nightly
false positives). The check is now
`SELECT max(cnt) FROM (SELECT count() AS cnt FROM (<limit-by query>) GROUP BY lb_key)`. Nothing is
normalised client-side any more, and 10000 rows no longer cross the wire. **`trimTrailingDotZeros`
is still applied by every other oracle** — scoping or removing it is a separate, still-open
follow-up (checklist rule C8).

**0b — degenerate dedupe ORDER BY keys are rejected.** `ClickHouseTableGenerator` gained
`hasDegenerateKeyDomain` / `isDedupeKeyColumn`: a dedupe or collapse engine's sorting key may no
longer be `Bool` or an `Enum` with fewer than `MIN_DEDUPE_KEY_DOMAIN` (8) entries — and the type
picker caps generated enums at 5 entries, so today that rejects every enum. `pickEngine` falls back
to plain MergeTree when no non-degenerate bare key column exists, the dedupe fallback ORDER BY uses
the same filter, and `ReplacingMergeTree` now **always** emits its ver argument (previously 50%).
With a two-value key a background merge collapses visible cardinality between two reads, which is
what produced the 08-07 `TLPWhere: size of the result sets mismatch (91 and 26)` false positive.
Verified on a 12-minute dev-vm run: 0 dedupe tables with a Bool/Enum sorting key, 0 of 75
ReplacingMergeTree tables without a ver argument.

**1 — boolean-position and truth-value predicates** (`--truth-value-predicate-emission`, default
on). `generatePredicate()` now emits `NOT (NOT x)`, `NOT x`, `x IS [NOT] TRUE/FALSE/UNKNOWN`,
`x IS NOT DISTINCT FROM lit`, `nullIf/ifNull/coalesce(x, lit)` over numeric columns, plus
`LIKE`/`ILIKE ... ESCAPE` over String columns; half the time the wrapper is compared against a
numeric or float constant, which is the *value position* that matters. Rendered through real AST
nodes (`ClickHouseUnaryPrefixOperation`, `ClickHousePostfixText`, and the new
`ClickHouseWrappedExpression`), never `ClickHouseRawText`, so the KeyCondition oracle's
`materialize()` rewrite still reaches the column references. **This finds a real, unfiled
wrong-result bug on head — see the entry below.**

**2 — `FloatPruning` oracle** (`--float-pruning-oracle`). Private fixture with
Float32/Float64/Nullable(Float64) columns holding NaN, ±inf, -0.0 and NULL across several parts
(one part all-NaN), float ORDER BY / PARTITION BY / minmax + bloom_filter skip indexes /
materialized statistics. Two assertions: (a) a negated float comparison in WHERE must select the
same key multiset as the same predicate evaluated as a `groupArrayIf` aggregate argument over a
full scan, and (b) `count(P) + count(NOT P) + count(P IS NULL) = count(*)`.
**Authoring lesson: `materialize()` plus `use_skip_indexes=0 / allow_statistics_optimize=0 /
convert_query_to_cnf=0 / optimize_move_to_prewhere=0 / force_primary_key=0` does NOT defeat
partition-level or primary-key-level pruning** — the first draft used that as its reference arm and
was silently comparing two equally-pruned answers. The sound reference is a predicate that never
reaches a WHERE clause at all: `groupArrayIf(k, ifNull((P), 0))` over the whole table. Copy that
pattern for any future pruning oracle.

**3 — `DistributedPlanEquivalence` oracle** (`--distributed-plan-equivalence-oracle`). One
generated read must return the same multiset under plain local execution,
`make_distributed_plan = 1`, `serialize_query_plan = 1`, a `cluster('default', ...)` read with
`parallel_replicas_local_plan` on and off, and `enable_parallel_replicas = 1` +
`max_parallel_replicas = 3` + `parallel_replicas_for_non_replicated_merge_tree = 1` over both the
local and a `Distributed(...)` relation. Five query shapes including a three-way comma join whose
middle relation is a VIEW (the #111727 shape). The single-node `default` cluster exists on head
(1 shard, 1 replica, localhost), so all six profiles genuinely execute.

**4 — views and comma joins reach multi-relation FROM lists.** Three changes:
`--persistent-view-emission` (default on) adds a `VIEW` DDL action to the provider that creates up
to 3 plain `v<n>` views per database, so views survive in the schema snapshot instead of existing
only inside `ViewEquivalence`'s single iteration; `--comma-join-emission` (default on) lets the
join generator emit **genuine ON-less CROSS joins** — previously every CROSS was handed an ON
clause and silently degraded into an INNER join, so the fork could never produce `FROM t0, v0, t1`
— and raises the chain to up to four relations; and `ClickHouseJoinReorderOracle` builds a VIEW over
one of its private tables 40% of the time. Because views are now visible to every oracle,
**write paths must filter them**: `ClickHouseAlterGenerator` and `ClickHouseMutationGenerator` moved
to `getDatabaseTablesWithoutViews()`, and `ClickHouseCERTOracle` / `ClickHouseRowPolicyOracle` grew
`!isView()` filters. Any new oracle that INSERTs, ALTERs or OPTIMIZEs a schema-picked table must do
the same.

**5 — join-order enumerator sweep.** `ClickHouseJoinReorderOracle.checkEnumerationAlgorithms` runs
the same N-way join under `query_plan_optimize_join_order_algorithm` ∈ {greedy, dpsize, dpsub,
dphyp, dphyp+greedy, dpsub+greedy}, plus `query_plan_enable_optimizations = 0`,
`query_plan_join_shard_by_pk_ranges = 1` and `query_plan_optimize_join_order_max_searched_plans=1`.
**The setting is `query_plan_optimize_join_order_algorithm`, not `query_plan_join_reorder_algorithm`
as the plan guessed.** `dpsize` and `dphyp` only support inner joins and raise
`Code: 717 (EXPERIMENTAL_FEATURE_ERROR) "Failed to find a valid join order, try adding 'greedy'
algorithm as fallback"` on outer/semi/anti chains; that is a legitimate unsupported-shape error, not
a finding, and is tolerated in a dedicated `algorithmErrors` set (924 reproducers in the first
validation run were all this one message).

**6 — `CodecRoundtrip` oracle** (`--codec-roundtrip-oracle`). A table with random per-type
`CODEC(...)` declarations and a `CODEC(NONE)` mirror holding the same rows (including NaN, ±inf,
-0.0, denormals) must answer identically, still after `OPTIMIZE ... FINAL`, and still after an
`ALTER TABLE ... MODIFY COLUMN ... CODEC` mutation. The coded table sometimes carries
`allow_experimental_adaptive_codec_selection = 1` (PR #111834). Lossy codecs (`SZ3`, `ZXC`) are
excluded from the equality arm by allowlist and only have row count and NULL mask asserted; if the
lossy DDL is rejected the oracle retries with a lossless float codec instead of dropping the
iteration. `ALP` was also added to the general schema's float codec pool in `ClickHouseColumnBuilder`.

### Known-open bugs the 2026-08-15 batch deliberately fires on

Triage a run by these first; they are expected noise on a current head, not regressions.

- **UNFILED — `NOT (NOT key)` in value position prunes valid parts.** Found by item 1's emission,
  confirmed on head 26.8.1.1470. The projection says the predicate is true for every row, the WHERE
  form returns a subset, and `EXPLAIN indexes = 1` prints `Condition: (c1 in (-Inf, 3])`. Root cause
  is the `name == "not"` branch of `cloneDAGWithInversionPushDown` in
  `src/Storages/MergeTree/KeyCondition.cpp` treating `not` as purely logical and ignoring
  `boolean_context`, so two flips cancel and `NOT NOT c1` degrades to bare `c1`. **No setting
  disables it** — `materialize()`, `use_skip_indexes=0`, `allow_statistics_optimize=0`,
  `query_plan_enable_optimizations=0` and `optimize_move_to_prewhere=0` all still return the wrong
  rows — so `KeyCondition` cannot catch it. **NoREC and TLPWhere do** (`countIf(P)` = 2 vs
  `count() WHERE P` = 1). Wrong since at least 24.8. Triage by `NOT (NOT` in the failing query.
  ```sql
  CREATE TABLE t (c1 Int32) ENGINE = MergeTree ORDER BY c1;
  INSERT INTO t VALUES (0); INSERT INTO t VALUES (100);
  SELECT c1, (NOT (NOT c1)) <= 3.14 FROM t;            -- predicate is 1 for BOTH rows
  SELECT count() FROM t WHERE (NOT (NOT c1)) <= 3.14;  -- 1, must be 2
  SELECT countIf((NOT (NOT c1)) <= 3.14) FROM t;       -- 2, correct
  ```
- **[#113417](https://github.com/ClickHouse/ClickHouse/issues/113417) /
  [#112036](https://github.com/ClickHouse/ClickHouse/issues/112036) — NaN rows dropped by float part
  pruning under a negated comparison.** The `FloatPruning` oracle is a deliberate detector for this
  family and fires on a current head **at default settings** — a 6-minute standalone run produced
  326 worker deaths over 175 queries. It is therefore **deliberately absent from
  `run-sqlancer.sh`'s `ALL_ORACLES`**; run it standalone with `--oracles FloatPruning` and add it
  back once these issues close. (A constantly-firing oracle kills a worker and orphans a database
  per iteration, which is what stalled the 2026-06-14 20h run.) Confirmed on 26.8.1.1470:
  ```sql
  CREATE TABLE fp (k Int64, f32 Float32, f64 Float64) ENGINE = MergeTree ORDER BY (f64, k)
    PARTITION BY f32 SETTINGS index_granularity = 8, allow_floating_point_partition_key = 1;
  INSERT INTO fp VALUES (0, nan, nan), (1, 1.5, 1.5), (2, -inf, inf), (3, 0, -0.0);
  INSERT INTO fp VALUES (4, nan, nan), (5, nan, nan);
  INSERT INTO fp VALUES (6, 100, -3.14), (7, -1.5, 0.0000001);
  SELECT k FROM fp WHERE NOT (f64 < 1.5);   -- {1,2}; must be {0,1,2,4,5} (the NaN rows are dropped)
  SELECT count() FROM fp WHERE (NOT (f64 < 1.5));          -- 2
  SELECT count() FROM fp WHERE NOT (NOT (f64 < 1.5));      -- 3
  SELECT count() FROM fp WHERE (NOT (f64 < 1.5)) IS NULL;  -- 0, and 2+3+0 != 8
  ```
- **[#114113](https://github.com/ClickHouse/ClickHouse/issues/114113)** — `LOGICAL_ERROR "Left and
  right columns have same names"` out of `chooseJoinOrder` for a three-way comma join whose middle
  relation is a VIEW; aborts asan/ubsan servers. Item 4 makes this shape reachable, so the message
  is **pinned** via `ClickHouseErrors.getKnownOpenJoinOrderBugs()` (consumed by
  `addExpectedExpressionErrors` and by `ClickHouseJoinReorderOracle`). It did **not** reproduce on
  the release build 26.8.1.1470 with the plan's minimal `SELECT * FROM t0, v0, t1 WHERE <bool>`.
  **Remove the pin when the issue closes.**

## Filed ClickHouse bugs — reproducer → issue (open only)

Bugs SQLancer found here that are filed and still OPEN upstream. Minimal repros so a future run can
recognise an already-filed bug instead of re-investigating it. **Re-verify against current head
before acting** — when an issue is fixed/closed, delete its entry from this list. (Check state:
`gh issue view <N> --repo ClickHouse/ClickHouse --json state -q .state`.)

- **[#107186](https://github.com/ClickHouse/ClickHouse/issues/107186)** — `hasToken` (and `hasAllTokens`/`hasAnyTokens`) return **wrong results with default settings** via *exact direct read* from a text index whose tokenizer is not `splitByNonAlpha` (asciiCJK / array / ngrams / splitByString / sparseGrams) or that has a `preprocessor`. `query_plan_direct_read_from_text_index=1` (default) answers the predicate from the index posting lists using the index's tokenizer instead of `hasToken`'s fixed `splitByNonAlpha` semantics. **NOT GATED — deliberately fires every run** via the `TextIndexDirectRead` oracle (the user opted to let it fire to also catch relatives/regressions); triage by the `#107186` assertion string. Its SPLIT_CONTROL arm must stay clean. **When fixed on head, `TextIndexDirectRead` falls silent — remove this entry then.** Verified reproducing on head 26.6.1.735 (2026-06-13).
  ```sql
  CREATE TABLE t (s String, INDEX idx s TYPE text(tokenizer = 'asciiCJK')) ENGINE = MergeTree ORDER BY tuple();
  INSERT INTO t VALUES ('我来自北京邮电大学');
  SELECT count() FROM t WHERE hasToken(s, '北京邮电大学');                                -- 1 WRONG (direct read)
  SELECT count() FROM t WHERE hasToken(s, '北京邮电大学') SETTINGS use_skip_indexes = 0;  -- 0 correct
  ```
- **[#106649](https://github.com/ClickHouse/ClickHouse/issues/106649)** — `LOGICAL_ERROR "Column identifier <c> is already registered"` (Code 49) when a mutation's WHERE has an `IN (subquery)` whose inner SELECT joins two subquery-wrapped derived tables projecting the **same column name** (26.6 regression from PR #98884 routing mutations through the new analyzer; fix in flight as PR #106025). Mutation form required; empty tables suffice (analysis-time). **PINNED** via the substring `"is already registered"` in `ClickHouseErrors.getKnownOpenMutationAnalyzerBugs()` (consumed only by the mutation generator + `MutationAnalyzer` oracle) — **remove the pin when #106025 merges and head no longer reproduces.** Verified reproducing on head 26.6.1.399 (2026-06-10).
  ```sql
  CREATE TABLE a (k Int32, m Int64) ENGINE=MergeTree ORDER BY k;
  CREATE TABLE b (k Int32) ENGINE=MergeTree ORDER BY k;
  ALTER TABLE a UPDATE m = 1 WHERE k IN (SELECT x.k FROM (SELECT k FROM b) AS x
    JOIN b AS e ON e.k = x.k JOIN (SELECT k FROM b) AS y ON y.k = e.k);  -- Code 49 LOGICAL_ERROR
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
  with a preprocessor, `hasToken` index-path ≠ scan-path is the #107186 bug, which
  would make NoREC false-positive.

The divergences above are NOT by-design — they are **ClickHouse#107186** (OPEN):
`hasToken`'s exact direct read answers from the index posting lists with the
index's tokenizer/preprocessor instead of `hasToken`'s fixed `splitByNonAlpha`
semantics, so with `query_plan_direct_read_from_text_index=1` (default) it returns
wrong rows on asciiCJK/array/ngrams/splitByString/sparseGrams/preprocessor indexes.
This was MISCLASSIFIED as by-design during the first build; #107186 confirms it is
a wrong-result bug. The `TextIndexDirectRead` oracle deliberately targets it.

The oracles:
- `TextIndexLike` — LIKE/ILIKE over splitByNonAlpha|ngrams, arms DEFAULT /
  `ignore_data_skipping_indices` / `use_text_index_like_evaluation_by_dictionary_scan=0`
  / DIRECT_READ_OFF, plus a Java `contains` ground truth, plus an optional
  lightweight-DELETE(+OPTIMIZE FINAL) topology arm whose ground truth counts over
  live rows (the #107309 delete-masked-part class).
- `TextIndexDirectRead` — the **#107186 detector** (default-ON, fires every run by
  design until #107186 is fixed). Builds a private table per iteration with one of
  splitByNonAlpha(control) / asciiCJK / array / ngrams / sparseGrams / splitByString
  / `preprocessor=lower(s)`, then asserts `hasToken`/`hasAllTokens`/`hasAnyTokens`
  give identical keys under default (`direct_read=1`) vs `use_skip_indexes=0`. The
  SPLIT_CONTROL arm must stay clean (sound baseline); all other arms fire on
  #107186. **When #107186 is fixed on head this oracle should fall silent — if it
  keeps firing only on SPLIT_CONTROL, that is a NEW bug.** (Replaced the original
  `TextIndexPreprocessor` oracle, which wrongly codified the buggy direct-read
  answer as its ground truth.)
- `TextIndexContainer` — `Array(String)`+`array` tokenizer (`has`/`hasAny`/`hasAll`
  vs exact Java `List` ground truth) and `Map(String,String)` key-vs-value
  isolation (`mapContainsKey`/`mapContainsValue`), across index-on/ignored/scan.
- `TextIndexLifecycle` — CREATE-with-index == (index-free + `ALTER ADD INDEX` +
  `MATERIALIZE INDEX SETTINGS mutations_sync=2`) == `use_skip_indexes=0` scan.

Validated: 2026-06-13 dev-vm, head 26.6.1.735, 1h full-fleet (167k queries) =
0 false positives from TextIndexLike/Container/Lifecycle/#6. **`TextIndexDirectRead`
was added afterwards and DELIBERATELY fires on #107186** (the only expected
"reproducer" family on a current-head run; triage by its `#107186` assertion
string and ignore until the bug is fixed). Remaining uncovered (optional
follow-ups): `unicodeWord` tokenizer, `hasPhrase` order-sensitivity,
JSON-subcolumn text index, `tokens()`/`mergeTreeTextIndex` ground-truth oracles.

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
