---
title: "feat: ClickHouse coverage expansion — types, DDL, query features, comparator + oracle correctness"
type: feat
status: in-progress
date: 2026-05-27
---

# feat: ClickHouse coverage expansion

## Implementation status (2026-05-27 — end of session)

The **entire critical path** is done. Plus codec/statistics breadth.

| # | Workstream | Status | Commit / note |
|---|-----------|--------|--------|
| 1 | Correctness foundation (TLPGroupBy + ComparatorHelper) | **landed** | `80bfd4f0` + `f84502bc` (UNION-rejection fix) |
| 2 | Composite types (Tuple, Map, Enum) | pending | — |
| 3 | Temporal types (Time, Time64, Interval) | pending | — |
| 4 | Geo types (Point/Ring/Polygon/MultiPolygon) | pending | — |
| 5 | AggregateFunction + SimpleAggregateFunction | pending | depends on 2 |
| 6 | JSON, Variant, Dynamic | pending | plan: largest single workstream |
| 7 | Nested | pending | — |
| 8 | ALTER ADD/DROP/MODIFY/RENAME COLUMN | **landed** | `2561d53e` |
| 9 | Mutations + barrier | **landed** | `e82a260f` |
| 10 | SELECT FINAL diff oracle + engine pool unpin | **landed** | `50cfaa66` + `2899f02e` |
| 11 | Statistics (inline + SEMR) | **landed** | `6c1911af` |
| 12 | Quota / Settings Profile / RowPolicy DDL | pending | refactor of existing RowPolicyOracle |
| 13 | Codec breadth | **landed** | `3bf7d79d` |
| 14 | Dictionaries | pending | needs lifecycle + new oracle |
| 15 | JOINs in generator (scaffolded in TLPBase) | partial | existing (pre-plan) |
| 16 | Subqueries in FROM/SELECT | pending | — |
| 17 | CTEs (WITH) | pending | — |
| 18 | PREWHERE (scaffolded in TLPBase) | partial | existing (pre-plan) |
| 19 | Window functions | pending | major: AST + new oracle |
| 20 | ARRAY JOIN (scaffolded; superseded by 2026-05-18-002) | partial | existing (pre-plan) |
| 21 | ASOF / ANY / PASTE JOIN | pending | extends workstream 15 |
| 22 | Lambdas / higher-order array functions | pending | major: AST + propagation |

### Validation run (in-flight)

25-oracle × 5-min sequential validation kicked off on dev-vm at 10:27Z, expected complete 12:50Z.
Per-oracle reproducer counts archived in `logs/per-oracle-<ts>/summary.tsv`.

Mid-run partial results (first 8 oracles):
- TLPWhere/TLPHaving/NoREC/PQS/CERT: **0 reproducers** each (clean).
- TLPDistinct: 2 (minimal noise, likely existing false positives).
- TLPGroupBy: **1331** — regression from `80bfd4f0` (bare `UNION` rejected by CH); **fixed in `f84502bc`** but re-run needed with new jar.
- TLPAggregate: 24 — the new ULP_TOLERANT_MULTISET path now surfaces multi-row aggregate divergences that the old 1×1 special-case was masking. Per plan design; needs follow-up triage to separate real CH bugs from rendering artefacts.

### Session summary

Critical path complete: **correctness foundation → ALTER → mutations → FINAL diff oracle** (with engine pool re-unpinned so the diff oracle has work to do). Plus the smaller cross-cutting additions (codec breadth, statistics inline + SEMR). 9 workstreams remain pending; they were each scoped as standalone PRs by the plan's own framing and are individually multi-hour efforts. Defer to follow-up sessions.

**Target repo:** `fm4v/sqlancer`
**Target branch:** new feature branches off `main`, one per workstream (squash-merge), or stacked PRs off `nik/clickhouse-add-pqs-cert-coddtest` if the active query-primitives PR has not landed yet.

## Why this plan exists

The 2026-05-26 capability audit found two structurally unsound oracle paths and ~40 ClickHouse features the generator does not exercise. Implementing everything in one PR would break validation in dozens of places at once. This plan decomposes the surface into independently shippable workstreams, each sized to land in 1 PR, ordered so each one ships with green validation against its prerequisites.

## Goals

- Eliminate the two known structurally unsound oracle paths (TLPGroupBy + the 1×1-only float comparator).
- Add every type, DDL statement, and query feature on the 2026-05-26 audit's "missing" list, sized as one PR per workstream.
- Each PR ships independently green against a 15-min-per-oracle smoke (current baseline: ~110 q/s × 6 threads × 15 min ≈ 590 k queries; throughput regressions >20 % block the PR).
- Preserve oracle compatibility — no existing oracle should turn off, and new types/features must opt-in via expected-error catalogue updates so old oracles don't accumulate false positives.

## Non-goals

- Replicated/Distributed engines, MaterializedPostgreSQL/MySQL sources, Iceberg/Parquet/S3 ingest. Out of scope for this plan; track separately.
- Coverage-guided generation, persistent seed corpus. Tracked as a separate "fuzzer leverage" plan; orthogonal to feature coverage.
- An HTML triage dashboard / fingerprint dedup ledger / cross-version replay matrix. Tracked separately under "triage automation."

## Dependency graph

```
Correctness foundation ─┬─► every later workstream (clean validation baseline)
                        │
Composite types ────────┼─► AggregateFunction (needs Tuple/Map for state types)
(Tuple, Map, Enum)      │   Nested (≈ Tuple-of-Arrays)
                        │
Temporal types          │
(Time/Time64/Interval)  │
                        │
Geo types               │
(Point/Ring/Polygon/    │
 MultiPolygon)          │
                        │
JSON/Variant/Dynamic ───► Lambdas (subcolumn access uses these)

ALTER {ADD/DROP/MODIFY} COLUMN + RENAME ──► Mutations
                                            Statistics
                                            Dictionaries (ALTER MODIFY)

Mutations ──► SELECT FINAL diff oracle (requires merge-history scaffolding)

Quota/RowPolicy DDL — replaces hand-rolled RowPolicyOracle setup
Codec breadth — standalone
Dictionaries — depends on ALTER column scaffolding

JOINs in generator ──► Subqueries in FROM/SELECT
                       CTEs (same FROM-position machinery)
                       ASOF/ANY/PASTE
PREWHERE in generator — standalone
Window functions — standalone
ARRAY JOIN — superseded by active query-primitives plan if landed
Lambdas ──► requires Array(T) (already there) + JSON subcolumns optional
```

Critical path for "next big bug class": **Correctness foundation → ALTER COLUMN scaffolding → Mutations → SELECT FINAL diff oracle**. That sequence alone re-creates the database10 LEFT-ANTI-JOIN-post-OPTIMIZE bug class as a first-class oracle target.

## Existing in-flight work this plan must reconcile with

- `docs/plans/2026-05-16-001-feat-clickhouse-type-system-foundation-plan.md` (completed): widened `ClickHouseType.Kind` to 22 primitives + added `FixedString`/`Decimal`/`DateTime64Type`/`Array`/`Nullable`/`LowCardinality`/`Unknown` constructors. The sealed interface is `permits`-restricted, so every new composite type below requires a `permits` clause edit + matching `unwrap()` case.
- `docs/plans/2026-05-18-002-feat-clickhouse-query-primitives-plan.md` (active): covers aggregate combinators (-If, -OrNull, -Array, -State, -Merge, -ForEach, -Resample, -Map, -Distinct), UNION/INTERSECT/EXCEPT set operations, and ARRAY JOIN. **If this lands first, the ARRAY JOIN workstream below is empty.** AggregateFunction(name, T) state types depend on `-State`/`-Merge` shipping there.
- `docs/plans/2026-05-17-001-feat-clickhouse-semr-oracle-settings-randomization-plan.md` (completed): SEMR / SEMRMulti exist. New settings introduced below (statistics, mutations) should be added to SEMR's randomisation pool, not just defaulted.

---

## Correctness foundation (TLPGroupBy + ComparatorHelper)

**One PR. Land first; every subsequent workstream's validation depends on a low-false-positive baseline.**

### Scope

- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPGroupByOracle.java` — wrap UNION-ALL-of-three combined query in an outer canonicalising aggregation (e.g. `SELECT … FROM (UNION ALL) GROUP BY <same keys>` with the same aggregate functions), so a group key appearing in multiple WHERE-partition branches collapses before set comparison. Add a `strictMode` boolean (default off, opt-in via `--tlp-groupby-strict`) that disables canonicalisation for occasional adversarial sweeps.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPDistinctOracle.java` — same outer-DISTINCT-over-UNION-ALL pattern is already correct shape; verify and add explicit comment so it isn't "fixed" in the wrong direction by accident.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java:64` — replace `HashSet.size()` comparison with `ComparatorHelper.assumeResultSetsAreEqual` so it picks up multiset semantics from this workstream.
- `src/sqlancer/ComparatorHelper.java`:
  - Replace `HashSet`-based content comparison with Guava `HashMultiset` (Guava 33.4 already pinned in `pom.xml` per CLAUDE.md). Multiset equality is structurally correct for SQL result sets — duplicate-count mismatches caught.
  - Promote `equals(double, double)` ULP-tolerance from dead code into the main comparison path: when both sides have the same cardinality, walk each row and apply `isEqualDouble` to numeric-typed cells. Detect numeric cells via a dual parse (try-`Double.parseDouble`, fall back to string compare).
  - Add a `ComparisonMode` parameter so oracles can opt into strict-exact (TLPSetOp INTERSECT/EXCEPT) vs. ULP-tolerant (TLPCombinator avg variants) explicitly.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPAggregateOracle.java:79-86` — remove the special-case 1×1 branch; route through the new `ComparatorHelper` path with `ComparisonMode.ULP_TOLERANT`.
- `src/sqlancer/clickhouse/ClickHouseErrors.java` — no change expected.

### Tests

- Unit tests under `src/test/java/sqlancer/` for `ComparatorHelper` covering: multiset (duplicate counts), ULP-tolerance triggers on float columns only, NaN handling (Java `Double.NaN` equality is `false` — must be explicit), Inf handling, empty-vs-NULL distinction in RowBinary path.
- Replay tests: run a saved `database*.log` from a recent run that previously surfaced a TLPGroupBy false positive, and confirm this workstream squashes it.

### Validation

- 15-min smoke on each oracle on dev VM. Baseline metric: previous runs averaged ~3 saved reproducers per 3 h with 25 oracles. Target: no new reproducer family introduced; expect ≥ 30 % reduction in TLP-family false positives.
- Throughput regression cap: 20 %. The comparator's per-row numeric detection adds work; mitigate by caching the parsed-as-double-or-not decision per column index per query.

### Risks

- Outer-aggregation canonicalisation can mask *genuine* GROUP BY result bugs. The `--tlp-groupby-strict` flag preserves the adversarial path for periodic sweeps.
- Multiset equality on string-rendered values is still subject to float-rendering quirks (NaN as "NaN" vs "nan"); RowBinary path already settled on "NaN"/"Infinity" rendering, but TLPSetOp builds queries it ships back to CH — confirm both sides render through the same reader.

---

## Composite types: Tuple, Map, Enum8/Enum16

### Scope

- `src/sqlancer/clickhouse/ClickHouseType.java`:
  - Add `Tuple(List<ClickHouseType> elements)` record. `unwrap()` returns `this` (the tuple itself is the value). `supportsLiteralEmission` recurses on elements.
  - Add `Map(ClickHouseType keyType, ClickHouseType valueType)`. Constraint: key must be a hashable type per CH semantics — String, FixedString, integer kinds, UUID, Date, DateTime. Enforce at construction.
  - Add `Enum(int width, List<EnumEntry> entries)` with `width ∈ {8, 16}` and `EnumEntry(String name, int value)`. Render as `Enum8('a'=1, 'b'=2)`.
  - Update sealed `permits` clause; add `unwrap()` cases; add `fromClickHouseDataType` cases.
- `src/sqlancer/clickhouse/gen/ClickHouseColumnBuilder.java` — emit DDL column-type fragments for new types. Tuple/Map nesting bounded at depth 2 to keep query strings small.
- `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java`:
  - Literal emission for tuples: `(1, 'a', toDate('2024-01-01'))`.
  - Literal emission for maps: `map('a', 1, 'b', 2)`.
  - Literal emission for enums: bare-string spelling.
  - Field access expressions: `tup.1`, `tup.2`, `m['key']`.
  - Cast paths for each new type.
- `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java` — visitor cases for tuple/map/enum literals + access ops.
- `src/sqlancer/clickhouse/ast/` — new AST node `ClickHouseTupleAccess` (positional `.N`), `ClickHouseMapAccess` (`m[k]`).
- `src/sqlancer/clickhouse/ClickHouseErrors.java` — add expected errors for enum out-of-domain assignment, map key-collision under `map_keys_check`, and tuple width mismatches.
- `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java` — exclude Map/Enum/Tuple from ORDER BY for now (the dedupe-engine NaN-pinning footnote already covers function-of-numeric ORDER BY; same hazard).

### Tests

- Unit: round-trip `Tuple(Int32, String)` through ColumnBuilder + ExpressionGenerator + ToStringVisitor.
- Smoke: a synthetic mini-run that forces Tuple/Map/Enum in every generated table, ensure existing TLPWhere/NoREC still pass.

### Validation

- 15-min smoke on each oracle. Throughput cap: 20 %.
- Expected new tolerated errors: `Code: 36 (BAD_ARGUMENTS)` on enum-domain violations, `Code: 50 (UNKNOWN_TYPE)` on misformed Tuple.

### Risks

- Existing oracles emit expressions over `columns` blind to type — `gen.generateExpressionWithColumns` may produce `tup + 1`, which CH rejects. Guard by adding a `typeFilter` parameter or sticking to columns whose primary type is numeric/string in the expression generator until lambdas land.

---

## Temporal types: Time, Time64, Interval

### Scope

- `src/sqlancer/clickhouse/ClickHouseType.java`:
  - Add `Time` (record, no parameters, second resolution).
  - Add `Time64(int precision)`.
  - Add `Interval(IntervalKind kind)` with `IntervalKind ∈ {Nanosecond, Microsecond, Millisecond, Second, Minute, Hour, Day, Week, Month, Quarter, Year}`.
- `src/sqlancer/clickhouse/gen/ClickHouseColumnBuilder.java` + `ExpressionGenerator.java`:
  - Literal emission via `toTime('12:30:45')`, `toTime64('12:30:45.123', 3)`, `INTERVAL 1 DAY`.
  - Arithmetic: Date + Interval = Date, DateTime + Interval = DateTime. Type-aware expression generator must allow these without conflating with plain integer add.
- `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java` — interval literal rendering.

### Tests

- Round-trip via `system.columns`; assert type spelling matches.

### Validation

- 15-min smoke; expected error to add: `Code: 70 (CANNOT_CONVERT_TYPE)` on illegal interval arithmetic.

### Risks

- Time/Time64 are recent CH additions (≥24.x); pre-24 server compatibility not required since runs target HEAD.

---

## Geo types: Point, Ring, Polygon, MultiPolygon

### Scope

- `src/sqlancer/clickhouse/ClickHouseType.java`:
  - `Point` ≈ `Tuple(Float64, Float64)`.
  - `Ring` ≈ `Array(Point)`.
  - `Polygon` ≈ `Array(Ring)`.
  - `MultiPolygon` ≈ `Array(Polygon)`.
  - Implemented as distinct records (not type aliases) so DDL renders as `Point`, not the underlying tuple/array spelling.
- `ClickHouseColumnBuilder.java`, `ClickHouseExpressionGenerator.java`:
  - Literal emission: `(0.0, 0.0)::Point`, `[(0,0),(1,1),(1,0)]::Ring`, etc.
  - Geo functions: `pointInPolygon`, `polygonAreaCartesian`, `polygonsDistanceSpherical`. Add a `ClickHouseGeoFunction` enum with arity + arg-types.
- `ClickHouseToStringVisitor.java` — geo literal cast wrappers.

### Validation

- 15-min smoke. New tolerated errors: `Code: 36` on degenerate polygons (self-intersecting), `Code: 70` on dimension mismatch in geo functions.

### Risks

- Geo functions are CPU-heavy; cap probability per generated expression at 5 % to avoid drowning out other coverage.

---

## AggregateFunction + SimpleAggregateFunction

### Scope

- **Requires the composite-types workstream (Tuple/Map for state types) and active query-primitives plan's `-State`/`-Merge` combinators.**
- `src/sqlancer/clickhouse/ClickHouseType.java`:
  - `AggregateFunction(String name, List<ClickHouseType> args)`. Renderer: `AggregateFunction(sum, Int64)`.
  - `SimpleAggregateFunction(String name, ClickHouseType arg)`. Allowed names restricted to associative-commutative aggregates (`sum`, `min`, `max`, `any`, `anyLast`, `groupBitAnd`, `groupBitOr`, `groupBitXor`).
- Reading these columns requires `finalizeAggregation(col)` or the `-Merge` combinator at SELECT time — `ClickHouseExpressionGenerator.java` must auto-wrap when projecting an `AggregateFunction` column.
- INSERT path for AggregateFunction columns: use the `-State` combinator over the corresponding scalar column from a sibling table, or `arrayReduce('sumState', [1,2,3])`. New helper in `ClickHouseInsertGenerator.java`.
- New oracle: `ClickHouseAggregateStateRoundtripOracle.java` under `oracle/aggstate/`. Asserts `finalizeAggregation(arrayReduce('sumState', groupArray(c)))` ≡ `sum(c)` for every supported `-State`/`-Merge` pair. Built-in differential, no TLP wrapping needed.

### Validation

- 15-min smoke per oracle. The new aggstate oracle gets a 60-min dedicated run to populate the expected-errors catalogue.

### Risks

- AggregateFunction state binary format is version-sensitive; CH HEAD changes occasionally break replay. Pin the version inside the saved reproducer filename — note this depends on the separate triage-automation plan landing.

---

## JSON, Variant, Dynamic

**Largest single workstream. Splits into stacked sub-PRs (JSON first, then Variant, then Dynamic) if the PR grows beyond ~1500 lines.**

### Scope

- `src/sqlancer/clickhouse/ClickHouseType.java`:
  - `JSON` (record, optional `maxDynamicTypes` parameter, default unset = unlimited). New JSON v2 (CH 24.10+) is the target spelling.
  - `Variant(List<ClickHouseType> alternatives)` — discriminated union of up to N types.
  - `Dynamic` (record, no parameters, optional `maxTypes` parameter).
- `ClickHouseColumnBuilder.java` + `ClickHouseExpressionGenerator.java`:
  - JSON literal emission: `'{"a": 1, "b": "x"}'::JSON`. Path access: `j.a`, `j.b.^Int64`.
  - Variant literal: any of the alternative-type literals wrapped in a `::Variant(Int32, String, ...)` cast.
  - Variant subcolumn access: `v.Int32`, `v.String`. Predicate: `variantElement(v, 'Int32')`.
  - Dynamic field access: `dyn.Int32`, `dyn.String`, etc.
- `ClickHouseToStringVisitor.java` — JSON path, Variant element access, Dynamic element access AST nodes.
- New AST nodes: `ClickHouseJsonPath`, `ClickHouseVariantElement`, `ClickHouseDynamicElement`.
- Expression-generator type filter: most existing arithmetic / comparison generators must skip `Variant`/`Dynamic`/`JSON` columns unless wrapped in a subcolumn access. Add a `requiresSubcolumnAccess(ClickHouseType)` helper.
- `ClickHouseErrors.java`: add `Code: 386 (NO_COMMON_TYPE)`, `Code: 53 (TYPE_MISMATCH)` on raw variant in scalar position, JSON-specific errors `Code: 1003` family.
- New oracle: `ClickHouseDynamicSubcolumnOracle.java` — assert that `dynamicElement(d, T)` ≡ `CAST(d AS T)` on rows where the dynamic value's runtime type is T.

### Validation

- 15-min smoke. New tolerated errors: a JSON-rendering family (the JSON v2 wire format collapses sub-objects in ways the comparator must handle — add JSON normalisation hook to ComparatorHelper, or ban JSON columns from comparison contexts).

### Risks

- JSON's wire format renders inconsistently between RowBinaryWithNamesAndTypes and TSV. Decision point: for JSON-typed result columns, normalise to a canonical key-sorted form before set comparison, or refuse to compare JSON-shaped result columns at all. Recommendation: refuse to compare; route JSON columns into `errors`-style oracles (does it parse, does it round-trip) rather than result-equality oracles, in this workstream.

---

## Nested

### Scope

- `src/sqlancer/clickhouse/ClickHouseType.java` — `Nested(List<NestedField>)`. CH renders nested as `Nested(field1 T1, field2 T2)`; column access at SELECT time yields `Array(T_i)`.
- `ClickHouseColumnBuilder.java` — DDL emission only; nested fields cannot be referenced via dot-paths in WHERE without first being ARRAY JOINed (depends on the ARRAY JOIN workstream / active query-primitives plan).
- Expression generator: skip nested columns in scalar contexts until ARRAY JOIN lands.

### Validation

- 15-min smoke. Few new expected errors expected.

### Risks

- Nested is a legacy spelling for `Tuple(Array(T1), Array(T2), ...)` under the hood; CH HEAD treats them mostly equivalently but a handful of optimizer paths still differ. Generator coverage of both spellings is the point.

---

## ALTER ADD/DROP/MODIFY COLUMN + RENAME

### Scope

- New AST: `ClickHouseAlterColumnStatement` with subtypes `ADD COLUMN`, `DROP COLUMN`, `MODIFY COLUMN`, `RENAME COLUMN`, `RENAME TABLE`, `COMMENT COLUMN`.
- `src/sqlancer/clickhouse/gen/ClickHouseAlterGenerator.java` (new file).
- `src/sqlancer/clickhouse/ClickHouseProvider.java` — register `ClickHouseStatement` cases for ALTER in the statement bag, with low probability (≈5 %) so they don't dominate runs.
- Sqlancer schema-cache invalidation: after ALTER lands, the in-memory `ClickHouseSchema` snapshot must be refetched. Add a `state.invalidateSchema()` call wired through `Provider`.
- Expected errors: `Code: 36 (BAD_ARGUMENTS)` on type-narrowing MODIFY, `Code: 47 (UNKNOWN_IDENTIFIER)` on rename target collisions.

### Validation

- 15-min smoke. Schema invalidation correctness is the riskiest piece — add a unit test that asserts `state.getSchema()` returns the post-ALTER column shape.

### Risks

- Concurrent ALTER + INSERT in different sqlancer worker threads against the same table. Worker threads operate on distinct schema names by convention, so this should not race; verify by adding an assertion that ALTER targets only the worker's own schema.

---

## Mutations: ALTER UPDATE/DELETE + lightweight DELETE + barrier helper

### Scope

- New AST: `ClickHouseAlterMutation` (ALTER TABLE … UPDATE col=expr WHERE p / ALTER TABLE … DELETE WHERE p) and `ClickHouseLightweightDelete` (DELETE FROM t WHERE p).
- `src/sqlancer/clickhouse/gen/ClickHouseMutationGenerator.java` (new file).
- New helper: `ClickHouseMutationBarrier.waitForMutations(state, tableName, timeoutSeconds)` — polls `system.mutations WHERE table=tableName AND is_done=0`. Default timeout 30 s; on timeout, log a warning and continue (don't abort the whole run, but skip the next oracle iteration on that table).
- `ClickHouseProvider.java` — emit mutations at low probability (≈3 %), always immediately followed by the barrier helper.
- Expected errors: `Code: 159 (TIMEOUT_EXCEEDED)` on barrier timeout, `Code: 36` on UPDATE of non-existent column, `Code: 32 (ATTEMPT_TO_READ_AFTER_EOF)` on lightweight delete from empty table (rare CH bug class — confirm not a real bug before adding).

### Validation

- 15-min smoke per oracle. Mutation barrier wall-clock cost is a real budget item; if smoke runs surface average mutation completion >10 s, drop the mutation probability to 1 %.

### Risks

- `apply_mutations_on_fly` setting interaction: mutations that never finish on the merge thread still get applied virtually on read. SEMR-style toggle of this setting (dependency on completed SEMR plan) will likely surface optimizer-path bugs. Add as a SEMR randomisation candidate.
- Mutation × projection × MV × lightweight-delete interaction is the highest historical bug density in CH. Expect a wave of new findings; budget triage time accordingly.

---

## SELECT … FINAL + final=1 + FINAL/OPTIMIZE differential oracle

### Scope

- `src/sqlancer/clickhouse/ast/ClickHouseSelect.java` — add `final` boolean flag, render as `… FINAL` after FROM target.
- `ClickHouseExpressionGenerator.java` / generator: emit FINAL with ≈10 % probability on selects against ReplacingMergeTree/SummingMergeTree (currently those engines are pinned out — this workstream also re-enables them with the function-of-numeric ORDER BY guard noted in `TableGenerator.java:65`).
- New oracle: `ClickHouseFinalMergeOracle.java` under `oracle/final/`. Per iteration:
  1. Capture `result_before = SELECT … FROM t` (no FINAL, multi-part table).
  2. Capture `result_final = SELECT … FROM t FINAL`.
  3. `OPTIMIZE TABLE t FINAL`; capture `result_after = SELECT … FROM t`.
  4. Assert `result_final == result_after`. (`result_before` may legitimately differ — that's the merge-pending state; it's not asserted.)
- Engine generator unpin (`TableGenerator.java:68`): refuse function-of-numeric ORDER BY for Replacing/Summing/Aggregating; allow column-only ORDER BY.

### Validation

- 15-min smoke. Re-enabling dedupe engines is risky — re-run a recent stable baseline against just MergeTree to confirm this workstream doesn't regress noise levels.

### Risks

- `do_not_merge_across_partitions_select_final` setting interaction: FINAL behaves differently when this is on; randomise via SEMR.

---

## Statistics

### Scope

- `ClickHouseAlterStatistics` AST: `ALTER TABLE … MODIFY STATISTICS col TYPE tdigest, uniq, count_min`.
- DDL also supports inline `STATISTICS (tdigest)` on column declarations — add to `ClickHouseColumnBuilder.java` as a low-probability opt-in.
- `MATERIALIZE STATISTICS col IN PARTITION p`.
- SEMR randomisation pool addition: `allow_statistics_optimize`, `allow_statistic_optimize` (typo'd alias still supported in CH HEAD).
- No new oracle in this workstream; SEMR already covers the relevant "Q invariant under setting toggle" pattern, and this workstream makes statistics presence the toggled axis.

### Validation

- 15-min smoke.

### Risks

- Statistics is a young CH subsystem (24.5 first ship, evolving in 25.x). Expect SEMR oracle to surface findings; treat them as real bugs by default.

---

## Quota / Settings Profile / Row Policy DDL

### Scope

- New AST: `ClickHouseCreateQuota`, `ClickHouseCreateSettingsProfile`, `ClickHouseCreateRowPolicy`, plus matching ALTER + DROP.
- `ClickHouseRowPolicyOracle.java` (line ~1-200) currently hand-rolls policies inline. Refactor to use the new generator so policies are co-generated with schemas and the oracle can vary them freely. Keep the oracle's invariant unchanged.
- DROP cleanup at end of each iteration's database lifecycle (so quotas / profiles don't accumulate across iterations in the same CH instance).
- Expected errors: `Code: 192 (UNKNOWN_USER)` if a profile references a non-existent user; `Code: 497 (ACCESS_DENIED)` if RowPolicy + quota cross-block legitimate queries.

### Validation

- 15-min smoke per oracle. RowPolicyOracle's invariant must continue to hold under the new generator.

### Risks

- Quotas have a time-window component; iteration loops faster than the smallest quota window (1 second). Set quota windows to MAX_INT or skip TIME-based quota generation.

---

## Codec breadth

### Scope

- `src/sqlancer/clickhouse/gen/ClickHouseColumnBuilder.java` — currently emits CODEC clauses (per audit), but only `LZ4HC` per saved reproducers. Widen to: `LZ4`, `LZ4HC(level)`, `ZSTD(level)`, `ZSTD_QAT(level)`, `DEFLATE_QPL`, `NONE`, `DoubleDelta` (numeric only), `Gorilla` (Float32/64 only), `FPC` (Float32/64 only), `T64` (integer/Date/DateTime only), `Delta` (numeric), and codec chains (e.g. `Delta(2), ZSTD(3)`).
- Add type-aware filter: each codec has a constraint on supported column type. Build a `CodecConstraintTable` at module init.

### Validation

- 15-min smoke. No new oracle; this is generator-only coverage widening. Smoke should produce a `system.parts` distribution with diverse `data_compressed_bytes` / `data_uncompressed_bytes` ratios (verify post-run).

### Risks

- Misapplied codec → INSERT-time error, increases the "failed CREATE TABLE" rate in saved reproducers. Mitigate by validating codec×type at the generator level, not at the CH level.

---

## Dictionaries

### Scope

- New AST: `ClickHouseCreateDictionary`, `ClickHouseDropDictionary`, `ClickHouseAlterDictionary`. Dictionary lifecycle DDL.
- `ClickHouseDictionaryGenerator.java` — emit CREATE DICTIONARY over an existing CH-sourced table (LAYOUT = hashed | complex_key_hashed | flat | range_hashed; SOURCE = CLICKHOUSE(table=…); LIFETIME 0 for static dicts).
- Expression generator additions: `dictGet(d, col, key)`, `dictGetOrDefault`, `dictGetOrNull`, `dictHas`. Routed through `ClickHouseExpressionGenerator.java`.
- New oracle: `ClickHouseDictGetVsJoinOracle.java` under `oracle/dict/`. Asserts `SELECT … dictGet('d', 'col', t.k) FROM t` ≡ `SELECT … src.col FROM t LEFT JOIN src ON t.k = src.k` for every CLICKHOUSE-sourced dictionary. Built-in differential.
- DROP DICTIONARY in cleanup at end of iteration.

### Validation

- 15-min smoke. Dictionary cache invalidation under source DELETE is a known historical bug class — expect findings; defer fixing them to upstream.

### Risks

- Dictionary cache behaviour depends on LIFETIME; randomised LIFETIME values can cause test-iteration timing flakes. Pin LIFETIME 0 (static) for the dict differential oracle; vary it only in SEMR-style robustness sweeps.

---

## JOINs in the generator

### Scope

- New AST: `ClickHouseJoin` with `JoinKind ∈ {INNER, LEFT, RIGHT, FULL, CROSS}` (ASOF/ANY/PASTE deferred to a later workstream) and `Strictness ∈ {ALL, DISTINCT}`.
- `ClickHouseSelect.java` already has a `FROM` field; extend to accept a join tree.
- `ClickHouseExpressionGenerator.java`: synthesise join keys typed-consistently across both sides (string × string, integer × integer; widen via `accurateCast` when widths differ).
- Existing `JoinAlgorithmOracle.java` `PartitionMirrorOracle.java` `CastOracle.java` currently hand-roll JOINs — keep their hand-rolling for backwards compat, but expose a `ClickHouseJoinGenerator.singleJoin(state, tables)` helper they can opt into.
- Statement bag: ≈ 30 % of SELECTs include a join when ≥ 2 tables exist in the schema.

### Validation

- 15-min smoke. Joins multiply the comparator's per-row cost (more columns); confirm throughput hit ≤ 20 %.

### Risks

- Join keys can produce Cartesian explosions on small tables. Cap query LIMIT at 10 000 rows in generator output to keep `max_result_rows=1_000_000` budget intact.

---

## Subqueries in FROM / SELECT

### Scope

- `ClickHouseSelect.java` — `FromTarget` becomes a sealed type: `TableRef | Subquery | JoinTree`.
- `ClickHouseExpressionGenerator.java` — `generateSubquery(int depthBudget)` recursively builds a subquery within the existing table set, bounded at depth 2.
- Scalar subqueries in SELECT: `SELECT t.c, (SELECT count() FROM other) FROM t`. Bounded at one per SELECT.

### Validation

- 15-min smoke. Expected new errors: `Code: 70` on scalar-subquery returning >1 row, `Code: 184 (UNKNOWN_AGGREGATE_FUNCTION)` on misformed aggregates inside subquery.

### Risks

- Depth-2 subqueries produce very long query strings. Confirm parser overhead is acceptable.

---

## CTEs (WITH)

### Scope

- Two CTE forms in CH:
  1. `WITH expr AS alias` — alias-CTE, scalar reuse.
  2. `WITH name AS (SELECT …)` — subquery-CTE.
- New AST: `ClickHouseCTE` with both variants. Threaded through `ClickHouseSelect.java`.
- `ClickHouseExpressionGenerator.java` — pick from a per-query pool of CTE aliases when emitting expressions / FROM targets.
- Cap CTE count per query at 3.

### Validation

- 15-min smoke. CTEs intersect heavily with the analyzer; expect SEMR-style findings under `allow_experimental_analyzer={0,1}`.

### Risks

- CH HEAD has dropped the legacy non-analyzer path on most release branches but the setting still exists; with both paths exercised, divergences are usually real bugs against the legacy path, not the analyzer. Tag findings accordingly.

---

## PREWHERE in the generator

### Scope

- `ClickHouseSelect.java` — already accepts a where clause; split into `prewhere` + `where`.
- Generator: with ≈ 20 % probability, route a subset of WHERE predicates into PREWHERE (must reference base-table columns only, no expressions touching JOIN-side columns).
- `optimize_move_to_prewhere` setting — add to SEMR pool.

### Validation

- 15-min smoke. PREWHERE × lightweight-DELETE × FINAL is one of the documented historical landmines; expect findings.

### Risks

- Generator-level PREWHERE must respect the "columns only from primary table" rule; misroute → `Code: 47 (UNKNOWN_IDENTIFIER)`.

---

## Window functions

### Scope

- New AST: `ClickHouseWindowFunction` with:
  - `name ∈ {ROW_NUMBER, RANK, DENSE_RANK, NTILE, LAG, LEAD, FIRST_VALUE, LAST_VALUE, NTH_VALUE, percent_rank, cume_dist}` plus aggregates-as-window.
  - `partitionBy: List<Expression>`.
  - `orderBy: List<Expression>`.
  - `frame: WindowFrame` with `kind ∈ {ROWS, RANGE, GROUPS}`, `start`, `end` (each one of UNBOUNDED PRECEDING / FOLLOWING / CURRENT ROW / n PRECEDING / n FOLLOWING), optional `EXCLUDE ∈ {NO OTHERS, CURRENT ROW, GROUP, TIES}`.
- `ClickHouseToStringVisitor.java` — full window-function renderer.
- New oracle: `ClickHouseWindowEquivalenceOracle.java` under `oracle/window/`. Built-in equivalences:
  - `sum(x) OVER (ORDER BY id ROWS UNBOUNDED PRECEDING)` at the last row ≡ `sum(x)` over the full table.
  - `row_number() OVER (ORDER BY id)` at row K ≡ K.
  - `lag(x, 1) OVER (ORDER BY id)` ≡ a self-join offset by 1.
  - `count(*) OVER ()` ≡ `count(*)` (scalar).

### Validation

- 15-min smoke. Window functions are the largest single AST addition; expect parser overhead and long query strings.

### Risks

- RANGE frame semantics over Decimal / DateTime64 columns have a history of off-by-one bugs at frame boundaries. Plan-time decision: do we emit RANGE frames in this workstream or split into a follow-up? Recommendation: include — that's where the bugs are.

---

## ARRAY JOIN

**Empty if the active `2026-05-18-002-feat-clickhouse-query-primitives-plan.md` lands first.** Otherwise:

### Scope

- New AST: `ClickHouseArrayJoin` with `LEFT ARRAY JOIN` / `ARRAY JOIN` variants; attaches to FROM.
- Generator: ≈ 10 % probability on tables that have an Array column (currently only Array(T)) or, after Nested lands, a Nested column.

### Validation

- 15-min smoke.

### Risks

- LEFT ARRAY JOIN vs ARRAY JOIN semantic difference on empty arrays — must be type-aware in the comparator-friendly direction.

---

## ASOF / ANY / PASTE JOIN

### Scope

- Extension of the JOINs workstream's `JoinKind` enum: `ASOF` (ordered key join), `ANY` (first-match), `PASTE` (positional zip).
- ASOF requires a typed-key `>=` predicate as the last ON condition; generator must pick a numeric/date column on each side for the inequality match.
- PASTE requires both sides to have the same row count and no ON clause; generator must drive matching cardinalities via constrained `numbers()` table-function arguments.

### Validation

- 15-min smoke. Expected errors: `Code: 48 (NOT_IMPLEMENTED)` on ASOF without an inequality key, `Code: 50 (UNKNOWN_TYPE)` on PASTE mismatch.

### Risks

- ASOF + parallel-replicas + projection is a known CH landmine; expect findings.

---

## Lambda / higher-order functions

### Scope

- New AST: `ClickHouseLambda(List<String> params, Expression body)`.
- Higher-order functions: `arrayMap`, `arrayFilter`, `arrayFold`, `arrayCount`, `arrayExists`, `arrayAll`, `arraySplit`, `arraySort` (with comparator lambda), `arrayFirst`, `arrayLast`.
- Generator: when a column is `Array(T)`, with ≈ 20 % probability wrap the column reference in a higher-order function with a synthesised lambda body (depth-1 expression over the lambda parameter).

### Validation

- 15-min smoke. Lambdas appear in many real-world bug reports; expect findings.

### Risks

- Lambdas over `Nullable(T)` arrays — the lambda parameter type must be `Nullable(T)`, which the expression generator currently doesn't propagate. Add a `lambdaParamType` propagation pass.

---

## Cross-cutting concerns

### Expected-errors catalogue
Every workstream adds new tolerated CH errors. Keep `ClickHouseErrors.java`'s additions tightly scoped (one constant block per workstream) and add a top-of-file index so it remains audit-able. The audit's noted risk — that the global allowlist could swallow a real bug for a different oracle — is mitigated by per-oracle scoping; that's a separate follow-up in the "triage automation" plan.

### Validation throughput baseline
Current baseline (2026-05-26 3-h run on dev-VM):
- 987 k queries / 14 databases / ~110 q/s / 92 % statement success / 0 fatal worker deaths.
- 3 saved reproducers, all false-positive families (1× TLPCombinator ULP, 2× 26.6 DISTINCT-NaN).

Each PR gate:
- Throughput ≥ 80 % of baseline at 6 threads × 12 GiB heap × 6 cpu / 28 GiB CH.
- New false-positive families ≤ 1 (catalogued in the PR description with grep-able root cause).

### Cross-version sanity
After the correctness foundation, mutations, FINAL diff oracle, and window functions land, run a one-shot validation against `clickhouse-server:24.10` (LTS) in addition to HEAD to confirm features don't depend on HEAD-only behaviour.

### Critical path
The shortest dependency chain that produces the largest bug-finding impact:

1. Correctness foundation
2. ALTER COLUMN scaffolding
3. Mutations
4. SELECT FINAL diff oracle

Everything else (composite types, temporal types, geo, codecs, PREWHERE, ARRAY JOIN, lambdas) is highly parallelisable once the correctness foundation is in.

## Out-of-scope (track separately)

1. Triage automation: fingerprint dedup ledger, cross-version replay matrix, ddmin reproducer minimiser.
2. Persistent seed corpus + coverage-keyed mutation.
3. Per-oracle scoped error allowlists.
4. Run-shape regression metric on the fuzzer itself.
5. Replicated/Distributed engines.
6. External table-function ingest (Iceberg, Parquet, S3, MySQL, PostgreSQL sources).
