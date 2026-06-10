---
title: "feat: Catch mutation-analyzer bugs (ClickHouse #106649 / PR #98884 surface)"
type: feat
status: completed
date: 2026-06-10
---

# feat: Catch mutation-analyzer bugs (ClickHouse #106649 / PR #98884 surface)

**Branch/remote:** work on the current branch `nik/clickhouse-add-pqs-cert-coddtest`, push to remote `nik` (`fm4v/sqlancer`).

## Overview

ClickHouse PR #98884 routed mutation analysis (`ALTER TABLE … UPDATE/DELETE`, lightweight
`UPDATE`/`DELETE`, `MATERIALIZE COLUMN`) through the new analyzer in 26.6. Issue #106649 is the
first fallout this fork *could not* have caught: `Code: 49 LOGICAL_ERROR "Column identifier id is
already registered"` when a mutation's `WHERE` contains `IN (subquery)` whose subquery joins two
subquery-wrapped derived tables that each project the same column name.

This plan closes the gap two ways:

1. **Generic generator coverage** — let the mutation generator emit real predicates (including
   `IN (subquery)` and a new joined-derived-tables subquery shape) so the whole fleet can stumble
   into this bug class organically.
2. **A dedicated `MutationAnalyzer` oracle** — deterministically exercises the PR #98884 surface
   matrix every iteration (mutation kinds × WHERE shapes × MATERIALIZE COLUMN × alias columns ×
   `validate_mutation_query`), with a narrow error-tolerance set and an affected-rows consistency
   assertion for wrong-result coverage beyond crashes.

## Problem Frame

Research confirmed the trigger shape is **unreachable today**:

- `gen/ClickHouseMutationGenerator.java` builds its WHERE via
  `generateExpressionWithColumns` — a numeric-only descent that can never produce `IN (subquery)`,
  scalar subqueries, or JOINs. The richer `generatePredicate()` path (which has
  `generateInSubquery`, added in roadmap U1.1) is never called from mutations.
- `generateInSubquery` only emits single-table inner SELECTs — no joins, no derived tables, no
  colliding projected names. The join AST (`ClickHouseExpression.ClickHouseJoin` +
  `ClickHouseTableReference`) cannot express `(SELECT …) AS x JOIN …`; the house escape hatch is
  pre-rendered SQL (`ClickHouseRawText`, and fully hand-built strings in self-contained oracles —
  see `oracle/materialize/ClickHouseSubqueryMaterializeOracle.java`).
- The error-tolerance side needs **no work to catch**: neither `LOGICAL_ERROR` nor
  "Column identifier … is already registered" matches any substring in `ClickHouseErrors.java`,
  so once generated, the exception propagates → AssertionError → worker death → reproducer.

PR #98884's broader surface (per its description and its test `03988_mutations_with_analyzer`):
DELETE/UPDATE with IN subquery (including subquery referencing the table being mutated —
the deadlock-avoidance path), MATERIALIZE COLUMN (incl. constant defaults), alias columns in
mutations (newly supported), virtual columns/subcolumns in mutation expressions,
`validate_mutation_query` gating, Memory engine mutations.

## Requirements Trace

- R1. The mutation generator can emit predicate-grade WHERE clauses, including `IN (subquery)`,
  so analyzer bugs in the mutation path are reachable by the general fleet.
- R2. The expression generator can emit the #106649 subquery shape: `IN (SELECT a.k FROM
  (SELECT k FROM t1) AS a JOIN t2 AS e ON … JOIN (SELECT k FROM t3) AS b ON …)` with deliberately
  colliding projected column names, usable from both SELECT predicates and mutation WHEREs.
- R3. A dedicated oracle deterministically covers the PR #98884 matrix each iteration and
  asserts mutation/SELECT affected-rows consistency (wrong-result coverage, not just crashes).
- R4. No sustained false positives: validated by the standard dev-vm convergence loop; tolerance
  discipline preserved (no broad Code 49 / LOGICAL_ERROR tolerance — only the one known-filed
  signature, temporarily, with a removal condition).
- R5. The coverage demonstrably exercises the bug *class*: the oracle and generator verifiably
  emit the trigger shapes (confirmed in transcripts/logs), so any bug of this class — current or
  future — is catchable. Reproducing the specific filed #106649 is **not** a requirement; if
  current head still has it, the catch falls out naturally (and then the signature gets pinned),
  but the plan does not depend on the bug remaining open.

## Scope Boundaries

- **No general derived-table support in the join AST.** The #106649 shape is built as raw/hand-
  rendered SQL per the `SubqueryMaterialize`/`ClickHouseRawText` precedent. Extending
  `ClickHouseJoin` to derived tables is a separate, larger refactor (roadmap U5.3 territory).
- **No correlated subqueries in mutation WHEREs.** Upstream is pivoting to rejecting them with
  `NOT_IMPLEMENTED` (PR #106025). Generate only non-correlated subqueries; tolerate the
  `NOT_IMPLEMENTED` rejection message if one slips through.
- **Memory-engine mutation coverage is a stretch variant** inside the oracle's own tables only —
  the schema generator's engine pool stays MergeTree-family.
- **No `enable_analyzer=0` differential.** The provider pins the analyzer on; toggling it off to
  diff old-vs-new analyzer mutation results would mask the very path under test and doubles the
  mutation count. Out of scope.
- Filing/triaging upstream bugs found by the new coverage is follow-on work, not part of this
  plan's done-criteria (except the #106649 validation catch in U5).

## Context & Research

### Relevant Code and Patterns

- `src/sqlancer/clickhouse/gen/ClickHouseMutationGenerator.java` — four `MutationKind`s
  (ALTER_UPDATE, ALTER_DELETE, LIGHTWEIGHT_DELETE, LIGHTWEIGHT_UPDATE); WHERE via numeric-only
  `generateExpressionWithColumns`; expected errors = `getExpectedExpressionErrors() +
  getMutationErrors()`; invoked from `ClickHouseProvider.Action.MUTATION` (weight ~1-in-6, 0/1
  per database setup).
- `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` — `generatePredicate()` (the
  full boolean predicate path), `generateInSubquery(col)` (raw-text `col [NOT] IN (SELECT c FROM
  db.t [WHERE …])`), `generateScalarSubquery()`. Raw-text emission via
  `ast/ClickHouseRawText.java`.
- `src/sqlancer/clickhouse/oracle/patch/ClickHousePatchPartConsistencyOracle.java` — the template
  for the new oracle: self-contained per-`check()` table with `AtomicLong`-suffixed name, DROP in
  `finally`, **narrow tolerance** (sessions + mutations + UNKNOWN_TABLE only — deliberately omits
  the global expression list), `IgnoreMeException` precondition gate, settings-toggle assertions
  via `ComparatorHelper`.
- `src/sqlancer/clickhouse/oracle/materialize/ClickHouseSubqueryMaterializeOracle.java` —
  precedent for hand-building derived-table SQL strings.
- `src/sqlancer/clickhouse/ClickHouseErrors.java` — per-family substring lists; convention: keep
  substrings multi-word; never tolerate broad tokens; sqlancer-typing gaps go in expression
  errors, real server invariants (Code 49) never do.
- `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` + `.claude/run-sqlancer.sh:29`
  (`ALL_ORACLES`) — both must be updated or the oracle never runs under `--oracles all`.
  **Known drift:** `DictGetVsJoin`, `WindowEquivalence`, `DynamicSubcolumn`, `SubqueryMaterialize`
  are in the factory but missing from `ALL_ORACLES` (fix opportunistically in U4).

### Institutional Learnings

- A `Code 49` on a mutation during DB setup today would *not* be swallowed — the gap is purely
  that nothing generates the trigger SQL. Generation is the work; detection is free.
- Narrow-tolerance oracles are the mechanism for hunting crash signatures the global list
  tolerates (PatchPartConsistency vs the globally-tolerated `NOT_FOUND_COLUMN_IN_BLOCK`). The
  global expression list tolerates `"Missing columns"`, `"Ambiguous column"`,
  `AMBIGUOUS_IDENTIFIER`, `"there are only columns"` — a #106649-class bug surfacing under one of
  those *messages* would be swallowed by the generator path but caught by the narrow oracle.
- Equivalence-oracle authoring rule: avoid two-statement reads of mutable state. Here the oracle
  *intends* a before/after compare around its own mutation on its own private table with
  sync-mutation settings in-statement — no other *client* writer exists. Background merges are
  the remaining autonomous writer; they are neutralized by single-part seeding and by keeping
  part-layout-dependent predicates (virtual columns) out of the consistency arm (see Unit 3).
- "Registered but dormant" trap: a factory-registered oracle that depends on generator output
  that never occurs. The new oracle is self-contained (builds its own tables/SQL), avoiding this.
- Self-contained oracles must check `execute()` returns on their CREATE/INSERTs and convert
  tolerated setup failures into `IgnoreMeException`.
- Float noise rule: keep the consistency assertion on integer counts only (`count()`,
  `countIf`) — no float aggregates.

### External References

- https://github.com/ClickHouse/ClickHouse/issues/106649 — target bug; trigger minimization in
  the body (mutation form required; both join sources must be subquery-wrapped projecting the
  same name; two joins required; analysis-time, empty tables suffice).
- https://github.com/ClickHouse/ClickHouse/pull/98884 — surface inventory (merged 2026-05-22,
  in 26.6).
- https://github.com/ClickHouse/ClickHouse/pull/106025 — upstream fix in flight; pivoted to
  reject *correlated* subqueries in mutations with `NOT_IMPLEMENTED`, fix non-correlated; its
  regression test now includes the exact #106649 shape.

## Key Technical Decisions

- **Raw-rendered SQL for the joined-derived-tables subquery**, not AST extension: the visitor has
  no derived-table-in-JOIN path, and `ClickHouseRawText` / hand-built strings are the established
  escape hatch. Cheap, contained, consistent with U1.1's `generateInSubquery`.
- **Two delivery vehicles, not one.** The generator upgrade (U1+U2) gives breadth (every oracle's
  setup phase can trip analyzer mutation bugs, with whatever schema variety the run has). The
  dedicated oracle (U3) gives depth and determinism (the exact matrix fires every iteration, with
  narrow tolerance and a consistency assertion). Either alone leaves a known hole.
- **Validate-first, then pin the known signature.** #106649 is open on head, so this coverage
  would flood runs with already-filed reproducers. Sequence: U5 first proves the oracle and
  generator path reproduce #106649 (catch confirmed); then pin the signature with a comment
  naming #106649/#106025 and the removal condition (delete when #106025 merges and head no
  longer reproduces). Two mechanics constraints discovered in review:
  - **Substring lists are OR-matched** (`ExpectedErrors` keeps independent substrings), and the
    column name sits between the phrases in the real message (`Column identifier id is already
    registered`), so a "paired substring" pin is inexpressible. Pin either the single substring
    `"is already registered"` or a regex `Column identifier .* is already registered` via the
    `ExpectedErrors` regex facility — never `"Column identifier"` as a standalone entry (that
    would tolerate far more than the filed signature).
  - **Do not put the pin in `getMutationErrors()`** — that list is also consumed by
    `ClickHousePatchPartConsistencyOracle` and `ClickHouseFinalMergeOracle` on their *read*
    paths, so the pin would leak beyond mutations. Place it in a dedicated pin list (e.g. a
    `getKnownOpenMutationAnalyzerBugs()`-style method) consumed only by the mutation generator's
    expected-error build and the new oracle's narrow set.
  This is a deliberate, narrow, documented exception to the "never tolerate Code 49" rule —
  bounded to one filed signature on the mutation path only, never the bare `LOGICAL_ERROR` token.
- **Consistency assertion design (UPDATE):** pre-read `SELECT count() FROM t WHERE <pred>` as
  `expected`; run `ALTER … UPDATE marker = <sentinel> WHERE <pred>` with `mutations_sync=1`,
  where `marker` is a dedicated column initialized to a value distinct from the sentinel; then
  assert `countIf(marker = sentinel) == expected`. For DELETE: `count_before - count_after ==
  expected`. Integer-only, single-writer, private table.
- **`validate_mutation_query` randomized 0/1** in the oracle's mutation SETTINGS — PR #98884
  gates validation behind it and adds an `ignore_in_subqueries` analyzer path for `=0`; both arms
  deserve traffic. With `=0`, skip the consistency assertion arm if the mutation errors
  (validation off means more server-side late failures are legitimate).
- **MUTATION action weight raised modestly** (e.g. 0/1 at 1-in-6 → 0–2 with similar expectation
  mass) so the upgraded generator actually fires often enough to matter, without destabilizing
  setup-phase data for downstream oracles (mutations are sync on the dev-vm via
  `mutations_sync=2`).

## Open Questions

### Resolved During Planning

- *Would a Code 49 on a setup-phase mutation be caught today?* Yes — propagates as
  AssertionError + reproducer; nothing tolerates it. Generation is the only gap.
- *AST or raw SQL for the derived-table join?* Raw SQL (see Key Technical Decisions).
- *Won't this flood runs with the open #106649?* Yes, hence validate-first-then-pin (see Key
  Technical Decisions).
- *Does the oracle need data?* The crash class is analysis-time (empty tables suffice), but the
  consistency assertion needs rows — seed small deterministic data (tens of rows) so both kinds
  of coverage run per iteration.

### Deferred to Implementation

- Exact probability split in the mutation WHERE (numeric path vs `generatePredicate()` vs forced
  IN-joined-derived-tables) — tune during the U5 convergence loop based on tolerated-error rates.
- Which new tolerable error substrings the predicate-grade mutation WHEREs surface (e.g.
  lightweight-update gating messages interacting with subqueries, `NOT_IMPLEMENTED` for
  correlated forms) — collect from the first smoke run; add to `getMutationErrors()`
  individually, multi-word, per house convention.
- Whether the Memory-engine variant inside the oracle is worth keeping (depends on observed
  noise) — implement behind a small probability, drop if it churns.
- Whether `MATERIALIZE COLUMN` needs a generator-side emission too (the oracle covers it
  deterministically; `ClickHouseAlterGenerator` may already emit some form — check during
  implementation and avoid duplicate work).

## Implementation Units

- [x] **Unit 1: Joined-derived-tables IN-subquery shape in the expression generator**

**Goal:** `generateInSubquery` (or a sibling) can emit the #106649 trigger shape with randomized
knobs: `col IN (SELECT a.<k> FROM (SELECT <k> FROM t1) AS a JOIN <t2> AS e ON e.<x> = a.<k> JOIN
(SELECT <k> FROM t3) AS b ON b.<k> = e.<y>)` — both derived tables projecting the **same column
name**, two joins minimum.

**Requirements:** R2

**Dependencies:** None

**Files:**
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java`
- Test: `test/sqlancer/clickhouse/` (rendering-shape unit test alongside existing generator tests if present; otherwise validated via U5 smoke — see Verification)

**Approach:**
- Extend the existing IN-subquery roll: with some probability, instead of the single-table inner
  SELECT, build the joined-derived-tables form as raw text from 2–3 randomly chosen schema
  tables/columns with compatible types. Reuse the U1.1 `ClickHouseRawText` pattern.
- Randomize: which sides are derived vs plain (per the issue, *both* derived is the trigger, but
  emitting mixed forms broadens coverage), the projected column name collision (always collide at
  least when both sides are derived), join count (2–3), `IN` vs `NOT IN`, optional inner WHERE.
- Keep it non-correlated: inner query references only its own FROM sources, never the outer
  table's columns.
- Multiset-safety: an `IN (subquery)` predicate is deterministic given data — safe for TLP/NoREC
  use, same classification as U1.1.

**Patterns to follow:**
- `generateInSubquery` / `generateScalarSubquery` raw-text construction in
  `ClickHouseExpressionGenerator.java`.
- `ClickHouseSubqueryMaterializeOracle` derived-table string building.

**Test scenarios:**
- Happy path: generated string for the both-derived form contains two `(SELECT … ) AS` segments
  projecting the same column name and two `JOIN … ON` clauses; parses/renders without exception.
- Edge case: schema with a single 1-column table — generator must still produce valid SQL
  (self-join of derived forms of the same table) or fall back to the single-table IN form.
- Edge case: type compatibility — ON-clause column pairs are equality-comparable types (reuse the
  typed-pair logic from `generateJoinClause` or constrain to same-type columns).
- Error path: none at generation time (no I/O); malformed-SQL risk covered by the U5 smoke where
  any syntax error would surface as an untolerated `SYNTAX_ERROR`.

**Verification:**
- A short dev-vm smoke with `--oracles TLPWhere` shows the new shape appearing in `-cur.log`
  transcripts and no new untolerated error families attributable to it.

---

- [x] **Unit 2: Predicate-grade WHERE in the mutation generator**

**Goal:** Mutation WHEREs draw from the full predicate path (including IN-subqueries and the
Unit-1 shape), making the #98884 analyzer path reachable by the general fleet.

**Requirements:** R1

**Dependencies:** Unit 1

**Files:**
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseMutationGenerator.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseProvider.java` (MUTATION action weight)
- Modify: `src/sqlancer/clickhouse/ClickHouseErrors.java` (new mutation-tolerable substrings as
  discovered; the temporary #106649 pin lands here in U5)

**Approach:**
- Probability split in WHERE construction: keep the numeric path as one arm; add
  `generatePredicate()` as another; add a forced Unit-1 IN-joined-derived-tables arm at low
  probability so the exact trigger fires regularly.
- Wiring caution: `generatePredicate()` reads the expression generator's internal column-ref
  state (populated via `addColumns`), which the mutation generator never sets today — naive
  wiring compiles but silently degenerates to constant-only predicates. Populate the refs with
  the mutated table's columns as unqualified references, matching the existing empty-alias
  pattern already used for the numeric WHERE path in `ClickHouseMutationGenerator`.
- Keep WHERE column sourcing as-is (already includes ALIAS/MATERIALIZED columns — part of the
  #98884 surface; do not "fix" that).
- Raise MUTATION weight modestly (see Key Technical Decisions).
- Expected-error set stays `getExpectedExpressionErrors() + getMutationErrors()` — the generator
  path accepts the global list's blind spots (the narrow oracle covers them); new legitimate
  rejection messages found during smoke runs get added to `getMutationErrors()` individually.

**Test scenarios:**
- Happy path: over a sample of generated mutations, all four `MutationKind`s appear with
  subquery-bearing WHEREs; lightweight UPDATE retains its `SETTINGS enable_lightweight_update=1`
  suffix when the WHERE is predicate-grade.
- Edge case: table whose only columns are non-numeric — predicate path must still produce a
  boolean WHERE (it does for SELECTs today; confirm no mutation-specific assumption breaks).
- Error path: a mutation rejected with a *tolerated* mutation error (e.g. lightweight-delete on
  projection-bearing table, Code 344) is logged unsuccessful but does not kill the worker.
- Integration: a full `generateDatabase` cycle with raised MUTATION weight leaves tables readable
  by downstream oracles (mutations are sync; no oracle sees mid-mutation state on the dev-vm).

**Verification:**
- Dev-vm smoke (30 min, `--oracles all`): mutation statements with IN-subqueries visible in
  transcripts; `Threads shut down` attributable only to known/filed signatures; no new
  false-positive family in triage.

---

- [x] **Unit 3: `MutationAnalyzer` oracle (deterministic #98884 matrix)**

**Goal:** A self-contained oracle that exercises the PR #98884 surface every iteration with
narrow error tolerance and an affected-rows consistency assertion.

**Requirements:** R3, R5

**Dependencies:** None (hand-builds its own SQL; can be implemented in parallel with U1/U2)

**Files:**
- Create: `src/sqlancer/clickhouse/oracle/mutate/ClickHouseMutationAnalyzerOracle.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (registration lands in U4)

**Approach:**
- Per `check()`: create a private 3-table cluster (`mutan_<id>_a/_b/_edges`, AtomicLong-suffixed),
  seeded with tens of deterministic rows plus a dedicated `marker` column on the mutated table;
  DROP in `finally`; `IgnoreMeException` on tolerated setup failure.
- Seeding/merge discipline: seed each private table with a **single INSERT** (one part) so
  background merges cannot change part layout between the pre-count and the mutation. The
  mutated table's engine is plain MergeTree for the consistency arm (dedupe-family engines would
  break the count-delta assertion via merge-time row collapse).
- Randomize one cell of the matrix per iteration:
  - **Mutation kind:** ALTER UPDATE / ALTER DELETE / lightweight UPDATE / lightweight DELETE /
    MATERIALIZE COLUMN (the last on a table created with a MATERIALIZED or DEFAULT column,
    including a constant-default variant per the PR's `getTableExpressionDataOrNull` fix).
  - **WHERE shape:** (a) the exact #106649 form — IN-subquery joining two derived tables with
    colliding projected names; (b) IN-subquery whose inner FROM references **the mutated table
    itself** (the PR's `currently_processing_in_background_mutex` deadlock-avoidance path — also
    asserts the statement *completes*, since a deadlock would surface as `max_execution_time`
    timeout); (c) plain IN-subquery over another table; (d) predicate referencing an ALIAS
    column; (e) predicate referencing a virtual column (`_part_offset` or block-number columns on
    a patch-enabled variant).
  - **Settings:** `mutations_sync=1` in-statement for ALTER kinds; the lightweight DELETE arm
    needs `lightweight_deletes_sync` instead (`mutations_sync` does not govern `DELETE FROM` —
    its default is synchronous today, but state the knob explicitly so a future default change
    can't silently reintroduce the before/after race); lightweight UPDATE is synchronous by
    design and carries its gating setting. `validate_mutation_query` randomized 0/1.
  - **Engine (stretch):** small-probability Memory-engine variant of the mutated table.
  - The WHERE-shape dimension and the affected-rows assertion apply only to the four
    UPDATE/DELETE kinds. The MATERIALIZE COLUMN arm has no WHERE — its trigger surface is the
    column's DEFAULT/MATERIALIZED expression; it gets crash coverage plus an optional value
    assertion (materialized values equal the expression recomputed in a SELECT).
  - Shape (e) (virtual-column predicates) is part-layout-dependent — use it for crash coverage
    only, excluded from the consistency-assertion arm.
- **Tolerance:** sessions + mutations + UNKNOWN_TABLE only — never the global expression list
  (PatchPartConsistency precedent), so analyzer bugs surfacing as "Ambiguous column"/"Missing
  columns"-style messages are caught here even though the generator path tolerates them. Two
  deviations from adopting the mutations bucket wholesale:
  - **Exclude `"TIMEOUT_EXCEEDED"`** (the first entry of `getMutationErrors()`): shape (b)'s
    deadlock class manifests as a `max_execution_time` timeout, and inheriting that substring
    would make the promised deadlock finding silently uncatchable. Trade-off accepted: a benign
    slow-mutation timeout on a loaded dev-vm becomes a (triagable) finding rather than tolerated
    noise. Alternatively check the statement's execute() result on shape (b) explicitly.
  - **Audit the remaining mutation-error substrings per-entry during implementation**: the
    bucket contains analyzer-bug-shaped phrases (e.g. `"Cannot find column"`, `"Cannot read
    from"`) that are blind spots of the same kind the oracle exists to remove — keep each only
    with a reason, and document the ones kept as accepted blind spots.
- **Assertions:** (1) any untolerated exception = bug (the crash class); (2) consistency:
  pre-count of the WHERE predicate vs rows actually mutated (sentinel `countIf` for UPDATE,
  count-delta for DELETE; skip this arm when `validate_mutation_query=0` and the mutation
  errored). Integer counts only.

**Execution note:** Validate the invariant on a hand-built passing case before trusting fuzz
output (house validation discipline). The known-signature pin is applied in U5 **only if**
current head still reproduces #106649.

**Patterns to follow:**
- `oracle/patch/ClickHousePatchPartConsistencyOracle.java` (lifecycle, naming, tolerance,
  precondition, comparator usage).
- `oracle/materialize/ClickHouseSubqueryMaterializeOracle.java` (derived-table SQL strings).

**Test scenarios:**
- Happy path: on a fixed build where the predicate matches N rows, ALTER UPDATE with shape (c)
  marks exactly N rows; ALTER DELETE removes exactly N.
- Happy path (executed in U5): shape (a) SQL is emitted and executed against head; if the bug is
  still open there, it raises Code 49 "Column identifier … is already registered" →
  AssertionError reproducer (natural side effect, not a requirement).
- Edge case: predicate matches 0 rows → consistency assertion passes with 0 == 0 (no vacuous
  IgnoreMe).
- Edge case: self-referencing IN-subquery (shape b) completes within `max_execution_time` —
  timeout here is a finding (deadlock class), not a tolerated error.
- Error path: setup CREATE/INSERT fails with a tolerated error → `IgnoreMeException`, no
  worker death, table cleanup still runs.
- Error path: mutation rejected under `validate_mutation_query=1` with a legitimate validation
  message → tolerated (add the specific substring to the oracle's set), iteration abandoned
  without asserting.
- Integration: two concurrent workers run the oracle simultaneously — no table-name collision,
  no cross-worker interference (AtomicLong naming).

**Verification:**
- The hand-built passing case holds on a fixed build; the #106649 shape reproduces on current
  head (proves R5); a 30-min dev-vm run shows no false-positive reproducers from this oracle
  after the known signature is pinned.

---

- [x] **Unit 4: Registration and run wiring**

**Goal:** The oracle runs under `--oracles all`; wiring drift is fixed.

**Requirements:** R3

**Dependencies:** Unit 3

**Files:**
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (enum constant
  `MutationAnalyzer`, javadoc naming the bug shape per house convention)
- Modify: `.claude/run-sqlancer.sh` (`ALL_ORACLES` — add `MutationAnalyzer`; also add the four
  drifted names: `DictGetVsJoin`, `WindowEquivalence`, `DynamicSubcolumn`, `SubqueryMaterialize`)
- Modify: `.claude/CLAUDE.md` (note the new oracle in the operational notes)

**Test scenarios:**
- Test expectation: none — pure wiring/config. Verified by U5: the oracle appears in run output
  under `--oracles all`.

**Verification:**
- `--oracles MutationAnalyzer` runs standalone; `--oracles all` includes it; the four drifted
  oracles now also execute (watch for surprise noise from them — they've never run under `all`;
  if any is noisy, drop it back out and note why rather than blocking this plan).

---

- [x] **Unit 5: Dev-vm validation, #106649 catch proof, and known-signature pin**

**Goal:** Prove the coverage catches the target bug class, converge to zero false positives, and
make runs livable while #106649 remains open upstream.

**Requirements:** R4, R5

**Dependencies:** Units 1–4

**Files:**
- Modify: `src/sqlancer/clickhouse/ClickHouseErrors.java` (temporary pin in a **dedicated pin
  list**, not `getMutationErrors()` — see Key Technical Decisions; substring
  `"is already registered"` or the regex form; comment referencing #106649/#106025 and the
  removal condition)
- Modify: `.claude/CLAUDE.md` (add #106649 to the "Filed ClickHouse bugs" list with its minimal
  repro, so future triage recognizes it; record the pin's removal condition)
- Modify: `src/sqlancer/clickhouse/oracle/mutate/ClickHouseMutationAnalyzerOracle.java` (the
  oracle needs the same pinned signature in its narrow set, with the same removal comment —
  otherwise every iteration dies on the open bug)

**Approach:**
- Pre-check before the first oracle run: the matrix can also rediscover **other** filed-bug
  families — notably the patch-part read-crash family (`NOT_FOUND_COLUMN_IN_BLOCK`), which the
  narrow tolerance deliberately re-exposes via shape (e) × lightweight-UPDATE. Check the
  open/closed state of every filed signature the matrix can reach; any still-open one gets the
  same pin-with-removal-condition treatment as #106649 (the pin procedure generalizes — it is
  not a one-signature special case).
- Sequence: (1) rebuild and sync to dev-vm (`--rebuild` — rsync excludes `target/`); (2) run
  `--oracles MutationAnalyzer` briefly against current head and confirm in transcripts that the
  matrix shapes are actually emitted and executed (R5 — class coverage proof). If head still
  reproduces #106649, the Code 49 reproducer appears here as a natural side effect; (3) apply
  the signature pin in both places **only if** head still reproduces — if #106025 already
  merged, skip the pin entirely and keep the stricter unpinned behavior; (4) standard convergence
  loop (≤5 × 30-min iterations, `--oracles all`), triaging reproducers per the CLAUDE.md bucket
  script, until the only findings are filed/known signatures; (5) tune Unit-2 probabilities and
  add newly discovered legitimate mutation-rejection substrings as needed.
- Triage caution from house rules: attribute reproducers by the failing query's first ~6 lines
  only, never whole-file grep.

**Test scenarios:**
- Happy path: step (2) shows the matrix shapes (incl. shape (a)) in transcripts from the oracle
  and the Unit-2 generator path; if #106649 is still open on head, the Code 49 reproducer
  appears as well.
- Error path: post-pin run still surfaces a *different* Code 49 message → propagates as a
  reproducer (the pin must not swallow it) — verify by checking the pin's substrings against any
  new Code 49 in triage.
- Integration: full `--oracles all` run exits 255 only for known/filed signatures; `Threads shut
  down` count matches the triaged reproducer count.

**Verification:**
- Class-coverage proof recorded (transcript snippets showing the emitted trigger shapes; plus
  the reproducer log snippet if head still had #106649).
- Final convergence run: zero unexplained reproducers; no new false-positive family.
- Work committed on `nik/clickhouse-add-pqs-cert-coddtest` and pushed to the `nik` remote
  (`fm4v/sqlancer`).

## System-Wide Impact

- **Interaction graph:** Unit 2 changes setup-phase data dynamics for *all* oracles (more
  mutations, subquery WHEREs). Mutations are synchronous on the dev-vm (`mutations_sync=2`
  server-side), so downstream oracles see settled state; off-dev-vm ad-hoc containers would
  reintroduce the mutation-race false-positive class — one more reason the dev-vm-only rule
  stands.
- **Error propagation:** untouched by design — the catch mechanism *is* the existing
  untolerated-exception → AssertionError → reproducer path. The only tolerance changes are
  additive, narrow, multi-word substrings plus one documented temporary pin.
- **State lifecycle risks:** the oracle's private tables are created/dropped per iteration;
  `finally`-DROP plus AtomicLong naming prevents leaks/collisions. Orphans from killed workers
  are handled by the existing disk-cleanup script's orphan-database drop.
- **API surface parity:** none — no CLI flags added (oracle selected by name like all others).
  If a tuning flag becomes necessary during U5 (e.g. shape weights), follow the
  `--tlp-groupby-strict` precedent in `ClickHouseOptions.java`.
- **Integration coverage:** the U5 convergence loop is the integration test — unit-level checks
  cannot prove "no false positives under concurrency on real CH head".
- **Unchanged invariants:** TLP/NoREC/multiset oracle semantics are untouched (the new IN-subquery
  shape is deterministic-given-data, same class as U1.1); the global expression-error list is not
  widened except by specific mutation-rejection messages; `PatchPartConsistency`'s narrow set is
  not touched.

## Risks & Dependencies

| Risk | Mitigation |
|------|------------|
| Open #106649 floods runs with known reproducers | Validate-first-then-pin; pin is narrow (`"is already registered"` substring or regex form), documented with removal condition tied to #106025 |
| The pin masks a *different* future Code 49 with a similar message | Pin lives in a dedicated list consumed only by the mutation generator and the new oracle (not the shared `getMutationErrors()`, which FinalMerge/PatchPartConsistency read paths also use); removal condition recorded in CLAUDE.md; triage step explicitly checks new Code 49s against the pin |
| Predicate-grade mutation WHEREs surface a long tail of legitimate rejection messages (worker-death noise) | Expected; convergence loop adds them individually to `getMutationErrors()`; probabilities tunable; worst case the forced trigger arm drops to very low probability |
| Self-referencing IN-subquery hits the real deadlock path and hangs the worker | `max_execution_time=30` is pinned per-connection; the oracle treats timeout on shape (b) as a finding, not a hang |
| Adding the four drifted oracles to `ALL_ORACLES` introduces unrelated noise | They're validated oracles that drifted out of the script; if any is noisy in U5, remove it again and note why — independent of this plan's core |
| Consistency assertion false-positives from rows already equal to the sentinel | Dedicated `marker` column initialized to a non-sentinel value; sentinel chosen outside the seed domain |
| Upstream #106025 merges mid-implementation | Then skip the pin (U5 step 3) — verify head no longer reproduces and keep the unpinned, stricter behavior |

## Documentation / Operational Notes

- `.claude/CLAUDE.md`: add #106649 to the filed-bugs list (with the issue's minimal repro) and a
  short section on the new oracle + the pin's removal condition.
- Auto-memory: after U5, record the validated coverage and pin status (project-type memory), so
  a future session knows to remove the pin when #106025 merges.

## Sources & References

- Related code: `src/sqlancer/clickhouse/gen/ClickHouseMutationGenerator.java`,
  `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java`,
  `src/sqlancer/clickhouse/oracle/patch/ClickHousePatchPartConsistencyOracle.java`,
  `src/sqlancer/clickhouse/oracle/materialize/ClickHouseSubqueryMaterializeOracle.java`,
  `src/sqlancer/clickhouse/ClickHouseErrors.java`,
  `src/sqlancer/clickhouse/ClickHouseOracleFactory.java`, `.claude/run-sqlancer.sh`
- Related PRs/issues: ClickHouse/ClickHouse#106649, ClickHouse/ClickHouse#98884,
  ClickHouse/ClickHouse#106025
- Prior plan: `docs/plans/2026-05-29-001-feat-clickhouse-coverage-expansion-roadmap-plan.md`
  (U1.1 IN-subquery, U5.3 join-shape pattern, oracle wiring conventions)
