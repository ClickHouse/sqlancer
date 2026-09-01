# Fix the two oracle defects behind the 2026-08-25..09-01 NightlySQLancer red runs

Status: implemented in https://github.com/ClickHouse/sqlancer/pull/16 (2026-09-01). Task 1 landed as exclusion, not Option A; see the PR for why mandatory FINAL is unsound on these engines, and note that the TLPWhere that fired is the shared `TLPWhereOracle`, whose tables come from `TestOracleUtils`, not `ClickHouseTLPBase`. Original text follows unchanged. Author handoff from a CI triage of the four most recent `NightlySQLancer` runs (ClickHouse/ClickHouse). Scope is this repository's Java code only. No ClickHouse changes here.

## Why this document exists

`NightlySQLancer` went red on 2026-08-25, 08-28, 08-31 and 09-01. The findings were triaged one by one. The conclusion:

| Family | Verdict |
| --- | --- |
| `CountOptimization`, `PartitionMirror`, `SubqueryMaterialize`, `TLPAggregate`, `TLPDistinct`, `TLPGroupBy`, `ViewEquivalence`, `ReadInOrderToggle`, `FinalMerge`, `TLPSetOp` on the predicate `(((NOT ((NOT (col)))))<op>(const))` | **Real ClickHouse bug.** Do not touch. |
| `QueryConditionCache / Code 701 CLUSTER_DOESNT_EXIST` (67 of 87 findings on 2026-08-25) | Already fixed CI-side by ClickHouse commit `770043a2cce`, which adds a `default` cluster to the job's `config.d`. Gone in all later runs. Nothing to do. |
| `TLPWhere` 2026-09-01 `database3` | **False positive in this repo.** Task 1 below. |
| `CountOptimization` reproducer content | **Defect in this repo.** Task 2 below. |

So 10 of the 11 findings in the latest run are one genuine upstream wrong-results bug, and the remaining work on our side is two contained defects plus an audit.

### The real ClickHouse bug, for context only

A predicate of the shape `(((NOT ((NOT (col)))))<=(const))` is a tautology, because `not(not(x))` is 0 or 1. ClickHouse nevertheless derives a real key range from it and silently drops rows:

```sql
CREATE TABLE tp (c0 Int32) ENGINE = MergeTree() ORDER BY tuple() PARTITION BY c0;
INSERT INTO tp VALUES (-5), (0), (5), (100);

SELECT groupArray(c0) FROM tp WHERE (((NOT ((NOT (c0)))))<=(1.5));   -- [0,-5]   WRONG
SELECT countIf((((NOT ((NOT (c0)))))<=(1.5))) FROM tp;               -- 4        correct
```

`EXPLAIN indexes=1` reports `Min-Max / Partition  Condition: (c0 in (-Inf, 1])  Parts: 2/4`. Root cause is `src/Storages/MergeTree/KeyCondition.cpp`, the `if (name == "not")` branch of `cloneDAGWithInversionPushDown`: it flips `need_inversion` and drops the `not` node without checking `boolean_context`, so `not(not(x))` collapses to `x`. That is valid only in truth-tested position; as a comparison argument `not(not(x))` means `x != 0`. Filed as https://github.com/ClickHouse/ClickHouse/issues/117581. Verified reproducing on 26.8.1 (partition pruning, partition minmax and primary-key granule pruning) and on the CI head 26.9.1.1. No other upstream issue covers it (searched `KeyCondition`, `cloneDAGWithInversionPushDown`, `boolean_context`, `double negation`, `NOT NOT`, `tautology`, `partition pruning`; the closest are #109998, #110266, #112242 and #105009, all closed and distinct).

**Do not gate, suppress, or stop generating this predicate shape.** It is the most valuable thing the 2026-08 oracle batch found. Add an entry for #117581 to the `## Filed ClickHouse bugs` list in `.claude/CLAUDE.md` now, and delete it when the issue closes, per the convention already used there for #107186 / #106649 / #106125.

If you write your own reproducer, keep SQLancer's parenthesisation. `(((NOT ((NOT (c0)))))<=(1.5))` parses as `(NOT (NOT c0)) <= 1.5`, which is the intended meaning, but `NOT(NOT(c0)) <= 1.5` parses as `NOT ((NOT c0) <= 1.5)`, a different expression, because prefix `NOT` binds looser than comparison. Check any hand-written form with `SELECT formatQuery('...')` before drawing a conclusion.

---

## Task 1 (P0): read-twice oracles race background merges on merging engines

### Evidence

2026-09-01 `arm_release`, `database3`, `TLPWhere`:

```
The size of the result sets mismatch (216 and 204)!
First  query: (SELECT * FROM t1, t0)                                            -> 216
Second query: (SELECT * FROM t1, t0 WHERE p) UNION ALL ... NOT p ... IS NULL     -> 204
```

`t0` holds 12 rows, `t1` holds 18. 216 is 18x12 and 204 is 17x12, so exactly one `t1` row disappeared between the first and the second read. `t1` is:

```sql
CREATE TABLE t1 (c0 SimpleAggregateFunction(min, Int64), c1 Date32, c2 UInt32)
ENGINE = AggregatingMergeTree() ORDER BY c1 PARTITION BY (- (c2)) ...
```

Three separate `INSERT`s omit `c1`, so three parts carry `c1 = '1970-01-01'`. A background merge collapsed two of them between the two queries. The TLP partition itself is sound: `p = cos(t1.c2)` is Float64, and `p` / `NOT p` / `p IS NULL` is a complete partition of a Float64 truth value. The relation underneath it is not stable, so the TLP invariant simply does not apply.

The provider already pins `alter_sync=2`, `mutations_sync=2` and `async_insert=0` in `.claude/clickhouse-config/` for exactly this class of problem. Background merges are the remaining hole: nothing in the config or the oracles keeps them from firing between two reads of the same table.

### Where

- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java:146`

```java
if (select.getJoinClauses().isEmpty() && table.getTable().supportsFinal()
        && Randomly.getBooleanWithRatherLowProbability()) {
    select.setFinal(true);
}
```

`ClickHouseTable.supportsFinal()` (`src/sqlancer/clickhouse/ClickHouseSchema.java:454`) already returns exactly the unstable set: `ReplacingMergeTree`, `SummingMergeTree`, `AggregatingMergeTree`, `CollapsingMergeTree`, `VersionedCollapsingMergeTree`. The problem is the `Randomly.getBooleanWithRatherLowProbability()` gate: most of the time a merging engine is read twice with no `FINAL`, which is a race by construction.

### Fix

Three options, in order of preference.

**Option A (recommended): make `FINAL` mandatory, not probabilistic, whenever the oracle will read the same table more than once.**

At `ClickHouseTLPBase.java:146`, drop the probability gate and set `FINAL` unconditionally when `table.getTable().supportsFinal()` and there are no join clauses. TLP over `SELECT ... FINAL` is still a valid partition of a well-defined relation, so no coverage of the merging engines is lost. It does lose the "non-FINAL read of a merging engine" shape, which is what `FinalMerge` exists to cover deliberately.

The join case (`!select.getJoinClauses().isEmpty()`) cannot take `FINAL` per-table here, so for that path reject the table instead: throw `IgnoreMeException` when any joined table `supportsFinal()`.

**Option B: per-table merge freeze around a check.** Wrap the body of each read-twice check in

```java
new SQLQueryAdapter("SYSTEM STOP MERGES " + table.getName(), errors, true).execute(state);
try { ... } finally {
    new SQLQueryAdapter("SYSTEM START MERGES " + table.getName(), errors, true).execute(state);
}
```

More general than A and it preserves the non-`FINAL` shape, but it does not settle a merge that is already in flight, so it needs an `OPTIMIZE TABLE ... FINAL` (or a wait on `system.merges`) to reach a fixed point first, and it adds two statements per check. Do not use a global `SYSTEM STOP MERGES`: several oracles (`FinalMerge`, `PartitionLifecycle`, `ConcurrentMutation`) need merges to happen.

**Option C: reach a fixed point once.** After data generation, run `OPTIMIZE TABLE t FINAL` on every table where `supportsFinal()`. Cheapest to implement, but it is only correct until the next `INSERT`, and the generators insert throughout a session, so treat this as a fallback rather than the fix.

Whatever is chosen, put the decision behind one shared helper rather than copying it per oracle, for example `ClickHouseTable.isStableForRepeatedReads()` next to `supportsFinal()`, so the audit in Task 3 has a single call site to check.

### Acceptance

- A targeted run of `TLPWhere`, `TLPDistinct`, `TLPGroupBy`, `TLPAggregate` and `TLPHaving` restricted to the merging engines produces zero `The size of the result sets mismatch` findings whose two cardinalities are exact multiples of a smaller table's row count.
- `FinalMerge` still fires on `AggregatingMergeTree` and friends (it must keep reading them without `FINAL` on one side; it is the oracle whose whole point is that comparison).
- `mvn -B package -DskipTests=true -Djacoco.skip=true` passes and the Eclipse formatter is clean.

---

## Task 2 (P1): `CountOptimization` writes a reproducer that does not contain the failing SQL

### Evidence

2026-09-01 `arm_release`, `database12.log`, 30 MB, `CountOptimization` finding:

```
count-optimization row-drop cross-check mismatch: predicate (((NOT ((NOT (t0.c0)))))<=(1))
  count() WHERE pred = 9997
  countIf(pred)      = 10000
  table: t0
```

`grep -cF optimize_trivial_count_query database12.log` returns **0**, and there is no `count()) FROM t0` line anywhere in the file. By contrast `ClickHouseExtendedDatetimeOracle`, which runs the identical `count() WHERE pred` versus `countIf(pred)` cross-check, contributes 4062 logged `toString(count())` statements to the same file. Triaging this finding required rebuilding the six queries by hand from the oracle source.

### Root cause

`ComparatorHelper.getResultSetFirstColumnAsString` (`src/sqlancer/ComparatorHelper.java:66`) calls only `state.getLogger().writeCurrent(queryString)`. That writes to the rolling `databaseN-cur.log`, **not** to the reproducer. The reproducer `databaseN.log` is built from `StateToReproduce.statements` (`src/sqlancer/StateToReproduce.java:54`), which is only appended to by `state.getState().logStatement(...)`.

`ClickHouseCountOptimizationOracle` has zero `logStatement` calls; every read goes through `readSingleValue` -> `getResultSetFirstColumnAsString`. Its `AssertionError` also does not embed the SQL, only the predicate text and the two counts. The result is a finding with no reproducible statement sequence.

Same defect, lower impact, in `ClickHouseKeyConditionOracle` and `ClickHouseReadInOrderToggleOracle`: also zero `logStatement` calls, but they report through `ComparatorHelper.assumeResultSetsAreEqual`, which embeds both queries in the assertion message, so their findings stay triageable.

### Fix

In `src/sqlancer/clickhouse/oracle/countopt/ClickHouseCountOptimizationOracle.java`, adopt the pattern already used by `ClickHouseExtendedDatetimeOracle:128`:

```java
private void logStmt(String stmt) {
    if (state.getOptions().logEachSelect()) {
        state.getLogger().writeCurrent(stmt);
        state.getState().logStatement(stmt);
    }
}
```

Call it immediately before each of the six reads, at lines 72, 73, 79, 80, 85, 86, and before the two `GROUP BY` reads at 100 and 102. Prefer folding the call into `readSingleValue` (line 115) so no future read can skip it, and add an explicit `logStmt` for the two `ComparatorHelper.getResultSetFirstColumnAsString` calls in the `GROUP BY` block.

Also include the two full SQL strings in the `check:88` `AssertionError` message, the way `mismatch` at line 109 already includes `base: <sql>`.

Then do the same for `ClickHouseKeyConditionOracle` and `ClickHouseReadInOrderToggleOracle`.

### Acceptance

A forced `CountOptimization` failure produces a `databaseN.log` whose tail contains all six statements, including their `SETTINGS optimize_trivial_count_query = ...` suffixes, and replaying that file with `clickhouse-client --multiquery --ignore-error --max_partitions_per_insert_block=100000` reproduces the mismatch.

---

## Task 3 (P1): audit the 2026-08-15 and 2026-08-16 oracle batches for both defects

The two defects above are not specific to two oracles; they are the two ways a "generate, read twice, compare" oracle goes wrong in this provider. Sweep every oracle added in the P0/P1 coverage batches (`docs/plans/2026-08-15-001-feat-clickhouse-4month-coverage-gap-plan.md`) and answer, per oracle:

1. Does it read the same table more than once, or compare a table against a copy or mirror of it? If yes, does it either force `FINAL` or reject tables where `supportsFinal()`?
2. Does every statement it issues reach `StateToReproduce` via `logStatement`, or is the SQL otherwise recoverable from the `AssertionError` message?

Known state of the oracles that fired in the four runs:

| Oracle | `logStatement` calls | merge guard |
| --- | --- | --- |
| `CountOptimization` | 0 | none |
| `KeyCondition` | 0 | none |
| `ReadInOrderToggle` | 0 | none |
| `PartitionMirror` | 3 | none |
| `SubqueryMaterialize` | 4 | none |
| `FinalMerge` | 0 | uses `supportsFinal` (by design) |
| `StatisticsPartPruning` | 5 (via `logStmt`) | none |

`StatisticsPartPruning` is the newest oracle and already has the logging right; use it as the reference for new code.

---

## Task 4 (P2): note about oracle-list plumbing

`ci/jobs/sqlancer_job.sh` in ClickHouse/ClickHouse scrapes `ALL_ORACLES=` out of `.claude/run-sqlancer.sh` at run time and clones this repo's `main`. Two consequences worth knowing when landing changes here:

- Adding an oracle to `ALL_ORACLES` puts it into the nightly immediately, with no ClickHouse-side commit and therefore no praktika digest change and no cache invalidation.
- The job's only escape hatch is `EXCLUDED_ORACLES` in that shell script, currently `TextIndexDirectRead`. If a new oracle turns out to be noisy, the fix belongs here, not there.

Also note that praktika reports for master refs expire within a few days: on 2026-09-01 only the 09-01 report was still fetchable from S3, and 08-25 / 08-28 / 08-31 all returned 403. Anything older has to be triaged from `gh run view --job <id> --log`, which carries the analysis summary but no reproducer SQL. That makes Task 2 more valuable than it looks: the assertion message is often the only surviving artefact.

---

## Explicit non-goals

- Do not add the `not(not(x))` predicate shape to any suppression list, and do not remove `not` from the expression generator's value positions.
- Do not add a global `SYSTEM STOP MERGES`.
- Do not touch the `default` cluster config; that fix already landed on the ClickHouse side.
- Do not change `ComparatorHelper` to call `logStatement` for every SELECT. It is shared with every other DBMS provider in this fork, and the reproducer files would grow by the full oracle read volume. Fix it per oracle.
