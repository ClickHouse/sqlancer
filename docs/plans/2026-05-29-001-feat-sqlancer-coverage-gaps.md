---
title: "Analysis: SQLancer coverage gaps vs recent CH wrong-result + fuzz bugs"
type: analysis
status: draft
date: 2026-05-29
---

# What SQLancer is missing to catch recent ClickHouse bugs

Sources analysed:
- 7 wrong-result + 5 crash issues authored by AnotherYx (last 6 months)
- 12 most recent issues labelled `fuzz`

## The dominant unmissed pattern: **Materialization round-trip**

6 of 7 of AnotherYx's wrong-result bugs share a structural shape that **no
sqlancer oracle currently exercises**: the bug surfaces only when an
intermediate result is materialised into a table before the next query reads
it.

Representative bugs:

| # | Title | Materialisation step |
|---|-------|----------------------|
| 106080 | Conjunctive filter evaluated in one step loses row | `INSERT INTO temp_0 SELECT ... WHERE p1; SELECT ... FROM temp_0 WHERE p2` |
| 106082 | Scalar subquery equality loses only matching row | `INSERT INTO temp ... ; WHERE col = (SELECT ... FROM temp)` |
| 106083 | Same — scalar subquery + predicate materialisation | same shape |
| 106084 | OR-splitting loses row from NOT A AND B branch | `INSERT INTO temp SELECT ... FROM joined; WHERE OR(...)` |
| 105716 | RIGHT OUTER JOIN default rows survive INNER JOIN | `INSERT INTO temp SELECT ... FROM t1; RIGHT OUTER ... INNER JOIN t2` |
| 105717 | Same | identical shape, slight variation |
| 105718 | Memory-materialised subquery changes AVG(Float64) | `INSERT INTO mem_table SELECT * FROM source; SELECT AVG(...) FROM mem_table` |

**Why no oracle catches these.** Every wrong-result oracle today
(TLPWhere/Distinct/GroupBy/Having/Aggregate, NoREC, PQS, CERT, CODDTest,
SEMR, JoinAlgorithm, Cast, etc.) compares **two queries against the same
schema**. None test: *"the same logical data, accessed via two different DDL
paths (direct vs materialised), returns identical results."*

The 7 bugs all fall through the gap because each is detected by comparing a
direct read to a read-via-temp-table — a comparison sqlancer never makes.

### Proposed: `MaterializationRoundtripOracle`

Pseudocode for the new oracle:

```
1. Pick a SELECT q over the existing schema (any oracle's q would work, but
   start with the TLPBase fetch-column synthesis).
2. result_direct = q.
3. Render the column types of q's output (via `DESCRIBE (q)` or
   `SELECT * FROM (q) LIMIT 0` + system.columns inspection).
4. CREATE TABLE temp_N AS q  -- captures types
   OR  CREATE TABLE temp_N (...) ENGINE = Memory + INSERT INTO temp_N q
   (Memory engine is critical for bug #105718.)
5. result_materialized = SELECT * FROM temp_N.
6. Assert result_direct == result_materialized as multisets.
7. DROP TABLE temp_N.
```

**Variants to cover the bug families:**
- Vary the target engine: `MergeTree`, `Memory`, `Log`. Bug #105718
  specifically requires Memory; bug #105716 specifically requires Log.
- Vary whether `q` is a simple SELECT, a JOIN, a scalar subquery, or contains
  an OR predicate. Drives bugs 106080-106084 from above.
- Vary whether a predicate is split across the materialisation (`WHERE p1`
  before INSERT, `WHERE p2` after) vs applied as a single conjunction.

### Why this would have caught all 7

Each of the 7 bugs already has a published reproducer where the
materialised path disagrees with the direct path. The oracle's invariant
"both paths produce the same result" is exactly violated. The generator only
needs to produce the *shape* (multi-step pipeline, OR predicate, OUTER JOIN
chain) — which our existing generator already does in fetch columns and
WHERE — to surface the bug.

---

## The second unmissed pattern: **Subquery wrapping**

Bug #105743 (fuzz-labelled, last week):

```sql
-- Returns 500 rows with one row_number()-sequence
SELECT row_number() OVER (PARTITION BY tuple(''), g
                          ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), a
FROM _s1;

-- Returns 500 rows with a DIFFERENT row_number()-sequence
SELECT *
FROM (SELECT row_number() OVER (PARTITION BY tuple(''), g
                                ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), a
      FROM _s1);
```

This violates the invariant: *"wrapping a query in `SELECT * FROM (...)`
must not change the rows produced."*

**No oracle today tests this directly.** ViewEquivalenceOracle tests a view
vs an inlined query (which is conceptually similar but not the same:
ClickHouse's view-expansion path differs from raw-subquery-flattening).

### Proposed: `SubqueryWrappingOracle`

```
1. Pick a SELECT q.
2. result_direct = q.
3. result_wrapped = "SELECT * FROM (" + q + ")".
4. Assert result_direct == result_wrapped.
```

This is trivial to implement and would have caught #105743 immediately.

---

## The third unmissed pattern: **OUTER → INNER JOIN chain with default-value propagation**

Bugs #105716 and #105717 are duplicates of each other (same root cause):

```sql
SELECT t2.c0
FROM t0
RIGHT OUTER JOIN temp_1 AS subq0
  ON ((t0.c0 = subq0.subq0_c0) AND (t0.c0 IS NULL))
INNER JOIN t2
  ON (t0.c0 = t2.c0);
```

When `t0` is empty, the RIGHT OUTER JOIN populates `t0.c0` with default
values (empty string for `String`). The INNER JOIN against `t2` should
reject these because `'' != 'a'` — but doesn't.

### What sqlancer is missing

- **Empty-table coverage**: sqlancer's TableGenerator always populates every
  table via the INSERT action. There's no path to deliberately leave a table
  empty. Bug #105716 only fires when `t0` is empty.
- **OUTER→INNER chain**: sqlancer's `gen.getRandomJoinClauses` adds JOINs
  iteratively but doesn't bias toward OUTER followed by INNER (the bug
  shape).
- **Engine variety**: Bug #105716 uses Log engine, not MergeTree. Sqlancer's
  TableGenerator emits only MergeTree-family engines.

### Proposed: `OuterInnerJoinChainOracle`

```
1. Pre-emptively create some tables WITHOUT any inserts (empty tables).
2. Build a JOIN tree of N ≥ 3 tables.
3. Force at least one OUTER JOIN earlier than at least one INNER JOIN.
4. Assert: result is empty whenever any table that the OUTER JOIN's
   null-producing side defaults from has rows that the INNER JOIN's predicate
   excludes.
```

Lower-leverage by itself, but cheap to add once the empty-table fixture is
in.

### Generator additions needed

- **Memory + Log engines** in the engine pool, with the existing engine-
  schema-aware filter (so Memory doesn't pick ORDER BY etc.).
- **Empty-table action**: with low probability, skip the INSERT for one
  table, so the schema has both populated and empty fixtures.

---

## Float-aggregation order (bug #105718)

```
Without materialisation: AVG = 1526333417.9989731
With Memory materialisation: AVG = 1526333417.9989934
```

The difference is in the last few ULP, but reproduces deterministically
based on whether the source is read inline or via Memory table.

This is *not* a sqlancer-side false positive — the ULP drift is observable
and reproduces.

**To catch this**: the `MaterializationRoundtripOracle` above with multiset
comparison in `ComparisonMode.SET` (strict, not ULP-tolerant) and a numeric
column with > 10⁹ scale values. The current `ULP_TOLERANT_MULTISET` would
absorb the divergence; the strict path surfaces it.

---

## Crashes (#100325-#100329, all by AnotherYx)

| # | Crash signature |
|---|-----------------|
| 100325 | IAST::setAlias_aborted |
| 100326 | IdentifierResolveScope_aborted |
| 100327 | ExpressionActions_aborted |
| 100328 | ColumnsDescription::rename_aborted |
| 100329 | IColumn::assertTypeEquality_aborted |

These are CH-side `chassert(...)` aborts (ASAN/UBSAN builds only). Against a
release build, they manifest as benign JDBC exceptions, so sqlancer's
oracles silently absorb them via the expected-errors path.

**What's missing**: a **server-health probe** between iterations.

### Proposed: `connectionHealthCheck()` after every oracle iteration

```
After each ProviderAdapter.generateAndTestDatabase() call:
  1. SELECT 1 against the connection.
  2. If it fails with a connection-refused / EOF / timeout error:
     a. Capture the last N queries from this iteration's log.
     b. Throw an explicit AssertionError("server crashed").
  3. Otherwise: proceed.
```

This wouldn't catch chassert aborts on release builds (CH doesn't abort
there), but **against an ASAN build, every chassert would crash the server
and be detected immediately.** The 5 chassert bugs above would all be
visible the moment the random generator produced the trigger shape.

The existing connection-test path (`--use-connection-test`) is disabled
in our setup. Even when enabled it only runs at startup, not between
iterations.

---

## ColumnsDescription::rename (#100328) — generator-side hint

The crash signature is `ColumnsDescription::rename_aborted`. That code path
fires during `ALTER TABLE ... RENAME COLUMN`. We added that emission in
workstream 8, so we are exercising the surface — but only against a release
build that won't abort. **An ASAN run of just the existing ALTER COLUMN
generator would likely surface this crash within minutes.**

Action item: cross-version sanity against an ASAN build. Already noted as
`Cross-version sanity` in `2026-05-27-001-feat-clickhouse-coverage-
expansion-plan.md`.

---

## Summary table

| Oracle / fixture | Bugs it would catch | Effort |
|------------------|---------------------|--------|
| `MaterializationRoundtripOracle` (MergeTree + Memory + Log variants) | #106080-106084 (4 bugs), #105716, #105717, #105718 | Medium |
| `SubqueryWrappingOracle` | #105743 | Small |
| Empty-table fixture + `OuterInnerJoinChainOracle` | #105716, #105717 (also caught by above) | Small |
| Memory + Log engine in pool | #105716, #105718 | Small (schema-aware filter exists) |
| Per-iteration connection health probe | #100325-100329 (under ASAN build) | Small |
| Cross-version validation against ASAN build | All 5 chassert bugs | Tooling |
| `PredicateStageOracle` (one-step vs multi-step filter) | #106080 | Medium |

## Priority recommendation

If you only build one new oracle, build **`MaterializationRoundtripOracle`
with Memory + MergeTree + Log engine variants** — it would have caught 6 of
the 7 wrong-result bugs in the AnotherYx set, all of which were filed in
the last few months. The structural pattern is identical across all of
them, and our existing generator can already produce the inputs.

If you build a second, build **`SubqueryWrappingOracle`** — it's ~30 LOC
and catches the most recent labelled-fuzz win bug (#105743).

The remaining items (empty-table fixture, engine pool widening, ASAN build,
per-iteration health probe) are infrastructure changes that compound across
all future oracles.
