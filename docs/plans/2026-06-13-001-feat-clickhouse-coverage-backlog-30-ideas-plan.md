---
title: "feat: ClickHouse SQLancer coverage backlog — 30 prioritized oracle / generator / feature ideas"
type: feat
status: completed
date: 2026-06-13
supersedes_pending_of:
  - docs/plans/2026-05-29-001-feat-clickhouse-coverage-expansion-roadmap-plan.md
related:
  - docs/plans/2026-06-10-002-feat-clickhouse-26x-feature-coverage-plan.md
  - docs/plans/2026-06-10-001-feat-mutation-analyzer-coverage-plan.md
---

# feat: ClickHouse SQLancer coverage backlog — 30 prioritized ideas

## Overview

This is a **research/backlog plan**: a single prioritized list of 30 structured ideas for
*missing* SQLancer coverage of ClickHouse, spanning **new oracles**, **generator/type/engine/DDL
emission**, and **function-family catalog growth**. It is the unified successor to the three
existing plans — it folds in every still-pending unit from the 2026-05-29 roadmap and
re-prioritizes the whole surface by bug-finding value.

It does **not** pre-write generator or oracle code. Each idea carries enough structure (goal,
ClickHouse surface, the invariant or emission, the bug class it catches, the soundness risk, file
targets, effort, verification) that any one entry can be promoted to a full `ce:plan`
implementation plan and handed to `ce:work`. The existing roadmap plan is the template for that
promotion.

## Problem Frame

The provider is **mature**: 47 oracles wired in `--oracles all`, ~115 Java files, a rich
expression generator. But a systematic audit (method below) shows the *generator* lags the
*oracle* and *type* models — and ClickHouse 26.x has shipped a wave of young, wrong-result-prone
execution paths the fuzzer never reaches. Three structural patterns recur:

1. **Dormant oracles/types waiting on emission.** Map/Tuple/Nested/geo/Interval/signed-wide-int
   types are fully modelled in `ClickHouseType.java` but never selected by
   `ClickHouseSchema.pickScalarType`. AggregateFunction columns are modelled but unemitted.
   Several invariants would start firing the instant the surface is emitted.
2. **Default-on execution paths with no differential oracle.** PREWHERE, read-in-order,
   aggregation-in-order, lazy/late materialization, `_minmax_count_projection`, count
   optimizations, engine-specific read paths — all run by default, all can return wrong results,
   none have a dedicated on/off or cross-form differential.
3. **Whole feature families are dark.** Non-MergeTree engines (Memory/Log/Buffer/Null/Set/Join/
   Dictionary/CoalescingMergeTree/GraphiteMergeTree), table-level TTL, partition lifecycle DDL,
   `ALTER MODIFY {TTL,SETTING,CODEC}`, refreshable MVs, vector/HNSW + tokenbf indexes, GROUP BY
   modifiers, window frames, LIMIT BY, column transformers, table functions in `FROM`, and large
   scalar/aggregate function families (quantile/uniq/topK/groupArray/sequence/encoding/IP/bitmap).

The highest-value work is therefore a **mix**: cheap generator emissions that light up the
existing 47 oracles for free, plus a focused set of new differential/metamorphic oracles on the
youngest 26.x execution paths — every one constrained by the soundness rules this fork has already
paid for in false positives (§Key Technical Decisions).

## Audit Method

- Inventoried all 47 oracle factory entries (`src/sqlancer/clickhouse/ClickHouseOracleFactory.java`)
  and the `run-sqlancer.sh` `ALL_ORACLES` list.
- Mapped emitted **types** (`ClickHouseSchema.pickScalarType`, `ClickHouseType`,
  `ClickHouseTypeParser`, `gen/ClickHouseColumnBuilder`), **DDL/engines**
  (`gen/ClickHouseTableGenerator`, `gen/ClickHouseAlterGenerator`,
  `gen/ClickHouseMutationGenerator`, `gen/ClickHouseDictionaryGenerator`,
  `gen/ClickHouseAccessDdlGenerator`, `gen/ClickHouseStatisticsGenerator`,
  `gen/ClickHouseInsertGenerator`), and **expressions/query structure**
  (`gen/ClickHouseExpressionGenerator`, `ast/`, `oracle/tlp/ClickHouseTLPBase`,
  `ClickHouseToStringVisitor`).
- Cross-checked against the three prior plans' done/pending units and the
  ClickHouse 26.1–26.6 changelogs (text-index GA, async-insert dedup default-on, JOIN reordering,
  materialized CTEs, JSON skip indexes, vector index, lazy materialization).

## Current Coverage Snapshot (what already exists — do not re-propose)

- **Oracles (47):** TLPWhere/Distinct/GroupBy/Aggregate/Having, NoREC, PQS, CERT, CODDTest,
  SEMR(+Multi), EET, SetOpTLP, CombinatorTLP, QccCache, QueryCache, SortedUnionLimitBy,
  SchemaRoundtrip, JoinAlgorithm, JoinReorder, JoinUseNulls, NaturalJoin, Cast, Parallelism,
  PartitionMirror, KeyCondition, TableFunctionIN, ViewEquivalence, FinalMerge,
  AggregateStateRoundtrip, MaterializedViewConsistency, ProjectionToggle, PatchPartConsistency,
  DictGetVsJoin, WindowEquivalence, DynamicSubcolumn, SubqueryMaterialize, MutationAnalyzer,
  ExtendedDatetime, StatsToggle, TopK, MaterializedCte, JsonSkipIndex, TextIndexLike/DirectRead/
  Container/Lifecycle.
- **Types emitted:** the integer/float/string/date/datetime primitives, FixedString, Decimal(≤38),
  DateTime64/Time64(0–6), Enum8/16, IPv4/IPv6/UUID, UInt128/256 (rare), SimpleAggregateFunction(1%),
  plus `Nullable`/`LowCardinality`/`Array` wrappers (`Array` gated off by default).
- **Engines:** MergeTree 78%, Replacing 8%, Summing 6%, Collapsing/VersionedCollapsing 4%,
  Aggregating 4% (schema-aware `pickEngine`).
- **DDL:** ORDER BY, PARTITION BY, PRIMARY KEY, SAMPLE BY, per-table SETTINGS, skip indexes
  (minmax/set/bloom_filter/ngrambf_v1/text), projections (count + order), DEFAULT/MATERIALIZED/
  ALIAS/CODEC/STATISTICS columns, ALTER ADD/DROP/MODIFY/RENAME/COMMENT COLUMN + ADD/MATERIALIZE/
  CLEAR/DROP INDEX + ADD PROJECTION, mutations (ALTER UPDATE/DELETE, lightweight UPDATE/DELETE),
  dictionaries (HASHED/FLAT/COMPLEX_KEY_HASHED), quotas/profiles/row-policies, INSERT VALUES.

## Scope Boundaries

- **Backlog, not implementation.** No code, no exact SQL strings beyond illustrative snippets.
- **Single-node only.** Distributed/replicated multi-host, Keeper clustering, Kafka/S3/Iceberg/
  Paimon/BigLake external engines, Arrow Flight, and WASM UDFs are out of scope (the fork runs one
  `clickhouse-server` container). `remote`/`cluster` against `127.0.0.1` is in scope (idea #14).
- **Reader-bounded.** Anything that materialises `Variant`/`Dynamic`/`JSON` into a projected
  column stays blocked until the client-v2 reader probe passes (idea #18 owns that gate).
- **Soundness over breadth.** An idea that cannot be made sound under §Key Technical Decisions is
  demoted or dropped, not shipped to drown runs in false positives.
- Already-shipped surfaces in the snapshot above are explicitly **not** re-proposed.

## Context & Research

### Relevant Code and Patterns

- Oracle wiring: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` + `run-sqlancer.sh`
  `ALL_ORACLES`. Every new oracle adds one enum entry + one list token.
- Type emission: `ClickHouseSchema.pickScalarType` (weights), `ClickHouseType` (constructors +
  `supportsLiteralEmission`), `ClickHouseTypeParser` (read-back).
- Single-snapshot equivalence pattern: `oracle/eet/ClickHouseEETOracle.assertSingleSnapshotEquivalent`
  (the template for all value-equivalence oracles — see §Key Technical Decisions).
- Differential toggle pattern: `oracle/projection/ClickHouseProjectionToggleOracle`,
  `oracle/stats/ClickHouseStatsToggleOracle`, `oracle/topk/ClickHouseTopKOracle` (run the same
  query twice under different `SETTINGS`, compare).
- Container ground-truth pattern: `oracle/textindex/ClickHouseTextIndexContainerOracle` (compare
  CH result against a Java-computed ground truth over the inserted fixture).
- Self-contained-fixture pattern: `oracle/view/ClickHouseMaterializedViewConsistencyOracle`,
  `oracle/mutate/ClickHouseMutationAnalyzerOracle` (build private suffixed tables per iteration).
- EET catalog: `oracle/eet/ClickHouseEETIdentities.java` (add roundtrip identity rows here).

### Institutional Learnings (provider CLAUDE.md / MEMORY — the false-positive ledger)

These are *paid-for* lessons; every oracle idea below is constrained by them:

- **Single-snapshot two-column compare** beats two separate queries — kills the async-mutation/
  merge race (`mutations_sync=2` on dev-vm masks it but the single-snapshot form is correct
  everywhere).
- **Float aggregates are order-sensitive** (`sum(Float)` partial-vs-full rounds differently, the
  #99109 class). Differential/aggregate oracles restrict to **exact-integer aggregates + non-float
  GROUP BY keys**.
- **TLP + GROUP BY** inflates UNION-ALL counts (same group key across partitions) → project the
  group keys themselves, not arbitrary fetch columns (`bbe5ed17`).
- **SEMI/ANTI eliminated-side column reads are ANY-like / non-deterministic by design**
  (#107073, closed) — any oracle touching SEMI/ANTI must restrict ON/projection to live columns.
- **Multi-branch numeric `multiIf`/union expressions settle on `Variant(...)`** which the reader
  can't decode → wrap in a concrete `CAST(... AS Nullable(Float64))`.
- **`count()` masks row-drop bugs** (SummingMergeTree #106125, TopK #107309) → measure row output,
  not count, for presence bugs.

### External References

- ClickHouse 26.1–26.4 release notes: Variant-in-all-functions (26.1), dedup default for all
  inserts incl. dependent MVs (26.2), text index GA (26.2), JOIN reordering for ANTI/SEMI/FULL +
  async-insert default-on + materialized CTEs (26.3), JSON skip indexes / Arrow Flight (26.4).
- Vector similarity (HNSW/usearch) index + lazy/late materialization of secondary indices (25.10+).
- `docs/QueryPlanGuidance.md` (in-repo) for planner-setting surface.

## Key Technical Decisions

- **D1 — Soundness checklist gates every oracle idea.** Before an oracle is wired into
  `ALL_ORACLES` it must pass: (a) single-snapshot or single-fixture comparison where two *forms*
  are compared; (b) exact-integer aggregates + non-float keys for any aggregate/decomposition
  identity; (c) no read of a SEMI/ANTI eliminated-side column; (d) deterministic ORDER BY
  tie-breaks before any positional row compare; (e) `toTypeName` probe → CAST-wrap any
  multi-branch/union expression that risks a `Variant` common type; (f) presence/row-drop bugs
  measured by row output, not `count()`. This checklist is repeated as a §cross-cutting section
  so each promoted plan inherits it.
- **D2 — Prefer "free coverage" emissions first.** Where an existing oracle (TLP/NoREC/SEMR/CERT/
  CODDTest/FinalMerge) picks up a surface the instant a type/engine/clause is emitted, the
  generator emission is higher ROI than a bespoke oracle and is sequenced earlier.
- **D3 — Every new oracle is gated by a default-on flag** in `ClickHouseOptions` (mirroring
  `--text-search-predicate-emission`, `--eet-26x-modes`) so a noisy oracle can be silenced without
  a rebuild, and known-open-bug oracles (like `TextIndexDirectRead`) can be deliberately left on.
- **D4 — Bug-class triage tags.** Each idea names whether it catches **crashes** (LOGICAL_ERROR/
  exceptions, globally tolerated today so only a dedicated oracle catches them) or **wrong
  results** (row-set / value divergence). Wrong-result oracles are weighted higher.
- **D5 — Version-pin and probe-before-emit.** Exact 26.x setting names / index syntaxes flagged
  "probe at implementation" must be confirmed against current `head` (CLAUDE.md always-pull rule)
  before wiring; record the resolved `SELECT version()` next to any new oracle's first validation.

## Prioritization Matrix

Priority = (bug-class weight: wrong-result > crash) × (path youth / known-bug-density) ×
(1 / soundness-risk) × (breadth of oracles lit for generator items). Effort: S ≤ ~1 day,
M ~2–4 days, L ~1 week+.

| # | Idea | Kind | Pri | Effort | Catches | Roadmap fold |
|---|------|------|-----|--------|---------|--------------|
| 1 | Engine-equivalence (Memory/Log/TinyLog mirror) | Oracle+Gen | P0 | M | wrong-result | — |
| 2 | PREWHERE ≡ WHERE + move_to_prewhere toggle | Oracle | P0 | S | wrong-result | — |
| 3 | read-in-order / aggregation-in-order toggle | Oracle | P0 | S | wrong-result | — |
| 4 | count-optimization / `_minmax_count_projection` toggle | Oracle | P0 | S | wrong-result | — |
| 5 | lazy/late-materialization toggle (26.x) | Oracle | P0 | S | wrong-result | — |
| 6 | GROUP BY ROLLUP/CUBE/GROUPING SETS/WITH TOTALS + decomp | Gen+Oracle | P0 | M | wrong-result | **5.2** |
| 7 | LIMIT/OFFSET/LIMIT BY/WITH TIES + ranking | Gen+Oracle | P1 | M | wrong-result | **5.1** |
| 8 | Window-frame clauses + frame-equivalence | Gen+Oracle | P1 | M | wrong-result | **5.4** |
| 9 | SEMI/ANY join emission + SEMI→IN/EXISTS rewrite | Gen+Oracle | P1 | M | wrong-result | **5.3** |
| 10 | Column transformers (* EXCEPT/REPLACE/APPLY, DISTINCT ON) | Gen+Oracle | P2 | S | wrong-result | — |
| 11 | CoalescingMergeTree + last-non-null FINAL | Gen+Oracle | P1 | M | wrong-result | — |
| 12 | Replacing/Versioned FINAL == argMax dedup ground truth | Oracle | P1 | S | wrong-result | — |
| 13 | Set/Join/Dictionary/Graphite special engines + joinGet/IN-set | Gen+Oracle | P2 | M | wrong-result | 1.4-fu |
| 14 | Table functions in FROM + remote/cluster single-node equiv | Gen+Oracle | P1 | M | wrong-result | — |
| 15 | Map/Tuple/Nested columns + container ground truth | Gen+Oracle | P1 | L | wrong+crash | — |
| 16 | Signed Int128/256 + Decimal256 + Interval columns | Gen | P1 | S | wrong-result | 1.2-adj |
| 17 | Geo types + geo-function metamorphic | Gen+Oracle | P2 | M | wrong+crash | — |
| 18 | Dynamic/Variant/JSON columns (reader-probe-gated) + subcolumn | Gen+Oracle | P2 | L | wrong-result | **4.0/4.1** |
| 19 | quantile-family consistency | Oracle | P1 | S | wrong-result | **3.1** |
| 20 | uniq-family / count(DISTINCT) exactness | Oracle | P1 | S | wrong-result | **3.1** |
| 21 | argMin/argMax & groupArray ground truth | Oracle | P1 | S | wrong-result | **3.1** |
| 22 | AggregateFunction columns + -State/-Merge roundtrip expansion | Gen+Oracle | P1 | M | wrong-result | **3.2** |
| 23 | Encoding/hash/IP/bitmap roundtrip EET identities | EET cat | P1 | S | wrong-result | — |
| 24 | sequence/funnel/retention deterministic-fixture | Oracle | P2 | M | wrong-result | — |
| 25 | Partition lifecycle (DETACH/ATTACH/DROP/REPLACE/MOVE) | Gen+Oracle | P1 | M | wrong-result | **2.3** |
| 26 | ALTER MODIFY {TTL,SETTING,CODEC,COLUMN} + MATERIALIZE COLUMN/TTL | Gen | P2 | M | wrong+crash | — |
| 27 | Table-level TTL (DELETE/WHERE/GROUP BY/RECOMPRESS) + determinism | Gen+Oracle | P2 | M | wrong-result | — |
| 28 | MATERIALIZED/ALIAS/DEFAULT col == defining-expr | Oracle | P1 | S | wrong-result | — |
| 29 | Async/sync INSERT dedup (26.2 default-on) equivalence | Oracle | P1 | M | wrong-result | — |
| 30 | Vector (HNSW) + tokenbf_v1 skip-index correctness | Gen+Oracle | P2 | L | wrong-result | — |

---

## The 30 Ideas

> Each block: **Goal · CH surface · Invariant/emission · Bug class · Soundness risk · Files ·
> Effort · Verification.** File paths are repo-relative. "Wire" everywhere means: add a
> `ClickHouseOptions` flag, an `ClickHouseOracleFactory` enum entry, and a `run-sqlancer.sh`
> `ALL_ORACLES` token.

### Theme A — Differential oracles on default-on execution paths (highest ROI)

These run *every* query through a planner optimization that ships on by default; a toggle
differential is the cheapest, highest-precision way to catch wrong results.

- [ ] **1. Engine-equivalence oracle** `[Oracle+Gen]` `[P0]`
  - **Goal:** Catch engine-specific *read-path* bugs by asserting two storage engines holding
    identical data answer an arbitrary read-only query identically.
  - **CH surface:** Memory, TinyLog, StripeLog, Log, and `MergeTree ORDER BY tuple()` — engines
    that preserve the exact inserted multiset (no dedup/collapse).
  - **Invariant/emission:** Per iteration build a private fixture, INSERT the same rows into a
    `MergeTree`-mirror and a `Memory`/`Log`-family table, run the same generated `SELECT` (no
    ORDER BY needed — compare as multisets) against both; assert equal.
  - **Bug class:** wrong-result — engine read path, type serialization, predicate pushdown
    differences. Nothing currently compares *across engines*.
  - **Soundness risk:** Medium. Must exclude dedup/collapse/aggregating engines (different
    visible cardinality by design); multiset (order-insensitive) compare; no `FINAL`.
  - **Files:** create `src/sqlancer/clickhouse/oracle/engineq/ClickHouseEngineEquivalenceOracle.java`;
    modify `gen/ClickHouseTableGenerator.java` (helper to emit a non-MergeTree mirror), `ClickHouseType`
    (Memory/Log engines aren't in `pickEngine` — add a mirror path, not the main pool), factory + run script.
  - **Effort:** M.
  - **Verification:** 1h dev-vm full-fleet, 0 cross-engine false positives; a deliberately
    injected read bug (e.g. force a wrong predicate) is caught.

- [ ] **2. PREWHERE ≡ WHERE equivalence oracle** `[Oracle]` `[P0]`
  - **Goal:** Moving a predicate into `PREWHERE`, and toggling `optimize_move_to_prewhere`, must
    never change the result set.
  - **CH surface:** `PREWHERE` (already emitted in `ClickHouseTLPBase`, but no equivalence check);
    `optimize_move_to_prewhere`, `move_all_conditions_to_prewhere`.
  - **Invariant/emission:** Three forms of one query — predicate in `WHERE`, predicate in
    `PREWHERE`, and `WHERE` with `optimize_move_to_prewhere=0` — all equal (multiset).
  - **Bug class:** wrong-result — PREWHERE column-read / lazy-read interaction (historically bug-dense).
  - **Soundness risk:** Low. Single deterministic predicate over physical columns; avoid ALIAS/
    MATERIALIZED columns in PREWHERE (not always allowed) and non-deterministic functions.
  - **Files:** create `src/sqlancer/clickhouse/oracle/prewhere/ClickHousePrewhereEquivalenceOracle.java`;
    factory + run script. Reuse `gen/ClickHouseExpressionGenerator.generatePredicate`.
  - **Effort:** S.
  - **Verification:** equal results across all three forms over a 1h run.

- [ ] **3. Read-in-order / aggregation-in-order toggle oracle** `[Oracle]` `[P0]`
  - **Goal:** Catch wrong results from the read-in-order and in-order-aggregation optimizations.
  - **CH surface:** `optimize_read_in_order`, `optimize_aggregation_in_order`,
    `read_in_order_use_buffering` — default-on planner paths keyed on ORDER BY / GROUP BY matching
    the sorting key.
  - **Invariant/emission:** Query with `ORDER BY <pk-prefix> LIMIT n` and `GROUP BY <pk-prefix>`
    run with each setting on vs off; assert identical (ORDER BY queries compared positionally with
    a deterministic full-key tiebreak; GROUP BY with integer aggregates only).
  - **Bug class:** wrong-result — premature stop / wrong granule order under LIMIT.
  - **Soundness risk:** Medium — must add a total-order tiebreak (append all key columns) so the
    positional compare is well-defined; integer aggregates only.
  - **Files:** create `oracle/readorder/ClickHouseReadInOrderToggleOracle.java`; factory + run script.
  - **Effort:** S.
  - **Verification:** clean 1h run; pairs with idea #4/#5 as a "planner-toggle" family.

- [ ] **4. Count-optimization / implicit-projection toggle oracle** `[Oracle]` `[P0]`
  - **Goal:** Harden the area that already produced a real bug here (#106573 implicit-projection
    GROUP BY collapse; #106125 SummingMergeTree count-masking).
  - **CH surface:** `optimize_trivial_count_query`, `optimize_use_implicit_projections`,
    `optimize_use_projections`, `_minmax_count_projection`.
  - **Invariant/emission:** `count()` / `count()` with WHERE on a pk/partition column, and a
    GROUP-BY-pk count, run with each setting on vs off; assert identical. Crucially also assert
    `count()` over WHERE equals `countIf(<pred>)` over no-WHERE (the row-drop cross-check).
  - **Bug class:** wrong-result — trivial-count and minmax-count projections returning wrong
    cardinality. Directly extends the `ProjectionToggle` win.
  - **Soundness risk:** Low (count is integer-exact). Avoid GROUP BY on float keys.
  - **Files:** create `oracle/countopt/ClickHouseCountOptimizationOracle.java`; factory + run script.
  - **Effort:** S.
  - **Verification:** would re-catch #106573 on a vulnerable head; clean otherwise.

- [ ] **5. Lazy/late-materialization toggle oracle** `[Oracle]` `[P0]`
  - **Goal:** Fuzz the youngest 26.x planner addition — lazy column materialization / late
    materialization of secondary indices.
  - **CH surface:** `query_plan_optimize_lazy_materialization` (and the late-materialization
    secondary-index settings) — **probe exact setting names at implementation (D5)**.
  - **Invariant/emission:** Query with `ORDER BY <expr> LIMIT n` and several heavy projected
    expressions (the shape that triggers lazy materialization) run with the setting on vs off;
    positional compare with deterministic tiebreak.
  - **Bug class:** wrong-result — a column materialized from the wrong row after a LIMIT-driven
    reorder.
  - **Soundness risk:** Medium — deterministic ORDER BY tiebreak required; CAST-wrap any
    multi-type projection (Variant trap).
  - **Files:** create `oracle/lazymat/ClickHouseLazyMaterializationToggleOracle.java`; factory + run.
  - **Effort:** S.
  - **Verification:** clean 1h run; setting-name probe confirmed against `head`.

### Theme B — Query-structure surface + decomposition oracles (folds roadmap 5.1/5.2/5.3/5.4)

- [ ] **6. GROUP BY modifiers + decomposition oracle** `[Gen+Oracle]` `[P0]` `(roadmap 5.2)`
  - **Goal:** Emit `WITH ROLLUP`/`WITH CUBE`/`GROUPING SETS`/`WITH TOTALS` and verify by
    decomposition.
  - **CH surface:** GROUP BY modifiers — entirely absent from `ClickHouseTLPBase`.
  - **Invariant/emission:** `ROLLUP` rows minus the super-aggregate (NULL-key) rows == plain
    GROUP BY; `CUBE` == `UNION` of the corresponding `GROUPING SETS`; `WITH TOTALS` total row ==
    grand aggregate. Identify super-aggregate rows via `GROUPING()`.
  - **Bug class:** wrong-result — wrong grouping-set enumeration / NULL-key handling.
  - **Soundness risk:** Medium — integer aggregates + non-float keys; use `GROUPING()` not
    `isNull` to find rollup rows (a real NULL key would otherwise be misclassified).
  - **Files:** modify `ast/ClickHouseSelect.java`, `ClickHouseToStringVisitor.java`,
    `gen/ClickHouseExpressionGenerator` (modifier emission); create
    `oracle/groupby/ClickHouseGroupingDecompositionOracle.java`; factory + run.
  - **Effort:** M.
  - **Verification:** decomposition holds over a 1h run; emission also feeds TLPGroupBy/Aggregate.

- [ ] **7. LIMIT/OFFSET/LIMIT BY/WITH TIES + ranking oracle** `[Gen+Oracle]` `[P1]` `(roadmap 5.1)`
  - **Goal:** Render the dead `limitClause`/`offsetClause` fields, add `LIMIT … WITH TIES` and
    `LIMIT n BY cols`, verify with metamorphic ranking invariants.
  - **CH surface:** main-query LIMIT/OFFSET (only in subqueries today), `WITH TIES`, `LIMIT BY`.
  - **Invariant/emission:** `LIMIT n WITH TIES` is a superset of `LIMIT n` and the extra rows tie
    on the ORDER BY key with row n; `LIMIT n BY k` returns ≤ n rows per distinct `k`;
    `LIMIT a, b` == `LIMIT b OFFSET a`.
  - **Bug class:** wrong-result — off-by-one / tie-boundary / per-group cap errors.
  - **Soundness risk:** Medium — needs a deterministic total ORDER BY; WITH-TIES superset check is
    order-insensitive on the surplus.
  - **Files:** modify `ast/ClickHouseSelect.java`, `ClickHouseToStringVisitor.java`,
    `oracle/tlp/ClickHouseTLPBase.java`; create `oracle/limit/ClickHouseLimitRankingOracle.java`;
    factory + run.
  - **Effort:** M.
  - **Verification:** invariants hold; LIMIT emission also enriches every TLP/NoREC query.

- [ ] **8. Window-frame clauses + frame-equivalence oracle** `[Gen+Oracle]` `[P1]` `(roadmap 5.4)`
  - **Goal:** Add `ROWS`/`RANGE`/`GROUPS BETWEEN` frames + lag/lead/nth_value offsets, verify
    against an array-based ground truth.
  - **CH surface:** window frames (only default frame today); `ClickHouseWindowFunction`.
  - **Invariant/emission:** `sum(x) OVER (PARTITION BY p ORDER BY k ROWS UNBOUNDED PRECEDING)`
    == per-partition prefix sum via `arrayCumSum(groupArray(x))`; `lag(x, m)` == shifted
    `groupArray`; default frame == explicit `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW`.
  - **Bug class:** wrong-result — frame-boundary off-by-one, peer-group handling in RANGE/GROUPS.
  - **Soundness risk:** Medium — integer `x` and a unique ORDER BY key within partition (peer ties
    make RANGE vs ROWS legitimately differ; pick a unique key for the ROWS↔array check).
  - **Files:** modify `ast/ClickHouseWindowFunction.java`, `ClickHouseToStringVisitor.java`,
    `gen/ClickHouseExpressionGenerator`; extend `oracle/window/ClickHouseWindowEquivalenceOracle.java`
    or create `oracle/window/ClickHouseWindowFrameOracle.java`; factory + run.
  - **Effort:** M.
  - **Verification:** frame identities hold over 1h.

- [ ] **9. SEMI/ANY join emission + SEMI→IN/EXISTS rewrite oracle** `[Gen+Oracle]` `[P1]` `(roadmap 5.3)`
  - **Goal:** Emit the deliberately-excluded `ANY`/`SEMI` join kinds in the general fleet and add
    a rewrite-equivalence oracle.
  - **CH surface:** `LEFT/RIGHT SEMI`, `LEFT/RIGHT ANY` joins (rendering exists; not generated in
    the default fleet path).
  - **Invariant/emission:** `a SEMI JOIN b ON a.k=b.k` projecting only `a`'s columns ==
    `SELECT a.* FROM a WHERE a.k IN (SELECT k FROM b)`; `LEFT ANY JOIN` cardinality == `LEFT JOIN`
    deduped to one match per left row (count invariant only).
  - **Bug class:** wrong-result — semijoin rewrite divergence.
  - **Soundness risk:** **High — must obey #107073.** Projection/ON may reference **only**
    preserved-side (live) columns; never an eliminated-side column. Reuse the
    `liveAliasesBeforeJoin` restriction from `JoinReorder`.
  - **Files:** modify `oracle/tlp/ClickHouseTLPBase.java` join picker / `gen` join emission;
    create `oracle/join/ClickHouseSemiJoinRewriteOracle.java`; factory + run.
  - **Effort:** M.
  - **Verification:** equal over 1h with the live-column restriction enforced.

- [ ] **10. Column transformers + DISTINCT ON equivalence** `[Gen+Oracle]` `[P2]`
  - **Goal:** Cover ClickHouse's `* EXCEPT/REPLACE/APPLY`, `COLUMNS('regex')`, and `DISTINCT ON`.
  - **CH surface:** SELECT-list transformers + `DISTINCT ON` — entirely absent.
  - **Invariant/emission:** `SELECT * EXCEPT(c)` == explicit column list without `c`;
    `SELECT * APPLY(toString)` == per-column `toString`; `SELECT COLUMNS('^c')` == matching
    explicit list; `DISTINCT ON (k) ...` cardinality == distinct `k` count.
  - **Bug class:** wrong-result — transformer expansion / regex-matcher bugs (mostly parser/analyzer).
  - **Soundness risk:** Low — pure syntactic-equivalence compares (multiset).
  - **Files:** modify `ClickHouseToStringVisitor.java`, `oracle/tlp/ClickHouseTLPBase.java`;
    create `oracle/transform/ClickHouseColumnTransformerOracle.java`; factory + run.
  - **Effort:** S.
  - **Verification:** equal over 1h.

### Theme C — Engines & merge-semantics ground truth

- [ ] **11. CoalescingMergeTree + last-non-null FINAL oracle** `[Gen+Oracle]` `[P1]`
  - **Goal:** Cover the young `CoalescingMergeTree` engine (keeps the last non-null value per
    column per sorting key on merge).
  - **CH surface:** `CoalescingMergeTree` — not in `pickEngine`.
  - **Invariant/emission:** emit it with `Nullable` value columns + a monotonically increasing
    sequence column; `SELECT * FINAL` == manual coalesce: per key, the
    `argMax(value, seq)` over the **non-null** values of each column.
  - **Bug class:** wrong-result — merge-time coalescing logic.
  - **Soundness risk:** Medium — needs an explicit insertion-order sequence column to define
    "last"; multiple inserts + `OPTIMIZE FINAL` to force the merge (see CLAUDE.md multi-INSERT
    history rule).
  - **Files:** modify `gen/ClickHouseTableGenerator.pickEngine` + `supportsFinal`; create
    `oracle/coalesce/ClickHouseCoalescingFinalOracle.java`; factory + run.
  - **Effort:** M.
  - **Verification:** ground truth matches FINAL over a 1h run; engine also rides FinalMerge/TLP.

- [ ] **12. Replacing/Versioned FINAL == argMax dedup ground-truth oracle** `[Oracle]` `[P1]`
  - **Goal:** Upgrade FINAL coverage from *self-consistency* (`FinalMerge`) to a *value-level
    ground truth*.
  - **CH surface:** `ReplacingMergeTree([ver[, is_deleted]])`, `VersionedCollapsingMergeTree` —
    already emitted.
  - **Invariant/emission:** `SELECT key, val FROM t FINAL` ==
    `SELECT key, argMax(val, ver) FROM t GROUP BY key` (Replacing); for `is_deleted`, filter the
    surviving max-version row where `is_deleted=0`. Versioned: net Sign-weighted survivors.
  - **Bug class:** wrong-result — FINAL dedup picking the wrong version (the #106125/#107309
    family lives near here).
  - **Soundness risk:** Medium — requires a unique sorting key + a strictly-increasing version
    column emitted by the generator; force a merge (multi-INSERT + OPTIMIZE FINAL).
  - **Files:** create `oracle/final_/ClickHouseReplacingDedupOracle.java`; small generator hint to
    guarantee a strictly-increasing version column when Replacing is picked; factory + run.
  - **Effort:** S.
  - **Verification:** ground truth == FINAL; complements `FinalMerge`'s structural check.

- [ ] **13. Special engines (Set/Join/Dictionary/Graphite) + joinGet/IN-set oracle** `[Gen+Oracle]` `[P2]` `(roadmap 1.4 follow-up)`
  - **Goal:** Cover the special-purpose engines and their access functions.
  - **CH surface:** `Set`, `Join`, `Dictionary` (as engine), `GraphiteMergeTree`.
  - **Invariant/emission:** `x IN set_engine_table` == `x IN (SELECT k FROM source)`;
    `joinGet(join_table, 'v', k)` == scalar `LEFT JOIN` lookup; extend `DictGetVsJoin` to
    `RANGE_HASHED`/`CACHE`/`IP_TRIE`/`POLYGON` layouts; `GraphiteMergeTree` rides FinalMerge/TLP.
  - **Bug class:** wrong-result — set membership / joinGet / dictionary layout lookups.
  - **Soundness risk:** Medium — Set/Join engines have strict creation constraints; build private
    fixtures; dictionary layouts each need a matching key shape.
  - **Files:** modify `gen/ClickHouseDictionaryGenerator.java` (layouts), `gen/ClickHouseTableGenerator`
    (Set/Join/Graphite mirror tables); create `oracle/specialengine/ClickHouseJoinGetSetOracle.java`;
    factory + run.
  - **Effort:** M.
  - **Verification:** lookups equal; new dictionary layouts pass DictGetVsJoin.

- [ ] **14. Table functions in FROM + remote/cluster single-node equivalence** `[Gen+Oracle]` `[P1]`
  - **Goal:** Emit `numbers()/values()/generateRandom()/format()` in `FROM`, and assert
    `remote('127.0.0.1', …)` / `cluster('default', …)` == the local table.
  - **CH surface:** table functions — absent from the FROM generator.
  - **Invariant/emission:** `SELECT … FROM remote('127.0.0.1', currentDatabase(), t)` ==
    `SELECT … FROM t` (multiset); `numbers(n)` == an equivalent `VALUES`/`range` set.
  - **Bug class:** wrong-result — distributed-read serialization / table-function expansion (caught
    even on a single node).
  - **Soundness risk:** Low for the remote↔local identity; `generateRandom` needs a fixed seed.
  - **Files:** modify `oracle/tlp/ClickHouseTLPBase.java` FROM builder, `ClickHouseToStringVisitor`;
    create `oracle/tablefn/ClickHouseRemoteLocalEquivalenceOracle.java` (sibling to existing
    `TableFunctionIN`); factory + run.
  - **Effort:** M.
  - **Verification:** remote==local over 1h.

### Theme D — Type / container surface (folds roadmap 4.0/4.1)

- [ ] **15. Map / Tuple / Nested column emission + container ground-truth oracle** `[Gen+Oracle]` `[P1]`
  - **Goal:** Light up the large `Map`/`Tuple`/`Nested` surface — modelled in `ClickHouseType` but
    never emitted (blocked only by missing **literal emission**, *not* the reader; verify reader
    renders Map/Tuple stably first).
  - **CH surface:** `Map(K,V)`, `Tuple(...)`, `Nested(...)`.
  - **Invariant/emission:** add literal emission (`map('a',1,'b',2)`, `(1,'x')`, Nested arrays) and
    a `pickScalarType` path; container oracle compares `mapKeys/mapValues/mapContains/length`,
    `tupleElement/untuple/.N`, and Nested `arrayJoin` against a Java-computed ground truth over the
    inserted fixture (mirrors `TextIndexContainer`).
  - **Bug class:** wrong-result (map/tuple function bugs) + crash (serialization edge cases).
  - **Soundness risk:** Medium — **probe the client-v2 reader on Map/Tuple/Nested first** (D5); keep
    keys in the Map-allowed key-type set; no nested `Variant`.
  - **Files:** modify `ClickHouseSchema.pickScalarType`, `ClickHouseType` (literal emission),
    `gen/ClickHouseExpressionGenerator` (map/tuple functions); create
    `oracle/container/ClickHouseMapTupleContainerOracle.java`; factory + run.
  - **Effort:** L.
  - **Verification:** reader probe passes; ground truth matches; surface also feeds TLP/NoREC.

- [ ] **16. Signed Int128/256 + Decimal256 + Interval columns** `[Gen]` `[P1]` `(roadmap 1.2-adjacent)`
  - **Goal:** Free coverage for arithmetic/CERT/EET/KeyCondition oracles on the widest numeric
    types and on Interval.
  - **CH surface:** `Int128`/`Int256` (only `UInt128/256` rare today), `Decimal256` (only ≤38
    today), `Interval*` (modelled, never picked).
  - **Invariant/emission:** add small `pickScalarType` weights for signed wide ints, `Decimal(P>38)`,
    and a couple of Interval kinds. No new oracle — existing arithmetic/comparison oracles exercise
    overflow/precision edge cases for free.
  - **Bug class:** wrong-result — wide-int overflow, decimal precision-upgrade, interval arithmetic.
  - **Soundness risk:** Low — these read as text; CERT already tolerates overflow errors. Confirm
    `getString` renders Int256/Decimal256 via BigInteger/BigDecimal (it does for UInt256 today).
  - **Files:** modify `ClickHouseSchema.pickScalarType`; small constant/literal additions in
    `gen/ClickHouseExpressionGenerator`.
  - **Effort:** S.
  - **Verification:** types appear in schemas; no new reader exceptions over a 1h run.

- [ ] **17. Geo types + geo-function metamorphic oracle** `[Gen+Oracle]` `[P2]`
  - **Goal:** Cover `Point/Ring/Polygon/MultiPolygon` columns and geo functions (modelled, never
    emitted).
  - **CH surface:** geo types + `pointInPolygon`, `polygonArea*`, `greatCircleDistance`,
    `polygonsIntersection*`.
  - **Invariant/emission:** emit geo columns with literal coordinate arrays; metamorphic identities:
    every polygon vertex is `pointInPolygon` true; `polygonArea ≥ 0`; `greatCircleDistance(p,p)=0`;
    `polygonsIntersection(a,a)` area == `polygonArea(a)`.
  - **Bug class:** wrong-result (geometric predicates) + crash (degenerate polygons).
  - **Soundness risk:** Medium — geo math is float (tolerance compares only); use simple integral
    coordinates; avoid self-intersecting polygons for area identities.
  - **Files:** modify `ClickHouseSchema.pickScalarType`, `ClickHouseType` (geo literal emission),
    `gen/ClickHouseGeoFunction.java`; create `oracle/geo/ClickHouseGeoMetamorphicOracle.java`;
    factory + run.
  - **Effort:** M.
  - **Verification:** identities hold within tolerance over 1h.

- [ ] **18. Dynamic / Variant / JSON column emission (reader-probe-gated) + subcolumn roundtrip** `[Gen+Oracle]` `[P2]` `(roadmap 4.0/4.1)`
  - **Goal:** Unblock the polymorphic-type column surface that the reader currently can't decode.
  - **CH surface:** `Dynamic`, `Variant`, `JSON` (26.1 "Variant in all functions"; 26.4 JSON skip
    indexes already covered for predicates, not for projected columns).
  - **Invariant/emission:** **first**, the roadmap-4.0 probe: confirm
    `ClickHouseRowBinaryParser.getString` renders Dynamic/Variant/JSON stably (today it throws
    `IndexOutOfBoundsException` — this is the gate). If/when it passes (reader fix or a text-CAST
    read path), enable emission and extend `DynamicSubcolumnOracle` to Variant/JSON path access:
    `CAST(v AS T)` roundtrip and `v.subcol` == the inserted value.
  - **Bug class:** wrong-result — subcolumn extraction / type unification.
  - **Soundness risk:** **High / blocked.** Do not emit into projections until the probe passes;
    until then, only WHERE-side Variant (already shipped via `--variant-where-emission`).
  - **Files:** modify `transport/ClickHouseRowBinaryParser` (or add a text-CAST read path),
    `ClickHouseSchema.pickScalarType`; extend `oracle/dynamicsub/ClickHouseDynamicSubcolumnOracle.java`.
  - **Effort:** L.
  - **Verification:** probe report attached; roundtrip holds once unblocked.

### Theme E — Aggregate & function-family catalog (folds roadmap 3.1/3.2)

- [ ] **19. Quantile-family consistency oracle** `[Oracle]` `[P1]` `(roadmap 3.1)`
  - **Goal:** Grow beyond `quantileExact` and verify the family is internally consistent.
  - **CH surface:** `quantile*`, `quantiles`, `median*`.
  - **Invariant/emission:** `quantileExact(0.5)(x)` == `median(x)`; `quantiles(p1,p2)(x)[1]`
    == `quantile(p1)(x)`; monotonic in level (`quantileExact(0.1) ≤ quantileExact(0.9)`);
    `quantileExactLow ≤ quantileExact ≤ quantileExactHigh`.
  - **Bug class:** wrong-result — quantile interpolation / level handling.
  - **Soundness risk:** Low — integer column `x` keeps `Exact` variants exact; single-snapshot
    multi-column compare.
  - **Files:** modify `ast/ClickHouseAggregate.java` (enum), `gen/ClickHouseExpressionGenerator`;
    create `oracle/aggfamily/ClickHouseQuantileConsistencyOracle.java`; factory + run.
  - **Effort:** S.
  - **Verification:** identities hold over 1h.

- [ ] **20. uniq-family / count(DISTINCT) exactness oracle** `[Oracle]` `[P1]` `(roadmap 3.1)`
  - **Goal:** Verify the exact members of the cardinality family agree.
  - **CH surface:** `uniqExact`, `count(DISTINCT …)`, `groupUniqArray`.
  - **Invariant/emission:** `uniqExact(c)` == `count(DISTINCT c)` == `length(groupUniqArray(c))`;
    `uniqExact(a, b)` == `count(DISTINCT (a, b))`.
  - **Bug class:** wrong-result — distinct-counting path divergence (the 26.6 DISTINCT-NaN
    coalescence family lives here — use integer columns to avoid the *known* NaN behavior).
  - **Soundness risk:** Low — exact only; integer/string columns (no float → no NaN coalescence
    confound).
  - **Files:** create `oracle/aggfamily/ClickHouseUniqExactnessOracle.java`; factory + run.
  - **Effort:** S.
  - **Verification:** equal over 1h.

- [ ] **21. argMin/argMax & groupArray ground-truth oracle** `[Oracle]` `[P1]` `(roadmap 3.1)`
  - **Goal:** Tie order-sensitive aggregates to an explicit ordering ground truth.
  - **CH surface:** `argMin`/`argMax`, `groupArray`, `groupArraySorted`, `any`/`anyLast`.
  - **Invariant/emission:** `arraySort(groupArray(c))` == the sorted multiset of `c`;
    `argMax(v, k)` is **value-equal to** `v` from the row with the unique max `k`
    (require a unique `k` to avoid the legitimate ANY tie); `groupArraySorted(n)(c)` == first `n`
    of `arraySort(groupArray(c))`.
  - **Bug class:** wrong-result — arg-extremum / group-array ordering.
  - **Soundness risk:** Medium — `argMax` tie is ANY-like, so require a unique extremal key in the
    fixture (or compare only when the max is unique).
  - **Files:** create `oracle/aggfamily/ClickHouseArgExtremumOracle.java`; factory + run.
  - **Effort:** S.
  - **Verification:** ground truth matches over 1h.

- [ ] **22. AggregateFunction columns + -State/-Merge roundtrip expansion** `[Gen+Oracle]` `[P1]` `(roadmap 3.2)`
  - **Goal:** Emit full `AggregateFunction(name, T)` columns (via `-State` inserts) and broaden the
    state-roundtrip oracle beyond `sum`.
  - **CH surface:** `AggregateFunction(...)` + `AggregatingMergeTree`; `-State`/`-Merge`/`-Merge`
    combinators.
  - **Invariant/emission:** insert `<agg>State(x)` into an `AggregateFunction` column; assert
    `finalizeAggregation(<agg>Merge(state))` == the direct `<agg>(x)` over the same rows, for the
    **deterministic/exact** aggregates: `sum`(int), `min`, `max`, `uniqExact`, `quantileExact`,
    `groupArray`(after sort). Extends `AggregateStateRoundtripOracle`.
  - **Bug class:** wrong-result — state serialization / merge across parts (version-sensitive).
  - **Soundness risk:** Medium — exact aggregates only; force multi-part merge; version-pin (state
    bytes are unstable across CH versions — only compare within one server).
  - **Files:** modify `ClickHouseSchema.pickScalarType` (AggregateFunction column),
    `gen/ClickHouseInsertGenerator` (-State literal insert); extend
    `oracle/aggstate/ClickHouseAggregateStateRoundtripOracle.java`; factory unchanged.
  - **Effort:** M.
  - **Verification:** roundtrip holds for each added aggregate over 1h.

- [ ] **23. Encoding / hash / IP / bitmap roundtrip EET identities** `[EET catalog]` `[P1]`
  - **Goal:** Cheap, exact, high-precision identities over big untested scalar families.
  - **CH surface:** `hex/unhex`, `base64Encode/Decode`, `bin/unbin`, `IPv4NumToString/StringToNum`,
    `IPv6…`, `bitmapBuild/bitmapToArray`, `bitAnd/Or/Xor/Shift`.
  - **Invariant/emission:** add EET-catalog rows: `unhex(hex(x)) == x`,
    `base64Decode(base64Encode(s)) == s`, `IPv4NumToString(toUInt32(ipv4)) == toString(ipv4)`-class,
    `bitmapToArray(bitmapBuild(arr)) == arraySort(arrayDistinct(arr))`,
    `reinterpretAsUInt32(reinterpretAsString(u)) == u`.
  - **Bug class:** wrong-result — codec/encoding/bitmap roundtrip bugs.
  - **Soundness risk:** Low — exact integer/string roundtrips; single-snapshot two-column compare.
  - **Files:** modify `oracle/eet/ClickHouseEETIdentities.java`, `gen/ClickHouseExpressionGenerator`
    (emit these functions); `oracle/eet/ClickHouseEETOracle.java` if a new mode tag is needed.
  - **Effort:** S.
  - **Verification:** identities hold over 1h; EET is already a proven low-noise oracle.

- [ ] **24. Sequence / funnel / retention deterministic-fixture oracle** `[Oracle]` `[P2]`
  - **Goal:** Cover the parametric event-sequence aggregates that have no current coverage.
  - **CH surface:** `sequenceMatch`, `sequenceCount`, `windowFunnel`, `retention`.
  - **Invariant/emission:** build a *tiny deterministic* per-iteration fixture (a handful of
    (timestamp, event) rows whose funnel/sequence answer is computable in Java) and assert CH ==
    Java ground truth; plus the relations `windowFunnel` is monotone non-increasing in step,
    `retention[0] >= retention[i]`.
  - **Bug class:** wrong-result — pattern/window matching.
  - **Soundness risk:** Medium — these are highly parameter-sensitive; ground-truth only the
    constrained fixtures, do not fuzz arbitrary patterns over arbitrary data.
  - **Files:** create `oracle/sequence/ClickHouseSequenceFunnelOracle.java`; factory + run.
  - **Effort:** M.
  - **Verification:** ground truth matches over 1h on the constrained fixtures.

### Theme F — DDL lifecycle (folds roadmap 2.3)

- [ ] **25. Partition lifecycle oracle (DETACH/ATTACH/DROP/REPLACE/MOVE)** `[Gen+Oracle]` `[P1]` `(roadmap 2.3)`
  - **Goal:** Cover partition-level DDL with clean row-set invariants.
  - **CH surface:** `ALTER TABLE … DETACH/ATTACH/DROP/REPLACE/MOVE PARTITION`, `FREEZE`.
  - **Invariant/emission:** on a partitioned fixture, `DETACH PARTITION p; ATTACH PARTITION p`
    == row-set identity (multiset before == after); `DROP PARTITION p` removes exactly the rows
    with that partition value (`count after == count where partexpr != p`); `REPLACE PARTITION`
    from an identical copy == identity; `MOVE PARTITION t→t2` conserves total rows.
  - **Bug class:** wrong-result — partition metadata / attach reload.
  - **Soundness risk:** Low-Medium — needs a real `PARTITION BY` (already emitted ~50%); guard
    against background merges renaming parts mid-check (`SYSTEM STOP MERGES`).
  - **Files:** modify `gen/ClickHouseAlterGenerator.java` (partition ops); create
    `oracle/partlifecycle/ClickHousePartitionLifecycleOracle.java`; factory + run.
  - **Effort:** M.
  - **Verification:** identities hold over 1h.

- [ ] **26. ALTER MODIFY {TTL, SETTING, CODEC, COLUMN-type} + MATERIALIZE COLUMN/TTL** `[Gen]` `[P2]`
  - **Goal:** Reach the `ALTER MODIFY` forms and the `MATERIALIZE` mutations the generator never
    emits — free coverage for every oracle + the bug-dense column-rewrite path.
  - **CH surface:** `MODIFY TTL / SETTING / COLUMN <type> / COLUMN CODEC`,
    `MATERIALIZE COLUMN / TTL / INDEX`.
  - **Invariant/emission:** no new oracle — emit these as fleet actions; `MODIFY COLUMN` type-change
    triggers a data rewrite that existing TLP/NoREC reads then validate; `MATERIALIZE COLUMN`
    pairs with idea #28's stored-vs-computed oracle.
  - **Bug class:** wrong-result + crash — column data rewrite / TTL re-evaluation.
  - **Soundness risk:** Low — these are schema actions; tolerate the expected
    `ALTER`-incompatibility errors in `ClickHouseErrors`.
  - **Files:** modify `gen/ClickHouseAlterGenerator.java`; add tolerated errors in
    `ClickHouseErrors.java`.
  - **Effort:** M.
  - **Verification:** no new false positives; column-type-change paths exercised.

- [ ] **27. Table-level TTL + TTL-determinism oracle** `[Gen+Oracle]` `[P2]`
  - **Goal:** Cover table/column TTL (`DELETE` / `WHERE` / `GROUP BY` / `RECOMPRESS`), entirely
    absent today.
  - **CH surface:** `TTL <expr> DELETE [WHERE …]`, `TTL <expr> GROUP BY … SET …`, `RECOMPRESS`.
  - **Invariant/emission:** with a **fixed-`now` column** (not wall clock), after
    `OPTIMIZE FINAL` + `materialize_ttl_after_modify`, surviving rows ==
    `SELECT … WHERE NOT(<ttl-expired-predicate>)`; `TTL … GROUP BY k` survivors == manual
    `GROUP BY k` aggregation of the expired rows.
  - **Bug class:** wrong-result — TTL row removal / rollup.
  - **Soundness risk:** **High if time-based** — pin "now" to a constant column so expiry is
    deterministic across the two reads; otherwise wall-clock advance creates false positives.
  - **Files:** modify `gen/ClickHouseTableGenerator.java` (TTL clause), `gen/ClickHouseColumnBuilder`
    (column TTL); create `oracle/ttl/ClickHouseTtlDeterminismOracle.java`; factory + run.
  - **Effort:** M.
  - **Verification:** survivors == predicate complement over 1h with pinned time.

- [ ] **28. MATERIALIZED / ALIAS / DEFAULT column == defining-expression oracle** `[Oracle]` `[P1]`
  - **Goal:** Verify the *stored/aliased* value equals re-computing its defining expression — the
    generator already emits these columns but nothing checks them.
  - **CH surface:** `DEFAULT`/`MATERIALIZED`/`ALIAS` columns; `ALTER … MATERIALIZE COLUMN`.
  - **Invariant/emission:** `SELECT mat_col, (<defining expr>) FROM t` — the two columns equal
    row-positionally (single snapshot); after `ALTER TABLE t MATERIALIZE COLUMN mat_col`, the
    stored value still equals the expression. For `DEFAULT`, an INSERT omitting the column yields
    the default expression's value.
  - **Bug class:** wrong-result — materialized-value drift, the PR#98884 `MATERIALIZE COLUMN`
    analyzer surface (complements `MutationAnalyzer`).
  - **Soundness risk:** Low — single-snapshot two-column compare; CAST-wrap if the defining
    expression risks a Variant common type.
  - **Files:** create `oracle/matcol/ClickHouseMaterializedColumnOracle.java`; factory + run.
  - **Effort:** S.
  - **Verification:** stored == recomputed over 1h, incl. post-MATERIALIZE.

- [ ] **29. Async / sync INSERT dedup equivalence oracle** `[Oracle]` `[P1]`
  - **Goal:** Fuzz the 26.2 default-on insert deduplication (now uniform across sync **and** async
    inserts, and dependent MVs).
  - **CH surface:** `async_insert`, `insert_deduplicate` (default-on 26.2), `*_deduplication_window`.
  - **Invariant/emission:** inserting the *same block twice* (both sync, both async, and one of
    each) leaves the table row-count and contents **unchanged** vs a single insert; with a
    dependent MV, the MV is deduped identically. Contrast with a distinct block (must add rows).
  - **Bug class:** wrong-result — dedup window / async-flush dedup / MV-dedup divergence (brand-new
    default-on behavior).
  - **Soundness risk:** Medium — async inserts need a flush/await (`wait_for_async_insert=1`);
    dedup window must be large enough; use a private fixture per iteration.
  - **Files:** create `oracle/insertdedup/ClickHouseInsertDedupOracle.java`; factory + run; possibly
    a flag for async vs sync arm.
  - **Effort:** M.
  - **Verification:** double-insert == single-insert; distinct block grows the table; clean over 1h.

### Theme G — Advanced index correctness

- [ ] **30. Vector (HNSW) + tokenbf_v1 skip-index correctness oracles** `[Gen+Oracle]` `[P2]`
  - **Goal:** Cover the two skip-index families the generator never emits: the young
    `vector_similarity` (HNSW/usearch) index and the older `tokenbf_v1` token bloom filter.
  - **CH surface:** `INDEX … TYPE vector_similarity('hnsw', <metric>, <dim>)` on `Array(Float32)`;
    `INDEX … TYPE tokenbf_v1(…)` on `String`. **Probe exact syntax/args against `head` (D5).**
  - **Invariant/emission:**
    - *tokenbf (sound, exact):* bloom filters never produce false negatives, so
      `hasToken(s,t)` / `s = c` / `s IN (…)` with the index **==** the same query with
      `use_skip_indexes=0` (mirrors the `TextIndex*` oracles for the bloom index).
    - *vector (approximate — containment only):* with a high-recall setting, top-1 of
      `ORDER BY L2Distance(v, q) LIMIT 1` via the index == exact brute-force top-1
      (`use_skip_indexes=0`); for k>1, assert the index result set's max distance ≤ the exact
      k-th distance × a margin (subset/recall check, **never** exact-equality — ANN is
      approximate by design).
  - **Bug class:** wrong-result — bloom false-negative (a real bug if it ever happens) / vector
    pruning dropping the true nearest neighbor (issues cited in research).
  - **Soundness risk:** **High for vector** — ANN is approximate; only the top-1-exact-recall and
    containment invariants are sound. tokenbf is exact and low-risk.
  - **Files:** modify `gen/ClickHouseTableGenerator.java` (index emission), `ClickHouseSchema`
    (Array(Float32) vector column); create `oracle/vecindex/ClickHouseVectorIndexRecallOracle.java`
    and `oracle/tokenbf/ClickHouseTokenBfOracle.java`; factory + run.
  - **Effort:** L.
  - **Verification:** tokenbf index==scan exact; vector top-1 recall holds with high-recall settings.

---

## Recommended Sequencing (phased)

Ordered by ROI and dependency. Each phase is independently shippable.

### Phase 1 — Cheap default-on differentials + free-coverage emissions (P0, fast wins)
- Oracles needing no generator work: **2** (PREWHERE), **3** (read-in-order), **4** (count-opt),
  **5** (lazy-mat), **12** (Replacing dedup), **19/20/21** (aggregate ground truths), **23** (EET
  encoding/IP/bitmap), **28** (materialized-column).
- Generator-only free coverage: **16** (wide ints / Decimal256 / Interval).
- Rationale: all are S effort, low soundness risk, and several harden areas with *already-found*
  bugs (#106573, #106125).

### Phase 2 — Query-structure surface (folds roadmap 5.1/5.2/5.3/5.4)
- **6** (GROUP BY modifiers), **7** (LIMIT/WITH TIES), **8** (window frames), **9** (SEMI/ANY),
  **10** (column transformers). These also enrich every TLP/NoREC query for free.

### Phase 3 — Engines & merge semantics
- **1** (engine-equivalence), **11** (CoalescingMergeTree), **13** (special engines),
  **14** (remote/cluster + table functions).

### Phase 4 — Type/container + aggregate-state expansion (folds roadmap 3.2/4.x)
- **15** (Map/Tuple/Nested — reader-probe first), **22** (AggregateFunction columns),
  **17** (geo), **18** (Dynamic/Variant/JSON — gated on the reader probe).

### Phase 5 — DDL lifecycle + advanced indexes
- **25** (partition lifecycle), **26** (ALTER MODIFY), **27** (table TTL), **29** (insert dedup),
  **24** (sequence/funnel), **30** (vector + tokenbf).

## Cross-Cutting Soundness Checklist (every new oracle must pass before wiring)

This is the false-positive ledger distilled to a gate (see §Institutional Learnings). A promoted
plan inherits it verbatim:

1. **Single-snapshot / single-fixture compare.** Compare two *forms* as two columns of one query
   (or one fixture read twice atomically), never two independent statements — kills the
   mutation/merge race.
2. **Exact-integer aggregates + non-float GROUP BY keys** for any aggregate or decomposition
   identity — floats round order-dependently (#99109 class).
3. **No SEMI/ANTI eliminated-side column reads** — they are ANY-like / non-deterministic by design
   (#107073). Restrict ON/projection to live columns.
4. **Deterministic total ORDER BY tiebreak** before any positional row compare.
5. **`toTypeName` probe → CAST-wrap** any multi-branch/union numeric expression that risks a
   `Variant(...)` common type the reader can't decode.
6. **Measure presence/row-drop by row output, not `count()`** (#106125, #107309).
7. **Force part history when the bug needs a merge** — multi-INSERT + `OPTIMIZE FINAL` /
   `SYSTEM STOP MERGES` to pin topology (the `database10` / #106419 lesson).
8. **Pin time** (constant "now" column) for any TTL/date-relative invariant.
9. **Default-on flag + version-pin** the oracle; record resolved `SELECT version()` at first
   validation; re-confirm probed setting/syntax names against current `head`.
10. **Validate on the dev-vm**, 1h full-fleet, target **0 false positives** before merge (per-oracle
    query-validity audit via `.claude/collect-oracle-validity.sh`).

## System-Wide Impact

- **Oracle registry:** each oracle adds one `ClickHouseOracleFactory` enum entry + one
  `run-sqlancer.sh` `ALL_ORACLES` token + one `ClickHouseOptions` flag. The list is approaching
  ~50+ — consider grouping flags (e.g. `--planner-toggle-oracles`) if it sprawls.
- **Generator emission blast radius:** new types/engines/clauses flow into *every* oracle, so each
  emission must be guarded (so a new type can't crash an unrelated oracle's read path) and probed
  through the client-v2 reader first (Map/Tuple/geo/wide-int/AggregateFunction).
- **Error tolerance:** new DDL/mutation/engine actions will surface new expected errors — extend
  `ClickHouseErrors` narrowly (per-surface, not global) to avoid masking real bugs (the
  PatchPart/Mutation precedent).
- **Run cost:** more oracles per iteration = lower per-oracle throughput. Keep heavy oracles
  (vector, container, AggregateFunction-state) at lower selection weight or behind their own flag.
- **Unchanged invariants:** the wire transport stays single (`ClickHouseClientV2Transport`); the
  `max_result_rows` universal cap and global error tolerances are untouched; `TextIndexDirectRead`
  stays deliberately-firing until #107186 is fixed.

## Risks & Dependencies

| Risk | Mitigation |
|------|------------|
| New oracle floods runs with false positives | Soundness checklist gate + 1h dev-vm validation at 0 FP before wiring into `ALL_ORACLES`; default-on flag to silence fast (D3). |
| Reader can't decode a newly-emitted type (Map/Tuple/geo/Variant) → worker death | Probe `getString` rendering **before** emission (idea #18/#15 own the probe); keep projection emission gated. |
| Approximate features (vector ANN, SAMPLE) tempt exact-equality oracles | Only ship containment/recall/superset invariants for approximate paths (idea #30). |
| Probed 26.x setting/syntax names drift on `head` | D5 always-pull + re-probe at implementation; mark probed names explicitly. |
| Engine-equivalence picks a dedup/collapse engine and reports a false mismatch | Restrict the mirror set to exact-multiset engines (Memory/Log/TinyLog/StripeLog + `MergeTree ORDER BY tuple()`). |
| Oracle-list sprawl raises maintenance + lowers throughput | Phase the rollout; weight heavy oracles low; consider flag-grouping. |

## Open Questions

### Resolved During Planning
- **Composition:** balanced mix of oracles + generators + features (user-selected).
- **Roadmap overlap:** fold all pending roadmap units into one unified backlog (user-selected) —
  done: 5.1→#7, 5.2→#6, 5.3→#9, 5.4→#8, 3.1→#19/20/21, 3.2→#22, 2.3→#25, 4.0/4.1→#18, 1.4-fu→#13.
- **Artifact shape:** prioritized 30-item catalog (each promotable to a full `ce:plan`), not 30
  atomic implementation units.

### Deferred to Implementation (probe against current `head`)
- Exact setting names for lazy/late materialization (#5) and the count-optimization family (#4).
- Exact `vector_similarity` and `tokenbf_v1` index argument grammar (#30); current HNSW recall
  settings (`ef`, quantization) needed for the top-1 exactness arm.
- Whether the client-v2 reader renders `Map`/`Tuple`/`Nested`/geo/`Int256`/`Decimal256`/
  `AggregateFunction` stably (#15/#16/#17/#22) or needs a text-CAST read path (#18).
- `CoalescingMergeTree` exact merge semantics for the last-non-null tiebreak when two inserts share
  a sequence value (#11).
- Which special engines (Set/Join/Dictionary/Graphite) are creatable under the run's settings (#13).

## Sources & References

- Current code: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java`,
  `ClickHouseSchema.java` (`pickScalarType`), `ClickHouseType.java`,
  `gen/ClickHouseTableGenerator.java`, `gen/ClickHouseExpressionGenerator.java`,
  `gen/ClickHouseAlterGenerator.java`, `oracle/tlp/ClickHouseTLPBase.java`,
  `oracle/eet/ClickHouseEETOracle.java`, `ClickHouseToStringVisitor.java`, `run-sqlancer.sh`.
- Prior plans: `docs/plans/2026-05-29-001-feat-clickhouse-coverage-expansion-roadmap-plan.md`
  (pending units folded in), `docs/plans/2026-06-10-002-feat-clickhouse-26x-feature-coverage-plan.md`,
  `docs/plans/2026-06-10-001-feat-mutation-analyzer-coverage-plan.md`.
- Provider operational notes & false-positive ledger: `.claude/CLAUDE.md`, auto-memory `MEMORY.md`.
- ClickHouse releases: [26.3](https://clickhouse.com/blog/clickhouse-release-26-03),
  [26.1](https://clickhouse.com/blog/clickhouse-release-26-01),
  [Changelog 2026](https://clickhouse.com/docs/whats-new/changelog),
  [Vector search docs](https://clickhouse.com/docs/engines/table-engines/mergetree-family/annindexes),
  [Late materialization of secondary indices](https://clickhouse.com/videos/late-materialization-secondary-indices).
- Filed-bug context (areas these ideas harden): #106573 (implicit-projection GROUP BY), #106125
  (SummingMergeTree FINAL row-drop), #107309 (TopK skip-index), #107073 (SEMI/ANTI non-determinism),
  #107186 (text-index direct read).
