---
title: "feat: ClickHouse 26.1–26.5 feature-coverage expansion (text index, JSON skip indexes, join reorder, top-k, intervals, NATURAL JOIN, mat-CTE, stats, new functions)"
type: feat
status: active
date: 2026-06-10
---

# ClickHouse 26.x feature-coverage expansion

## Overview

Add generator emission and sound oracles for ten ClickHouse 26.1–26.5 feature areas that the
fuzzer does not exercise today. The work follows the repo's established pattern: free-coverage
generator emissions where TLP/NoREC/SEMR pick the surface up automatically, plus narrow
differential/metamorphic oracles (ProjectionToggle / KeyCondition / MutationAnalyzer templates)
where the bug class needs a dedicated invariant.

This plan is the gap-analysis companion to the 2026-05-29 coverage roadmap
(`docs/plans/2026-05-29-001-feat-clickhouse-coverage-expansion-roadmap-plan.md`): it covers the
26.x-release-driven surfaces that roadmap did not, and explicitly defers to the roadmap's pending
units where they already own a surface (polymorphic-type probe 4.0/4.1, SEMI→IN/EXISTS 5.3).

## Problem Frame

ClickHouse 26.1–26.5 shipped a cluster of new query-execution surfaces — text-index-accelerated
LIKE, JSON skip indexes, join reordering for ANTI/SEMI/FULL, top-k dynamic filtering on by
default, compound INTERVAL literals, NATURAL JOIN, experimental materialized CTEs,
statistics-driven planning — all of which are young code with high bug density and all of which
produce *wrong results* (not crashes) when they fail. The fuzzer's current generator never emits
these constructs, so the entire surface is dark. Each item maps to a concrete wrong-result bug
class (index drops matching rows, false-negative granule skipping, reorder changes the result
set, missing/extra top-N rows, double CTE evaluation, stats wrongly prune data).

### Corrections to the request's premises (verified against changelogs / head `Settings.cpp`, 2026-06-10)

These verified facts override the version/name claims in the original request:

1. **Text index**: type name is `text` (not `inverted`), syntax
   `INDEX i (col) TYPE text(tokenizer = 'splitByNonAlpha')`. LIKE acceleration landed 26.4
   (PR #98149, `splitByNonAlpha` tokenizer only; 26.5 extended to `array`). Gate
   `enable_full_text_index` defaults to **true** on head — no experimental flag needed.
   Controlling settings: `use_text_index_like_evaluation_by_dictionary_scan` (default true),
   `text_index_like_min_pattern_length` (default **4** — shorter patterns bypass the index).
2. **`force_data_skipping_indices`** does not "force" silently — it **fails the query with
   `INDEX_NOT_USED`** if a named index didn't participate. Use it as a vacuity guard, and use
   `ignore_data_skipping_indices` for the index-off differential arm (no DDL churn).
3. **JSON skip indexes** (26.4, PR #98886): bloom_filter / tokenbf_v1 / ngrambf_v1 / text over
   `JSONAllPaths(json)`; `JSONAllValues` is new in 26.4 (PR #100730), returns `Array(String)`
   of leaf values; `INDEX v JSONAllValues(j) TYPE text(...)` is auto-used for subcolumn
   predicates.
4. **Join reordering**: controlled by `query_plan_optimize_join_order_limit` (default 10; 0 =
   off). There is no `allow_experimental_join_reordering`. ANTI/SEMI/FULL swapping landed 26.3
   (PR #97498). Test knob `query_plan_optimize_join_order_randomize` (26.4). Known fixed
   wrong-result in this class: PR #101504 (26.5) — validates the bug class is live.
5. **Top-k**: `use_top_k_dynamic_filtering` / `use_skip_indexes_for_top_k` became **default-on
   in 26.5** (not 26.4). Var-length sort columns are excluded by default since 26.5; opt back in
   via `use_top_k_dynamic_filtering_for_variable_length_types` (default false). Also new in
   26.5: `query_plan_top_k_through_join` (default on).
6. **Materialized CTEs** (26.3, PR #94849): `WITH name AS MATERIALIZED (subquery)`, gated by
   `enable_materialized_cte` (default **false**, tier EXPERIMENTAL on head).
7. **Compound INTERVAL** (26.4, PR #100453): `INTERVAL '5 12:30:45' DAY TO SECOND` etc., no
   gate. A **bare** compound literal evaluates to a `Tuple(IntervalDay, …)` — only useful (and
   only sound for our reader) inside datetime arithmetic.
8. **NATURAL JOIN** (26.4, PR #99840): parser-level rewrite to `JOIN … USING(common)`. **No
   common columns → silently becomes CROSS JOIN.**
9. **Statistics**: single-file format 26.2; `allow_statistics_optimize`/`use_statistics` are
   **no longer experimental** (default true); since 26.4 stats are **auto-created** for new
   tables (`auto_statistics_types='minmax, uniq'`).
10. **`OVERLAY`**: only the SQL-standard *keyword form* (`OVERLAY(s PLACING r FROM p FOR l)`) is
    new (26.4, PR #101681); the `overlay()` function predates 26.x. `naturalSortKey` is 26.3.
11. **Fuzzer-critical companion**: 26.1 turned **`use_variant_as_common_type` on by default** —
    mixed-type `if`/`multiIf`/`array`/UNION emissions now silently produce `Variant(...)` common
    types, which the client-v2 reader cannot decode. The existing multiIf CAST-wrap rule must be
    applied to every new mixed-type emission in this plan.

## Requirements Trace

- R1. Each in-scope 26.x surface gets generator emission (the fleet's TLP/NoREC/SEMR see it) or a
  self-contained oracle that builds its own private tables — no dark features remain among items
  1–10 except those explicitly deferred.
- R2. Each surface with a known wrong-result bug class gets a *sound* dedicated invariant
  (differential on/off or metamorphic equivalence) with narrow error tolerance.
- R3. Zero new false-positive families: every unit obeys the repo authoring rules (single-snapshot
  two-column compares for value equivalence, integer/string-only comparisons, CAST-wrapping of
  Variant-prone emissions, dedupe-engine determinism gates) and is validated by a convergence run
  on the dev-vm before being left on by default.
- R4. Nothing in this plan emits a type the client-v2 RowBinary reader cannot decode
  (`Variant`/`Dynamic`/raw `JSON` projections) into any fleet-shared read path.
- R5. Overlap with the 2026-05-29 roadmap is resolved by reference, not duplication. Ownership
  split: roadmap Unit 4.0/4.1 owns the polymorphic-type *read probe and fleet emission* of
  Dynamic/Variant/JSON columns; roadmap Unit 5.3 owns *SEMI→IN/EXISTS rewrite equivalence* and
  fleet-wide SEMI/ANY emission; this plan's Unit 3 owns only the *reorder-toggle differential*
  over its own private tables.

### Request-item → unit mapping

| Request item | Surface | Owner |
|---|---|---|
| 1 | Text index on LIKE/ILIKE | Unit 1 |
| 2 | JSON skip indexes (JSONAllPaths/JSONAllValues) | Unit 7 |
| 3 | JOIN reordering for ANTI/SEMI/FULL | Unit 3 |
| 4 | Compound INTERVAL literals | Unit 4 |
| 5 | NATURAL JOIN | Unit 5 |
| 6 | Top-k dynamic filtering | Unit 2 |
| 7 | Materialized CTEs | Unit 8 |
| 8 | Variant in all functions | Unit 10 (gated on roadmap Unit 4.0) |
| 9 | Statistics-driven planning | Unit 9 (stats differential; QPG out of scope) |
| 10 | naturalSortKey / OVERLAY / JSONAllValues | Unit 6 (JSONAllValues lives in Unit 7) |

## Scope Boundaries

- **No QPG infrastructure.** Item 9's "query-plan guidance" half is a separate engine-level
  investment; this plan ships only the stats on/off differential. QPG can be a follow-up plan.
- **No transport/reader work.** JSON and Variant coverage is designed around *never reading* an
  undecodable value (typed-path projections, count()/Array(String) reads). Teaching
  `ClickHouseRowBinaryParser` to decode JSON/Variant/Dynamic stays with roadmap Unit 4.0.
- **No non-deterministic-function divergence hunting in materialized CTEs.** The request notes
  such divergences "are themselves findings," but they cannot be auto-classified by an invariant
  oracle (any difference is expected); CTE bodies stay deterministic here.
- **Variant-in-all-functions (request item 8) is gated, not built.** It is blocked on roadmap
  Unit 4.0's read/compare probe; this plan only adds a slim WHERE-side arm behind that gate
  (Unit 10).
- **No replication/cluster surfaces** — single-node head container, as everywhere in this repo.

## Context & Research

### Relevant Code and Patterns

*(Locate by symbol, not line number — anchors below drift with every commit.)*

- `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java` — `renderSkipIndex()` already
  emits `bloom_filter(0.01)` / `set(100)` / `minmax` / `ngrambf_v1(3,256,2,0)` INDEX clauses;
  the per-table SETTINGS block already varies `index_granularity`.
- `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` — `LIKE` exists as a binary
  comparison operator; single-unit interval arithmetic lives in `generateDateIntervalArith`;
  named scalar functions are hand-built via `ClickHouseRawText`.
- `src/sqlancer/clickhouse/ast/ClickHouseExpression.java` — join-kind enum already has
  `LEFT/RIGHT_ANTI`, `LEFT/RIGHT_SEMI`, `FULL_OUTER`, `ASOF`; SEMI/ANY are excluded from TLP's
  deterministic join set (`DETERMINISTIC_JOIN_TYPES` comment in the expression generator) —
  correct, keep.
- `src/sqlancer/clickhouse/ClickHouseSessionSettings.java` — `SEMR_SETTINGS` list (~25 toggles;
  `query_plan_direct_read_from_text_index` and `query_plan_text_index_add_hint` are already
  present) + `pickRandomProfile()`; new result-preserving toggles land here for free SEMR
  coverage.
- `.claude/run-sqlancer.sh` — **`ALL_ORACLES` is a hardcoded list**; factory registration alone
  does NOT put an oracle into `--oracles all` runs (documented drift: DictGetVsJoin et al.
  silently never ran until re-added 2026-06-10). Every new oracle must be appended there.
- Differential-oracle templates: `src/sqlancer/clickhouse/oracle/projection/` (ProjectionToggle:
  same query, setting 0 vs 1, multiset compare), `oracle/keycond/` (pruning-on vs
  materialize()-wrapped full scan), `oracle/parallelism/` (3 profiles).
- Self-contained private-table oracle template: `src/sqlancer/clickhouse/oracle/mutate/`
  (MutationAnalyzer: AtomicLong-suffixed private tables, deterministic matrix, narrow error
  tolerance) and `oracle/materialize/` (MV consistency: numbers()-fed multi-block inserts).
- Single-snapshot value-equivalence template: `oracle/eet/ClickHouseEETOracle.java`
  (`assertSingleSnapshotEquivalent` — two expression forms as two columns of ONE query,
  positional compare).
- Oracle wiring: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (enum-based, one case per
  oracle; `MutationAnalyzer` at ~line 342 is the most recent example).
- Statistics: `src/sqlancer/clickhouse/gen/ClickHouseStatisticsGenerator.java` (MODIFY /
  MATERIALIZE / DROP STATISTICS; tdigest/uniq/countmin/minmax) and
  `gen/ClickHouseColumnBuilder.java` (~line 125, inline `STATISTICS(kind)` DDL) already exist.
- CTE scaffolding: `oracle/tlp/ClickHouseTLPBase.java` (~line 516) emits alias-CTEs only;
  subquery-CTE bodies are explicitly out of its scope.

### Institutional Learnings (provider CLAUDE.md / MEMORY)

- **Single-snapshot rule**: value-equivalence oracles compare two forms as two columns of one
  query, never two statements (EET conversion 2026-06-02). Applies to Units 4, 6.
- **Float noise**: differential/aggregate oracles restrict to exact-integer aggregates and
  non-float keys or drown in `sum(Float)` ordering noise (#99109 class). Applies to every
  differential unit here.
- **Variant common-type trap**: any multi-branch emission with dissimilar numeric branches needs
  `CAST(... AS Nullable(Float64))` wrapping; verify new emissions with `toTypeName()`. Stricter
  now that `use_variant_as_common_type` defaults on (26.1).
- **Dedupe-engine determinism**: anything comparing row sets must respect the
  `isValidOrderByForDedupe` gates; private-table oracles should use plain MergeTree.
- **`max_result_rows=1M` cap** is pinned on every connection; oracle queries returning large sets
  must tolerate `TOO_MANY_ROWS_OR_BYTES` (already global).
- **Probe-gating**: head-only features (here: `enable_materialized_cte`, text-index LIKE
  settings) should tolerate `UNKNOWN_SETTING`/`BAD_ARGUMENTS` at oracle start and self-disable,
  per the roadmap's probe-gated WS pattern, so the suite still runs against older images.

### External References

- 26.4 changelog/blog: text-index LIKE (PR #98149), JSON skip indexes (PR #98886), JSONAllValues
  (PR #100730), compound INTERVAL (PR #100453), NATURAL JOIN (PR #99840), OVERLAY keyword
  (PR #101681), auto-statistics (PR #101275).
- 26.5: top-k defaults-on (PR #99537) + var-length restriction (PR #104216), top-k through join
  (PR #104268), join-reorder wrong-result fix (PR #101504).
- 26.3: ANTI/SEMI/FULL reorder (PR #97498), materialized CTE (PR #94849), naturalSortKey
  (PR #90322).
- 26.1: Variant in all functions (PR #90900), `use_variant_as_common_type` default-on
  (PR #90677).
- Text-index docs: clickhouse.com/docs/engines/table-engines/mergetree-family/textindexes.

## Key Technical Decisions

- **Index-off arm via `ignore_data_skipping_indices`, not DROP INDEX**: same data, same parts, no
  DDL churn between arms; `force_data_skipping_indices` is used only as a vacuity guard arm
  (catches the oracle silently never engaging the index). Rationale: correction #2 above.
- **Top-k soundness via key-only projection**: `ORDER BY k1,…,kn LIMIT N` is non-deterministic
  under ties *in non-key columns*, but the ordered list of the **sort-key tuples themselves** is
  deterministic. The oracle projects exactly the ORDER BY columns and compares ordered lists.
  Rationale: avoids the classic tie false-positive without needing a unique tiebreaker.
- **SEMI/ANTI determinism via left-side-only projection**: SEMI/ANTI join results are
  deterministic as a *set of left rows*; right-side columns are not. The join-reorder oracle
  restricts projections accordingly (FULL may project both sides). Rationale: same reason the
  TLP join set excludes SEMI/ANY today.
- **JSON never enters the global type picker**: JSON columns live only on oracle-private tables;
  reads are limited to `count()`, declared **typed paths** (`JSON(a Int64, b String)` subcolumns
  read as concrete types), and `JSONAllPaths`/`JSONAllValues` (`Array(String)`, decodable).
  Untyped-path access returns `Dynamic` — WHERE-side only, never projected. Rationale: R4;
  reuses the MutationAnalyzer private-table pattern instead of waiting on roadmap Unit 4.0.
- **Token corpus drives LIKE patterns**: the text-index oracle inserts strings built from a known
  token vocabulary and generates `%token%` patterns from that same vocabulary (plus adversarial
  derivatives: token substrings, boundary-spanning fragments, case flips for ILIKE). Random
  patterns would almost never match and the "index drops matching rows" class would be vacuous.
- **Compound-interval and OVERLAY equivalences ride the EET oracle** as new modes using the
  existing single-snapshot two-column comparator, not new oracle classes. Rationale: exact
  parser-level identities; EET already owns this shape.
- **Materialized CTE differential is probe-gated and last**: experimental gate default-false,
  highest churn risk; the oracle probes `SET enable_materialized_cte=1` once and self-disables on
  `UNKNOWN_SETTING`.
- **New result-preserving toggles also join `SEMR_SETTINGS`** (`use_top_k_dynamic_filtering`,
  `use_skip_indexes_for_top_k`, `query_plan_top_k_through_join`,
  `use_text_index_like_evaluation_by_dictionary_scan`) so the blanket SEMR oracle gets free
  cross-product coverage beyond the dedicated oracles.

## Open Questions

### Resolved During Planning

- *Is the transport a blocker for JSON/Variant items?* — For Variant yes (deferred behind roadmap
  Unit 4.0); for JSON no, via the typed-path/Array(String) projection design above.
- *Does the codebase already create skip indexes?* — Yes (`renderSkipIndex`), so item 1 is an
  extension (add `text` type + tokenizer args), not net-new.
- *Which setting toggles join reordering?* — `query_plan_optimize_join_order_limit` 0 vs default;
  no experimental gate exists (correction #4).
- *Does top-k need roadmap Unit 5.1 (LIMIT rendering)?* — No: the top-k oracle hand-builds its
  queries like MutationAnalyzer does. Unit 5.1 remains valuable for fleet breadth but is not a
  dependency.
- *OVERLAY scope* — keyword-form ≡ function-form identity (the new 26.4 surface) plus a
  substring-splice metamorphic restricted to ASCII inputs (byte vs UTF-8 semantics).

### Deferred to Implementation

- Exact tokenizer-argument grammar for `text(...)` (e.g. `sparseGrams` parameters) — probe
  against head at implementation; start with `splitByNonAlpha` + `ngrams(3)`.
- Whether negative compound intervals (`INTERVAL '-2-6' YEAR TO MONTH`) parse — probe; include
  only if accepted.
- Whether NATURAL LEFT/RIGHT/FULL variants are all supported by PR #99840 — probe; generate the
  supported subset.
- ~~Whether `ClickHouseStatisticsGenerator` is wired into the fleet~~ — **resolved during
  review**: it is dead code (zero callers outside `gen/`+`ast/`). Decision: Unit 9's oracle is
  its only consumer initially; it joins the fleet `Action` pool (`ClickHouseProvider.Action`)
  at low probability only *after* Unit 9's convergence run proves the DDL is noise-free.
- Materialized-CTE error families under the experimental gate — collect from the first
  convergence run, then scope the narrow tolerance list.

## High-Level Technical Design

> *This illustrates the intended approach and is directional guidance for review, not
> implementation specification. The implementing agent should treat it as context, not code to
> reproduce.*

Two delivery shapes, both established in this repo:

```text
Shape A — fleet-table setting differential (Units 2, 9):
  oracle: SELECT <deterministic projection> ... SETTINGS feature=ON
       vs SELECT <same>                 ... SETTINGS feature=OFF
  compare multisets (or ordered key-lists for top-k); narrow error tolerance
  RESTRICTED to plain-MergeTree fleet tables (ClickHouseTable.getEngine()):
  dedupe engines (Replacing/Summing/Collapsing/Aggregating) change visible
  rows when a background merge lands between the two arms — the documented
  2026-05-20 false-positive class. mutations_sync covers mutations, not merges.
  templates: ProjectionToggle / KeyCondition

Shape B — metamorphic identity, single snapshot (Units 4, 6):
  SELECT (formA) AS a, (formB) AS b FROM t          -- one query, two columns
  assert positional equality                         -- template: EET modes

Shape C — self-contained private-table oracle (Units 1, 3, 5, 7, 8):
  AtomicLong-suffixed tables, seeded data designed for the bug class
  (token corpus / NULL+dup keys / shared-column schemas / JSON document
  corpus / CTE fixtures); plain MergeTree only; no concurrent writers, so
  multi-statement comparison is race-free (Unit 5's three-form differential
  relies on this)
  template: MutationAnalyzer / MaterializedViewConsistency
```

Unit dependency sketch:

```text
U1 text-index ──────────────┐
U2 top-k ───────────────────┤  independent, Phase 1
U3 join-reorder ────────────┘
U4 intervals (EET mode) ────┐
U5 NATURAL JOIN ────────────┤  independent, Phase 2
U6 naturalSortKey/OVERLAY ──┘  (U6 sort-key emission feeds U2's var-length arm)
U7 JSON model + skip idx ──── Phase 3 (largest net-new piece)
U8 mat-CTE ───┐
U9 stats ─────┤  Phase 4 (gated / interplay-sensitive)
U10 Variant ──┘  (blocked on roadmap Unit 4.0 probe)
```

## Implementation Units

### Phase 1 — differential oracles on default-on 26.4/26.5 execution paths

- [ ] **Unit 1: Text index `text(...)` + token-aware LIKE/ILIKE + index on/off oracle**

**Goal:** Fuzz the 26.4 text-index LIKE/ILIKE acceleration path; bug class: index drops matching
rows (false-negative granule skipping or dictionary-scan misses).

**Requirements:** R1, R2, R3.

**Dependencies:** None.

**Files:**
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java` (`renderSkipIndex` — add
  `text(tokenizer = 'splitByNonAlpha')` and `text(tokenizer = 'ngrams(3)')` variants for String
  columns, alongside the existing `ngrambf_v1`)
- Modify: `src/sqlancer/clickhouse/ClickHouseSessionSettings.java` (add
  `use_text_index_like_evaluation_by_dictionary_scan` to `SEMR_SETTINGS`)
- Create: `src/sqlancer/clickhouse/oracle/textindex/ClickHouseTextIndexLikeOracle.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (wire `TextIndexLike`)
- Modify: `.claude/run-sqlancer.sh` (append `TextIndexLike` to the hardcoded `ALL_ORACLES` after
  the convergence run — factory registration alone does not reach `--oracles all`)
- Test: `test/sqlancer/clickhouse/oracle/textindex/ClickHouseTextIndexLikeOracleTest.java`
  (pattern/DDL rendering; package-mirroring test dir per repo convention), plus dev-vm
  convergence run

**Approach:**
- Self-contained (Shape C): private MergeTree table, one indexed String column + one
  `count()`-able key column; small `index_granularity` (4–8) and multi-block inserts so multiple
  granules exist and skipping is observable.
- Rows are built from a fixed token vocabulary (alphanumeric, length ≥ 4 to clear
  `text_index_like_min_pattern_length`); patterns drawn from the same vocabulary plus
  adversarial derivatives: mid-token substrings, fragments spanning a token boundary (the
  dictionary scan must still match — LIKE is substring semantics, the tokenizer is not),
  case-flipped tokens for ILIKE, sub-4-char patterns (must transparently fall back), patterns
  containing non-alphanumerics (fall back).
- Three arms per pattern, all `SELECT count() / SELECT key ORDER BY key`: (a) default (index
  eligible), (b) `SETTINGS ignore_data_skipping_indices='<idx>'`, (c) dictionary-scan toggle
  flipped. All arms must agree. Vacuity guard at most once every ~10 iterations (not per
  pattern): run one known-token pattern with `force_data_skipping_indices='<idx>'` and treat
  `INDEX_NOT_USED` as "feature not engaged" (IgnoreMe + lifetime engagement counter), not a
  finding. `INDEX_NOT_USED` tolerance stays oracle-local, never global.
- The `renderSkipIndex` extension also gives the general fleet free `text`-index coverage under
  TLP/NoREC (their LIKE predicates now sometimes hit a text index).

**Patterns to follow:** `oracle/keycond/` (pruning differential), `oracle/mutate/` (private
tables, deterministic matrix), `renderSkipIndex` existing structure.

**Test scenarios:**
- Happy path: indexed-token `LIKE '%token%'` — arms (a)/(b)/(c) agree on count and key list.
- Happy path: ILIKE with case-flipped token agrees across arms.
- Edge case: pattern spanning a token boundary (substring of `tokA tokB` crossing the space —
  via `%kA tok%`-style fragments) agrees across arms.
- Edge case: 3-char pattern (below min length) and pattern with `_`/`%`-escapes agree across
  arms (fallback path).
- Edge case: empty table and all-rows-match token both agree (0 and N).
- Error path: `force_data_skipping_indices` vacuity probe returning `INDEX_NOT_USED` is treated
  as IgnoreMe, never an assertion failure.
- Integration: 30-min dev-vm run with the oracle in `--oracles all` shows >0 index-engaged
  iterations (vacuity counter) and 0 false positives.

**Verification:** Rendering unit tests pass; convergence run clean; vacuity counter confirms the
index path actually fires.

- [ ] **Unit 2: Top-k dynamic-filtering differential oracle**

**Goal:** Catch missing/extra rows in `ORDER BY … LIMIT N` under `use_top_k_dynamic_filtering` /
`use_skip_indexes_for_top_k` (default-on since 26.5).

**Requirements:** R1, R2, R3.

**Dependencies:** None hard (does not need roadmap Unit 5.1; hand-builds queries). The
var-length arm is complete from day one using String/FixedString fleet columns; U6's
`naturalSortKey` is an optional Phase-2 enrichment of the sort-key pool, not a prerequisite.

**Files:**
- Create: `src/sqlancer/clickhouse/oracle/topk/ClickHouseTopKOracle.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (wire `TopK`)
- Modify: `src/sqlancer/clickhouse/ClickHouseSessionSettings.java` (add the three top-k toggles
  to `SEMR_SETTINGS`)
- Modify: `.claude/run-sqlancer.sh` (append `TopK` to `ALL_ORACLES` after convergence)
- Test: `test/sqlancer/clickhouse/oracle/topk/ClickHouseTopKOracleTest.java`

**Approach:**
- Runs against fleet tables (Shape A), restricted to **plain MergeTree** via
  `ClickHouseTable.getEngine()` — dedupe engines change visible rows when a background merge
  lands between the two arms (merge race; `mutations_sync` does not cover merges). Pick a
  table, pick 1–3 sort columns (mix ASC/DESC, NULLS FIRST/LAST), pick N from {0, 1, small,
  ~row-count boundary}.
- **Soundness rule:** projection = exactly the ORDER BY columns; compare **ordered lists** of
  the key tuples — deterministic even with ties at the LIMIT boundary (tied rows have identical
  key tuples). Render keys via the standard string read; skip float sort keys (NaN ordering +
  float-render noise per the float rule). The comparison is positional string equality with
  explicit null-cell handling — never a Java-side sort over rendered values (the known
  `ComparableTimSort` NPE family on SQL NULLs).
- Arms: all-on (defaults) vs `use_top_k_dynamic_filtering=0, use_skip_indexes_for_top_k=0,
  query_plan_top_k_through_join=0`; a String-keyed arm additionally toggles
  `use_top_k_dynamic_filtering_for_variable_length_types=1` (off-by-default path that had the
  regression).
- Occasionally wrap the FROM in a LEFT JOIN (exercises `query_plan_top_k_through_join`).
- Multi-part tables preferred (the fleet's insert history provides this) so the skip-index top-k
  path engages.

**Patterns to follow:** `oracle/projection/` ProjectionToggle (toggle differential),
`oracle/setop_limit/` SortedUnionLimitBy (existing ORDER BY/LIMIT handling).

**Test scenarios:**
- Happy path: Int-keyed ORDER BY ASC LIMIT 10, on vs off arms equal.
- Edge case: heavy duplicate keys so ties straddle the LIMIT boundary — key-tuple lists equal.
- Edge case: Nullable key with NULLS FIRST and NULLS LAST; LIMIT 0; LIMIT ≥ row count;
  LIMIT with OFFSET.
- Edge case: String / LowCardinality(String) / FixedString keys with the var-length opt-in arm.
- Edge case: DESC + multi-column mixed-direction sort.
- Integration: LEFT JOIN-wrapped arm agrees (top-k-through-join path).
- Error path: float sort keys are skipped (never compared).

**Verification:** Unit tests on query construction + comparison soundness (tie case); clean
convergence run.

- [ ] **Unit 3: Join-reorder differential oracle for ANTI/SEMI/FULL**

**Goal:** Catch result-set changes introduced by join reordering (26.3 extended swapping to
ANTI/SEMI/FULL; PR #101504 proves the wrong-result class).

**Requirements:** R1, R2, R3, R5 — ownership split per R5: roadmap Unit 5.3 owns SEMI→IN/EXISTS
rewrite equivalence and fleet-wide SEMI/ANY emission; this unit owns only the reorder-setting
differential over its own private tables (the join kinds appear here solely as reorder inputs).

**Dependencies:** None.

**Files:**
- Create: `src/sqlancer/clickhouse/oracle/join/ClickHouseJoinReorderOracle.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (wire `JoinReorder`)
- Modify: `.claude/run-sqlancer.sh` (append `JoinReorder` to `ALL_ORACLES` after convergence)
- Test: `test/sqlancer/clickhouse/oracle/join/ClickHouseJoinReorderOracleTest.java`

**Approach:**
- Self-contained (Shape C): 3–4 private MergeTree tables seeded with a small key domain (0–9),
  deliberate NULL keys, and duplicate keys with skewed cardinalities (one big table, two 1–5 row
  tables — the asymmetry that drives reordering decisions and broke #106426).
- Query: chain of 2–3 joins drawing kinds from {INNER, LEFT, FULL, LEFT SEMI, LEFT ANTI,
  RIGHT SEMI, RIGHT ANTI} with equality ON clauses plus an occasional cross-relation WHERE.
- **Projection rules:** SEMI/ANTI arms project left-side columns only (right side is
  non-deterministic); FULL/INNER/LEFT may project both sides. Integer/String columns only.
- Arms: `query_plan_optimize_join_order_limit=10` (default) vs `=0` (off) vs
  `query_plan_optimize_join_order_randomize=1` (shuffled order). Multiset compare.
- Stats interplay: reordering is stats-driven; since auto-stats (26.4) the private tables get
  minmax/uniq automatically — occasionally run `MATERIALIZE STATISTICS` after seeding to make
  the cost model see the skew (links to Unit 9).

**Patterns to follow:** `oracle/join/ClickHouseJoinAlgorithmOracle` (existing join differential,
algorithm axis), MutationAnalyzer (private-table lifecycle).

**Test scenarios:**
- Happy path: INNER+LEFT chain, reorder on/off/randomized agree.
- Edge case: LEFT ANTI with NULL keys on both sides (NULL never matches — the rows ANTI keeps).
- Edge case: LEFT SEMI with duplicate right keys (no row multiplication allowed).
- Edge case: FULL JOIN with disjoint key ranges (all-unmatched rows on both sides).
- Edge case: cross-relation WHERE referencing two different tables (the #101504/#106426 shape).
- Edge case: empty table in the chain.
- Error path: known-open #106426 signature (`Join restriction violated`) recognised and routed
  per the existing known-bugs pinning pattern if it fires here.
- Integration: convergence run clean with all three arms.

**Verification:** Unit tests for projection restriction + arm construction; convergence run clean.

### Phase 2 — metamorphic identities (cheap, high-precision)

- [ ] **Unit 4: Compound INTERVAL ≡ sum-of-single-unit EET mode**

**Goal:** Catch parse/decomposition divergence in 26.4 compound interval literals.

**Requirements:** R1, R2, R3.

**Dependencies:** None.

**Files:**
- Modify: `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETOracle.java` (new mode
  `COMPOUND_INTERVAL`)
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` (emit compound
  interval literals in datetime arithmetic for fleet breadth, alongside the existing
  `generateDateIntervalArith` single-unit path)
- Test: `test/sqlancer/clickhouse/oracle/eet/ClickHouseEETIdentitiesTest.java` (existing EET
  test class), rendering assertions per kind-pair

**Execution note:** EET picks its mode via `Randomly.fromOptions(Mode.values())` and EET is
already in `ALL_ORACLES` — a new enum constant goes live fleet-wide on merge. Gate the new mode
behind a weight/flag (precedent: `--tlp-groupby-strict`) until its convergence run passes; same
applies to Unit 6's modes.

**Approach:**
- Identity: `d + INTERVAL '<v>' <FROM> TO <TO>` vs `d + INTERVAL a U1 + INTERVAL b U2 + …` as
  **two columns of one query** (single-snapshot rule), positional compare. Generate the value
  string and its decomposition from the same random components so both sides are constructed,
  never parsed back.
- Cover all 7 kind pairs (`YEAR TO MONTH`, `DAY TO HOUR/MINUTE/SECOND`, `HOUR TO
  MINUTE/SECOND`, `MINUTE TO SECOND`); apply to Date/Date32/DateTime/DateTime64 columns, minus
  pairs that are invalid for pure Date arithmetic if probing shows errors.
- Never emit a bare compound literal as a fetch column (it is a Tuple of intervals —
  reader-hostile and pointless); arithmetic context only. Subtraction arm too
  (`d - INTERVAL …`).

**Patterns to follow:** `ClickHouseEETOracle` ALGEBRAIC_ID / MULTIIF_EQUIV modes
(`assertSingleSnapshotEquivalent`).

**Test scenarios:**
- Happy path: each of the 7 kind pairs renders both forms and they compare equal on DateTime.
- Edge case: zero components (`'0 00:00:00' DAY TO SECOND`); max-ish components (e.g. 23 hours,
  59 minutes); component values that carry (e.g. `'1-11' YEAR TO MONTH` near year boundaries).
- Edge case: subtraction; DateTime64(3) sub-second column (compound adds whole seconds — values
  must still match exactly).
- Edge case: Date column + DAY TO SECOND (result becomes DateTime — both forms must agree on
  type and value; CAST-wrap if `toTypeName` probing shows divergent types).
- Error path (probe-derived): negative compound values included only if the probe shows they
  parse; otherwise excluded by construction.

**Verification:** EET rendering tests; convergence run with the new mode clean.

- [ ] **Unit 5: NATURAL JOIN rewrite-equivalence oracle**

**Goal:** Catch wrong implicit-column sets / duplicate-column handling in 26.4 NATURAL JOIN.

**Requirements:** R1, R2, R3.

**Dependencies:** None.

**Files:**
- Create: `src/sqlancer/clickhouse/oracle/join/ClickHouseNaturalJoinOracle.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (wire `NaturalJoin`)
- Modify: `.claude/run-sqlancer.sh` (append `NaturalJoin` to `ALL_ORACLES` after convergence)
- Test: `test/sqlancer/clickhouse/oracle/join/ClickHouseNaturalJoinOracleTest.java`

**Approach:**
- Self-contained (Shape C): two private **AtomicLong-suffixed** plain-MergeTree tables
  (MutationAnalyzer naming pattern — required for thread-safety, since multiple oracle threads
  share the database) whose schemas are generated with a controlled overlap — k shared column
  names (same or compatible types), plus per-table private columns. The oracle therefore
  *knows* the expected USING set.
- Three statements compared pairwise (multiset over an explicit projection list):
  `SELECT <explicit cols> FROM a NATURAL [INNER|LEFT|RIGHT|FULL] JOIN b` vs
  `… a JOIN b USING (<shared>)` vs the explicit-ON + manual-dedup form. Private tables receive
  no concurrent mutations, so multi-statement comparison is race-free here.
- Column-set check is part of the invariant: `SELECT *` arm compared via DESCRIBE/result column
  count — NATURAL must expose shared columns once.
- Zero-shared-columns case: assert parity with explicit `CROSS JOIN` (documented rewrite), as
  its own arm — this is exactly the silent-semantics trap worth pinning.
- Shared Nullable columns with NULLs on both sides (USING equality never matches NULL) are a
  priority seed shape.

**Patterns to follow:** `oracle/dict/ClickHouseDictGetVsJoinOracle` (rewrite-equivalence over
known schema), MutationAnalyzer private tables.

**Test scenarios:**
- Happy path: 1 and 2 shared columns, INNER — three forms agree on rows and column count.
- Edge case: all columns shared; zero shared (CROSS parity arm).
- Edge case: shared Nullable column with NULL keys both sides — NULLs unmatched in all forms.
- Edge case: NATURAL LEFT/FULL with unmatched rows — shared-column values come from the
  non-NULL side per USING semantics; forms agree.
- Edge case: duplicate key values both sides (row multiplication identical across forms).
- Error path: unsupported NATURAL variants (per probe) excluded by construction.

**Verification:** Unit tests assert the computed USING set and rendered forms; convergence run
clean.

- [ ] **Unit 6: `naturalSortKey` + `OVERLAY` emission and identities**

**Goal:** Cover the new 26.3/26.4 scalar-function surfaces (per-function wrong results).

**Requirements:** R1, R2, R3.

**Dependencies:** None (feeds Unit 2's var-length sort-key arm).

**Files:**
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` (emit
  `naturalSortKey(strExpr)` as a String→String function; emit OVERLAY keyword form in string
  expression contexts)
- Modify: `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETOracle.java` (new identities,
  weight/flag-gated until converged — see Unit 4's execution note)
- Test: `test/sqlancer/clickhouse/oracle/eet/ClickHouseEETIdentitiesTest.java` + visitor
  rendering tests

**Approach:**
- **OVERLAY**: EET single-snapshot identity `OVERLAY(s PLACING r FROM p FOR l)` ≡
  `overlay(s, r, p, l)` (exact parser-sugar identity — any divergence is a parser bug), plus the
  FROM-only variant. A second, ASCII-restricted metamorphic arm vs the
  `concat(substring(…), r, substring(…))` splice for in-range positions (byte-semantics caveat
  keeps this arm ASCII-only; out-of-range/negative positions stay on the sugar-identity arm
  only).
- **naturalSortKey**: emission into the general string-function pool (TLP/NoREC/SEMR coverage
  for free); EET comparator-consistency identity on integer-embedded strings (e.g. rendered
  version-like strings): `naturalSortKey(s1) < naturalSortKey(s2)` must equal the
  numeric-aware comparison the generator computed when it built s1/s2 from known numeric runs.
  Also: add `naturalSortKey(col)` to Unit 2's candidate sort keys (var-length top-k stress).
- Both functions' emissions are String-typed — no Variant common-type risk, no CAST needed.

**Patterns to follow:** EET string/regex roundtrip identities (roadmap Unit 6.2, shipped);
`ClickHouseRawText` hand-built function calls.

**Test scenarios:**
- Happy path: OVERLAY sugar ≡ function form, FROM and FROM…FOR variants.
- Edge case: p=1, p=length(s), l=0, r='' on the sugar identity (all positions legal-or-not must
  simply *agree* between forms).
- Edge case (splice arm): replacement longer/shorter than l; ASCII-only enforced by
  construction.
- Happy path: naturalSortKey ordering of `v1.2` vs `v1.10`-style constructed strings matches
  the known numeric order.
- Edge case: leading zeros (`007` vs `7`), digit runs longer than Int64 renders, empty string,
  strings without digits (falls back to byte order — both sides constructed accordingly).
- Integration: short fuzz run shows both functions appearing in TLP-generated predicates with
  no new error families (add genuinely-new server error strings to the narrow lists only).

**Verification:** EET identity tests; fuzz smoke clean.

### Phase 3 — net-new JSON model

- [ ] **Unit 7: JSON column model + JSONAllPaths/JSONAllValues skip indexes + on/off oracle**

**Goal:** Cover 26.4 JSON skip indexes (bug class: false-negative granule skipping on JSON path
predicates) without violating the transport constraint. Largest net-new piece — SQLancer has no
JSON generation model today.

**Requirements:** R1, R2, R3, R4.

**Dependencies:** Unit 1 (reuses its index on/off arm machinery and vacuity guard).

**Files:**
- Create: `src/sqlancer/clickhouse/gen/ClickHouseJsonDocumentGenerator.java` (document corpus:
  fixed path schema, random leaf values, optional absent paths — a separate class, not an
  oracle-private helper, because roadmap Unit 4.1's future fleet JSON emission reuses it)
- Create: `src/sqlancer/clickhouse/oracle/jsonidx/ClickHouseJsonSkipIndexOracle.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (wire `JsonSkipIndex`)
- Modify: `.claude/run-sqlancer.sh` (append `JsonSkipIndex` to `ALL_ORACLES` after convergence)
- Test: `test/sqlancer/clickhouse/gen/ClickHouseJsonDocumentGeneratorTest.java`,
  `test/sqlancer/clickhouse/oracle/jsonidx/ClickHouseJsonSkipIndexOracleTest.java`

**Approach:**
- Self-contained only (Shape C); JSON never enters the global type picker (R4, and the schema
  comment at `ClickHouseSchema.java` ~line 620 stays authoritative for the fleet).
- Private table: `j JSON(p_int Int64, p_str String)` — two *declared typed paths* (readable as
  concrete subcolumns) plus generator-controlled *untyped* paths (predicate-only). Key column
  for projections. Small granularity + multi-block inserts, as Unit 1.
- Index matrix per iteration, one of: `INDEX i JSONAllPaths(j) TYPE bloom_filter/tokenbf_v1/
  ngrambf_v1/text(...)`, or `INDEX i JSONAllValues(j) TYPE text(...)`.
- Predicates from the corpus: typed-path equality (`j.p_str = '<known leaf>'`), untyped-path
  via documented supported forms (equals / IN for bloom / IS NOT NULL), path-existence via
  `has(JSONAllPaths(j), '<path>')`. The generator knows ground truth (it built the documents),
  so expected counts are computable for a subset of predicates — assert exact counts there,
  index-on/off equality everywhere.
- Reads restricted to: `count()`, the key column, typed-path subcolumns, and
  `JSONAllPaths/JSONAllValues` (`Array(String)`). Raw `j` is never projected; untyped paths
  never projected (they read as Dynamic). **No new reader capabilities are required**: the
  client-v2 RowBinary reader already decodes `Array(String)` and concrete scalar types; this
  design stays entirely within that envelope, which is why it does not wait on roadmap
  Unit 4.0.
- Reuse Unit 1's three-arm structure (default / `ignore_data_skipping_indices` /
  `force_…` vacuity probe).

**Patterns to follow:** Unit 1's oracle; MaterializedViewConsistency (self-contained data
generation with computable ground truth).

**Test scenarios:**
- Happy path: typed-path equality predicate, each of the 5 index variants — on/off arms agree
  and (where ground truth applies) match expected count.
- Edge case: predicate on a path **absent** from some documents (the skip-avoidance rule in
  PR #98886 — absent path ⇒ default value; index must not skip those granules).
- Edge case: documents where the same path has mixed leaf types across rows (Dynamic typing
  inside JSON); predicate-only, on/off agree.
- Edge case: `IN`-list predicate on bloom_filter index; `IS NOT NULL` on each index type.
- Edge case: `has(JSONAllPaths(j), p)` for a path present in 0, some, and all rows.
- Edge case: empty JSON `{}` documents mixed in; deeply nested path (`a.b.c.d`).
- Error path: index-creation rejections for unsupported type/expression combos (probe-derived)
  are tolerated narrowly at CREATE, never at SELECT.
- Integration: convergence run clean; vacuity counter shows index engagement.

**Verification:** Document-generator unit tests (ground-truth counting); oracle arm tests;
convergence run clean.

### Phase 4 — gated and interplay-sensitive surfaces

- [ ] **Unit 8: Materialized CTE differential oracle (probe-gated, experimental)**

**Goal:** Cover 26.3 experimental materialized CTEs; bug class: double evaluation / wrong result
vs the inlined form.

**Requirements:** R1, R2, R3.

**Dependencies:** None hard; benefits from Phase 1 templates.

**Files:**
- Create: `src/sqlancer/clickhouse/oracle/cte/ClickHouseMaterializedCteOracle.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (wire `MaterializedCte`)
- Modify: `.claude/run-sqlancer.sh` (append `MaterializedCte` to `ALL_ORACLES` after convergence)
- Test: `test/sqlancer/clickhouse/oracle/cte/ClickHouseMaterializedCteOracleTest.java`

**Approach:**
- Probe once per oracle instance with a trivial query carrying
  `SETTINGS enable_materialized_cte = 1` (per-query SETTINGS clause, not a standalone `SET` —
  client-v2 pools connections, so a SET is not guaranteed to bind to later requests; every
  oracle query carries the clause); on `UNKNOWN_SETTING`, self-disable (IgnoreMe) — keeps the
  suite runnable on pre-26.3 images.
- Hand-built subquery CTEs over fleet tables (deterministic bodies only: integer aggregates,
  DISTINCT, WHERE — no floats, no nondeterministic functions, per scope boundary):
  `WITH x AS MATERIALIZED (q) SELECT … FROM x [JOIN x …]` vs the same statement with
  `AS (q)` inlined. Multiset compare.
- Reference the CTE 1–3 times in the outer query — multiplicity >1 is where
  materialize-once vs inline-twice semantics can diverge buggily even for deterministic bodies.
- Do **not** extend `ClickHouseTLPBase`'s alias-CTE scaffolding for this (fleet-wide subquery
  CTEs are roadmap-scale work); the oracle owns its own rendering.
- Experimental tier ⇒ collect server-error families from the first convergence run before
  whitelisting anything; start with an empty tolerance list plus the global set.

**Patterns to follow:** ProjectionToggle (setting differential), MutationAnalyzer (narrow
tolerance, hand-built SQL), the probe-gating pattern from the roadmap's WS4.

**Test scenarios:**
- Happy path: single-reference CTE, materialized vs inlined agree.
- Edge case: CTE referenced twice (join of x with itself); CTE referenced in a scalar-subquery
  position (the #101305 fixed-crash shape — should now work, and must agree).
- Edge case: empty CTE result; CTE over a table with duplicate rows (no dedup may be
  introduced).
- Edge case: chained CTEs (`WITH a AS MATERIALIZED (…), b AS MATERIALIZED (SELECT … FROM a)`).
- Error path: `UNKNOWN_SETTING` probe → oracle self-disables silently.
- Integration: convergence run; any new error family triaged before whitelisting.

**Verification:** Probe-gating test; convergence run on head clean.

- [ ] **Unit 9: Statistics on/off differential + wire the statistics generator**

**Goal:** Catch stats-driven planning wrong results (stats wrongly prune/skip data or flip join
decisions); make `ClickHouseStatisticsGenerator` a live fleet action.

**Requirements:** R1, R2, R3.

**Dependencies:** None hard. (Soft ordering note: stats mostly act *through* reorder/pruning
decisions, so triaging Unit 3's findings first avoids double-attribution; when isolating, this
oracle pins `query_plan_optimize_join_order_limit` and Unit 3 pins stats state.)

**Files:**
- Create: `src/sqlancer/clickhouse/oracle/stats/ClickHouseStatsToggleOracle.java` (sole consumer
  of `ClickHouseStatisticsGenerator`, which is currently dead code — zero call sites; it joins
  the fleet `ClickHouseProvider.Action` pool at low probability only after this unit's
  convergence run)
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (wire `StatsToggle`)
- Modify: `src/sqlancer/clickhouse/ClickHouseSessionSettings.java` (add `use_statistics` —
  **not** currently present; only `allow_statistics_optimize` + the `allow_statistic_optimize`
  typo-alias are in `SEMR_SETTINGS` today)
- Modify: `.claude/run-sqlancer.sh` (append `StatsToggle` to `ALL_ORACLES` after convergence)
- Test: `test/sqlancer/clickhouse/oracle/stats/ClickHouseStatsToggleOracleTest.java`

**Approach:**
- Fleet-table differential (Shape A), restricted to **plain MergeTree** tables (same merge-race
  rationale as Unit 2): same SELECT (predicates + joins, integer projections) under
  `use_statistics=1` vs `=0`. Probe the exact toggle name against head at oracle start — the
  research says stats optimization is no longer experimental (`use_statistics` preferred,
  `allow_statistics_optimize` canonical), but `ClickHouseColumnBuilder`'s inline-STATISTICS
  comment still claims `allow_experimental_statistics=1` is required; reconcile against the
  running server and update whichever is stale. Since 26.4 auto-stats (`minmax, uniq`) exist on
  every new table, the on-arm is meaningful by default; occasionally `MODIFY/MATERIALIZE
  STATISTICS` (existing generator) before the pair to vary stats kinds (tdigest/countmin) and
  staleness.
- Stats-staleness shape is the interesting seed: materialize stats, then bulk-DELETE/INSERT
  (mutations are sync on the dev-vm), then query — stale stats must change the *plan* only,
  never the result.
- `allow_statistics_optimize` (+ typo-alias) is already in `SEMR_SETTINGS`; this unit **adds**
  `use_statistics` there. The dedicated oracle's value over SEMR's blanket toggle is the
  *stats-mutation interplay* (staleness shapes) SEMR can't construct.

**Patterns to follow:** ProjectionToggle; `ClickHouseStatisticsGenerator` (existing DDL
builder).

**Test scenarios:**
- Happy path: filtered scan + 2-table join, stats on/off agree.
- Edge case: stale stats after sync DELETE of most rows — agree.
- Edge case: stats on a column with heavy skew/duplicates; DROP STATISTICS mid-sequence then
  re-query — agree.
- Edge case: each stats kind (tdigest/uniq/countmin/minmax) materialized at least once across a
  run (counter).
- Error path: stats DDL rejections on exotic column types tolerated narrowly at DDL time.
- Integration: convergence run clean; generator-wiring verified by observing
  MODIFY/MATERIALIZE STATISTICS statements in database logs.

**Verification:** Convergence run clean; stats DDL visible in run logs.

- [ ] **Unit 10: Variant predicate-side coverage (gated on roadmap Unit 4.0)**

**Goal:** Partially cover "Variant in all functions" (26.1) without violating R4: Variant
expressions in WHERE only, concrete projections.

**Requirements:** R1 (partial), R4, R5.

**Dependencies:** **Hard-gated on roadmap Unit 4.0's read/compare probe outcome.** If the probe
shows `getString` renders Variant stably, this unit is marked DEFERRED and removed from this
plan's delivery checklist — its 26.1 function-dispatch shapes fold into roadmap Unit 4.1's
expression pool instead. If the probe fails, this unit ships the WHERE-only design below.

**Files:**
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` (WHERE-context-only
  Variant expressions: `CAST(x AS Variant(Int64, String))` over existing columns, compared/
  filtered via `variantElement(v, 'Int64')`, `variantType(v)`, equality against typed
  constants — never projected raw)
- Test: rendering tests + fuzz smoke

**Approach:** Construct Variant values *inside* predicates from non-Variant columns (CAST), apply
the 26.1 "all functions" dispatch surface (comparisons, conditionals, variantElement extraction
CAST-wrapped to concrete types), keep every fetch column concrete. TLP partition invariance gives
the oracle for free. Emission probability low; behind a generator flag defaulting off until one
clean convergence run.

**Patterns to follow:** the multiIf CAST-wrap rule; `DynamicSubcolumn` oracle scaffolding.

**Test scenarios:**
- Happy path: Variant-typed predicate renders, `toTypeName` of every fetch column is concrete.
- Edge case: NULL Variant (`CAST(NULL …)`), variantElement on a non-active alternative
  (returns NULL), variantType comparisons.
- Error path: any reader `IndexOutOfBoundsException` in the smoke run ⇒ a fetch column leaked a
  Variant — treat as unit-blocking bug, fix before enabling.
- Integration: TLPWhere smoke with the flag on — 0 reader deaths, 0 new false positives.

**Verification:** Smoke run with flag on is clean; default-on only after a full convergence run.

## System-Wide Impact

- **Interaction graph:** `renderSkipIndex` changes (U1) affect every fleet table — TLP/NoREC/
  KeyCondition queries start touching text indexes; KeyCondition's `use_skip_indexes=0` arm
  already neutralizes them correctly. New `SEMR_SETTINGS` entries (U1, U2) enter the blanket
  SEMR cross-product immediately — each must be individually result-preserving (they are, per
  research) or SEMR drowns in noise.
- **Error propagation:** new oracles follow narrow-tolerance + global-list layering
  (MutationAnalyzer precedent); genuinely-new server error strings get added to the *oracle's*
  list, never the global one, until proven cross-cutting. `INDEX_NOT_USED` must stay
  oracle-local (it is an intended probe failure, not a global tolerance).
- **State lifecycle risks:** private-table oracles (U1, U3, U5, U7, U8) must DROP their
  AtomicLong-suffixed tables on every exit path or long runs leak tables into
  `clickhouse-disk-cleanup.sh` territory; follow MutationAnalyzer's cleanup. Fleet-table oracles
  (U2, U9) are read-only except U9's stats DDL, which is metadata-only.
- **API surface parity:** factory registration does **not** reach `--oracles all` —
  `run-sqlancer.sh` hardcodes `ALL_ORACLES`, and oracles have silently drifted out of it before
  (DictGetVsJoin et al., re-added 2026-06-10). Each unit's Files list therefore includes the
  `ALL_ORACLES` append as an explicit step, done only after that oracle's convergence run (R3);
  until then new oracles run via explicit `--oracles <Name>` smoke invocations.
- **Integration coverage:** the real verification layer is dev-vm convergence runs (30-min per
  unit, then a combined multi-hour run after each phase) — unit tests only pin rendering and
  comparison soundness.
- **Unchanged invariants:** the global type picker still never emits JSON/Variant/Dynamic (R4);
  TLP's deterministic-join set still excludes SEMI/ANY; the `max_result_rows` cap and
  `mutations_sync=2` dev-vm config are untouched and assumed by every unit.

## Risks & Dependencies

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Text-index oracle is vacuous (index never engages: pattern length, tokenizer, granule layout) | Med | Med (silent no-coverage) | `force_data_skipping_indices` vacuity-guard arm + engagement counter surfaced in run summary |
| Top-k tie semantics produce false positives despite key-only projection (e.g. collation/NULL ordering edge) | Low | High (noise) | Key-only ordered-list compare; float keys excluded; convergence run before default-on |
| SEMI/ANTI reorder differential trips the known non-determinism that excluded them from TLP | Med | High | Left-side-only projections; if a family persists, restrict SEMI arms to `count()` |
| Materialized CTE (EXPERIMENTAL tier) churns/renames its gate on head | Med | Low | Probe-gating; oracle self-disables on `UNKNOWN_SETTING` |
| JSON untyped-path predicates surface Dynamic-related server errors in WHERE context | Med | Med | Start typed-path-heavy; widen untyped predicates only after first convergence run |
| `use_variant_as_common_type` (26.1 default-on) makes *new* emissions produce Variant common types | Med | High (reader death) | Apply the CAST-wrap rule to every new mixed-type emission; `toTypeName` probes in unit tests |
| New SEMR settings interact with existing 40 toggles combinatorially | Low | Med | Add one at a time; each is independently verified result-preserving in its dedicated oracle first |
| Stats oracle overlaps Unit 3's reorder arms (double-attribution of findings) | Low | Low | Reorder oracle pins stats state; stats oracle pins reorder limit when isolating |

## Phased Delivery

- **Phase 1 (U1–U3):** default-on 26.4/26.5 execution paths — highest bug-density-per-effort;
  each is independent and lands with its own convergence run.
- **Phase 2 (U4–U6):** metamorphic identities — small diffs, near-zero false-positive risk by
  construction; can interleave with Phase 1 reviews.
- **Phase 3 (U7):** JSON model — largest net-new generator work; benefits from U1's proven
  index-arm machinery.
- **Phase 4 (U8–U10):** experimental/gated/interplay surfaces — sequenced last deliberately;
  U10 may dissolve into roadmap Unit 4.1 depending on the probe.

After each phase: archive run artefacts per the attempt-dir convention, triage reproducers by
`Caused by:` family, and update the known-open-bug pin list before starting the next phase.

## Documentation / Operational Notes

- Update `.claude/CLAUDE.md` oracle inventory (the run-sqlancer `--oracles all` set) as each
  oracle lands, mirroring the MutationAnalyzer entry style (one paragraph: matrix, tolerance,
  known pins).
- Each filed bug from these surfaces goes into the CLAUDE.md "Filed ClickHouse bugs" list with a
  minimal repro and pin-removal condition, per the existing convention.
- Convergence runs happen **only on the dev-vm** via `run-sqlancer.sh --rebuild`; exit 255 means
  reproducers-found, not failure.

## Sources & References

- Companion roadmap: `docs/plans/2026-05-29-001-feat-clickhouse-coverage-expansion-roadmap-plan.md`
  (pending Units 2.3, 4.0, 4.1, 5.1–5.4 remain owned there)
- Recent oracle precedents: `docs/plans/2026-06-10-001-feat-mutation-analyzer-coverage-plan.md`
- Code anchors: `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java` (renderSkipIndex),
  `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETOracle.java`
  (assertSingleSnapshotEquivalent), `src/sqlancer/clickhouse/ClickHouseOracleFactory.java`
- ClickHouse PRs: #98149, #98886, #100730, #100453, #99840, #101681, #97498, #94849, #90322,
  #90900, #90677, #99537, #104216, #104268, #101504, #101275, #93414
- Release blogs: clickhouse.com/blog/clickhouse-release-26-0{1,2,3,4}; full-text GA:
  clickhouse.com/blog/full-text-search-ga-release
