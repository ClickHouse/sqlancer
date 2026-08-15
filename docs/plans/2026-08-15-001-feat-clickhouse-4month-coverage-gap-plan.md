---
title: "feat: ClickHouse coverage gap audit, 2026-04-15 to 2026-08-15 (25 prioritized items)"
type: feat
status: p0-implemented
date: 2026-08-15
related:
  - docs/plans/2026-06-13-001-feat-clickhouse-coverage-backlog-30-ideas-plan.md
  - docs/plans/2026-06-10-002-feat-clickhouse-26x-feature-coverage-plan.md
  - docs/plans/2026-05-29-001-feat-clickhouse-coverage-expansion-roadmap-plan.md
---

# feat: ClickHouse coverage gap audit, last 4 months (25 prioritized items)

## Overview

This is a **research/backlog plan**, the successor to `docs/plans/2026-06-13-001-...-30-ideas-plan.md`. That plan audited the provider against the state of ClickHouse as of 2026-06-13 and its 30 ideas have shipped as 29 oracles. This plan audits the *next* window: every ClickHouse change merged between **2026-04-15 and 2026-08-15**, cross-referenced against what this fork can actually generate and assert today, and it ranks the resulting 25 gaps by expected bug-finding value.

It does not pre-write oracle or generator code. Each item carries enough structure (goal, ClickHouse surface with links, the invariant or emission, the bug class it catches, the evidence that the gap is real, the soundness risk, file targets, effort, verification) that any single entry can be promoted to a full implementation plan.

The plan is grounded in a concrete, measured miss. On 2026-08-13 the `SQLancerPP` job (10 minutes of *upstream* SQLancer, `general --database-engine CLICKHOUSE`) found a silent wrong result that this fork's 98 oracles have never surfaced in thousands of hours of fuzzing, because the miss was not in the oracle set but in the **predicate grammar**. Item 1 closes exactly that. See "Evidence from the 2026-08 nightlies" below.

## Problem Frame

Three structural patterns explain every gap in this audit.

**1. Sound oracles starved by a narrow grammar.** `ClickHouseKeyConditionOracle` compares a query against the same query with every column wrapped in `materialize()`, which defeats index and statistics analysis. That is a correct and powerful oracle. It cannot fire on a predicate shape the generator never emits. A repo-wide grep finds **zero** occurrences of `NOT (NOT`, `IS TRUE`, `IS FALSE`, `IS UNKNOWN`, `IS NOT DISTINCT FROM`, `LIKE ... ESCAPE`, `indexHint`, and `nullIf(key, sentinel)`. ClickHouse shipped or extended index pruning for most of those shapes inside this audit window, and the negation-handling code path in `KeyCondition.cpp` is provably wrong for one of them (item 1).

**2. Deliberate float avoidance has become a blind spot.** The provider CLAUDE.md records a hard-won rule: differential and aggregate oracles restrict themselves to exact-integer aggregates and non-float group keys, because `sum(Float)` is order sensitive and drowned early runs in noise. That rule is still right for *aggregate* oracles, but it has been over-applied. ClickHouse currently has an open cluster of wrong-result bugs whose entire trigger is NaN plus a negated float comparison feeding part, granule or statistics pruning ([#113417](https://github.com/ClickHouse/ClickHouse/issues/113417), [#112036](https://github.com/ClickHouse/ClickHouse/issues/112036), [#107074](https://github.com/ClickHouse/ClickHouse/pull/107074), [#106533](https://github.com/ClickHouse/ClickHouse/issues/106533)). A pruning oracle can be made NaN-safe *by construction* (same query, index on versus index off, row sets compared as multisets) and so is not exposed to float ordering at all. Item 2.

**3. Whole young subsystems are dark.** Plan-based parallel replicas, `make_distributed_plan`, `serialize_query_plan`, the DPhyp and DPsub join-order enumerators, the new statistics types, pipe operators, IEJoin, `GROUPS` window frames, and the second wave of text-index work all landed in this window. Several already have open wrong-result issues filed by other sources. This fork generates none of them.

## Audit Method

**Change inventory.** All merged, non-backport, non-cherry-pick, non-sync PRs in `ClickHouse/ClickHouse` with `merged_at > now() - INTERVAL 4 MONTH`, from the `qa_intelligence` database:

```sql
SELECT pr_number, primary_component, title, merged_at::Date AS d
FROM github_prs FINAL
WHERE is_private = 0 AND is_merged = 1
  AND merged_at > now() - INTERVAL 4 MONTH
  AND is_backport_pr = 0 AND is_cherrypick_pr = 0 AND is_sync_pr = 0
  AND changelog_category IN ('new_feature', 'experimental')
ORDER BY merged_at DESC;
```

Category counts for the window: `bug_fix` 1278, `ci` 1262, unlabelled 1022, `not_for_changelog` 760, `improvement` 468, `performance` 269, `new_feature` 150, `build` 123, `critical_bug_fix` 75, `experimental` 58. The same query with `changelog_category IN ('improvement','performance')` restricted to `primary_component IN ('Interpreter','Analyzer','Optimizer','Joins','MergeTree','Functions','AggregateFunctions','DataTypes')` produced the semantics-relevant improvement set.

**Blind-spot ranking.** Open, human-filed issues from the same window whose titles carry wrong-result vocabulary, grouped by component, used as ground truth for where *other* sources find bugs that this fork does not:

```sql
SELECT primary_component, count() AS n
FROM github_issues FINAL
WHERE is_private = 0
  AND created_at > now() - INTERVAL 4 MONTH
  AND closed_at = toDateTime64(0, 3)
  AND author NOT LIKE '%[bot]%'
  AND hasAnyTokens(title, 'wrong incorrect silently drops loses mismatch')
GROUP BY primary_component ORDER BY n DESC;
```

Result: Joins 9, DataLake 8, Fuzzer 8, MergeTree 7, Functions 7, Optimizer 7, S3Queue 6, Mutations 5, Backup 4, Formats 4, **ParallelReplicas 4**, Replication 4, DDL 4, Distributed 3, FullTextSearch 3, MaterializedView 3, AggregateFunctions 3, Analyzer 3, SkipIndex 2, Interpreter 2.

**Fork capability audit.** A regex sweep over `src/sqlancer/clickhouse/**/*.java` for every setting name, clause, type and function family named by the change inventory. An item only enters this plan if the sweep shows zero or clearly partial coverage, and the relevant file is named in the entry so the claim can be re-checked.

**Nightly triage.** The five most recent `NightlySQLancer` runs (2026-08-01, 08-04, 08-07, 08-10, 08-13) were triaged finding by finding, and every reproducer was replayed against fresh `clickhouse/clickhouse-server:head` 26.8.1.1424 on the dev VM. That triage is what makes items 1, 2 and 4 P0 rather than P1.

## Evidence from the 2026-08 nightlies

Five distinct failure families across the five runs. Two are our own false positives, two are known open ClickHouse bugs, one is a new ClickHouse bug that only the upstream tool found.

| Family | Runs | Verdict |
|---|---|---|
| `LimitRanking LIMIT-BY cap violation: key 0 appears 2 times` | 08-04, 08-07 | False positive. `ComparatorHelper.trimTrailingDotZeros` normalises `'0.0'` to `'0'`, so a String key holding both values looks like one key appearing twice. `checkLimitByCap` is the only oracle that counts per-key occurrences of normalised strings. |
| `TLPWhere: size of the result sets mismatch (91 and 26)` | 08-07 | False positive. `t0` was `ReplacingMergeTree() ORDER BY c0` with `c0 Bool` and no ver column; a background merge collapsed 7 visible rows to 2 between the two TLP queries (91 = 7x13, 26 = 2x13). |
| `Sorted vs plain UNION ALL diverged under outer LIMIT_BY` | 08-10 | Known bug [#106125](https://github.com/ClickHouse/ClickHouse/issues/106125), still open. Query-time `FINAL` on SummingMergeTree applies the "all summed columns are zero, drop the row" rule over only the columns the query reads. |
| `LOGICAL_ERROR: Left and right columns have same names`, server abort | **all 5** | Known bug [#114113](https://github.com/ClickHouse/ClickHouse/issues/114113), still open. A three-way comma join where one relation is a VIEW aborts the asan/ubsan server, so 2 to 3 of the PP job's 4 oracles die every run with `Server is not responding`. |
| PP WHERE oracle `result sets mismatch (9 and 7)` | 08-13 | **New, unfiled.** `NOT (NOT key)` in value position, see item 1. |

Two of the five families are our own soundness bugs and both have a concrete fix; they are tracked as items 0a and 0b below because they cost triage time on every run and must be fixed before this plan's oracles add more surface.

### The measured miss that motivates item 1

Minimal repro, default settings, wrong on 24.8.14.39, 25.8.29.51, 26.3.12.3, 26.6.2.160 and head 26.8.1.1424, so it is long standing rather than a regression:

```sql
CREATE TABLE t (c1 Int32) ENGINE = MergeTree ORDER BY c1;
INSERT INTO t VALUES (0);
INSERT INTO t VALUES (100);

SELECT c1, (NOT (NOT c1)) <= 3.14 FROM t;            -- predicate is 1 for BOTH rows
SELECT count() FROM t WHERE (NOT (NOT c1)) <= 3.14;  -- 1, must be 2
SELECT count() FROM t WHERE (c1 != 0) <= 3.14;       -- 2, correct
```

`EXPLAIN indexes = 1` prints `Condition: (c1 in (-Inf, 3])`. Root cause: in `src/Storages/MergeTree/KeyCondition.cpp` the `name == "not"` branch of `cloneDAGWithInversionPushDown` (and its AST twin `cloneASTWithInversionPushDown`) treats `not` as a purely logical operator, flipping `need_inversion` and recursing while ignoring the `boolean_context` flag. Two flips cancel and `NOT NOT c1` becomes bare `c1`. For a non-Bool column that is unsound: `NOT NOT c1` means `c1 != 0` and lives in {0, 1}, so the comparison against 3.14 is universally true, and the derived key range `c1 <= 3` prunes any part whose rows all lie above 3.

The fork's KeyCondition oracle would have caught this on the first iteration that emitted the shape. It never emitted the shape.

## Scope and Non-Goals

- **Single node only.** The fork runs one `clickhouse-server` container. `remote`, `cluster('default', ...)`, `Distributed` over `127.0.0.1` and `parallel_replicas_local_plan` are in scope; genuine multi-host clusters, Keeper ensembles and replication are not.
- **Differentially testable only.** Crash-durability and fsync bugs ([#111433](https://github.com/ClickHouse/ClickHouse/issues/111433), [#111330](https://github.com/ClickHouse/ClickHouse/issues/111330), [#111823](https://github.com/ClickHouse/ClickHouse/issues/111823), [#112095](https://github.com/ClickHouse/ClickHouse/issues/112095)) need power-loss injection, not a query oracle. Out of scope.
- **Explicitly excluded feature families**, even though they shipped in the window: AI functions (`aiGenerate`, `aiClassify`, `aiEmbed`, `aiFilter`, `aiRedact`, `aiSimilarity`), WASM UDFs, the Web UI, web terminal and `/schema` pages, Keeper internals and the Keeper dashboard, PromQL and the Prometheus remote-write handlers, Arrow Flight SQL, external data lakes (Iceberg, Paimon, BigQuery, Puffin, S3Queue, NATS, Kafka) and pure output-format work (PNG, GeoJSON, Vortex, Hive text). They are either non-deterministic, need external services, or have no assertable invariant inside the fork's model.
- **Reader-bounded.** Anything that materialises `Variant`, `Dynamic` or `JSON` into a projected column stays subject to the client-v2 reader constraint documented in the provider CLAUDE.md.
- **Soundness over breadth.** An item that cannot satisfy the cross-cutting checklist below is demoted, not shipped.

## Cross-cutting soundness checklist

Every item below inherits this. It is the accumulated false-positive ledger of this fork, and the two false positives in the 2026-08 nightlies are both checklist violations that predate the checklist.

- **C1. Single snapshot.** Two *forms* of one query are compared as two columns of one statement, or against one fixture built inside the iteration. Two separate statements are exposed to a merge or mutation landing between them, which is exactly what produced the 08-07 TLPWhere false positive.
- **C2. No degenerate dedupe engines.** A dedupe or collapse engine whose ORDER BY key has a tiny domain (`Bool`, a two-value `Enum`) has non-deterministic visible cardinality. Either reject such keys or always emit the ver argument.
- **C3. Exact-integer aggregates and non-float group keys** for any aggregate or decomposition identity. This does *not* forbid float *data*: item 2 uses floats and NaN deliberately, but compares row sets, never sums.
- **C4. No reads of a SEMI or ANTI eliminated-side column.** Those values are ANY-like by design, confirmed by [#107073](https://github.com/ClickHouse/ClickHouse/issues/107073).
- **C5. Deterministic total-order tiebreak** before any positional row compare.
- **C6. `toTypeName` probe before emission.** Any multi-branch or union expression that could settle on a `Variant` common type must be CAST-wrapped.
- **C7. Measure presence bugs with row output, not `count()`.** `count()` masks row-drop bugs ([#106125](https://github.com/ClickHouse/ClickHouse/issues/106125), [#107309](https://github.com/ClickHouse/ClickHouse/issues/107309)).
- **C8. Never normalise values before an identity or cardinality assertion.** The 08-04 and 08-07 `LimitRanking` false positives came from `trimTrailingDotZeros` collapsing `'0.0'` into `'0'`. Prefer server-side assertions over client-side normalised comparisons.
- **C9. Every new oracle is gated by a default-on flag** in `ClickHouseOptions`, plus one `ClickHouseOracleFactory` enum entry and one `.claude/run-sqlancer.sh` `ALL_ORACLES` token, so a noisy oracle can be silenced without a rebuild.

## Prioritization Matrix

Priority weighting: (bug class, wrong result above crash) x (subsystem youth and known-bug density) x (1 / soundness risk) x (number of existing oracles lit up by a generator-only change). Effort: S is up to about a day, M is 2 to 4 days, L is a week or more.

| # | Item | Kind | Pri | Effort | Catches |
|---|------|------|-----|--------|---------|
| 0a | Fix `LimitRanking` cap assertion (server-side) | Fix | P0 | S | own false positive |
| 0b | Fix degenerate ReplacingMergeTree dedupe key | Fix | P0 | S | own false positive |
| 1 | Boolean-position and three-valued predicate forms | Gen | P0 | S | wrong result |
| 2 | NaN-aware negated-comparison pruning oracle | Oracle | P0 | M | wrong result |
| 3 | Parallel-replicas / distributed-plan equivalence | Oracle | P0 | M | wrong result |
| 4 | Multi-table joins containing a VIEW | Gen | P0 | S | crash + wrong result |
| 5 | Join-order algorithm sweep (greedy/DPhyp/DPsub) | Oracle | P0 | S | wrong result |
| 6 | Codec roundtrip oracle | Oracle+Gen | P0 | M | wrong result |
| 7 | `GROUPS` window frame mode | Gen+Oracle | P1 | S | wrong result |
| 8 | Negative `LIMIT BY`, `WITH TIES` on negative `LIMIT` | Gen+Oracle | P1 | S | wrong result |
| 9 | Pipe operators equivalence | Gen+Oracle | P1 | M | wrong result |
| 10 | IEJoin (two inequalities in ON) | Gen+Oracle | P1 | M | wrong result |
| 11 | New statistics types (`null_count`, `uniq_v2`) | Gen+Oracle | P1 | S | wrong result |
| 12 | Query condition cache: ORDER BY LIMIT n, poisoning | Oracle | P1 | M | wrong result |
| 13 | Text index second wave | Gen+Oracle | P1 | M | wrong result |
| 14 | `Tuple` per-element aggregation in summing engines | Gen+Oracle | P1 | M | wrong result |
| 15 | Sparse columns: pruning and trivial count | Gen | P1 | S | wrong result |
| 16 | `optimize_or_like_chain`, `optimize_and_compare_chain` | Oracle | P1 | S | wrong result |
| 17 | `indexHint` | Gen+Oracle | P1 | S | wrong result |
| 18 | Mixed-direction sorting key + aggregation in order | Gen+Oracle | P1 | S | wrong result |
| 19 | MV lifecycle: OR REPLACE, atomic POPULATE, PAUSE | Gen+Oracle | P2 | M | wrong result |
| 20 | ALTER surface: ENUM values, CONSTRAINT, Tuple subfields | Gen+Oracle | P2 | M | wrong result |
| 21 | Projections with a WHERE clause | Gen | P2 | S | wrong result |
| 22 | `Nullable(Tuple)` and lossy numeric supertypes | Gen | P2 | S | crash + wrong result |
| 23 | QBit type and quantized distance functions | Gen+Oracle | P2 | M | wrong result |
| 24 | `AT TIME ZONE`, `AT LOCAL`, `LOCALTIME` | Gen | P2 | S | wrong result |
| 25 | Continuous queries, what-if indexes, QueryRunner | Gen+Oracle | P2 | L | crash |

## Implementation status

**P0 (items 0a, 0b, 1-6) is implemented and validated on dev-vm head 26.8.1.1470 (2026-08-15).**
P1 and P2 remain open. Deviations from the plan as written, all discovered during validation:

- **Item 1's oracle is NoREC/TLPWhere, not KeyCondition.** No setting or `materialize()` wrapper
  defeats the `NOT (NOT key)` pruning, so the KeyCondition oracle's no-prune arm returns the same
  wrong rows as the baseline. `countIf(P)` versus `count() WHERE P` does catch it. The bug is
  confirmed on head and still unfiled.
- **Item 2's reference arm had to change for the same reason.** The plan specified `materialize()`
  plus a pruning-off settings profile; that does not disable partition-level or primary-key-level
  pruning, so the shipped oracle compares against `groupArrayIf(k, ifNull((P), 0))` over a full scan
  instead, which no optimizer can prune.
- **Item 5's setting is `query_plan_optimize_join_order_algorithm`** (values greedy / dpsize /
  dpsub / dphyp, comma-separated fallback lists allowed), not `query_plan_join_reorder_algorithm`.
  `dpsize` and `dphyp` reject non-inner joins with Code 717 EXPERIMENTAL_FEATURE_ERROR, which is
  tolerated per-arm.
- **Item 4 needed a persistent-view DDL action**, not just a join-picker change: views already were
  visible to the join picker, but `ViewEquivalence` dropped its view inside the same iteration, so
  no schema snapshot ever contained one. It also needed a real ON-less CROSS join, because every
  CROSS was previously handed an ON clause and degraded into an INNER join.
- **#114113 did not reproduce** on release build 26.8.1.1470 with the plan's minimal repro. The
  error message is pinned anyway, per the plan.

See the `## P0 coverage batch, 2026-08-15` section of `.claude/CLAUDE.md` for operational detail.

## P0, prerequisite fixes

> These are not coverage items. They are the two false positives found in the 2026-08 nightly triage. Both cost triage time on every run, and both are checklist violations, so they land before new surface is added.

- [x] **0a. Assert the `LIMIT BY` cap server-side** `[Fix]` `[P0]` `[S]`
  - **Problem:** `ClickHouseLimitRankingOracle.checkLimitByCap` reads the key column through `ComparatorHelper.getResultSetFirstColumnAsString`, which pipes every value through `trimTrailingDotZeros`. That helper rewrites `'0.0'` into `'0'`. When the key column is a String holding both `'0.0'` (from the value generator) and `'0'` (from the `numbers(N)` filler), the client sees one key twice and reports a cap violation that does not exist. Confirmed on the 08-04 and 08-07 reproducers: replay against head shows `uniqExact(c0) = count() = 10000` and `LIMIT 1 BY` returning exactly 10000 rows.
  - **Fix:** compute the violation in ClickHouse instead of in Java: `SELECT max(cnt) FROM (SELECT k, count() AS cnt FROM (<limit-by query>) GROUP BY k)` and assert the result is at most `n`. This is also strictly cheaper, since it does not ship 10000 rows to the client.
  - **Files:** `src/sqlancer/clickhouse/oracle/limit/ClickHouseLimitRankingOracle.java`.
  - **Verification:** replay both saved reproducers, expect no assertion; one deliberately broken assertion (cap of 0) must still fire.
  - **Follow-up worth considering separately:** `trimTrailingDotZeros` in `src/sqlancer/ComparatorHelper.java` is a lossy normalisation applied to *every* oracle's result values. It exists to hide float text differences, but it silently merges distinct String values. Scoping it to columns whose type is float, or dropping it in favour of the ULP-tolerant comparison mode that already exists in the same file, would remove a whole class of latent false positives. Checklist rule C8.

- [x] **0b. Reject degenerate dedupe ORDER BY keys** `[Fix]` `[P0]` `[S]`
  - **Problem:** the engine pool picked `ReplacingMergeTree()` for `t0 (c0 Bool, c1 DateTime, c2 String) ORDER BY c0`. The gate allows ReplacingMergeTree when a viable ver column exists, but the emitted DDL carries no ver argument, and `isValidOrderByForDedupe` accepts `Bool` because it is a bare key column. With only two distinct keys, visible cardinality drops from 7 rows to 2 the moment a background merge runs, which is what produced the 08-07 TLPWhere `91 and 26` mismatch. Replay confirms the table sits at 2 rows.
  - **Fix:** require the dedupe ORDER BY key to have a non-degenerate domain (reject `Bool`, reject an `Enum` with fewer than some threshold of values, reject any column the generator knows it fills from a tiny value pool), or always emit the ver argument when ReplacingMergeTree is chosen. Both are cheap; doing both is better.
  - **Files:** `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java` (`pickEngine`, `isValidOrderByForDedupe`, `isBareKeyColumn`).
  - **Verification:** 1 hour full-fleet dev-VM run with the engine pool logged; assert no ReplacingMergeTree, CollapsingMergeTree or AggregatingMergeTree table is created with a `Bool` sorting key. Checklist rule C2.

## P0, coverage items

### Item 1. Boolean-position and three-valued predicate forms

- [x] **1. Emit boolean-position wrappers and SQL truth-value predicates in `generatePredicate()`** `[Gen]` `[P0]` `[S]`
  - **Goal:** feed the existing, already-sound `ClickHouseKeyConditionOracle` the predicate shapes for which ClickHouse performs index and statistics analysis, and which its negation-pushdown code handles incorrectly. This is the single highest-value change in the plan because the oracle already exists, the bug class is already proven, and the change is generator-only.
  - **ClickHouse surface:**
    - SQL truth-value predicates `IS TRUE`, `IS FALSE`, `IS UNKNOWN` and their `IS NOT` variants, added by [PR #99997](https://github.com/ClickHouse/ClickHouse/pull/99997) (closes [#99597](https://github.com/ClickHouse/ClickHouse/issues/99597)).
    - Index pruning for `IS NOT DISTINCT FROM` and `IS TRUE`, added by [PR #110006](https://github.com/ClickHouse/ClickHouse/pull/110006).
    - Index pruning for `nullIf(key, sentinel)` predicates, added by [PR #107308](https://github.com/ClickHouse/ClickHouse/pull/107308) (issue [#107209](https://github.com/ClickHouse/ClickHouse/issues/107209)).
    - Primary key and skip index use for boolean-position `ifNull` and `coalesce` predicates, [PR #106272](https://github.com/ClickHouse/ClickHouse/pull/106272), and and the closed issue behind the trivially-removable-wrapper simplification, [#109998](https://github.com/ClickHouse/ClickHouse/issues/109998).
    - `LIKE ... ESCAPE ...`, added by [PR #99774](https://github.com/ClickHouse/ClickHouse/pull/99774); text index support for `LIKE`/`ILIKE` with `ESCAPE` is still an unmerged PR, [#105848](https://github.com/ClickHouse/ClickHouse/pull/105848).
    - The `not` handling in `src/Storages/MergeTree/KeyCondition.cpp`, functions `cloneDAGWithInversionPushDown` and `cloneASTWithInversionPushDown`, plus the `boolean_context` flag they thread but do not honour for `not`.
  - **Emission:** extend `ClickHouseExpressionGenerator.generatePredicate()` with a low-probability arm that wraps a *numeric or nullable* column reference, not only a boolean expression, in one of: `NOT (NOT x)`, `NOT x`, `x IS TRUE`, `x IS NOT TRUE`, `x IS FALSE`, `x IS UNKNOWN`, `x IS NOT DISTINCT FROM <literal>`, `nullIf(x, <literal>)`, `ifNull(x, <literal>)`, `coalesce(x, <literal>)`, and then either uses the wrapper directly as a filter or compares it against a numeric or float constant. The comparison-against-a-constant case is the one that matters: it puts a boolean-valued expression in *value* position, which is where the `boolean_context` distinction becomes load bearing. Add a separate arm emitting `LIKE`/`ILIKE` with an `ESCAPE` character on String columns.
  - **Bug class:** wrong result, silent row loss through part, granule and statistics pruning. Already proven: see "Evidence" above. Also crash-adjacent, since this family has produced logical errors before, for example [#103929](https://github.com/ClickHouse/ClickHouse/pull/103929) and [#106362](https://github.com/ClickHouse/ClickHouse/issues/106362).
  - **Evidence the gap is real:** repo-wide grep returns zero hits for `NOT (NOT`, `IS TRUE`, `IS FALSE`, `IS UNKNOWN`, `IS NOT DISTINCT FROM` and `ESCAPE`. Upstream SQLancer's `general` provider does emit `NOT (NOT c1)` and found the bug in a 10-minute run on 2026-08-13.
  - **Soundness risk:** Low. The KeyCondition oracle compares a query to the `materialize()`-wrapped form of the same query, in one snapshot each, over the same fixture. No new comparison logic is introduced. The only care needed is that `IS UNKNOWN` on a non-Nullable column is a constant, which is fine but wastes iterations, so prefer Nullable columns for that arm.
  - **Files:** `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` (new arms in `generatePredicate` and the unary-operator catalog); `src/sqlancer/clickhouse/ast/` (a node for the postfix truth-value predicates, or render them through the existing unary path); `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java`; `src/sqlancer/clickhouse/ClickHouseOptions.java` (a `--truth-value-predicate-emission` flag, default on).
  - **Effort:** S.
  - **Verification:** the `NOT (NOT c1) <= 3.14` repro must be produced by the generator within a 15-minute run and caught by `KeyCondition`, `NoREC` or `TLPWhere`. Then a 1-hour full-fleet dev-VM run must show no *other* new assertion family from these arms.
  - **Note:** the underlying ClickHouse bug is not yet filed. Once it is, gate handling follows the `TextIndexDirectRead` precedent: leave the oracle firing deliberately and triage by the assertion string, or pin the message in `ClickHouseErrors` until the fix lands.

### Item 2. NaN-aware negated-comparison pruning oracle

- [x] **2. Float and NaN pruning-soundness oracle** `[Oracle]` `[P0]` `[M]`
  - **Goal:** assert that part, granule, partition and statistics pruning never removes a row that the predicate accepts, on float columns containing NaN and negative zero, under negated and CNF-rewritten comparisons. This is currently ClickHouse's densest open wrong-result cluster and the fork's most systematic blind spot.
  - **ClickHouse surface:** `convert_query_to_cnf`, `optimize_move_to_prewhere`, `use_skip_indexes`, `use_skip_indexes_on_data_read`, `allow_statistics_optimize` and the auto-statistics defaults, minmax and bloom_filter skip indexes, `PARTITION BY` over a float expression, and primary keys over float expressions. Known open bugs in exactly this shape: [#113417](https://github.com/ClickHouse/ClickHouse/issues/113417) statistics-based part pruning drops NaN rows for `NOT (f < c)`; [#112036](https://github.com/ClickHouse/ClickHouse/issues/112036) `convert_query_to_cnf = 1` rewrites `NOT (x < c)` to `x >= c` and silently drops NaN rows; the still-unmerged fix [#107074](https://github.com/ClickHouse/ClickHouse/pull/107074) for minmax skip index and partition pruning skipping NaN under negated float ranges; [#106533](https://github.com/ClickHouse/ClickHouse/issues/106533) metamorphic equivalence violation in HAVING due to NaN partition pruning; [#110266](https://github.com/ClickHouse/ClickHouse/issues/110266), closed, minmax over-prunes a NaN granule for `NOT (f > c)`. The fork already found a member of this family once, [#106262](https://github.com/ClickHouse/ClickHouse/issues/106262), through `TLPSetOp` and by accident.
  - **Invariant:** for one fixture and one predicate `P`, the row set of `SELECT <key> FROM t WHERE P` must equal the row set of the same query with all pruning disabled. "All pruning disabled" means the `materialize()` wrapper of the existing KeyCondition oracle plus `SETTINGS use_skip_indexes = 0, use_skip_indexes_on_data_read = 0, allow_statistics_optimize = 0, use_query_condition_cache = 0, optimize_move_to_prewhere = 0, convert_query_to_cnf = 0`. Compare as multisets of the key column. Additionally assert the union invariant `count(P) + count(NOT P) + count(P IS NULL) = count(*)` under both settings profiles, which is the shape that catches the CNF rewrite specifically.
  - **Emission requirement:** the fixture must deliberately contain NaN, positive and negative infinity, negative zero and NULL in a `Float32`, `Float64` and `Nullable(Float64)` column, spread across several parts so that pruning has something to prune, and the table must be created with (a) a float column in `ORDER BY`, (b) a float expression in `PARTITION BY`, and (c) minmax plus bloom_filter skip indexes and `auto_statistics_types` over the float columns, chosen per iteration. The predicate must include negated comparisons (`NOT (f < c)`, `NOT (f > c)`, `NOT (f BETWEEN a AND b)`) and their `IS NULL` companions.
  - **Bug class:** wrong result, silent empty or short result at default settings. Highest severity shape in the whole plan, because the query looks correct and returns no error.
  - **Why it is sound despite the float rule:** checklist rule C3 exists because float *aggregates* are order sensitive. This oracle computes no aggregate over floats other than `count()`, and compares row sets of a non-float key column. Two forms of the same query, one snapshot each, same fixture. NaN equality is never asserted in Java.
  - **Soundness risk:** Low to medium. The one real hazard is that `NaN` renders as `NaN` through the client-v2 reader while TSV would render `nan`; since both sides flow through the same reader, this is self-consistent, as documented in the provider CLAUDE.md. Project the key column, not the float column, to avoid the question entirely.
  - **Files:** create `src/sqlancer/clickhouse/oracle/keycond/ClickHouseFloatPruningOracle.java`; modify `src/sqlancer/clickhouse/gen/ClickHouseColumnBuilder.java` and `gen/ClickHouseInsertGenerator.java` (guarantee NaN, inf, -0.0 in the float value pool), `gen/ClickHouseStatisticsGenerator.java`; reuse `oracle/keycond/ClickHouseKeyConditionOracle.MaterializedColumnVisitor`; factory, options flag and `.claude/run-sqlancer.sh`.
  - **Effort:** M.
  - **Verification:** the oracle must reproduce [#112036](https://github.com/ClickHouse/ClickHouse/issues/112036) and [#113417](https://github.com/ClickHouse/ClickHouse/issues/113417) on a head build where they are still open, which is a positive control, and then run 1 hour full fleet with no *other* assertion family.

### Item 3. Parallel-replicas and distributed-plan equivalence

- [x] **3. Distributed-plan and parallel-replicas equivalence oracle** `[Oracle]` `[P0]` `[M]`
  - **Goal:** assert that a query answered through the new plan-based distributed and parallel-replica execution paths returns exactly what the plain local path returns. This subsystem was rewritten across five large PRs in this window and already has four open wrong-result issues, and the fork has zero coverage.
  - **ClickHouse surface:** plan-based parallel replicas parts 1 to 3, [PR #108504](https://github.com/ClickHouse/ClickHouse/pull/108504) aggregation, [PR #111063](https://github.com/ClickHouse/ClickHouse/pull/111063), [PR #112268](https://github.com/ClickHouse/ClickHouse/pull/112268) JOINs; multi-stage distributed queries [PR #106020](https://github.com/ClickHouse/ClickHouse/pull/106020); distributed execution of `CreatingSets` steps [PR #113826](https://github.com/ClickHouse/ClickHouse/pull/113826); `FINAL` reads in distributed plans [PR #108148](https://github.com/ClickHouse/ClickHouse/pull/108148); automatic setting adjustment when `make_distributed_plan` is on [PR #112463](https://github.com/ClickHouse/ClickHouse/pull/112463); per-replica ports for distributed plan workers [PR #107885](https://github.com/ClickHouse/ClickHouse/pull/107885); reimplemented reading in order for parallel replicas [PR #101434](https://github.com/ClickHouse/ClickHouse/pull/101434); `parallel_replicas_prefer_local_replica` [PR #100139](https://github.com/ClickHouse/ClickHouse/pull/100139); pushing a whole outer query to shards for trivial views [PR #101791](https://github.com/ClickHouse/ClickHouse/pull/101791); pushing ORDER BY into simple views for distributed optimization [PR #94102](https://github.com/ClickHouse/ClickHouse/pull/94102).
  - **Known open wrong results this would target:** [#111727](https://github.com/ClickHouse/ClickHouse/issues/111727) parallel replicas silently multiply results by the replica count when a three-or-more-table JOIN contains a VIEW; [#111654](https://github.com/ClickHouse/ClickHouse/issues/111654) custom-key parallel replicas silently drop a WHERE with an EXISTS operand and return one replica's unfiltered slice; [#111363](https://github.com/ClickHouse/ClickHouse/issues/111363) query condition cache poisoned by a parallel-replicas read of Merge over a VIEW, so later plain queries silently return wrong results; [#113622](https://github.com/ClickHouse/ClickHouse/issues/113622) a parameterized view inside an offloaded JOIN is shipped unqualified; [#113246](https://github.com/ClickHouse/ClickHouse/issues/113246) `make_distributed_plan` throws TYPE_MISMATCH for `IN (SELECT ...)` with a non-convertible literal; [#112028](https://github.com/ClickHouse/ClickHouse/issues/112028) `serialize_query_plan = 1` fails for `LowCardinality IN (subquery)` through a distributed read; [#111211](https://github.com/ClickHouse/ClickHouse/issues/111211) ORDER BY is not applied globally when reading a Distributed table through a Merge engine.
  - **Invariant:** run one generated read-only query in N execution profiles and assert identical multisets: (a) plain local; (b) `SETTINGS make_distributed_plan = 1`; (c) `SETTINGS serialize_query_plan = 1`; (d) through `cluster('default', currentDatabase(), t)` or a `Distributed('default', ...)` wrapper, with `parallel_replicas_local_plan` on and off; (e) with `enable_parallel_replicas = 1` plus `max_parallel_replicas > 1` against the single-node `default` cluster, which still exercises the coordinator and the announcement protocol. Where the query has an ORDER BY, apply checklist C5 and compare positionally, otherwise compare as multisets.
  - **Bug class:** wrong result, including row multiplication and silently dropped filters, plus crashes and TYPE_MISMATCH exceptions on the newer settings.
  - **Evidence the gap is real:** grep for `make_distributed_plan`, `parallel_replicas`, `serialize_query_plan` and `allow_experimental_parallel_reading` returns zero hits across the whole provider. `ClickHouseDistributedTableOracle` wraps a table in `Distributed('default', ...)` but never varies these settings, and `ClickHouseRemoteLocalEquivalenceOracle` compares `remote()` against local without them.
  - **Soundness risk:** Medium. `max_parallel_replicas` against a one-replica cluster is a real code path but a weak one; the `Distributed` and `cluster()` arms are stronger. Must avoid non-deterministic functions, and must not read SEMI or ANTI eliminated-side columns (C4), because the eliminated-side value legitimately changes with plan shape.
  - **Files:** create `src/sqlancer/clickhouse/oracle/distributed/ClickHouseDistributedPlanEquivalenceOracle.java`; modify `src/sqlancer/clickhouse/oracle/distributed/ClickHouseDistributedTableOracle.java` (reuse its wrapper-creation helper), `src/sqlancer/clickhouse/ClickHouseSessionSettings.java`, `ClickHouseErrors.java` (tolerate the genuinely-unsupported-shape errors these settings raise, narrowly); factory, options flag and run script.
  - **Effort:** M.
  - **Verification:** must reproduce [#111727](https://github.com/ClickHouse/ClickHouse/issues/111727) as a positive control once item 4 supplies the view-in-join emission; then 1 hour full fleet with no other family. Expect to need a narrow error allowlist first: these settings raise legitimate "not supported for this query shape" errors that are not bugs.

### Item 4. Multi-table joins containing a VIEW

- [x] **4. Put views into multi-table joins** `[Gen]` `[P0]` `[S]`
  - **Goal:** make the FROM-list generator able to place a VIEW as one relation of a three-or-more-relation join. Four separate bugs in this window need exactly that shape, including the one that aborts our own nightly PP server every single run.
  - **ClickHouse surface:** the join-order optimizer entry point, `src/Processors/QueryPlan/Optimizations/optimizeJoin.cpp` `chooseJoinOrder`, and `src/Interpreters/JoinExpressionActions.cpp`; `analyzer_inline_views`; `analyzer_compatibility_apply_final_to_all_joined_tables` [PR #111589](https://github.com/ClickHouse/ClickHouse/pull/111589); the multiple-join identifier-qualification compatibility setting [PR #110746](https://github.com/ClickHouse/ClickHouse/pull/110746).
  - **Known bugs of this exact shape:** [#114113](https://github.com/ClickHouse/ClickHouse/issues/114113) open, `SELECT * FROM t0, v0, t1 WHERE <bool>` raises `LOGICAL_ERROR "Left and right columns have same names"` from `chooseJoinOrder`, which aborts an asan or ubsan server; [#111727](https://github.com/ClickHouse/ClickHouse/issues/111727) open, parallel replicas multiply results when a three-or-more-table JOIN contains a VIEW; [#113245](https://github.com/ClickHouse/ClickHouse/issues/113245) open, `analyzer_inline_views = 1` plus a JOIN with a plain VIEW throws ALIAS_REQUIRED; [#111276](https://github.com/ClickHouse/ClickHouse/issues/111276) open, nested-alias JOIN USING key over Distributed can silently join by a shadowed column. Closed precedent for the same optimizer: [#106426](https://github.com/ClickHouse/ClickHouse/issues/106426), found by this fork.
  - **Emission:** the fork already creates views (`v_t1_9`-style names appear in every reproducer log) and already generates comma joins and explicit joins, but the join generator only selects base tables. Allow a view to be selected as a join relation with a modest probability, and independently allow the *same* base table to appear twice under different aliases, since `[__table3.c0, __table3.c1, __table1.c0, __table1.c1]` in the #114113 message shows overlapping column names across relations are part of the trigger. Also emit three-and-four-relation comma joins, not only two.
  - **Bug class:** crash (LOGICAL_ERROR, which is a server abort on sanitizer builds) plus wrong result under parallel replicas.
  - **Evidence the gap is real:** `ClickHouseViewEquivalenceOracle` exists and views are created, but no reproducer in the fork's saved logs contains a view inside a multi-relation FROM list, and the fork has never hit #114113 while upstream SQLancer hits it every run.
  - **Soundness risk:** Low for the emission itself. Note that the join-reorder restriction documented in the provider CLAUDE.md stays in force: any oracle consuming these queries must not read a SEMI or ANTI eliminated-side column (C4).
  - **Files:** `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java` (FROM-list construction), `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java`, `src/sqlancer/clickhouse/ClickHouseSchema.java` (view visibility to the join picker), `src/sqlancer/clickhouse/oracle/join/ClickHouseJoinReorderOracle.java` (allow a view relation).
  - **Effort:** S.
  - **Verification:** #114113's message must appear within a 30-minute run against a head asan build, or against a release build as a Code 49 exception. Because a LOGICAL_ERROR is a *finding*, it must be pinned in `ClickHouseErrors` with a comment naming #114113 until the fix lands, exactly as #106649 was pinned, otherwise every run drowns in it.
  - **Operational note, worth doing independently of this plan:** `ci/jobs/sqlancer_pp_job.sh` should restart the server between oracles. Today a single #114113 abort during the first oracle costs the job its remaining 2 to 3 oracles and produces 100 junk `Failed to create any table` reproducer files, which is roughly 75 percent of that job's runtime wasted on every nightly.

### Item 5. Join-order algorithm sweep

- [x] **5. Sweep the join-order enumeration algorithms** `[Oracle]` `[P0]` `[S]`
  - **Goal:** run the same multi-way join under every join-order enumerator and assert identical results. Two new enumerators landed in this window and one already has an open conjunct-dropping bug.
  - **ClickHouse surface:** the DPhyp join-reordering algorithm for inner joins [PR #98798](https://github.com/ClickHouse/ClickHouse/pull/98798); the DPsub enumeration algorithm [PR #107351](https://github.com/ClickHouse/ClickHouse/pull/107351); merging expressions into the join during reordering [PR #98533](https://github.com/ClickHouse/ClickHouse/pull/98533); `query_plan_optimize_join_order_limit`, `query_plan_join_reorder_algorithm`, and `query_plan_join_shard_by_pk_ranges`.
  - **Known open bugs:** [#111898](https://github.com/ClickHouse/ClickHouse/issues/111898) DPsub join-order reordering with `query_plan_enable_optimizations = 0` silently drops a non-equi `JOIN ON` conjunct on chained joins, a regression after [PR #109638](https://github.com/ClickHouse/ClickHouse/pull/109638); [#112236](https://github.com/ClickHouse/ClickHouse/issues/112236) `query_plan_merge_filter_into_join_condition` rebuilds the leftover WHERE conjunct with a truncating CAST to UInt8; [#111897](https://github.com/ClickHouse/ClickHouse/issues/111897) query condition cache poisoned by a `query_plan_join_shard_by_pk_ranges` plus `full_sorting_merge` multi-threaded join read.
  - **Invariant:** for one generated N-way join, compare results across `query_plan_join_reorder_algorithm` values (greedy, DPhyp, DPsub and whatever the current enum accepts, probed at implementation time), `query_plan_optimize_join_order_limit = 0` versus the default, and `query_plan_enable_optimizations` on versus off, which is the exact combination in #111898. Multiset compare, or positional with a C5 tiebreak.
  - **Bug class:** wrong result through a dropped join conjunct, plus logical errors from the optimizer.
  - **Evidence the gap is real:** grep for `dphyp`, `dpsub` and `greedy` returns zero hits. `ClickHouseJoinReorderOracle` exists and mentions `join_reorder`, but never varies the algorithm, so it only ever exercises the default enumerator.
  - **Soundness risk:** Low, and already paid for: the oracle's existing `liveAliasesBeforeJoin` restriction (permanent, per [#107073](https://github.com/ClickHouse/ClickHouse/issues/107073)) keeps SEMI and ANTI eliminated-side reads out, which is the one genuinely non-deterministic case here.
  - **Files:** `src/sqlancer/clickhouse/oracle/join/ClickHouseJoinReorderOracle.java`, `src/sqlancer/clickhouse/ClickHouseSessionSettings.java`.
  - **Effort:** S.
  - **Verification:** probe the accepted enum values against head first, since the setting name and values changed during the window. Then 1 hour full fleet, expecting #111898 as a positive control if it is still open.

### Item 6. Codec roundtrip oracle

- [x] **6. Compression codec roundtrip and merge-stability oracle** `[Oracle+Gen]` `[P0]` `[M]`
  - **Goal:** assert that data written through any codec reads back byte-identically for lossless codecs, and within a declared tolerance for lossy ones, and that it survives merges, mutations and codec changes. Six codec-related changes landed in this window, including two lossy codecs and an adaptive selector that changes codecs *during* merges, and the fork emits `CODEC(` in exactly one file.
  - **ClickHouse surface:** the ZXC codec [PR #110620](https://github.com/ClickHouse/ClickHouse/pull/110620); the revived SZ3 codec [PR #108788](https://github.com/ClickHouse/ClickHouse/pull/108788) and its NaN quantizer fix [PR #110762](https://github.com/ClickHouse/ClickHouse/pull/110762); the ALP RD variant and variant selection [PR #99654](https://github.com/ClickHouse/ClickHouse/pull/99654); quantization codecs for vector columns with two-stage retrieval [PR #108565](https://github.com/ClickHouse/ClickHouse/pull/108565); adaptive codec selection on merges and mutations [PR #111834](https://github.com/ClickHouse/ClickHouse/pull/111834); reading MergeTree parts with mixed codecs in one stream [PR #108592](https://github.com/ClickHouse/ClickHouse/pull/108592); packed part storage [PR #108118](https://github.com/ClickHouse/ClickHouse/pull/108118) and packed skip-index storage [PR #105321](https://github.com/ClickHouse/ClickHouse/pull/105321) with its uncompressed-size reporting fix [PR #109272](https://github.com/ClickHouse/ClickHouse/pull/109272).
  - **Related open PR:** [#114531](https://github.com/ClickHouse/ClickHouse/pull/114531) "Reject a lossy codec on columns backing keys and indexes" shows lossy codecs on key columns are a live hazard, which is precisely the shape a fuzzer will generate by accident.
  - **Invariant:** per iteration build one table with `CODEC(...)` on each column and an identical mirror with `CODEC(NONE)`, insert the same rows into both across several blocks, then assert (a) the two tables answer the same `SELECT` identically for lossless codecs, (b) each still matches after `OPTIMIZE TABLE ... FINAL` and after an `ALTER TABLE ... MODIFY COLUMN ... CODEC(...)` mutation, which is where adaptive selection and mixed-codec parts come in, and (c) for lossy codecs assert only the documented tolerance, or exclude them from the equality arm and assert instead that row count and NULL mask are preserved. Include the NaN, infinity and negative-zero float pool from item 2, since [PR #110762](https://github.com/ClickHouse/ClickHouse/pull/110762) was a NaN cast bug in a codec quantizer.
  - **Bug class:** wrong result and data corruption, the most severe category, plus crashes on decode.
  - **Evidence the gap is real:** `CODEC(` appears only in `oracle/altermodify/ClickHouseAlterModifyConsistencyOracle.java`, and the lossy-codec names appear only in `gen/ClickHouseColumnBuilder.java`. No oracle asserts a roundtrip.
  - **Soundness risk:** Medium. Lossy codecs must be partitioned off from the equality arm by an explicit allowlist, and the `Delta`, `DoubleDelta`, `Gorilla`, `T64`, `FPC` and `ALP` families each have type restrictions that must be respected at emission or the run fills with tolerated BAD_ARGUMENTS noise.
  - **Files:** create `src/sqlancer/clickhouse/oracle/codec/ClickHouseCodecRoundtripOracle.java`; modify `src/sqlancer/clickhouse/gen/ClickHouseColumnBuilder.java` (emit `CODEC(...)` per column with type-aware choices), `gen/ClickHouseTableGenerator.java` (mirror-table helper, shared with the engine-equivalence oracle), `gen/ClickHouseAlterGenerator.java` (`MODIFY COLUMN ... CODEC`); factory, options flag, run script.
  - **Effort:** M.
  - **Verification:** 1 hour full fleet with zero false positives on the lossless arm; a deliberate injection (declare a lossy codec as lossless in the allowlist) must fire.

## P1, coverage items

### Item 7. `GROUPS` window frame mode

- [ ] **7. Emit and ground-truth the `GROUPS` window frame mode** `[Gen+Oracle]` `[P1]` `[S]`
  - **Goal:** cover the third window frame mode. The fork has three window oracles and emits `ROWS` and `RANGE` frames, but `GROUPS` is brand new and completely unexercised.
  - **ClickHouse surface:** `GROUPS` frame mode for window functions, [PR #108653](https://github.com/ClickHouse/ClickHouse/pull/108653), merged 2026-08-13. Syntax is `GROUPS BETWEEN <n> PRECEDING AND <m> FOLLOWING`, where the offsets count *peer groups* (rows tied on the ORDER BY key) rather than rows.
  - **Invariant:** `GROUPS` is exactly ground-truthable in Java, which is the strongest oracle shape available. Fetch the fixture, sort by the window ORDER BY key, partition into peer groups, and compute the expected aggregate per row. Assert equality against ClickHouse. Additionally assert the degenerate identities: with a fixture whose ORDER BY key is unique, `GROUPS n PRECEDING` must equal `ROWS n PRECEDING`; with a fixture whose key is constant, every row is one peer group so `GROUPS 0 PRECEDING AND 0 FOLLOWING` must equal the whole-partition aggregate.
  - **Bug class:** wrong result, per-row aggregate values.
  - **Evidence the gap is real:** grep for `GROUPS BETWEEN` and the standalone token `GROUPS` returns zero hits. `oracle/window/ClickHouseWindowFrameGroundTruthOracle.java` already implements the Java ground-truth machinery for `ROWS` and `RANGE`, so this is an extension, not a new oracle.
  - **Soundness risk:** Low, but note the lesson already recorded for this family: ClickHouse fills boundary and synthesized cells with the *type default*, not NULL (the `lagInFrame` edge case). The ground truth must match that, or the oracle reports its own bug.
  - **Files:** `src/sqlancer/clickhouse/oracle/window/ClickHouseWindowFrameGroundTruthOracle.java`, `oracle/window/ClickHouseWindowFrameOracle.java`, `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` (frame clause emission).
  - **Effort:** S.
  - **Verification:** the two degenerate identities must hold on a synthetic unique-key and constant-key fixture; then 1 hour full fleet.

### Item 8. Negative `LIMIT BY` and `WITH TIES` on negative `LIMIT`

- [ ] **8. Emit negative LIMIT forms** `[Gen+Oracle]` `[P1]` `[S]`
  - **Goal:** cover the negative-offset LIMIT family. ClickHouse both *added* these forms and *rewrote* their execution path inside this window, and the fork's LIMIT oracle only emits non-negative limits.
  - **ClickHouse surface:** negative `LIMIT BY`, [PR #103222](https://github.com/ClickHouse/ClickHouse/pull/103222); `WITH TIES` for negative `LIMIT`, [PR #100930](https://github.com/ClickHouse/ClickHouse/pull/100930); the performance rewrite of `DISTINCT` in order, sort-merge joins, `LIMIT BY` and negative `LIMIT BY`, [PR #106502](https://github.com/ClickHouse/ClickHouse/pull/106502); removal of redundant `LIMIT BY` key expressions, [PR #106818](https://github.com/ClickHouse/ClickHouse/pull/106818); `DISTINCT` run independently per partition, [PR #108326](https://github.com/ClickHouse/ClickHouse/pull/108326); removal of the legacy `DistinctSortedTransform`, [PR #110170](https://github.com/ClickHouse/ClickHouse/pull/110170).
  - **Related open bug in the neighbourhood:** [#112029](https://github.com/ClickHouse/ClickHouse/issues/112029), Merge over Distributed plus JOIN plus `LIMIT n WITH TIES` keeps `WITH TIES` in the shard fragment but drops the ORDER BY.
  - **Invariant:** extend `ClickHouseLimitRankingOracle` with a negative-limit mode. `LIMIT -n BY k` takes the *last* n rows per key, so the sound assertion is that its result equals `LIMIT n BY k` computed over the reverse total order, which is a single-fixture, single-snapshot identity. For `WITH TIES` on a negative limit, keep the existing superset and sub-multiset containment assertions, which are already order-tolerant.
  - **Bug class:** wrong result, row count and per-key cardinality.
  - **Evidence the gap is real:** grep for `LIMIT -` and any negative-limit helper returns zero hits. `WITH TIES` appears only in `ClickHouseLimitRankingOracle` and `ClickHouseOptions`, and its `n` is always `1 + Randomly.getNotCachedInteger(0, 20)`, so never negative.
  - **Soundness risk:** Low, provided item 0a lands first: this oracle's per-key counting is the thing that `trimTrailingDotZeros` breaks (C8), and adding a mode before fixing that would add a second false-positive source.
  - **Files:** `src/sqlancer/clickhouse/oracle/limit/ClickHouseLimitRankingOracle.java`, `src/sqlancer/clickhouse/oracle/setop_limit/ClickHouseSortedUnionLimitByOracle.java`.
  - **Effort:** S.
  - **Verification:** 1 hour full fleet after 0a, expecting zero assertions; a hand-built fixture with known per-key tails validates the reverse-order identity.

### Item 9. Pipe operators

- [ ] **9. Pipe-operator equivalence oracle** `[Gen+Oracle]` `[P1]` `[M]`
  - **Goal:** cover an entirely new query syntax. A pipe query and its classic-SQL equivalent must return the same thing, which is a textbook metamorphic oracle and needs no ground truth at all.
  - **ClickHouse surface:** pipe operators in SQL queries, [PR #111151](https://github.com/ClickHouse/ClickHouse/pull/111151), merged 2026-08-11. Reachable at parse time, so it exercises the parser, the analyzer's query-tree construction, and every rewrite that assumes a classic clause order.
  - **Invariant:** the generator already builds a `ClickHouseSelect` AST and renders it through `ClickHouseToStringVisitor`. Add a second visitor that renders the same AST in pipe form, then assert the two render forms return identical results in one iteration. This mirrors the `MaterializedColumnVisitor` pattern in `ClickHouseKeyConditionOracle`, which already proves that a second visitor over one AST is a cheap way to build a differential.
  - **Bug class:** wrong result and parse or analysis crashes. New syntax on a mature analyzer is historically crash-dense.
  - **Evidence the gap is real:** grep for the pipe token returns zero hits.
  - **Soundness risk:** Low to medium. The risk is not the comparison, it is that the pipe renderer must be *semantically* faithful; a renderer bug looks like a ClickHouse bug. Mitigation: start with the small subset the renderer can prove (FROM, WHERE, aggregate, ORDER BY, LIMIT) and expand only when the subset runs clean, and probe the exact accepted syntax against head before wiring, since the feature is two weeks old.
  - **Files:** create `src/sqlancer/clickhouse/PipeSyntaxVisitor.java` next to `ClickHouseToStringVisitor.java`, and `src/sqlancer/clickhouse/oracle/pipe/ClickHousePipeEquivalenceOracle.java`; factory, options flag, run script.
  - **Effort:** M.
  - **Verification:** every rendered pipe query must parse (zero SYNTAX_ERROR after the subset is fixed, which is itself the signal that the renderer is faithful), then 1 hour full fleet with equal results.

### Item 10. IEJoin

- [ ] **10. Generate joins whose ON has two inequality comparisons** `[Gen+Oracle]` `[P1]` `[M]`
  - **Goal:** reach the new IEJoin algorithm. It only activates for a specific ON shape that the fork never generates, so the algorithm is currently untested by us.
  - **ClickHouse surface:** IEJoin support for joins whose ON has two inequality comparisons, [PR #109920](https://github.com/ClickHouse/ClickHouse/pull/109920), merged 2026-08-06. This is the interval-join algorithm, so the trigger shape is `ON a.x < b.x AND a.y > b.y`.
  - **Invariant:** the same join must return the same rows under IEJoin and under every other applicable `join_algorithm` (`hash`, `parallel_hash`, `grace_hash`, `full_sorting_merge`, and the new `parallel_full_sorting_merge` from [PR #109005](https://github.com/ClickHouse/ClickHouse/pull/109005)). This is exactly what `ClickHouseJoinAlgorithmOracle` already does; the missing piece is a generator that produces the two-inequality ON shape, plus `parallel_full_sorting_merge` in the algorithm list.
  - **Bug class:** wrong result, missing or duplicated join rows.
  - **Evidence the gap is real:** grep for `IEJoin` and `ie_join` returns zero hits; `parallel_full_sorting_merge` also returns zero hits, so the join-algorithm sweep is stale by one algorithm even for shapes it already generates.
  - **Soundness risk:** Low, with one caveat that has already bitten this fork: a non-equi join over a large fixture is quadratic, so the existing universal `max_result_rows` cap and the `TOO_MANY_ROWS_OR_BYTES` tolerance must stay in force, and the fixture for this arm should be small by construction.
  - **Files:** `src/sqlancer/clickhouse/oracle/join/ClickHouseJoinAlgorithmOracle.java` (add `parallel_full_sorting_merge`, add an IEJoin arm), `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` (two-inequality ON emission), `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java`.
  - **Effort:** M.
  - **Verification:** confirm via `EXPLAIN` or the query log that the IEJoin algorithm is actually selected for the generated shape, otherwise the arm is decorative. Then 1 hour full fleet.

### Item 11. New statistics types

- [ ] **11. Emit `null_count` and `uniq_v2` statistics and toggle the new defaults** `[Gen+Oracle]` `[P1]` `[S]`
  - **Goal:** cover the statistics types added in this window, and the two default changes that mean nearly every fuzzed table now carries statistics whether we asked for them or not.
  - **ClickHouse surface:** `null_count` statistics, [PR #102356](https://github.com/ClickHouse/ClickHouse/pull/102356), and NullCount statistics support for part pruning, [PR #104214](https://github.com/ClickHouse/ClickHouse/pull/104214); `uniq_v2` statistics backed by `UniqCombined64(12)`, [PR #107863](https://github.com/ClickHouse/ClickHouse/pull/107863); the default `auto_statistics_types` change from `basic, uniq` to `basic, uniq_v2`, [PR #110878](https://github.com/ClickHouse/ClickHouse/pull/110878); materializing column statistics on INSERT for small tables by default, [PR #109454](https://github.com/ClickHouse/ClickHouse/pull/109454); the sparse `checkInHyperrectangle` change, [PR #110153](https://github.com/ClickHouse/ClickHouse/pull/110153); skipping predicate statistics counters when the feature is off, [PR #108190](https://github.com/ClickHouse/ClickHouse/pull/108190).
  - **Known open bug this targets:** [#113417](https://github.com/ClickHouse/ClickHouse/issues/113417), statistics-based part pruning drops NaN rows, which is also item 2's positive control. The two items are complementary: item 2 supplies the float and NaN data, item 11 supplies the statistics variety.
  - **Invariant:** extend `ClickHouseStatsToggleOracle` so the statistics *type* is swept, not just the on/off setting, and so `auto_statistics_types` is varied explicitly rather than inherited from the server default. The assertion stays what it is today: the same query with `allow_statistics_optimize` on and off must return the same rows.
  - **Bug class:** wrong result through statistics-driven part pruning and through wrong selectivity estimates feeding PREWHERE ordering.
  - **Evidence the gap is real:** grep for `null_count`, `NullCount`, `uniq_v2` and `auto_statistics_types` returns zero hits; `gen/ClickHouseStatisticsGenerator.java` knows only `minmax`, `countmin` and `tdigest`.
  - **Soundness risk:** Low. Statistics are advisory, so any behaviour change under a statistics toggle is a bug by definition, which makes this one of the cleanest invariants available.
  - **Files:** `src/sqlancer/clickhouse/gen/ClickHouseStatisticsGenerator.java`, `src/sqlancer/clickhouse/oracle/stats/ClickHouseStatsToggleOracle.java`, `src/sqlancer/clickhouse/gen/ClickHouseAlterGenerator.java` (`MODIFY STATISTICS`, `MATERIALIZE STATISTICS`), `src/sqlancer/clickhouse/ClickHouseSessionSettings.java`.
  - **Effort:** S.
  - **Verification:** 1 hour full fleet. Note that a previously-found bug in this exact area, [#114791](https://github.com/ClickHouse/ClickHouse/issues/114791) "INSERT rejected with NOT_FOUND_COLUMN_IN_BLOCK when an ALIAS column declares STATISTICS", means the emission must avoid declaring statistics on ALIAS columns or pin that error.

### Item 12. Query condition cache

- [ ] **12. Cover `ORDER BY ... LIMIT n` caching and cross-query cache poisoning** `[Oracle]` `[P1]` `[M]`
  - **Goal:** cover the query condition cache shape whose default was flipped three times in four months, and the failure mode that a single-query oracle structurally cannot see: one query poisoning the cache so that a *later, different* query returns wrong results.
  - **ClickHouse surface:** enabling the query condition cache for `ORDER BY ... LIMIT n`, [PR #104478](https://github.com/ClickHouse/ClickHouse/pull/104478); better coverage for `ORDER BY ... LIMIT k`, [PR #110507](https://github.com/ClickHouse/ClickHouse/pull/110507); disabling it by default, [PR #111492](https://github.com/ClickHouse/ClickHouse/pull/111492); re-enabling it by default, [PR #114539](https://github.com/ClickHouse/ClickHouse/pull/114539), merged 2026-08-13; not disabling the cache for materialized lightweight deletes, [PR #112947](https://github.com/ClickHouse/ClickHouse/pull/112947); the cache key derivation, [#112016](https://github.com/ClickHouse/ClickHouse/issues/112016).
  - **Known open poisoning bugs:** [#111897](https://github.com/ClickHouse/ClickHouse/issues/111897), the cache is poisoned by a `query_plan_join_shard_by_pk_ranges` plus `full_sorting_merge` multi-threaded join read; [#111363](https://github.com/ClickHouse/ClickHouse/issues/111363), the cache is poisoned by a parallel-replicas read of Merge over a VIEW, and later plain queries silently return wrong results. Both are "query A breaks query B", which is a different oracle shape from anything the fork has.
  - **Invariant, two parts.** (a) Shape coverage: the existing oracle must emit `ORDER BY <key> LIMIT n` queries and compare `use_query_condition_cache` on versus off, cold versus warm. (b) Poisoning: run a *first* query designed to populate the cache (optionally under the settings named in the two open bugs, including the parallel-replica and sharded-join arms from item 3), then run a *second, structurally different* query over the same table with the cache on, and compare that second query against itself with the cache off and after `SYSTEM DROP QUERY CONDITION CACHE`. A divergence is a poisoning bug.
  - **Bug class:** wrong result, and the worst kind operationally, because the wrong answer is served to an innocent query and disappears when the cache is dropped.
  - **Evidence the gap is real:** `query_condition_cache` appears in `oracle/keycond/ClickHouseKeyConditionOracle.java` (only to disable it), `oracle/qcc/ClickHouseQueryConditionCacheOracle.java` and `ClickHouseSessionSettings.java`. A regex for the cache setting co-occurring with a LIMIT clause returns zero hits, and there is no two-query poisoning arm.
  - **Soundness risk:** Medium. The poisoning arm intentionally runs multiple statements, which conflicts with checklist C1, so it must be immune to merges instead: use a fixture that is not mutated inside the iteration, and always re-verify a divergence by repeating the second query after `SYSTEM DROP QUERY CONDITION CACHE` before asserting. If the divergence disappears after the drop, it is a genuine cache bug; if it persists, it is a merge artifact and the iteration is abandoned.
  - **Files:** `src/sqlancer/clickhouse/oracle/qcc/ClickHouseQueryConditionCacheOracle.java`, `src/sqlancer/clickhouse/ClickHouseSessionSettings.java`.
  - **Effort:** M.
  - **Verification:** must reproduce [#111897](https://github.com/ClickHouse/ClickHouse/issues/111897) or [#111363](https://github.com/ClickHouse/ClickHouse/issues/111363) as a positive control while they are open; then 1 hour full fleet.

### Item 13. Text index second wave

- [ ] **13. Extend the text-index oracles to the second wave of features** `[Gen+Oracle]` `[P1]` `[M]`
  - **Goal:** the fork has four text-index oracles built in June, and ClickHouse then shipped another nine text-index changes. Bring the oracles up to the current feature set.
  - **ClickHouse surface, all merged in this window:** the ICU tokenizer, [PR #109940](https://github.com/ClickHouse/ClickHouse/pull/109940); the Japanese MeCab tokenizer, [PR #111420](https://github.com/ClickHouse/ClickHouse/pull/111420); storing positions for better phrase search, [PR #103172](https://github.com/ClickHouse/ClickHouse/pull/103172), which is what makes `hasPhrase` order-sensitive; the text index postprocessor, [PR #98939](https://github.com/ClickHouse/ClickHouse/pull/98939) and its resubmit [PR #108606](https://github.com/ClickHouse/ClickHouse/pull/108606), plus the filter-only postprocessor fast path, [PR #109049](https://github.com/ClickHouse/ClickHouse/pull/109049); lazy posting-list evaluation mode, [PR #100035](https://github.com/ClickHouse/ClickHouse/pull/100035), and randomized posting-list apply mode, [PR #108814](https://github.com/ClickHouse/ClickHouse/pull/108814); text index parameters via table settings, [PR #100626](https://github.com/ClickHouse/ClickHouse/pull/100626); trivial count optimization for text indexes, [PR #111494](https://github.com/ClickHouse/ClickHouse/pull/111494); caching missing tokens, [PR #112742](https://github.com/ClickHouse/ClickHouse/pull/112742); generic exclusion search for text index analysis, [PR #110530](https://github.com/ClickHouse/ClickHouse/pull/110530); pushing current mark ranges into the text index analyzer, [PR #108114](https://github.com/ClickHouse/ClickHouse/pull/108114); configurable flush limits, [PR #111573](https://github.com/ClickHouse/ClickHouse/pull/111573); `system.stemmers`, [PR #100611](https://github.com/ClickHouse/ClickHouse/pull/100611); `tokenizeQuery` and `highlightQuery`, [PR #101054](https://github.com/ClickHouse/ClickHouse/pull/101054).
  - **Known open bugs nearby:** [#105848](https://github.com/ClickHouse/ClickHouse/pull/105848) text index for LIKE/ILIKE with ESCAPE; [#107038](https://github.com/ClickHouse/ClickHouse/issues/107038) skip indexes on subcolumns ignored when querying through a view; the still-unmerged fix [#113157](https://github.com/ClickHouse/ClickHouse/pull/113157) for text and token skip indexes over-pruning IPv6 columns.
  - **Invariant:** the four existing oracles already have the right shape. Add: the `icu` and Japanese tokenizers to `TextIndexLifecycle` and `TextIndexDirectRead` tokenizer lists; a `hasPhrase` arm with an exact Java ground truth over token *positions*, which is only meaningful now that positions are stored; a trivial-count arm asserting `count()` with the index equals `count()` with `use_skip_indexes = 0`, which is the shape [PR #111494](https://github.com/ClickHouse/ClickHouse/pull/111494) introduced and exactly the shape that hid [#106125](https://github.com/ClickHouse/ClickHouse/issues/106125) in another engine; a posting-list-mode sweep (default, lazy, and the randomized mode) asserting mode-invariance; and text index parameters supplied via table settings instead of inline index arguments, which is a different code path for the same semantics.
  - **Bug class:** wrong result, both over-pruning (missing rows) and under-pruning that masks tokenizer mismatches.
  - **Evidence the gap is real:** grep finds `unicodeWord`, `asciiCJK`, `ngrams`, `sparseGrams` and `splitByString` but no `icu` or MeCab tokenizer, no `hasPhrase`, and no posting-list mode setting.
  - **Soundness risk:** Medium, and the highest of any P1 item, because tokenizer-vs-needle soundness is the single most bug-prone area this fork has worked in. The provider CLAUDE.md documents which function and tokenizer combinations are *legitimately* divergent; any new tokenizer must be probed against that matrix before its arm is enabled, and `hasPhrase` must not be emitted by the general fleet's `generateTextSearchPredicate` (which is restricted to the all-tokenizer-safe trio) but only inside an oracle that controls the tokenizer.
  - **Files:** `src/sqlancer/clickhouse/oracle/textindex/ClickHouseTextIndexLifecycleOracle.java`, `.../ClickHouseTextIndexDirectReadOracle.java`, `.../ClickHouseTextIndexLikeOracle.java`, `.../ClickHouseTextIndexContainerOracle.java`, `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java` (`renderSkipIndex`), `gen/ClickHouseAlterGenerator.java`.
  - **Effort:** M.
  - **Verification:** the SPLIT_CONTROL arm of `TextIndexDirectRead` must stay clean, per the rule already recorded for [#107186](https://github.com/ClickHouse/ClickHouse/issues/107186). New tokenizer arms are enabled one at a time behind the existing flag, with a 1 hour run each.

### Item 14. `Tuple` per-element aggregation in summing engines

- [ ] **14. Emit Tuple columns in SummingMergeTree, AggregatingMergeTree and CoalescingMergeTree** `[Gen+Oracle]` `[P1]` `[M]`
  - **Goal:** cover per-element Tuple aggregation in the dedupe engine family, which is the same family that just produced an open wrong-result bug through a different mechanism.
  - **ClickHouse surface:** support for per-element aggregation of `Tuple` columns in `SummingMergeTree`, `AggregatingMergeTree` and `CoalescingMergeTree`, [PR #98039](https://github.com/ClickHouse/ClickHouse/pull/98039), merged 2026-06-11.
  - **Known open bug in the same family, worth using as a positive control:** [#106125](https://github.com/ClickHouse/ClickHouse/issues/106125), query-time `FINAL` on SummingMergeTree applies the zero-row-deletion rule over only the columns the query reads. The minimal repro found during this audit is worth adding to the fork's own regression notes: with `mini (k UInt32, v_nonzero Int32, v_zero UInt8) ENGINE = SummingMergeTree ORDER BY k` and two identical inserts of `(1,100,0),(2,200,0)`, `SELECT k, v_nonzero, v_zero FROM mini FINAL` returns 2 rows while `SELECT count() FROM mini FINAL` returns 0, and both become correct after `OPTIMIZE TABLE ... FINAL`. That is a strictly better repro than the one on the issue and demonstrates rule C7 (never measure a presence bug with `count()`).
  - **Invariant:** build a fixture with a `Tuple(Int64, Int64)` (and a named-tuple variant) column in a summing or aggregating engine, insert rows across several parts, then assert that query-time `FINAL` equals the result after a physical `OPTIMIZE TABLE ... FINAL`, element by element, and that the per-element sums equal the Java-computed sums over the inserted rows. The physical-versus-query-time FINAL comparison is the assertion that catches the #106125 class in general, and it should be added for scalar summed columns at the same time.
  - **Bug class:** wrong result, dropped rows and wrong aggregated values.
  - **Evidence the gap is real:** grep for a Tuple column inside a summing engine returns zero hits; `Tuple` is modelled as a type but is not emitted into these engines. `ClickHouseCoalescingFinalOracle` exists but does not use Tuple columns.
  - **Soundness risk:** Medium. Rule C2 applies (the ORDER BY key must have a non-degenerate domain), and rule C7 applies (compare rows, not counts). Integer tuple elements only, per C3.
  - **Files:** `src/sqlancer/clickhouse/gen/ClickHouseColumnBuilder.java`, `gen/ClickHouseTableGenerator.java` (`pickEngine` eligibility for tuple summation), `src/sqlancer/clickhouse/oracle/final_/ClickHouseFinalMergeOracle.java`, `oracle/coalesce/ClickHouseCoalescingFinalOracle.java`.
  - **Effort:** M.
  - **Verification:** the #106125 minimal repro must be caught by the query-time-versus-physical FINAL assertion, which is the positive control; then 1 hour full fleet.

### Item 15. Sparse columns

- [ ] **15. Emit high-default-ratio columns so sparse serialization engages** `[Gen]` `[P1]` `[S]`
  - **Goal:** make sparse serialization actually happen in fuzzed tables, so the new sparse-aware pruning and trivial-count paths are exercised by every existing oracle for free.
  - **ClickHouse surface:** sparsity-aware part and granule pruning plus the trivial-count optimization, [PR #105890](https://github.com/ClickHouse/ClickHouse/pull/105890); `InlinedVector` for the RPN stack in sparse `checkInHyperrectangle`, [PR #110153](https://github.com/ClickHouse/ClickHouse/pull/110153); the controlling table setting is `ratio_of_defaults_for_sparse_serialization`.
  - **Invariant:** none of its own. This is a pure emission change: set `ratio_of_defaults_for_sparse_serialization` explicitly in `CREATE TABLE` settings, and make the insert generator produce columns that are overwhelmingly default (say 95 percent zeros or empty strings) for a subset of columns. Every existing pruning, count and FINAL oracle then covers sparse serialization at no extra cost, which is the highest leverage available for an S-effort change.
  - **Bug class:** wrong result. Sparse columns have a separate read path, a separate default-filling path and now separate pruning logic.
  - **Evidence the gap is real:** grep finds `sparse` in only two files, `gen/ClickHouseTableGenerator.java` and `oracle/textindex/ClickHouseTextIndexDirectReadOracle.java`, and the insert generator does not bias any column towards defaults, so with random values sparse serialization essentially never triggers.
  - **Soundness risk:** Low. This changes the data distribution, not any assertion. One caution: a heavily-default column makes `LIMIT BY` and `DISTINCT` oracles see far fewer distinct keys, which interacts with rule C2, so do not apply the default bias to a dedupe engine's ORDER BY key.
  - **Files:** `src/sqlancer/clickhouse/gen/ClickHouseInsertGenerator.java`, `gen/ClickHouseTableGenerator.java`, `gen/ClickHouseColumnBuilder.java`.
  - **Effort:** S.
  - **Verification:** confirm via `system.parts_columns` that a fuzzed table actually has a sparse-serialized column, otherwise the change is decorative; then 1 hour full fleet.

### Item 16. Comparison and LIKE chain rewrites

- [ ] **16. Toggle `optimize_or_like_chain` and `optimize_and_compare_chain`** `[Oracle]` `[P1]` `[S]`
  - **Goal:** put a targeted differential on two AST rewrites that *prune* predicates, one of which was turned on by default in this window.
  - **ClickHouse surface:** enabling `optimize_or_like_chain` by default, [PR #94517](https://github.com/ClickHouse/ClickHouse/pull/94517), merged 2026-07-13, which rewrites a chain of `LIKE` disjunctions into `multiMatchAny`; the AND comparison-chain optimizer that detects conflicts and prunes redundancies, [PR #99736](https://github.com/ClickHouse/ClickHouse/pull/99736); the bound on its analysis cost, [PR #108757](https://github.com/ClickHouse/ClickHouse/pull/108757); and the older `convert_query_to_cnf`, which item 2 also covers from the float side.
  - **Known bug precedent:** [#104537](https://github.com/ClickHouse/ClickHouse/issues/104537), `tryOptimizeAndEqualsNotEqualsChain` loses type information when converting a `notEquals` chain to `NOT IN`, causing wrong results. Same code path, already broken once.
  - **Invariant:** the same query with each rewrite on and off must return the same rows. To make the arm actually reach the rewrites, the generator must emit long homogeneous chains: `col LIKE 'a%' OR col LIKE 'b%' OR ...` for the LIKE chain, and `col != 1 AND col != 2 AND col != 3 AND ... AND col < 10` for the comparison chain, including deliberately conflicting conjuncts (`col > 5 AND col < 3`) since conflict detection is the risky half of #99736.
  - **Bug class:** wrong result, over-aggressive pruning of a conjunct that was not actually redundant.
  - **Evidence the gap is real:** both setting names appear only in `ClickHouseSessionSettings.java`, meaning they can be randomized but nothing compares results across them, and the generator does not emit the chain shapes that trigger either rewrite.
  - **Soundness risk:** Low. Settings toggles over one fixture, one snapshot each.
  - **Files:** `src/sqlancer/clickhouse/oracle/settingflip/ClickHouseSettingFlipOracle.java` (a dedicated arm rather than generic randomization), `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` (chain emission), `ClickHouseSessionSettings.java`.
  - **Effort:** S.
  - **Verification:** verify via `EXPLAIN QUERY TREE` or `EXPLAIN SYNTAX` that the rewrite fires for the generated chains; then 1 hour full fleet.

### Item 17. `indexHint`

- [ ] **17. Emit `indexHint` and assert it does not change results** `[Gen+Oracle]` `[P1]` `[S]`
  - **Goal:** cover a function whose entire contract is "affects index analysis, never affects the result set", which makes it the purest possible pruning-soundness assertion, and which has an open wrong-result bug right now.
  - **ClickHouse surface:** `indexHint(...)`, handled as a logical no-op in `KeyCondition.cpp` (see `isLogicalOperator` and the `indexHint` branch of `cloneDAGWithInversionPushDown`, both of which item 1 also touches).
  - **Known open bug:** [#112035](https://github.com/ClickHouse/ClickHouse/issues/112035), `indexHint` in a WHERE over the right table of a LEFT JOIN prunes right-side granules and flips matched rows to unmatched, silently.
  - **Invariant:** `SELECT ... WHERE indexHint(P) AND Q` must return a superset of `SELECT ... WHERE P AND Q` and, because `indexHint` is documented as not filtering, exactly the rows of `SELECT ... WHERE Q`. Assert the latter equality directly. Emit it on both sides of a LEFT JOIN specifically, which is the #112035 shape.
  - **Bug class:** wrong result, silently dropped or flipped rows.
  - **Evidence the gap is real:** grep for `indexHint` returns zero hits in the provider.
  - **Soundness risk:** Low. One caveat: `indexHint` interacts with `force_primary_key` and with the CNF rewrite, so pin those settings explicitly in the arm rather than inheriting randomized values.
  - **Files:** `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java`, `src/sqlancer/clickhouse/oracle/keycond/ClickHouseKeyConditionOracle.java` (an `indexHint` arm fits naturally next to the `materialize()` arm).
  - **Effort:** S.
  - **Verification:** must reproduce [#112035](https://github.com/ClickHouse/ClickHouse/issues/112035) as a positive control while it is open; then 1 hour full fleet.

### Item 18. Mixed-direction sorting keys

- [ ] **18. Emit mixed-direction ORDER BY keys and sweep aggregation-in-order** `[Gen+Oracle]` `[P1]` `[S]`
  - **Goal:** reach the read-in-order and aggregation-in-order code paths for a sorting key that is not uniformly ascending, which is where they currently break.
  - **ClickHouse surface:** `optimize_read_in_order`, `optimize_aggregation_in_order`, `read_in_order_use_buffering`; read-in-order propagation through `SpillingHashJoin`, [PR #111973](https://github.com/ClickHouse/ClickHouse/pull/111973); avoiding scans for constant sort keys, [PR #113899](https://github.com/ClickHouse/ClickHouse/pull/113899); the unordered stream modifier, [PR #111794](https://github.com/ClickHouse/ClickHouse/pull/111794); `STREAM BOUNDED`, [PR #110653](https://github.com/ClickHouse/ClickHouse/pull/110653); reimplemented reading in order for parallel replicas, [PR #101434](https://github.com/ClickHouse/ClickHouse/pull/101434).
  - **Known open bug:** [#111901](https://github.com/ClickHouse/ClickHouse/issues/111901), `optimize_aggregation_in_order` over a mixed-direction sorting key `(a, b DESC)` collapses GROUP BY groups, a silent wrong result. Also nearby: [#114407](https://github.com/ClickHouse/ClickHouse/issues/114407), `toUnixTimestamp()` in ORDER BY silently loses primary-key pruning from 26.7, which is a performance regression rather than a wrong result but lives in the same emission gap.
  - **Invariant:** `ClickHouseReadInOrderToggleOracle` already compares the same query with these settings on and off. The missing piece is that `ClickHouseTableGenerator` never emits a descending or mixed-direction sorting key, so the oracle only ever tests the ascending case. Add `ORDER BY (a, b DESC)` and `ORDER BY (a DESC)` forms, and add GROUP BY prefixes of such keys to the oracle's query shapes.
  - **Bug class:** wrong result, collapsed or duplicated GROUP BY groups, and premature stop under LIMIT.
  - **Evidence the gap is real:** the sorting-key builder in `gen/ClickHouseTableGenerator.java` emits only bare or function-wrapped ascending expressions; no `DESC` appears in any generated `ORDER BY` in the saved reproducer logs.
  - **Soundness risk:** Low to medium. Rule C5 matters more than usual here: a positional compare over a mixed-direction key needs the full key as a tiebreak, in the same directions.
  - **Files:** `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java`, `src/sqlancer/clickhouse/oracle/readorder/ClickHouseReadInOrderToggleOracle.java`.
  - **Effort:** S.
  - **Verification:** must reproduce [#111901](https://github.com/ClickHouse/ClickHouse/issues/111901) as a positive control while it is open; then 1 hour full fleet. Note that a mixed-direction key also changes dedupe-engine behaviour, so re-check rule C2 after this lands.

## P2, coverage items

### Item 19. Materialized-view lifecycle

- [ ] **19. Cover MV lifecycle DDL: OR REPLACE, atomic POPULATE, PAUSE** `[Gen+Oracle]` `[P2]` `[M]`
  - **Goal:** the fork has one MV oracle, `ClickHouseMaterializedViewConsistencyOracle`, which builds an MV and checks that the source aggregate equals the MV-maintained aggregate. Four lifecycle operations were added or changed in this window and none of them are exercised.
  - **ClickHouse surface:** `CREATE OR REPLACE` for materialized views, [PR #100539](https://github.com/ClickHouse/ClickHouse/pull/100539); making `CREATE MATERIALIZED VIEW ... POPULATE` atomic, [PR #108715](https://github.com/ClickHouse/ClickHouse/pull/108715); `SYSTEM PAUSE VIEW` and `SYSTEM PAUSE VIEWS`, [PR #103252](https://github.com/ClickHouse/ClickHouse/pull/103252); unified `SYSTEM STOP/PAUSE/CANCEL/REFRESH` for streaming engines and materialized views, [PR #107476](https://github.com/ClickHouse/ClickHouse/pull/107476); naming the MV and target table in target-write errors, [PR #107234](https://github.com/ClickHouse/ClickHouse/pull/107234).
  - **Known open bugs nearby:** [#114436](https://github.com/ClickHouse/ClickHouse/issues/114436), an INSERT on a DDL-lagging Replicated-database replica silently skips an un-applied MV's cascade; [#111935](https://github.com/ClickHouse/ClickHouse/issues/111935), replica recovery silently drops MV inner-table data; [#114097](https://github.com/ClickHouse/ClickHouse/issues/114097), `StorageWindowView::getSourceTableSelectQuery` drops ORDER BY without resetting `order_by_all`; [#113493](https://github.com/ClickHouse/ClickHouse/issues/113493), a WINDOW VIEW cannot receive inserted data. The replica-related ones are out of scope (single node), the WINDOW VIEW ones are in scope.
  - **Invariant:** (a) `POPULATE` atomicity: create an MV with `POPULATE` over a non-trivial source, then assert the MV content equals the aggregate over the source rows that existed at creation time plus everything inserted after, with no double counting and no gap. This is the classic POPULATE race and the PR claims to have closed it. (b) `CREATE OR REPLACE`: replace an MV's definition and assert the new definition is in force for subsequent inserts and that the old target data is handled as documented. (c) `PAUSE`: pause the view, insert, assert the MV does not advance, resume, assert it catches up or does not, per documented semantics, then confirm the source is unaffected either way.
  - **Bug class:** wrong result, double-counted or missing MV rows.
  - **Evidence the gap is real:** grep for `OR REPLACE` and `POPULATE` returns zero hits.
  - **Soundness risk:** Medium to high, and the reason this is P2 rather than P1. The existing MV oracle already needs a totals-consistency precondition to survive a memory-starved server, and MV push failures under memory pressure are an environment artifact, not a wrong result. Any new arm needs the same guard.
  - **Files:** `src/sqlancer/clickhouse/oracle/view/ClickHouseMaterializedViewConsistencyOracle.java`, `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java` (MV DDL forms).
  - **Effort:** M.
  - **Verification:** 1 hour full fleet on an *unloaded* server first, to separate genuine findings from memory artifacts.

### Item 20. ALTER surface

- [ ] **20. Cover the ALTER operations added in this window** `[Gen+Oracle]` `[P2]` `[M]`
  - **Goal:** `ClickHouseAlterModifyConsistencyOracle` and `ClickHouseAlterGenerator` cover column type changes, codecs, TTL and settings. Four new or newly-cheapened ALTER operations landed and none are generated.
  - **ClickHouse surface:** `ALTER TABLE ... ADD ENUM VALUES`, [PR #93830](https://github.com/ClickHouse/ClickHouse/pull/93830); `ALTER TABLE ... MODIFY CONSTRAINT`, [PR #108768](https://github.com/ClickHouse/ClickHouse/pull/108768), plus the new `system.constraints` table, [PR #105337](https://github.com/ClickHouse/ClickHouse/pull/105337); making `ALTER MODIFY COLUMN` on a named `Tuple` metadata-only when adding subfields, [PR #107305](https://github.com/ClickHouse/ClickHouse/pull/107305); allowing an `ALTER` of a column type when its key subcolumns are unchanged, [PR #113862](https://github.com/ClickHouse/ClickHouse/pull/113862); allowing `COMMENT` after all other column modifiers, [PR #112788](https://github.com/ClickHouse/ClickHouse/pull/112788); fixing qualified names in mutations, [PR #109491](https://github.com/ClickHouse/ClickHouse/pull/109491).
  - **Known open bug of exactly this shape:** [#114588](https://github.com/ClickHouse/ClickHouse/issues/114588), a column named like an array subcolumn (`a.size0`) added via ALTER makes old parts silently return the subcolumn value instead of the column's own value. That is a pure ALTER-plus-old-parts wrong result, which is what item 20's invariant targets.
  - **Invariant:** the metadata-only operations are the interesting ones, because "metadata-only" means old parts are *not* rewritten and must still read correctly. So: build a table, insert across several parts, apply the ALTER, then assert that reading every column returns exactly what a freshly-created table with the post-ALTER schema and the same data returns. For `ADD ENUM VALUES` additionally assert that pre-existing rows keep their values and that the new values are insertable. For `MODIFY CONSTRAINT` assert that a row violating the new constraint is rejected on INSERT while existing rows are untouched, and cross-check `system.constraints`.
  - **Bug class:** wrong result on old parts after a metadata-only schema change, which is the highest-severity ALTER failure mode.
  - **Evidence the gap is real:** grep for `ADD ENUM`, `MODIFY CONSTRAINT` and `ADD CONSTRAINT` returns zero hits.
  - **Soundness risk:** Low to medium. Must run with `mutations_sync = 2` (already mounted on the dev VM) so a metadata-only versus rewriting distinction does not turn into a race.
  - **Files:** `src/sqlancer/clickhouse/gen/ClickHouseAlterGenerator.java`, `src/sqlancer/clickhouse/oracle/altermodify/ClickHouseAlterModifyConsistencyOracle.java`, `src/sqlancer/clickhouse/gen/ClickHouseColumnBuilder.java` (named Tuple columns).
  - **Effort:** M.
  - **Verification:** must reproduce [#114588](https://github.com/ClickHouse/ClickHouse/issues/114588) as a positive control while it is open; then 1 hour full fleet.

### Item 21. Projections with a WHERE clause

- [ ] **21. Emit projections that carry a WHERE clause and vary materialization timing** `[Gen]` `[P2]` `[S]`
  - **Goal:** `ClickHouseProjectionToggleOracle` already compares `optimize_use_projections` on and off, and it already found a real bug ([#106573](https://github.com/ClickHouse/ClickHouse/issues/106573), the implicit `_minmax_count_projection` GROUP BY collapse). Projections gained a WHERE clause and materialization-timing controls in this window, so the oracle's surface should grow.
  - **ClickHouse surface:** WHERE clause support for projections, [PR #102347](https://github.com/ClickHouse/ClickHouse/pull/102347), merged 2026-06-30, which makes a projection *partial* so the optimizer must prove the projection's predicate covers the query's; table settings controlling when projections are materialized, [PR #100993](https://github.com/ClickHouse/ClickHouse/pull/100993).
  - **Invariant:** unchanged from the existing oracle: the same query with `optimize_use_projections` on and off must agree. A partial projection makes this much sharper, because a projection with a WHERE clause that does *not* cover the query predicate must be rejected by the optimizer; if it is used anyway, rows go missing.
  - **Bug class:** wrong result, missing rows when a partial projection is used for a query it does not cover.
  - **Evidence the gap is real:** `projection` appears in 28 files, so the toggle machinery is mature, but the projection DDL generator does not emit a WHERE clause.
  - **Soundness risk:** Low. The assertion already exists and is already validated; this is emission only.
  - **Files:** `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java` (projection DDL), `src/sqlancer/clickhouse/oracle/projection/ClickHouseProjectionToggleOracle.java`.
  - **Effort:** S.
  - **Verification:** confirm via `EXPLAIN` that a partial projection is chosen for at least some generated queries; then 1 hour full fleet.

### Item 22. `Nullable(Tuple)` and lossy numeric supertypes

- [ ] **22. Emit `Nullable(Tuple)` columns and toggle `allow_lossy_numeric_supertype`** `[Gen]` `[P2]` `[S]`
  - **Goal:** two type-system changes that interact directly with the constraint that currently shapes this fork's expression emission.
  - **ClickHouse surface:** beta `Nullable(Tuple(...))`, [PR #107754](https://github.com/ClickHouse/ClickHouse/pull/107754); the `allow_lossy_numeric_supertype` setting for numeric `Variant` common types, [PR #107236](https://github.com/ClickHouse/ClickHouse/pull/107236); executing mixed-type-pair comparison and arithmetic kernels via conversion to a common type, [PR #110131](https://github.com/ClickHouse/ClickHouse/pull/110131).
  - **Why this matters specifically here:** the provider CLAUDE.md documents that a multi-branch conditional over dissimilar numeric types settles on a `Variant` common type, which the client-v2 reader cannot decode, which is why `generateMultiIf` CAST-wraps its output. `allow_lossy_numeric_supertype` changes exactly that supertype computation, so toggling it is both a test of new behaviour and a way to check that the fork's CAST-wrapping rule is still necessary and sufficient. [PR #110131](https://github.com/ClickHouse/ClickHouse/pull/110131) changes the same computation for plain arithmetic.
  - **Invariant:** for the setting, the same expression evaluated with the setting on and off must either agree or produce a *typed* difference that the oracle can see via `toTypeName`; assert agreement on the value when the types agree. For `Nullable(Tuple)`, this is emission plus a roundtrip: insert, read back, assert the NULL mask and every element survive, and confirm the reader can decode it before enabling the arm widely.
  - **Bug class:** crash (reader decode failure, which kills a worker) and wrong result (silent precision loss in a supertype).
  - **Evidence the gap is real:** grep for `Nullable(Tuple` and `allow_lossy_numeric_supertype` returns zero hits.
  - **Soundness risk:** Medium, entirely because of the reader. Rule C6 applies: probe `toTypeName` and a read-back before emitting widely, exactly as was done for `Variant` subcolumns.
  - **Files:** `src/sqlancer/clickhouse/ClickHouseSchema.java` (`pickScalarType`), `gen/ClickHouseColumnBuilder.java`, `ClickHouseTypeParser.java`, `ClickHouseSessionSettings.java`.
  - **Effort:** S.
  - **Verification:** a read-back probe must pass before the type is added to the pool; then 1 hour full fleet with no worker deaths.

### Item 23. QBit and quantized distance functions

- [ ] **23. Emit the QBit type and its distance functions** `[Gen+Oracle]` `[P2]` `[M]`
  - **Goal:** QBit received roughly ten PRs in this window and is entirely absent from the fork, which has a vector oracle but only for the similarity index.
  - **ClickHouse surface:** `Int8` support for QBit, [PR #108105](https://github.com/ClickHouse/ClickHouse/pull/108105); strides, [PR #108103](https://github.com/ClickHouse/ClickHouse/pull/108103); quantized transposed distance functions for `QBit(Int8)`, [PR #109405](https://github.com/ClickHouse/ClickHouse/pull/109405); `dotProductTransposed`, [PR #108100](https://github.com/ClickHouse/ClickHouse/pull/108100); partial reads for transposed distance on `Nullable(QBit)`, [PR #109358](https://github.com/ClickHouse/ClickHouse/pull/109358); `CAST` between QBit types with different parameters, [PR #109387](https://github.com/ClickHouse/ClickHouse/pull/109387); `CAST` from QBit to Array, [PR #108072](https://github.com/ClickHouse/ClickHouse/pull/108072); `length` for QBit, [PR #108071](https://github.com/ClickHouse/ClickHouse/pull/108071); truncating an oversized reference vector, [PR #109388](https://github.com/ClickHouse/ClickHouse/pull/109388); `quantizeBFloat16ToInt8` and `dequantizeInt8ToBFloat16`, [PR #108102](https://github.com/ClickHouse/ClickHouse/pull/108102) and [PR #109398](https://github.com/ClickHouse/ClickHouse/pull/109398); quantization codecs for vector columns with two-stage retrieval, [PR #108565](https://github.com/ClickHouse/ClickHouse/pull/108565); `randomHadamardTransform`, [PR #108227](https://github.com/ClickHouse/ClickHouse/pull/108227) and its constant fast path, [PR #112921](https://github.com/ClickHouse/ClickHouse/pull/112921).
  - **Known open bug in the family:** [#112242](https://github.com/ClickHouse/ClickHouse/issues/112242) and [#114531](https://github.com/ClickHouse/ClickHouse/pull/114531) both touch quantized or lossy storage of key and index columns.
  - **Invariant:** the sound assertions here are the *exact* ones, not the approximate ones. `CAST(qbit AS Array(...))` roundtrip must be lossless for the declared precision; `length(qbit)` must equal the array length; a transposed distance function must agree with the plain distance function on the same vectors to within the documented quantization tolerance; and `CAST` between QBit parameterizations must be consistent with doing the same conversion through `Array`. Approximate recall assertions are explicitly out of scope, exactly as `ClickHouseVectorIndexRecallOracle` already handles for HNSW.
  - **Bug class:** wrong result in distance values and in roundtrips.
  - **Evidence the gap is real:** grep for `QBit` returns zero hits; `oracle/vecindex/ClickHouseVectorIndexRecallOracle.java` covers the similarity index only.
  - **Soundness risk:** Medium. Quantized values are lossy by design, so every assertion must be either an exact roundtrip or an explicit tolerance; a naive equality assertion would be a false-positive factory.
  - **Files:** `src/sqlancer/clickhouse/ClickHouseSchema.java`, `gen/ClickHouseColumnBuilder.java`, `ClickHouseType.java`, `ClickHouseTypeParser.java`, create `src/sqlancer/clickhouse/oracle/vecindex/ClickHouseQBitRoundtripOracle.java`.
  - **Effort:** M.
  - **Verification:** roundtrip arm clean over 1 hour; the tolerance arm must be calibrated against the documented quantization error before it is enabled.

### Item 24. Time-zone postfix operators

- [ ] **24. Emit `AT TIME ZONE`, `AT LOCAL`, `LOCALTIME` and `LOCALTIMESTAMP`** `[Gen]` `[P2]` `[S]`
  - **Goal:** new datetime syntax that the fork's two datetime oracles can consume immediately.
  - **ClickHouse surface:** `AT TIME ZONE` and `AT LOCAL` postfix operators, [PR #106092](https://github.com/ClickHouse/ClickHouse/pull/106092); `LOCALTIME` and `LOCALTIMESTAMP` as aliases for `now()`, [PR #106139](https://github.com/ClickHouse/ClickHouse/pull/106139); `INTERVAL` expressions in `formatReadableTimeDelta`, [PR #64315](https://github.com/ClickHouse/ClickHouse/pull/64315); `toDateTimeOrNull()` with integer arguments, [PR #79791](https://github.com/ClickHouse/ClickHouse/pull/79791); the `addDays`/`addWeeks` fixed-offset fast path, [PR #109836](https://github.com/ClickHouse/ClickHouse/pull/109836); the `toStartOfInterval` fast path for SECOND, MINUTE and HOUR, [PR #109729](https://github.com/ClickHouse/ClickHouse/pull/109729).
  - **Invariant:** `x AT TIME ZONE 'tz'` must equal `toTimeZone(x, 'tz')`, which is a pure syntax-equivalence identity, and `AT LOCAL` must equal `toTimeZone(x, <server timezone>)`. Both are single-snapshot two-column comparisons, the cheapest sound shape available. `LOCALTIME` and `LOCALTIMESTAMP` are non-deterministic and must only be asserted for *type*, never for value. The fast-path PRs are covered for free by the existing `ClickHouseExtendedDatetimeOracle` and `ClickHouseTimezoneDatetimeOracle` once the functions are emitted with the relevant units.
  - **Bug class:** wrong result in timezone conversion, which historically has been a rich seam in this provider (`toStartOfYear(Date32)`, [#106419](https://github.com/ClickHouse/ClickHouse/issues/106419)).
  - **Evidence the gap is real:** grep for `AT TIME ZONE` and `AT LOCAL` returns zero hits.
  - **Soundness risk:** Low, provided `LOCALTIME` and `LOCALTIMESTAMP` are excluded from any value assertion. They are `now()` aliases and would make every oracle non-deterministic; emit them only in type probes, or not at all.
  - **Files:** `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java`, `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java`, `oracle/datetime/ClickHouseTimezoneDatetimeOracle.java`.
  - **Effort:** S.
  - **Verification:** the two identities must hold across the full timezone list used by the existing datetime oracles; then 1 hour full fleet.

### Item 25. Continuous queries, what-if indexes, QueryRunner

- [ ] **25. Reach the new engines and analysis-only features** `[Gen+Oracle]` `[P2]` `[L]`
  - **Goal:** three genuinely new features that no existing oracle can reach. Grouped because each is individually small in value but they share the same work (new DDL emission plus a narrow assertion), and because the crash surface of brand-new engines is usually worth the visit.
  - **ClickHouse surface:** MergeTree Continuous Query, [PR #105114](https://github.com/ClickHouse/ClickHouse/pull/105114); hypothetical (what-if) indexes, [PR #104608](https://github.com/ClickHouse/ClickHouse/pull/104608), and combined-index estimation for them, [PR #108934](https://github.com/ClickHouse/ClickHouse/pull/108934); the `QueryRunner` table engine, [PR #107888](https://github.com/ClickHouse/ClickHouse/pull/107888); the `eval` table function, [PR #110132](https://github.com/ClickHouse/ClickHouse/pull/110132); `mergeTreeCodecBlockCounts`, [PR #109623](https://github.com/ClickHouse/ClickHouse/pull/109623); `system.documentation`, [PR #107463](https://github.com/ClickHouse/ClickHouse/pull/107463).
  - **Invariant, per feature:** continuous queries are an incrementally-maintained result, so the assertion is the MV-consistency assertion in a different dress, source aggregate equals maintained aggregate, and it should reuse `ClickHouseMaterializedViewConsistencyOracle`'s totals precondition. Hypothetical indexes are *analysis only*, so the sound assertion is that adding a hypothetical index never changes a query's result, only its plan, which is a clean equality assertion and also a crash probe on a new analysis path. `QueryRunner` and `eval` are crash probes: emit them, tolerate documented errors, treat a logical error or a dead worker as a finding.
  - **Bug class:** crashes and logical errors, primarily. Wrong results for continuous queries.
  - **Evidence the gap is real:** grep for `CONTINUOUS`, `QueryRunner` and hypothetical-index syntax returns zero hits.
  - **Soundness risk:** Medium. These are young features whose exact syntax and settings must be probed against head before wiring, and whose error surface is not yet stable, so expect to spend most of the effort on a narrow error allowlist rather than on the assertion.
  - **Files:** `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java`, `gen/ClickHouseAlterGenerator.java`, `oracle/tablefn/ClickHouseTableFunctionINOracle.java`, `oracle/view/ClickHouseMaterializedViewConsistencyOracle.java`, `ClickHouseErrors.java`.
  - **Effort:** L.
  - **Verification:** syntax probe against head first; then each feature enabled separately behind its own flag with a 1 hour run.

## Appendix A. Sequencing

The dependency order that matters:

1. **0a and 0b first.** They remove the two false positives that currently cost triage time on every nightly, and item 8 must not land before 0a or it inherits the same normalisation bug.
2. **Item 1 next.** Generator-only, S effort, and it feeds an oracle that is already validated. It is the item with a proven bug waiting for it.
3. **Item 4 before item 3.** The view-in-multi-join emission is a prerequisite for the parallel-replicas oracle's strongest positive control, [#111727](https://github.com/ClickHouse/ClickHouse/issues/111727).
4. **Item 2 and item 11 together.** Item 2 supplies the NaN and float data, item 11 supplies the statistics variety, and their shared positive control is [#113417](https://github.com/ClickHouse/ClickHouse/issues/113417).
5. **Item 15 early, despite being P1.** It is S effort, changes no assertion, and lights up every existing pruning, count and FINAL oracle for free.
6. Everything else in priority order.

## Appendix B. What was deliberately left out, and why

| Area | Volume in window | Reason for exclusion |
|---|---|---|
| AI functions (`aiGenerate`, `aiClassify`, `aiExtract`, `aiTranslate`, `aiEmbed`, `aiFilter`, `aiRedact`, `aiSimilarity`, credentials and hardening) | ~12 PRs | Non-deterministic, needs an external LLM endpoint. No assertable invariant. |
| WASM UDFs (ABI, AssemblyScript, buffers, fuel mode, `DETERMINISTIC`, widening coercions) | ~6 PRs | Needs compiled WASM artifacts; the fuzzer has no way to generate a meaningful module. |
| Web UI, web terminal, `/schema`, Play tabs, docs search, CLI help | ~10 PRs | Not SQL. |
| Keeper (new storage, TTL nodes, CreateContainer, dashboard, load balancing) | ~8 PRs | Needs a Keeper ensemble; the fork runs a single standalone server. |
| PromQL and Prometheus remote-write | ~12 PRs | Separate query language and HTTP surface; would need its own provider, not an oracle. |
| Data lakes: Iceberg, Paimon, BigQuery, Puffin, `remove_orphan_files`, manifest compaction, metadata caches | ~12 PRs | Needs external object storage and catalogs. Note this is the second-largest open wrong-result component (DataLake 8), so it is the strongest candidate for a *separate* plan with an external fixture, not a gap in this one. |
| S3Queue, Kafka, NATS, message queues | ~8 PRs | Needs external brokers. Also 6 open wrong-result issues, same reasoning as above. |
| Output and input formats: PNG, animated PNG, GeoJSON, Vortex, Hive text, ORC union, `RowBinaryWithNamesAndTypesAndDefaults`, framing formats | ~10 PRs | Format round-trips are testable in principle but orthogonal to query semantics, and the fork is pinned to one wire format by the reader constraint. Candidate for a separate plan. |
| Backup and restore, including the incremental dedup bugs | ~4 PRs | Needs a backup destination and a restore cycle; different harness. |
| Crash and power-loss durability ([#111433](https://github.com/ClickHouse/ClickHouse/issues/111433), [#111330](https://github.com/ClickHouse/ClickHouse/issues/111330), [#111823](https://github.com/ClickHouse/ClickHouse/issues/111823), [#112095](https://github.com/ClickHouse/ClickHouse/issues/112095), [#113459](https://github.com/ClickHouse/ClickHouse/issues/113459), [#111380](https://github.com/ClickHouse/ClickHouse/issues/111380)) | 6 open issues | Needs fault injection (kill at a specific commit point), not a query oracle. |
| Observability: metrics, ProfileEvents, system log tables, jemalloc profiling, histogram metric log | ~20 PRs | Not results. Two open issues ([#114844](https://github.com/ClickHouse/ClickHouse/issues/114844), [#114843](https://github.com/ClickHouse/ClickHouse/issues/114843)) are about counter accuracy, which a fuzzer could in principle assert, but the payoff is low. |
| Pure performance work with no semantic change (SIMD kernels, prefetch tuning, hash-table layouts, `PackedStringHashTable`, `keys32`/`keys64`, sorting-algorithm swaps) | ~80 PRs | Covered transitively: any semantic break in these shows up in the existing TLP, NoREC, aggregate and join oracles, which already run over the same shapes. No new oracle needed. |

## Appendix C. Re-running this audit

The audit is reproducible. To refresh it for the next window:

1. Run the two `qa_intelligence` queries in "Audit Method" with the window moved forward.
2. Re-run the fork capability sweep: for each new setting name, clause keyword, type name and function name in the result, grep `src/sqlancer/clickhouse/**/*.java`. Zero hits means a candidate gap; the entry must then name the file that *would* have to change, so the claim stays checkable.
3. Triage every `NightlySQLancer` run since the last audit, replaying each reproducer against fresh `clickhouse/clickhouse-server:head`. Per-job reports are at `https://s3.amazonaws.com/clickhouse-test-reports/REFs/master/<sha>/result_sqlancer{,pp}.json` (gzipped despite the extension), and the artifact links they contain include the reproducer tarballs.
4. Anything found by the upstream `SQLancerPP` job but not by this fork is a grammar gap by definition, and goes straight to P0. That rule is what produced item 1.
