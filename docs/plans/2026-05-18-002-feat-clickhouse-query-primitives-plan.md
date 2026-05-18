---
title: "feat: ClickHouse query primitives — aggregate combinators, set operations, ARRAY JOIN"
type: feat
status: active
date: 2026-05-18
deepened: 2026-05-18
---

# feat: ClickHouse query primitives — aggregate combinators, set operations, ARRAY JOIN

**Target repo:** `fm4v/sqlancer` (Nikita's fork)
**Target branch:** `nik/clickhouse-add-pqs-cert-coddtest` (the in-flight PR continuing the recent EET/SEMR/CERT/CODDTest oracle series)

This plan lands as a **single PR / single landing event** on the target branch above — not as six sequential PRs. The Implementation Units are commit-level milestones, sequenced for review and bisectability but bundled into one PR. Per-phase yield gates run as internal milestones during the PR's pre-merge burn-in.

## Overview

Three orthogonal additions to the ClickHouse SQLancer module that expand the query-generator surface in directions no current oracle can express:

1. **Set-operation AST + TLP oracle** — first-class `ClickHouseSetOperation` AST node for `UNION ALL`, `UNION DISTINCT`, `INTERSECT`, `EXCEPT`. New `ClickHouseTLPSetOpOracle` that encodes the canonical TLP partition (and three set-op-specific variants) on top of it. Today `UNION ALL` is built by string concatenation inside `ClickHouseTLPAggregateOracle` and `ClickHouseTLPHavingOracle`; the AST node also unblocks future migration of those sites without changing their behaviour now.

2. **Aggregate combinators** — `ClickHouseAggregateCombinator` enum and a combinator-chain extension of `ClickHouseAggregate`. Generator emits suffixed forms (`sumIf`, `countIf`, `avgOrNull`, `groupArray`, `quantileResampleArray`, etc.) and a new oracle exercises the algebraic identities (`sumIf(x,c) ≡ sum(if(c,x,0))`, `countIf(c) ≡ sum(toUInt64(c))`, `avgOrNull(x) ≡ if(count(x)=0, NULL, sum(x)/count(x))`).

3. **ARRAY JOIN clause** — `ClickHouseSelect.arrayJoinExprs` field + visitor emission of `[LEFT] ARRAY JOIN <exprs>` between the FROM clause and any regular JOIN clauses (ClickHouse grammar binds ARRAY JOIN to the table before JOINs). Structurally lands behind a default-OFF flag with the generator never selecting it because no Array columns exist yet; activation is blocked on the v2 type-system foundation that introduces `Type.Array(inner)`.

The plan is sequenced so each unit lands as its own PR. Combinators and set-ops are independent of each other and of the type system. ARRAY JOIN structural plumbing is independent but the oracle activation must wait.

## Problem Frame

ClickHouse's aggregate-combinator suffix system and its INTERSECT/EXCEPT/UNION DISTINCT set operations are heavily used by real users and have been responsible for many historical bugs (combinator parser quirks, set-op planner mistakes, `INTERSECT DISTINCT` versus default semantics regressions). The current generator cannot express either: `ClickHouseAggregate` is a closed enum over `{AVG, COUNT, MAX, MIN, SUM}` (`src/sqlancer/clickhouse/ast/ClickHouseAggregate.java:17-28`), and `ClickHouseSelect` has no set-operation form (`src/sqlancer/clickhouse/ast/ClickHouseSelect.java`). The existing `UNION ALL` use in `ClickHouseTLPAggregateOracle.check()` and `ClickHouseTLPHavingOracle.check()` builds strings with `+ " UNION ALL " +` between rendered selects, which works for one fixed shape but cannot be reused for INTERSECT/EXCEPT TLP variants or for nested set-op trees.

ARRAY JOIN is the ClickHouse idiom for unnesting array columns into rows. It will be a meaningful chunk of bug surface once the type system supports `Array(T)` columns — but the type-system plan (`docs/plans/2026-05-16-001-feat-clickhouse-type-system-foundation-plan.md`) puts composite constructors in v2. Building ARRAY JOIN behind a feature flag now lets the v2 type work flip it on without an oracle-side refactor.

The TLP family lives in `src/sqlancer/clickhouse/oracle/tlp/`. Recent oracles (EET, SEMR, CERT, CODDTest, PQS) establish the per-oracle-subpackage / feature-flag / `ClickHouseOracleFactory` / `ClickHouseErrors` pattern this plan follows.

## Requirements Trace

**Phase A — Set Operations:**
- **R1.** A new `ClickHouseSetOperation` AST node exists, is registered in `ClickHouseVisitor` and `ClickHouseToStringVisitor`, and supports the four set-op kinds (`UNION ALL`, `UNION DISTINCT`, `INTERSECT`, `EXCEPT`) with explicit `ALL`/`DISTINCT` operator keywords.
- **R2.** A new `ClickHouseTLPSetOpOracle` runs against the test harness and validates at least the four canonical invariants (one per set-op kind) over the existing TLP predicate variants `(p, NOT p, p IS NULL)`.

**Phase B — Aggregate Combinators:**
- **R3.** A new representation extends `ClickHouseAggregate` to carry a combinator chain (one or more `ClickHouseAggregateCombinator` suffixes) plus per-combinator extra arguments. The to-string visitor renders the chain in source order.
- **R4.** The expression generator can emit combinator-suffixed aggregates when `enableCombinators` is on. Initial combinator set: `-If`, `-OrNull`, `-OrDefault`, `-Distinct`, `-Array`, `-State`, `-Merge`, `-ForEach`, `-Resample`, `-Map`.
- **R5.** A combinator oracle validates the named identities `sumIf(x, c) ≡ sum(if(c, x, 0))`, `countIf(c) ≡ sum(toUInt64(c))`, and at least one additional invariant per non-`-If` combinator that has a deterministic decomposition.

**Phase C — ARRAY JOIN:**
- **R6.** `ClickHouseSelect` carries an `arrayJoinExprs` field and `arrayJoinLeft` toggle, rendered between the FROM clause and any regular JOIN clauses. With no Array columns in scope (the default state until type-system v2), the generator does not produce ARRAY JOIN, so the field stays empty in all live runs.

**Cross-cutting:**
- **R7.** Each new oracle is wired through `ClickHouseOracleFactory`, registers `ClickHouseErrors` patterns it needs, and lands behind its own feature flag in `ClickHouseOptions`.
- **R8.** The 100k-iteration no-regression bar from the recent EET/SEMR plans is preserved: with all new flags OFF, oracle behaviour is unchanged from baseline.

## Scope Boundaries

- **Non-goal:** retiring the string-concat UNION ALL in `ClickHouseTLPAggregateOracle:56-63` and `ClickHouseTLPHavingOracle:60` — those oracles keep working as-is. The new AST node makes future migration possible; this plan does not commit to doing it.
- **Non-goal:** generating `Array(T)` columns or `arrayJoin(arr)` calls. That is type-system v2 work. Unit 8 below describes the activation but the unit itself is blocked.
- **Non-goal:** exhaustive enumeration of every aggregate × combinator combination at generation time. The generator samples the cross-product randomly; the error catalog filters illegal combinations.
- **Non-goal:** rewriting `ClickHouseAggregateFunction`'s closed enum into a full function registry. Combinators sit on top of the existing enum; aggregate names are still picked from `{AVG, COUNT, MAX, MIN, SUM}`. Any addition (e.g., `quantile` to give `-Resample` an interesting subject) is **explicitly deferred** to a follow-up — see "Deferred to Implementation" — not part of this plan.
- **Non-goal:** introducing `INTERSECT DISTINCT` / `EXCEPT DISTINCT` as separate enum members beyond what the four set-op kinds already encode. ClickHouse's default INTERSECT/EXCEPT semantics depend on `union_default_mode`; this plan pins them via a per-query `SETTINGS` suffix.
- **Non-goal:** any cross-DBMS abstraction. The new types live in `src/sqlancer/clickhouse/`.

## Context & Research

### Relevant Code and Patterns

- `src/sqlancer/clickhouse/ast/ClickHouseAggregate.java:17-66` — current aggregate AST and enum. `supportsReturnType` and `getAggregates(type)` define per-type filtering. No combinator concept.
- `src/sqlancer/clickhouse/ast/ClickHouseSelect.java` — flat select holder; no set-op or ARRAY JOIN field.
- `src/sqlancer/clickhouse/ClickHouseVisitor.java:64-96` — instanceof dispatch; every new AST node must be added here and to `ClickHouseToStringVisitor`.
- `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java:60-107` — `visit(ClickHouseSelect, inner)`. New emission positions: ARRAY JOIN between line 81 (FROM) and 88 (WHERE); set-op handling at the `asString` top level (line 98 in `ClickHouseVisitor`).
- `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java:120-125` — `visit(ClickHouseAggregate)`. Renders flat `func(expr)`; combinator chain must extend this.
- `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java:144-152, 240, 453-470` — aggregate insertion points in the generator. Combinator emission slots in here.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java:35-79` — common scaffolding for new TLP oracles. Subclass, set `select`, populate predicate variants via `initializeTernaryPredicateVariants()` inherited from `TernaryLogicPartitioningOracleBase`, render.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPAggregateOracle.java:25-85` — canonical TLP-with-UNION-ALL oracle. Direct template for set-op TLP. Note the mandatory `SETTINGS aggregate_functions_null_for_empty = 1` suffix (line 42).
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java:42, 61` — uses `SETTINGS aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0` (ClickHouse#12264 workaround). This suffix is mandatory whenever a predicate-rewrite produces three branches.
- `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETOracle.java:1-514` + `oracle/eet/ClickHouseEETIdentities.java:36-118` — mode-enum pattern for staged delivery, typed identity catalog with `safeFor` predicates, `pickIdentityForType` returning `Optional`. The combinator-identity oracle re-uses this shape.
- `src/sqlancer/clickhouse/oracle/semr/ClickHouseSEMROracle.java:19-39` — minimal oracle extending `ClickHouseTLPBase` and appending a `SETTINGS` suffix; reference for the new oracles' shape.
- `src/sqlancer/clickhouse/ClickHouseOptions.java:26-36` — `enableNullable`, `enableLowCardinality`, `randomSessionSettings` are exact templates for the new flags.
- `src/sqlancer/clickhouse/ClickHouseErrors.java:12-92, 99-110` — flat error pattern list, multi-word substrings only; reference for new catalog additions.
- `src/sqlancer/clickhouse/ClickHouseOracleFactory.java:22-97` — enum where each oracle gets a constant.
- `src/sqlancer/clickhouse/ast/ClickHouseExpression.java:17-19` — the legacy `TypeAffinity` enum already has `ARRAY, TUPLE, SET` tokens but no constructor emits them. They are dead until type-system v2.

### Institutional Learnings

From recent plans in `docs/plans/`:

- **HAVING-position predicate rewrites need the dual SETTINGS suffix `aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0`** to dodge ClickHouse#12264. Required on both sides of the equivalence (`docs/plans/2026-05-18-001-feat-clickhouse-eet-oracle-plan.md:81, 100, 279, 411`).
- **Multi-word substrings only in `ClickHouseErrors`**. `ExpectedErrors` does `error.contains(s)` — a bare `Setting` would absorb dozens of unrelated messages and silently mask real findings (`docs/plans/2026-05-17-001-feat-clickhouse-semr-oracle-settings-randomization-plan.md:82, 291, 301`). Verify with a negative-assertion unit test.
- **Per-query `SETTINGS` suffix beats `SET` on the connection** — `SET` leaks across neighbouring oracles in `CompositeTestOracle` (`docs/plans/2026-05-17-001-feat-clickhouse-semr-oracle-settings-randomization-plan.md:58, 348`).
- **Error catalog tuning is empirical**. Run ~1k–10k iterations against the CI-pinned image, triage off-catalog `SQLException`s, then commit (`docs/plans/2026-05-18-001-feat-clickhouse-eet-oracle-plan.md:120`, `docs/plans/2026-05-16-001-feat-clickhouse-type-system-foundation-plan.md:487`).
- **Mode-enum staged delivery**: define all modes up-front, return `IgnoreMeException` from unimplemented branches so units land independently (`docs/plans/2026-05-18-001-feat-clickhouse-eet-oracle-plan.md:229`).
- **Pre-flight mutual-exclusion checks belong in `Main.executeMain`**, not in `createDatabase` — N threads each print stack traces otherwise (`docs/plans/2026-05-17-001-feat-clickhouse-semr-oracle-settings-randomization-plan.md:243-245`).

### External References

None gathered. Local patterns (EET, SEMR, TLP, CODDTest) are strong and the design space here is well-bounded by ClickHouse's own SQL grammar. Combinator type constraints (`-Array` requires aggregate-state types, `-Resample` requires extra args, etc.) are discovered empirically via the error catalog rather than codified up front, matching the convention from `docs/plans/2026-05-16-001-feat-clickhouse-type-system-foundation-plan.md:209`.

## Key Technical Decisions

- **Set-op AST as a sibling of `ClickHouseSelect`, not a wrapper.** `ClickHouseSetOperation` carries `(left: ClickHouseExpression, op: SetOpKind, right: ClickHouseExpression)` and chains via right-association. The top-level rendering path in `ClickHouseVisitor.asString` (`src/sqlancer/clickhouse/ClickHouseVisitor.java:98-109`) gains a dedicated branch so that set-ops are emitted without the gratuitous outer parens that `visit(Select, true)` adds for nested inner selects. Why: emitting `(SELECT … FROM t) UNION ALL (SELECT … FROM t)` is fine, but `((SELECT …)) UNION ALL ((SELECT …))` triggers the ClickHouse parser to warn about syntax in some versions; mirrors the EET gotcha at `ClickHouseEETOracle.java:256-259`.

- **Combinator chain as a list on a new `ClickHouseAggregate` constructor, not new enum entries.** A combinator is `(suffix: ClickHouseAggregateCombinator, extraArgs: List<ClickHouseExpression>)`. The aggregate carries `chain: List<Combinator>` (empty for plain aggregates). Rendering is `<func><suffix1><suffix2>(expr [, extraArgs of suffix1 ...])`. Why: ClickHouse combinator chains are order-sensitive (`sumIfArray` ≠ `sumArrayIf`); per-enum SUM_IF / COUNT_IF / SUM_OR_NULL would scale combinatorially. The closest precedent is `ClickHouseAggregate.ClickHouseAggregateFunction` which already uses varargs typing; the chain extension preserves the existing API for callers that pass no chain.

- **Combinator extra-argument grammar is per-suffix, not free-form.** `-If` takes exactly one boolean condition argument. `-Resample` takes three positional args (key, from, to). `-OrNull`, `-OrDefault`, `-Distinct`, `-Array`, `-State`, `-Merge`, `-ForEach`, `-Map` take none. Why: these are ClickHouse's own grammar rules; embedding them in the combinator declaration ensures the generator emits a well-formed token sequence even if the column type is wrong (in which case the error catalog absorbs the failure, not the parser).
  - **Identity-oracle restriction.** The combinator-identity oracle (Unit 5) only fires when the chain contains **at most one extra-arg-bearing suffix**. Multi-extra-arg chains are valid to *generate* (Unit 4) for parser/planner fuzzing, but the equivalence oracle requires unambiguous arg ownership — `sumIfResample(x, c, k, f, t)` is syntactically valid yet ClickHouse may assign args to combinators in ways that diverge from the textbook decomposition, producing false positives. Implemented as a one-line predicate in `pickIdentityForType`: `chain.stream().filter(c -> !c.extraArgs.isEmpty()).count() <= 1`.
  - **`countIf` modelling asymmetry, documented explicitly.** `countIf(c)` has no value expression — only a boolean condition. The natural AST shape is `Aggregate(COUNT, c, [(IF, [])])` where the `expr` field carries the condition itself rather than a value column. This breaks the usual invariant that `aggregate.getExpr()` is a value expression; downstream type-aware consumers (the to-string visitor at `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java:123` is the only current `getExpr()` reader, but future consumers may mishandle it). Mitigation: a constructor-level assertion in `ClickHouseAggregate` rejects `(COUNT, expr, chain)` where `chain` contains `IF` and `expr` is not boolean-typed; consumers that walk aggregate AST must explicitly handle the `count + IF` case. Tracked as a test-scenario row in Unit 3.

- **ARRAY JOIN clause lands inert.** `ClickHouseSelect.arrayJoinExprs` defaults to empty, the visitor emits nothing when empty, and the generator never populates it until type-system v2 introduces a `Type.Array` constructor. Why: same precedent as the v1a/v1b split for the type system — structural change with flags OFF, behavioural change later. Activating it without `Array` columns would generate `ARRAY JOIN <non-array-expr>` queries that all fail with the same ILLEGAL_TYPE_OF_ARGUMENT, providing zero bug-finding signal.

- **Set-op TLP invariants are pinned per kind.** Each set-op needs its own invariant because their semantics differ. The plan ships **two primary invariants** (strongest signal) plus **two secondary cross-checks**:
  - **Primary — `UNION ALL` (multiset equality, two-sided):** `T ≡ Tp ⊎ Tnp ⊎ T_null_p` (canonical TLP). This is the strongest invariant — multiset equality catches *both* missing rows and over-counting rows in any branch.
  - **Primary — `UNION DISTINCT` (set equality, two-sided):** `DISTINCT(T) ≡ DISTINCT(Tp) ∪ DISTINCT(Tnp) ∪ DISTINCT(T_null_p)`. Catches set-side mis-partitioning. DISTINCT is applied at every leaf, not relying on the outer UNION DISTINCT to deduplicate, because EXCEPT ALL semantics (below) make per-leaf multiplicity load-bearing.
  - **Secondary — `INTERSECT` (pairwise-disjoint cross-check):** `Tp ∩ Tnp ≡ ∅`, `Tp ∩ T_null_p ≡ ∅`, `Tnp ∩ T_null_p ≡ ∅`. Renders as explicit `INTERSECT ALL` (with leaf-DISTINCT applied so multiset and set interpretations coincide).
  - **Secondary — `EXCEPT` (operator coverage, both forms):** TWO parts. (i) Coverage: `DISTINCT(T) EXCEPT DISTINCT(Tp) EXCEPT DISTINCT(Tnp) EXCEPT DISTINCT(T_null_p) ≡ ∅`. (ii) Pairwise disjointness via EXCEPT: `DISTINCT(Tp) EXCEPT DISTINCT(Tnp) ≡ DISTINCT(Tp)`, and the two symmetric variants. **Why both halves:** the goal is **EXCEPT-operator coverage**, not (purely) partition completeness — UNION ALL multiset equality already establishes the partition is correct. Both forms route through the EXCEPT planner code path with different argument shapes (chained vs binary), maximizing the chance of catching EXCEPT-specific planner bugs. The pairwise form happens to be logically equivalent to the INTERSECT empty-intersection invariant, but routes through a different operator — that operator-routing difference is the bug-finding value, acknowledged honestly.
  - **Rendering uses explicit operator keywords, not SETTINGS pinning alone.** Each rendered query writes `INTERSECT ALL`, `INTERSECT DISTINCT`, `EXCEPT ALL`, or `EXCEPT DISTINCT` literally — the explicit keyword is parsed even if the corresponding `*_default_mode` setting is unknown to the server. The SETTINGS suffix (`union_default_mode='DISTINCT', intersect_default_mode='ALL', except_default_mode='ALL', aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0`) is belt-and-suspenders, not the primary correctness anchor. Why: ClickHouse silently ignores unknown SETTINGS by default; if a `*_default_mode` setting is renamed across versions, the SETTINGS pinning becomes a no-op and the oracle silently relies on the server default. Explicit keywords eliminate that dependency.
  - **DISTINCT at every leaf for the UNION DISTINCT, INTERSECT, and EXCEPT invariants** is mandatory. With `intersect_default_mode='ALL'` and `except_default_mode='ALL'`, ClickHouse uses multiplicity-aware semantics. Leaf-DISTINCT collapses every leaf to multiplicity ≤ 1, so multiset and set interpretations coincide, and a forgotten DISTINCT on any one leaf produces spurious failures (e.g., EXCEPT ALL leaves residual copies of a row that appears multiple times in T but only once in Tp).
  - **Trivial-empty branches accepted as no-signal.** When predicate `p` filters all rows such that `Tp = Tnp = T_null_p = ∅`, the INTERSECT and EXCEPT invariants pass trivially. This is accepted because (i) the oracle's primary signal is UNION ALL/DISTINCT multiset equality, which still fails on any partition bug; (ii) randomly-generated predicates produce a mix of substantive and trivial cases, so amortized coverage holds. The oracle logs a "substantive vs trivial" counter via `state.getState().getLocalState().log(...)` so the bug-find-rate analysis can tell them apart.

- **Combinator oracle as a typed identity catalog, mirroring `ClickHouseEETIdentities`.** Each identity carries a name, a rewrite template, and a `safeFor: Predicate<ClickHouseType>` (and optionally a `safeForAggregate: Predicate<ClickHouseAggregateFunction>`). The oracle picks the aggregate first, then picks an identity whose predicates accept the aggregate-and-type pair. Why: makes the catalog auditable and additive — each new identity is one row, with its safety constraints encoded next to its rewrite, exactly like `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETIdentities.java:47-52`.

- **Feature flags default OFF in the PR; activation lands as separate commits on the same branch.** Bundled into one PR but sequenced as: structural commits → activation commit per phase. Why: bisectability — each commit individually compiles and passes; activation commits are revertible without losing structural work. This is a lighter-weight version of the type-system v1a/v1b split, calibrated to the lower risk of additive AST changes.

- **No `SETTINGS` mutation cross-oracle.** All ClickHouse-version-specific setting fixes (`SETTINGS aggregate_functions_null_for_empty=1`, etc.) are appended as per-query suffixes inside each oracle's `check()` — never via `SET` on the connection.

- **Non-deterministic-predicate deny-list, scoped to the new SetOpTLP oracle only.** Predicates containing `rand`, `randConstant`, `rand64`, `now`, `now64`, `today`, `yesterday`, `generateUUIDv4`, `randomString`, `randomFixedString`, `canonicalRand`, or any aggregate function classify a given row inconsistently across re-evaluation. The baseline `T` evaluates the predicate once per row; the three-branch composite evaluates it again per branch. A row whose predicate value flips between evaluations breaks the partition. **Mitigation:** the deny-list walker lives **only in the new `ClickHouseTLPSetOpOracle.check()`**, not in shared `ClickHouseTLPBase`. Why: adding the walker to `ClickHouseTLPBase` would silently change behaviour for the five existing TLP oracles whose 100k-iteration baselines were run without it — a stealth behaviour change under cover of a new-feature PR. Scope it locally; file a separate tracked follow-up for hardening the existing TLP oracles. The deny list is comment-documented and additive; new entries land empirically. **Caveat:** the deny-list approach is structurally a deny-list — it cannot distinguish "undiscovered non-deterministic function" from "real bug". A future hardening pass should consider an allow-list of known-pure functions instead; tracked as an open question.

- **`-OrNull` / `-OrDefault` combinator identities skip `aggregate_functions_null_for_empty=1`.** ClickHouse's setting and the `-OrNull` combinator both return NULL on empty input — but with subtly different semantics across versions (the setting wraps the *return type*; the combinator wraps the *value computation*). The combinator-identity oracle (Unit 5) avoids the double-encoding by setting `aggregate_functions_null_for_empty=0` for `-OrNull` and `-OrDefault` identity queries only. The set-op TLP oracle and other identities keep the default `=1`.

- **SetOpTLP fetchColumns must be aggregate-free.** TLP partition correctness depends on per-row classification. Aggregates collapse rows, so a query whose `fetchColumns` contain `sum(x)` cannot satisfy `T ≡ Tp ⊎ Tnp ⊎ T_null_p` row-for-row — the canonical TLP shape would need the outer query to re-aggregate (the existing `ClickHouseTLPAggregateOracle.java:56` pattern). **Mitigation:** SetOpTLP's `check()` asserts no `ClickHouseAggregate` in `fetchColumns` and throws `IgnoreMeException` otherwise. Aggregate-fetchCol set-op coverage is a separate oracle later.

- **Startup probe for pinned setting availability, per-setting and graceful.** Because explicit `INTERSECT ALL` / `EXCEPT DISTINCT` operator keywords are the load-bearing correctness anchor (not the SETTINGS pinning), the probe degrades per-setting rather than all-or-nothing. On the oracle's first `check()`, run three separate probes (`SELECT 1 SETTINGS intersect_default_mode='ALL'`, then `... except_default_mode='ALL'`, then `... union_default_mode='DISTINCT'`). For each, catch `SQLException` matching `UNKNOWN_SETTING` inline (do NOT add to `ExpectedErrors`) and mark that specific setting as unavailable. The oracle continues to run; the unavailable settings are simply omitted from the per-query SETTINGS suffix. If ALL three settings are unavailable, the oracle disables itself for the run because the implicit semantics are too version-dependent without any pinning. The probe result is cached in a `static` field with a server-version fingerprint key so it runs once per process per server version, not once per `check()` and not once per database.

## Open Questions

### Resolved During Planning

- **Q: Should the set-op AST migration of `ClickHouseTLPAggregateOracle` and `ClickHouseTLPHavingOracle` ride along with this plan?** Resolved: no. The string-concat path works; replacing it carries regression risk that's disproportionate to the cleanup value. The new AST node makes a future migration possible but does not commit to one.
- **Q: Plain combinators only, or chains?** Resolved: chains. The user explicitly chose "Full combinator matrix". `sumIfArray`, `quantileResampleDistinct`, and similar three-deep chains are valid ClickHouse and represent real bug surface. Implementation cost is identical (a list rather than a single suffix), and the to-string visitor folds them naturally.
- **Q: Which set-ops in the first cut?** Resolved by user direction: all four. INTERSECT and EXCEPT need their own invariants because the canonical TLP partition does not apply; the four invariants in Key Technical Decisions cover them.
- **Q: Should `ClickHouseSelect` model ARRAY JOIN at the AST level now, or wait?** Resolved: model now. Structural plumbing is cheap and lets the v2 type-system work flip a single flag rather than re-touch the select AST.
- **Q: How is the combinator emission gated to avoid an avalanche of `Unknown aggregate function` errors?** Resolved: the generator only emits combinator chains that are syntactically valid per the per-suffix extra-argument grammar. Type-level validity (`-Array` requires aggregate-state arguments) is intentionally **not** pre-validated — the error catalog absorbs the noise. Mirrors the empirical-discovery precedent from the type-system plan.

### Deferred to Implementation

- **Exact list of `ClickHouseErrors` substrings to add.** Discovered through 1k–10k iterations. Initial guesses: `Unknown aggregate function`, `NUMBER_OF_ARGUMENTS_DOESNT_MATCH`, `Combinator * is only applicable for aggregate functions`, `Aggregate function * is not supported`, `Number of columns doesn't match`, `Cannot find common type for tuple elements`, `union_default_mode`. Final catalog written after the activation commit's CI run.
- **Per-combinator probability weights in the generator.** The generator picks a combinator chain length and then each suffix. Initial weights pick `-If` and `-OrNull` most often; the empirical run after activation may rebalance toward `-Distinct` or `-Array` if the catalog absorbs too many failures from less-common suffixes.
- **Whether `-State` and `-Merge` combinators should ship before `AggregateFunction` columns exist.** They emit syntactically valid queries (`sumState(x)`) but the result type is `AggregateFunction(sum, T)` which has no column counterpart. Likely shipped behind an empirical-discovery decision after the activation commit.
- **Whether to add a `quantile` family aggregate** (`quantile`, `quantilesExact`, etc.) to `ClickHouseAggregateFunction` so the `-Resample` combinator has interesting subjects. Tracked as a follow-up; the plan does not commit.
- **Naming for the new TLP-style oracles.** Working names: `ClickHouseTLPSetOpOracle`, `ClickHouseTLPCombinatorOracle`. May change to match the existing `ClickHouseTLPAggregateOracle` convention (`ClickHouseTLPSetOpOracle`, `ClickHouseTLPCombinatorOracle`).
- **Exact ARRAY JOIN render position when the v2 type system lands**. Currently planned between FROM and WHERE; v2 may need ARRAY JOIN inside JOIN chains for correctness (`SELECT … FROM t ARRAY JOIN a LEFT JOIN s ON …`).

## High-Level Technical Design

> *This illustrates the intended approach and is directional guidance for review, not implementation specification. The implementing agent should treat it as context, not code to reproduce.*

**AST shape** (Java-ish sketch):

```
class ClickHouseSetOperation extends ClickHouseExpression {
  enum SetOpKind { UNION_ALL, UNION_DISTINCT, INTERSECT, EXCEPT }
  ClickHouseExpression left;     // typically ClickHouseSelect or nested ClickHouseSetOperation
  SetOpKind op;
  ClickHouseExpression right;
}

class ClickHouseAggregateCombinator {
  enum Suffix { IF, OR_NULL, OR_DEFAULT, DISTINCT, ARRAY, STATE, MERGE, FOR_EACH, RESAMPLE, MAP }
  Suffix suffix;
  List<ClickHouseExpression> extraArgs;  // grammar pinned per suffix
}

class ClickHouseAggregate {                // extended
  ClickHouseAggregateFunction func;
  ClickHouseExpression expr;
  List<ClickHouseAggregateCombinator> chain;  // empty for plain aggregates
}

class ClickHouseSelect {                   // extended
  // ...existing fields...
  List<ClickHouseExpression> arrayJoinExprs; // empty when no ARRAY JOIN
  boolean arrayJoinLeft;                     // LEFT ARRAY JOIN vs ARRAY JOIN
}
```

**Set-op TLP shape**, illustrative for `UNION ALL`:

```
predicate p                               -> select.where = p           -> Q_p
NOT p                                     -> select.where = NOT p       -> Q_np
p IS NULL                                 -> select.where = p IS NULL   -> Q_null

baseline   = ClickHouseSelect(no where)
threeBranch = ClickHouseSetOperation(
                ClickHouseSetOperation(Q_p, UNION_ALL, Q_np),
                UNION_ALL,
                Q_null)

assert multisetEqual(execute(baseline), execute(threeBranch))
```

For `INTERSECT`:

```
pairs = [(Q_p, Q_np), (Q_p, Q_null), (Q_np, Q_null)]
for (l, r) in pairs:
  intersected = ClickHouseSetOperation(l, INTERSECT, r)
  assert execute(intersected).isEmpty()
```

**Combinator identity catalog**, mirroring EET:

```
record Identity(
    String name,
    Predicate<ClickHouseAggregateFunction> safeForFunc,
    Predicate<ClickHouseType>             safeForType,
    Function<AggregateContext, RewriteSql> applyTo)

CATALOG = [
  Identity("sumIf",     fn == SUM,   isFoldablePrimitive,  ctx -> "sum(if(" + ctx.cond + "," + ctx.x + ",0))"),
  Identity("countIf",   fn == COUNT, any,                  ctx -> "sum(toUInt64(" + ctx.cond + "))"),
  Identity("avgOrNull", fn == AVG,   isFoldablePrimitive,  ctx -> "if(count(" + ctx.x + ")=0, NULL, sum(" + ctx.x + ")/count(" + ctx.x + "))"),
  ...
]
```

## Implementation Units

- [ ] **Unit 1: `ClickHouseSetOperation` AST + visitor support**

**Goal:** Add a first-class set-operation AST node and render it correctly. No oracle uses it yet.

**Requirements:** R1

**Dependencies:** None

**Files:**
- Create: `src/sqlancer/clickhouse/ast/ClickHouseSetOperation.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseVisitor.java` (add `visit(ClickHouseSetOperation)` to the interface; extend the instanceof chain at `:64-96`; teach `asString` at `:98-109` to recognise set-ops at the top level so the outer-parens path is skipped)
- Modify: `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java` (implement `visit(ClickHouseSetOperation)`; render `(left) <OP> (right)` with explicit operator names `UNION ALL`, `UNION DISTINCT`, `INTERSECT`, `EXCEPT`)
- Test: `test/sqlancer/clickhouse/ast/ClickHouseSetOperationTest.java`

**Approach:**
- `ClickHouseSetOperation` is a leaf class — three fields `(left, op, right)` plus a `SetOpKind` enum. Implements `ClickHouseExpression`.
- **Tree shape determines precedence at render time, not SQL operator precedence.** The visitor renders `(left) <OP> (right)` mechanically; the AST builder is responsible for constructing the tree to reflect the desired grouping. ClickHouse's actual set-op precedence is `INTERSECT > UNION = EXCEPT` (ANSI), but this node does not enforce it — callers wishing to honour SQL precedence must construct the tree accordingly. Documented in the class Javadoc.
- The visitor instanceof chain at `ClickHouseVisitor.java:64-96` gets one more `else if (expr instanceof ClickHouseSetOperation)` branch before the `throw new AssertionError(expr)` catch-all.
- `ClickHouseVisitor.asString(ClickHouseExpression)` at `:98-109` currently distinguishes `ClickHouseSelect` for the `inner=false` path. Set-ops need the same treatment to avoid the outer parens that `visit(Select, true)` adds when set-ops contain inner selects.

**Patterns to follow:**
- `src/sqlancer/clickhouse/ast/ClickHouseBinaryLogicalOperation.java` for the `(left, op, right)` shape.
- `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java:27-35` for the operator-with-parens rendering.

**Test scenarios:**
- Happy path: build `SetOperation(SelectA, UNION_ALL, SelectB)`, render via `ClickHouseVisitor.asString`, assert the rendered string contains `UNION ALL` and starts with `SELECT`, not `(`.
- Happy path: each of the four `SetOpKind` values renders with the expected operator name.
- Edge case: nested `SetOperation(SetOperation(A, UNION_ALL, B), INTERSECT, C)` renders with correct precedence parens.
- Edge case: a select with `JOIN` on its left renders correctly inside a set-op (regression against the EET-style outer-parens gotcha at `ClickHouseEETOracle.java:256-259`).
- Edge case: **select-of-set-op composition** — construct a `ClickHouseSelect` whose `fromClauses` contains a `ClickHouseSetOperation`. Render and assert the output matches `SELECT … FROM ((SELECT … FROM t1) UNION ALL (SELECT … FROM t2))`. This is the future-migration shape for `ClickHouseTLPAggregateOracle:56-63` and must compose cleanly.
- Edge case: explicit `INTERSECT ALL`, `INTERSECT DISTINCT`, `EXCEPT ALL`, `EXCEPT DISTINCT` operator keywords render in the SQL string (not just the bare `INTERSECT` / `EXCEPT` form that depends on server defaults).
- Integration: the visitor instanceof dispatch returns the right branch — passing a `ClickHouseSetOperation` to `ClickHouseVisitor.visit(ClickHouseExpression)` calls into the set-op visitor, not the catch-all `AssertionError`.

**Verification:**
- `mvn verify -DskipTests=true` passes.
- Unit tests for the four set-op kinds pass.
- No existing oracle behaviour changes (no oracle uses this node yet).

---

- [ ] **Unit 2: `ClickHouseTLPSetOpOracle` + activation flag**

**Goal:** A new TLP-family oracle that exercises the four set-op invariants from Key Technical Decisions.

**Requirements:** R2, R7, R8

**Dependencies:** Unit 1

**Files:**
- Create: `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPSetOpOracle.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseOptions.java` (add `enableSetOpTLP` flag, default OFF; `--test-set-op-tlp`)
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (add `SetOpTLP` enum constant)
- Modify: `src/sqlancer/clickhouse/ClickHouseErrors.java` (add `getExpectedSetOpErrors()` returning the empirical-discovery patterns; merge into the new oracle's `ExpectedErrors` builder in its constructor)
- Test: `test/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPSetOpOracleSmokeTest.java`

**Approach:**
- Subclass `ClickHouseTLPBase`. In `check()`: call `super.check()` to build the base select and TLP predicate variants, then **guard before proceeding**:
  - **Aggregate-free fetchColumns guard.** If `select.getFetchColumns()` contains any `ClickHouseAggregate`, throw `IgnoreMeException`. TLP partition correctness requires per-row classification; aggregates collapse rows.
  - **Non-determinism predicate guard.** Walk `predicate` (and `negatedPredicate`, `isNullPredicate`) for deny-listed identifiers (`rand`, `randConstant`, `rand64`, `now`, `now64`, `today`, `yesterday`, `generateUUIDv4`, `randomString`, `randomFixedString`, `canonicalRand`) and for any embedded `ClickHouseAggregate`. If any are found, throw `IgnoreMeException`. The deny list is a small private constant near the top of the file, with a one-line `// reason: re-evaluation flips classification` comment.
  - **Startup probe (one-shot per oracle instance).** On first `check()`, run `SELECT 1 SETTINGS intersect_default_mode='ALL', except_default_mode='ALL', union_default_mode='DISTINCT'` against the connection. If any of these raises `UNKNOWN_SETTING`, mark the oracle disabled for the run and throw `IgnoreMeException` from every subsequent `check()`. The probe is cached in an instance field.
- Pick a `SetOpKind` at random per `check()` (mode-enum staged delivery pattern from EET).
- For `UNION ALL`: build three branch selects (where = p, where = NOT p, where = p IS NULL), assemble into a `ClickHouseSetOperation` tree via right-association, compare the multiset to the baseline `T` rendered as a single select.
- For `UNION DISTINCT`: prefix the baseline with `SELECT DISTINCT` and each branch with `SELECT DISTINCT`, compare as sets. DISTINCT at every leaf is mandatory — not just the outer `UNION DISTINCT`.
- For `INTERSECT`: build three pairs `(Tp_distinct INTERSECT ALL Tnp_distinct)`, `(Tp_distinct INTERSECT ALL T_null_p_distinct)`, `(Tnp_distinct INTERSECT ALL T_null_p_distinct)` with leaf-DISTINCT; each must return empty.
- For `EXCEPT`: **two invariants, not one**:
  - **Coverage:** `DISTINCT(T) EXCEPT ALL DISTINCT(Tp) EXCEPT ALL DISTINCT(Tnp) EXCEPT ALL DISTINCT(T_null_p)` returns empty.
  - **Pairwise disjointness:** `DISTINCT(Tp) EXCEPT ALL DISTINCT(Tnp)` returns the same set as `DISTINCT(Tp)`, and the two symmetric variants.
- **Render explicit operator keywords** (`INTERSECT ALL`, `INTERSECT DISTINCT`, `EXCEPT ALL`, `EXCEPT DISTINCT`) in the SQL string — not the bare `INTERSECT` / `EXCEPT` form. SETTINGS pinning is belt-and-suspenders.
- All four queries get `SETTINGS union_default_mode='DISTINCT', intersect_default_mode='ALL', except_default_mode='ALL', aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0` appended.
- **Substantive-vs-trivial counter.** Log `state.getState().getLocalState().log("setop-tlp: kind=K, baseline_size=N, branch_sizes=(np,nnp,nnull), substantive=<bool>")` per `check()` for post-run analysis.
- Comparison: `ComparatorHelper.getResultSetFirstColumnAsString(...)` for the baseline, same for the branched, multiset or set comparison per kind.
- **Single-column fetchColumns constraint.** `getResultSetFirstColumnAsString` collapses each row to its first column; multi-column queries silently lose information for the comparison. To avoid this masking real planner bugs on multi-column tuples (especially INTERSECT/EXCEPT which is most interesting on tuples), SetOpTLP's generated `fetchColumns` is constrained to **one column** until a multi-column comparison helper exists. Documented as a limitation; a multi-column extension is a follow-up. Same constraint as the existing TLP oracles.

**Execution note:** Smoke-test the four invariants test-first against a real ClickHouse container before wiring up the full TLP loop.

**Patterns to follow:**
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPAggregateOracle.java:25-85` for the predicate-rewrite-and-UNION-ALL loop.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java:42, 61` for the dual SETTINGS suffix.
- `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETOracle.java:137` for the `Mode` enum + `pickMode()` pattern.
- `src/sqlancer/clickhouse/oracle/semr/ClickHouseSEMROracle.java:27-39` for the SETTINGS suffix style.

**Test scenarios:**
- Happy path (UNION ALL): with a non-Nullable column and a deterministic predicate `c1 > 0`, the baseline and three-branch UNION ALL produce identical multisets. Assert no `AssertionError`.
- Happy path (UNION DISTINCT): same, with set comparison.
- Happy path (INTERSECT): all three pairwise intersections return zero rows.
- Happy path (EXCEPT): the four-way EXCEPT chain returns zero rows.
- Edge case: empty table — all four invariants trivially hold; the oracle must not throw `AssertionError` on `0 == 0`.
- Edge case: a Nullable column where some rows satisfy `p IS NULL` — both the canonical UNION ALL invariant and the INTERSECT empty-intersection invariant should hold.
- Edge case: a table with one row and a `1=1` predicate — UNION ALL has Q_p with one row, Q_np with zero, Q_null with zero; the baseline has one row. The three-branch sum is one row.
- Edge case (EXCEPT pairwise disjointness): a table with two rows, predicate `c1 > 0` selects one row, `NOT c1 > 0` selects the other. `DISTINCT(Tp) EXCEPT ALL DISTINCT(Tnp)` returns exactly `DISTINCT(Tp)` (the one row in Tp).
- Edge case (DISTINCT-at-leaf load-bearing): construct a deterministic fixture where T has duplicate rows but each predicate branch sees them only once. Verify UNION ALL invariant fails as expected when leaf-DISTINCT is forgotten on T but holds when leaf-DISTINCT is applied — locks down the multiset-leaf semantics for future maintainers.
- Edge case (substantive-vs-trivial): a predicate that filters all rows to `T_null_p` only — INTERSECT and EXCEPT pass trivially; UNION ALL still validates the partition. The log entry records `substantive=false`.
- Error path (non-deterministic guard): construct a predicate containing `rand() > 0.5`. The guard rejects via `IgnoreMeException`; the oracle does not run the query. Verify the deny-list walker hits this path (negative-assertion test).
- Error path (aggregate-in-fetchCols guard): construct a base select whose `fetchColumns` contains `sum(c1)`. The guard rejects via `IgnoreMeException`.
- Error path (setting probe): on a server that rejects `intersect_default_mode`, the probe runs once, the oracle marks disabled, all subsequent `check()` invocations return `IgnoreMeException`. The probe error itself does not pollute `ClickHouseErrors`.
- Error path: an expression generator picks a column type that ClickHouse rejects in INTERSECT (e.g., `AggregateFunction` if it ever appears) — the catalog entry for `Number of columns doesn't match` absorbs the error and the oracle reports `IgnoreMeException`, not a false positive.
- Integration: enabling `--oracle=SetOpTLP` runs without crashing for ≥10 minutes on the CI-pinned ClickHouse image.
- Integration: with `--oracle=SetOpTLP` and no other flags, the no-regression bar holds — zero unhandled `SQLException` outside the catalog over a 100k-iteration smoke run.

**Verification:**
- All four invariants pass against a deterministic seed on the CI-pinned image.
- The oracle slot in `ClickHouseOracleFactory` is reachable via `--oracle=SetOpTLP`.
- `enableSetOpTLP=false` (the default) keeps the oracle unregistered when not explicitly named.

---

- [ ] **Unit 3: `ClickHouseAggregateCombinator` + extended `ClickHouseAggregate`**

**Goal:** AST representation for combinator chains; the to-string visitor renders them in source order. Plain aggregates remain valid.

**Requirements:** R3

**Dependencies:** None (independent of Units 1–2)

**Files:**
- Create: `src/sqlancer/clickhouse/ast/ClickHouseAggregateCombinator.java`
- Modify: `src/sqlancer/clickhouse/ast/ClickHouseAggregate.java` (add `List<ClickHouseAggregateCombinator> chain` field; default empty; backward-compatible constructor)
- Modify: `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java:120-125` (rewrite `visit(ClickHouseAggregate)` to fold the chain: emit `func + chainSuffixes`, then `(expr, extraArgsOfSuffix1, extraArgsOfSuffix2, ...)`)
- Test: `test/sqlancer/clickhouse/ast/ClickHouseAggregateCombinatorTest.java`

**Approach:**
- `ClickHouseAggregateCombinator` is `(suffix: Suffix, extraArgs: List<ClickHouseExpression>)`. The `Suffix` enum carries a `requiredArgCount` (or `argSpec`) and a textual suffix.
- Rendering convention: `<funcName><Suffix1Camel><Suffix2Camel>(expr, extraArgs1.., extraArgs2..)`. Order: suffixes appear in declaration order; extra args appear in the same order. Example: `sumIf(x, c)` is `(SUM, [(IF, [c])])` rendered as `sum + If + (x, c)`.
- The textual form per suffix:
  - `IF` → `If` (one extra arg: condition)
  - `OR_NULL` → `OrNull` (no extra args)
  - `OR_DEFAULT` → `OrDefault` (no extra args)
  - `DISTINCT` → `Distinct` (no extra args)
  - `ARRAY` → `Array` (no extra args)
  - `STATE` → `State` (no extra args)
  - `MERGE` → `Merge` (no extra args)
  - `FOR_EACH` → `ForEach` (no extra args)
  - `RESAMPLE` → `Resample` (three extra args: key, from, to — placed after the chain, inside parens)
  - `MAP` → `Map` (no extra args)
- Keep `ClickHouseAggregate.getFunc()` and `getExpr()` unchanged for backward compatibility with `ClickHouseTLPAggregateOracle.java:56` which reads `aggregate.getFunc().toString()`.

**Patterns to follow:**
- `src/sqlancer/clickhouse/ast/ClickHouseAggregate.java:17-66` for the enum-with-supported-types shape.
- `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java:187-194` for `func(arg1, arg2)` rendering.

**Test scenarios:**
- Happy path: `Aggregate(SUM, x, [])` renders as `SUM(x)` — backward compatible.
- Happy path: `Aggregate(SUM, x, [(IF, [c])])` renders as `sumIf(x, c)`.
- Happy path: `Aggregate(COUNT, c, [(IF, [])])` … wait — `countIf(c)` takes the condition as its *only* argument. Confirm grammar: `countIf(c)` has no `x`, just the condition. **Resolution**: `countIf` is modelled as `Aggregate(COUNT, c, [(IF, [])])` where the `expr` is the condition itself, not a value-x. Document this in the suffix's `argSpec`.
- Happy path: `Aggregate(SUM, x, [(IF, [c]), (ARRAY, [])])` renders as `sumIfArray(x, c)` — the `-Array` suffix appends to the chain, not the args.
- Happy path: `Aggregate(QUANTILE, x, [(RESAMPLE, [key, from, to])])` renders as `quantileResample(x, key, from, to)`.
- Edge case: empty chain `Aggregate(MAX, x, [])` renders as `MAX(x)` exactly as before; verify against a golden test that compares to the pre-change rendering.
- Edge case: three-deep chain `Aggregate(SUM, x, [(DISTINCT, []), (IF, [c]), (ARRAY, [])])` renders as `sumDistinctIfArray(x, c)`.
- Edge case (order-sensitivity, locks in correctness): construct `(SUM, x, [(IF, [c]), (ARRAY, [])])` and `(SUM, x, [(ARRAY, []), (IF, [c])])` — assert the rendered strings differ (`sumIfArray` vs `sumArrayIf`). This is the load-bearing test for chain-order preservation.
- Edge case (`countIf` asymmetry): construct `(COUNT, c, [(IF, [])])` where `c` is a boolean expression and `extraArgs` is empty. Assert it renders as `countIf(c)`. Assert the constructor accepts this shape (boolean-typed `expr` + chain-with-IF) but rejects `(COUNT, intExpr, [(IF, [])])` where `intExpr` is not boolean.
- Edge case (downstream `getExpr()` consumer): mock a `getExpr()`-walking consumer (mimicking `ClickHouseTLPHavingOracle.java:46-47`) and verify it handles the `countIf` shape without crashing. The asymmetry is documented in `ClickHouseAggregate`'s Javadoc.
- Error path: passing `extraArgs.size()` that doesn't match the suffix's `argSpec` is allowed at the AST level (the SQL parser rejects it) — verify the to-string visitor still renders something the parser will recognise and reject, rather than throwing a Java exception itself.

**Verification:**
- All four chain depths (0/1/2/3) render correctly.
- The `getFunc()` accessor still returns the base aggregate enum (no API break for callers).
- `mvn verify -DskipTests=true` passes.

---

- [ ] **Unit 4: Generator integration for combinator chains**

**Goal:** `ClickHouseExpressionGenerator` emits combinator chains when `enableCombinators` is on. The plain-aggregate path stays intact when off.

**Requirements:** R4

**Dependencies:** Unit 3

**Files:**
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java:144-152, 240, 453-470` (aggregate insertion points: add an opt-in chain-sampling branch gated by `state.getDbmsSpecificOptions().enableCombinators`)
- Modify: `src/sqlancer/clickhouse/ClickHouseOptions.java` (add `enableCombinators` boolean flag, default OFF; `--test-aggregate-combinators`)
- Modify: `src/sqlancer/clickhouse/ClickHouseErrors.java` (add `getExpectedCombinatorErrors()` returning combinator-specific substrings)

**Approach:**
- New method `ClickHouseExpressionGenerator.generateCombinatorChain(ClickHouseAggregate.ClickHouseAggregateFunction func): List<ClickHouseAggregateCombinator>`. Decision tree:
  1. Roll a low-probability boolean to attach any chain at all (e.g., `Randomly.getBooleanWithRatherLowProbability()` from the SEMR precedent at `ClickHouseSEMROracle.java:27`).
  2. Roll a chain length 1–3 with descending probabilities.
  3. For each position, pick a suffix. Initial weights: `IF: 30, OR_NULL: 20, OR_DEFAULT: 10, DISTINCT: 15, ARRAY: 5, STATE: 5, MERGE: 5, FOR_EACH: 3, RESAMPLE: 3, MAP: 4` (numeric weights, tunable from `ClickHouseOptions` later).
  4. For each suffix, generate the required extra args:
     - `IF` → one boolean expression (use `generateExpressionWithColumns(columns, remainingDepth)` and cast/wrap if needed).
     - `RESAMPLE` → three numeric expressions (key, from, to). For v1 these can be literal integers.
     - All others → no extra args.
- The generator does not pre-validate type compatibility (e.g., `-Array` on a non-aggregate-state column). The error catalog absorbs the failure.
- Existing aggregate insertion sites (`generateAggregateExpressionWithColumns` at `:144`, the inline branch at `:240`, the helper at `:453`) call the new chain generator when the flag is on, and pass `[]` when off.

**Execution note:** Smoke-test combinator emission with a 10k-iteration empirical run against the CI-pinned image to populate `getExpectedCombinatorErrors()`.

**Patterns to follow:**
- `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java:189-288` for the recursive-descent depth-limit shape.
- `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java:156-187` for numeric/integer pre-filter helpers (reused for `-Resample` extra args).

**Test scenarios:**
- Happy path: with `enableCombinators=false`, the generator's aggregate output is exactly identical to baseline (no chain). Verify with a fixed-seed run.
- Happy path: with `enableCombinators=true` and a fixed seed, at least one of the first 100 generated aggregates carries a non-empty chain.
- Happy path: a `sumIf` chain renders, executes against a real ClickHouse table, and returns the same result as the equivalent `sum(if(...))` expression.
- Edge case: chain length 3 with conflicting suffixes (`sumStateMergeArray`) — the generator still emits well-formed SQL; the parser may reject; the catalog absorbs.
- Edge case: `RESAMPLE` extra args with non-numeric expressions — the parser rejects; the catalog absorbs via `argument of function`.
- Error path: an invalid suffix combination on a type (e.g., `-Array` on `Int32`) — the catalog entry for `Combinator * is only applicable for aggregate functions` (or whichever ClickHouse error code) absorbs the failure. Iterate the empirical catalog until the no-regression bar holds.
- Integration: with `enableCombinators=true`, all existing oracles (TLPWhere, TLPAggregate, NoREC, CERT) still run to completion over a 100k-iteration smoke. New `SQLException` patterns are added to `ClickHouseErrors` as discovered.

**Verification:**
- 100k-iteration no-regression run with `enableCombinators=true`, `enableNullable=false`, `enableLowCardinality=false` produces zero unhandled `SQLException` outside the (expanded) catalog.
- The default-OFF state preserves baseline exactly.

---

- [ ] **Unit 5: `ClickHouseTLPCombinatorOracle` — algebraic-identity catalog**

**Goal:** New oracle that asserts named combinator identities — at minimum `sumIf`, `countIf`, `avgOrNull` from Requirements; extensible per the EET pattern.

**Requirements:** R5

**Dependencies:** Units 3, 4

**Files:**
- Create: `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPCombinatorOracle.java`
- Create: `src/sqlancer/clickhouse/oracle/tlp/ClickHouseCombinatorIdentities.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseOptions.java` (add `enableCombinatorTLP` flag; `--test-combinator-tlp`)
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (add `CombinatorTLP` enum constant)
- Test: `test/sqlancer/clickhouse/oracle/tlp/ClickHouseCombinatorIdentitiesTest.java`

**Approach:**
- Mirror `ClickHouseEETIdentities`. The catalog is a `List<Identity>` where each entry has:
  - A name (e.g., `"sumIf"`).
  - A `safeForFunc: Predicate<ClickHouseAggregateFunction>` (e.g., `fn -> fn == SUM`).
  - A `safeForType: Predicate<ClickHouseType>` (mirror the foldable-primitive predicate from `ClickHouseEETIdentities`).
  - A `applyTo(AggregateContext): RewriteSql` returning the equivalent expression in SQL string form.
- Identities to ship in v1:
  - `sumIf`: `sumIf(x, c) ≡ sum(if(c, x, 0))` — safe for numeric `x`. Setting: `aggregate_functions_null_for_empty=1`.
  - `countIf`: `countIf(c) ≡ sum(toUInt64(c))` — safe for any boolean-coercible `c`. Setting: `aggregate_functions_null_for_empty=1`.
  - `avgOrNull`: `avgOrNull(x) ≡ if(count(x)=0, NULL, sum(x)/count(x))` — safe for numeric `x`. **Setting override**: `aggregate_functions_null_for_empty=0` to avoid double-encoding the empty-NULL semantics.
  - `sumOrNull`: `sumOrNull(x) ≡ if(count(x)=0, NULL, sum(x))` — safe for numeric `x`. **Setting override**: `aggregate_functions_null_for_empty=0`.
  - `minIf`: `minIf(x, c) ≡ min(if(c, x, NULL))` — safe for any ordered type; requires `aggregate_functions_null_for_empty=1`.
  - `maxIf`: `maxIf(x, c) ≡ max(if(c, x, NULL))` — symmetric.
- **Identity-firing arg-ambiguity guard.** Before picking an identity, the oracle checks that the chain on the chosen aggregate contains **at most one extra-arg-bearing suffix**. If the chain has two or more (`sumIfResample`, etc.), throw `IgnoreMeException` — the catalog has no decomposition for ambiguous arg-distribution chains. The identity oracle's value comes from named simple rewrites, not from cross-product enumeration; the generator-side coverage of multi-extra-arg chains (Unit 4) still produces fuzzing signal through other oracles.
- Oracle structure: extend `ClickHouseTLPBase`. In `check()`: build the base select via `super.check()`, pick an aggregate + an identity whose `safeFor` predicates accept it, **apply the same non-determinism guard from Unit 2** to the picked expression (re-evaluation issues hit identities too), build both forms as full select queries (no UNION ALL — just two equivalent SELECTs), execute both, compare result sets with `ComparatorHelper.assumeResultSetsAreEqual` (mirroring `ClickHouseSEMROracle.java:29`).
- **Per-identity SETTINGS** are picked from the identity's declaration, not hardcoded at the oracle. Default is `aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0`; identities that need `=0` (the `-OrNull` family) override.

**Execution note:** Test the five seed identities directly against a real ClickHouse container before wiring up the oracle's `check()`.

**Patterns to follow:**
- `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETIdentities.java:36-118` for the catalog + `safeFor` predicate shape.
- `src/sqlancer/clickhouse/oracle/semr/ClickHouseSEMROracle.java:19-39` for the minimal two-form comparison oracle.

**Test scenarios:**
- Happy path (`sumIf`): on a table with `(c1 Int32, c2 UInt8)`, the rewrite produces identical results.
- Happy path (`countIf`): on the same table, the rewrite produces identical results.
- Happy path (`avgOrNull`): on a Nullable column with some null and some non-null rows, the rewrite returns the same value.
- Happy path (`minIf` / `maxIf`): on an empty filter result, both sides return NULL (because `aggregate_functions_null_for_empty=1`).
- Edge case: a column where `safeForType` rejects — `pickIdentityForType` returns empty; the oracle throws `IgnoreMeException`. The bar: no `AssertionError`.
- Edge case: `countIf(NULL)` — the rewrite `sum(toUInt64(NULL))` returns 0 in ClickHouse. Both sides agree.
- Edge case: large overflow input for `sumIf` — both sides agree on overflow handling.
- Edge case (arg-ambiguity guard): a generated aggregate with chain `[(IF, [c]), (RESAMPLE, [k, f, t])]` — the guard rejects via `IgnoreMeException`. Verify the guard hits this path.
- Edge case (`-OrNull` setting override): `avgOrNull(x)` with `aggregate_functions_null_for_empty=1` does NOT produce the same value as the rewrite `if(count(x)=0, NULL, sum(x)/count(x))` because the setting also coerces; the override `=0` is mandatory. Construct a test that demonstrates the divergence with `=1` and the convergence with `=0`.
- Error path: the empty-table case for `avgOrNull` — both sides return `NULL` (the `if(count=0,...)` branch). No false positive.
- Error path (non-determinism guard): a picked aggregate's `expr` contains `rand()` — guard rejects via `IgnoreMeException`.
- Integration: oracle runs for ≥10 minutes on the CI-pinned image without `AssertionError`.

**Verification:**
- All five seed identities pass on a deterministic seed.
- Catalog is extensible (a new identity is one row).

---

- [ ] **Unit 6: ARRAY JOIN structural plumbing on `ClickHouseSelect`**

**Goal:** Add the AST field, the visitor emission position, and the `enableArrayJoin` flag. The generator never populates the field; the visitor emits nothing when empty. Net oracle behaviour change: zero.

**Requirements:** R6

**Dependencies:** None

**Files:**
- Modify: `src/sqlancer/clickhouse/ast/ClickHouseSelect.java` (add `arrayJoinExprs: List<ClickHouseExpression>`, defaulting to `Collections.emptyList()`; add `arrayJoinLeft: boolean`; getters and setters)
- Modify: `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java:81-87` (between FROM emission and JOIN emission, emit `[LEFT] ARRAY JOIN <exprs>` if `arrayJoinExprs` is non-empty)
- Modify: `src/sqlancer/clickhouse/ClickHouseOptions.java` (add `enableArrayJoin` flag, default OFF; `--test-array-join`)
- Test: `test/sqlancer/clickhouse/ast/ClickHouseSelectArrayJoinTest.java`

**Approach:**
- Single-field extension on `ClickHouseSelect`. The visitor emits nothing when `arrayJoinExprs.isEmpty()`. When non-empty, emit `" ARRAY JOIN "` (or `" LEFT ARRAY JOIN "` if `arrayJoinLeft`) followed by a comma-separated list rendered via `visit(arrayJoinExprs)`.
- The `enableArrayJoin` flag is declared but unused by any generator path. Its existence is the contract the type-system v2 work flips on.
- A comment block on `ClickHouseSelect.arrayJoinExprs` documents the deferred activation explicitly (the field is here so v2 can use it; until then, leave empty).

**Patterns to follow:**
- `src/sqlancer/clickhouse/ast/ClickHouseSelect.java:23-67` for the field-getter-setter shape.
- `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java:82-87` for the conditional-emission pattern.

**Test scenarios:**
- Happy path (default empty): construct a `ClickHouseSelect` with `arrayJoinExprs = []`, render via `ClickHouseVisitor.asString`, assert the output contains neither `ARRAY JOIN` nor `LEFT ARRAY JOIN`.
- Happy path (non-empty): construct with `arrayJoinExprs = [columnReference("arr")]`, render, assert the output contains `ARRAY JOIN arr` and that ARRAY JOIN is between FROM and WHERE.
- Happy path (LEFT): with `arrayJoinLeft = true` and one expression, the output contains `LEFT ARRAY JOIN`.
- Edge case: multiple expressions render comma-separated: `ARRAY JOIN arr1, arr2`.
- Integration: with the default empty field and `enableArrayJoin = false`, all existing oracles run identically to baseline. The new field's existence does not change query strings for any pre-existing test.

**Verification:**
- The new field defaults to empty in all existing call sites.
- 100k-iteration smoke with all flags at defaults produces zero string-level diffs against pre-change baseline (for a fixed seed corpus).

---

- [ ] **Unit 7: Error catalog tuning — empirical iteration**

**Goal:** Populate `ClickHouseErrors` with the substring patterns discovered through 1k–10k-iteration runs of each new oracle. Runs incrementally alongside Units 2, 4–5.

> **Note:** The ARRAY JOIN oracle + Array column activation (formerly Unit 7) is **not an implementation unit in this plan** — it depends on v2 of `docs/plans/2026-05-16-001-feat-clickhouse-type-system-foundation-plan.md`, which has no committed date. See Future Considerations for the activation sketch.

**Requirements:** R7, R8 (no-regression bar)

**Dependencies:** Units 2, 4, 5 (one round per unit's activation commit).

**Files:**
- Modify: `src/sqlancer/clickhouse/ClickHouseErrors.java` (add or extend `getExpectedSetOpErrors`, `getExpectedCombinatorErrors`, `getExpectedArrayJoinErrors`; merge into the relevant oracle's `ExpectedErrors` builder)
- Test: `test/sqlancer/clickhouse/ClickHouseErrorsSpecificityTest.java` (negative-assertion test — assert specific multi-word substrings do *not* match common unrelated ClickHouse error strings)

**Approach:**
- For each oracle's activation commit: run 1k–10k iterations against the CI-pinned ClickHouse image. Capture every `SQLException` not absorbed by the catalog. Triage:
  - If the message describes a true generator bug, fix the generator (don't paper over it).
  - If the message describes a known ClickHouse parse-reject or type-reject, add a multi-word substring that catches it but does not catch unrelated messages.
- Initial candidate substrings (refined empirically):
  - Set-ops: `Number of columns doesn't match`, `Cannot find common type for tuple elements`, `INCOMPATIBLE_COLUMNS`.
  - Combinators: `Unknown aggregate function`, `NUMBER_OF_ARGUMENTS_DOESNT_MATCH`, `Combinator * is only applicable for aggregate functions`, `Aggregate function * is not supported`, `Cannot apply combinator`, `AGGREGATE_FUNCTION_THROW`.
  - ARRAY JOIN (when unblocked): `ARRAY JOIN requires array argument`, `Cannot ARRAY JOIN`.
- **Settings-probe errors stay out of the oracle's error catalog.** The startup probe in Unit 2 catches `UNKNOWN_SETTING` separately and uses it to disable the oracle for the run. Do NOT add the `union_default_mode` / `intersect_default_mode` / `except_default_mode` substrings to the oracle's `ExpectedErrors` — that would mask the probe's signal and let setting-name drift go undetected.
- Verify each new substring via the negative-assertion test pattern from the SEMR plan (`docs/plans/2026-05-17-001-feat-clickhouse-semr-oracle-settings-randomization-plan.md:301`).

**Patterns to follow:**
- `src/sqlancer/clickhouse/ClickHouseErrors.java:104-110` for the comment-block-explaining-why convention.

**Test scenarios:**
- Happy path: a deterministic-seed run with each oracle on for 10k iterations produces zero unhandled `SQLException`.
- Edge case (negative assertion): the unit test asserts that the substring `Setting` is NOT in the catalog (would mask too many real bugs).
- Edge case (negative assertion): the substring `function` is NOT in the catalog (would mask unrelated function-name errors).
- Integration: each oracle's `ExpectedErrors` includes both the global expression-errors catalog and its oracle-specific additions.

**Verification:**
- 100k-iteration no-regression smoke at the final PR burn-in passes (single combined run; see Success Metrics).
- Negative-assertion specificity test passes.

## System-Wide Impact

- **Interaction graph:** `ClickHouseVisitor` instanceof chain at `src/sqlancer/clickhouse/ClickHouseVisitor.java:64-96` gains one new node type (`ClickHouseSetOperation`). The `asString(ClickHouseExpression)` top-level dispatcher at `:98-109` gains a set-op branch. Existing oracles that traverse expression trees (`ClickHouseTLPBase`, `ClickHouseEETOracle`, `ClickHouseCERTOracle`) do not currently encounter set-ops, but the new oracle will produce them — review whether any oracle does generic expression-walking that would need set-op awareness.
- **Error propagation:** Empirical catalog discovery is per-unit. A premature merge of an oracle without its activation-run catalog tuning would produce false positives. Mitigated by the default-OFF-then-activation-PR pattern (Units 2, 4–5 each gate behind their own flag).
- **State lifecycle risks:** None at the schema or session level. All ClickHouse setting overrides go through per-query `SETTINGS` suffixes (no `SET` on the connection, per the SEMR institutional learning).
- **API surface parity:** `ClickHouseAggregate` API stays backward-compatible — `getFunc()`, `getExpr()` unchanged. Existing call sites (`ClickHouseTLPAggregateOracle.java:56` reads `getFunc().toString()`) continue to work. New `getChain()` accessor is additive.
- **Integration coverage:** Each new oracle is wired through `ClickHouseOracleFactory` and runs via `--oracle=<name>` from `Main.executeMain`. The standard `CompositeTestOracle` composition path picks up the new oracles automatically.
- **Unchanged invariants:**
  - `ClickHouseTLPAggregateOracle.check()` and `ClickHouseTLPHavingOracle.check()` continue to build `UNION ALL` via string concatenation. The new AST node does **not** replace their internals in this plan.
  - `ClickHouseSelect`'s existing fields and their visitor emission positions are unchanged. ARRAY JOIN is an additive emission between FROM and any regular JOIN clauses; no existing emission moves.
  - `ClickHouseAggregateFunction`'s closed enum remains closed. Aggregates new to the combinator oracle (e.g., `quantile`, `groupArray`) are deferred to a follow-up.
  - All existing feature flags (`testJoins`, `enableNullable`, `enableLowCardinality`, etc.) keep their current behaviour. New flags default OFF.
  - The pre-flight mutual-exclusion check pattern from `Main.executeMain` is preserved (none of the new oracles introduce new mutual-exclusion constraints; the combinator and set-op flags are independent).

## Risks & Dependencies

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Combinator type-constraint errors flood the catalog and mask real bugs | Med | High | Empirical iteration per Unit 8; multi-word-substring discipline from SEMR plan; negative-assertion test. |
| Set-op semantics differ across ClickHouse versions (union_default_mode etc.) | Med | Med | Render explicit `INTERSECT ALL/DISTINCT` and `EXCEPT ALL/DISTINCT` keywords in the SQL — not SETTINGS pinning alone. Settings remain as belt-and-suspenders. |
| Pinned `SETTINGS` names rename or disappear in a future ClickHouse version, causing `Unknown setting` floods that mask real regressions elsewhere | Med (12-month horizon) | Med | Startup probe at `ClickHouseProvider` session setup (`SELECT 1 SETTINGS intersect_default_mode='ALL'`). On `UNKNOWN_SETTING`, disable SetOpTLP for the run via `IgnoreMeException` shortcircuit in `check()`. Catalog the probe error separately from oracle-level errors. |
| Non-deterministic predicates (`rand()`, `now()`, `generateUUIDv4()`, etc.) produce false-positive TLP failures because per-row classification flips across re-evaluations | Low today (current generator does not emit them) / High once non-deterministic function generation is added | High (silent false positives) | Deny-list walker scoped to the new SetOpTLP oracle only (not shared `ClickHouseTLPBase`). `IgnoreMeException` if the predicate references any deny-listed identifier or contains an aggregate. List is comment-documented and additive. Same bug exists latently in `ClickHouseTLPAggregateOracle` / `ClickHouseTLPHavingOracle` — tracked as a follow-up but not fixed in this plan. **Structural caveat**: deny-list cannot distinguish "undiscovered non-deterministic function" from "real bug"; a future pass should consider an allow-list of known-pure functions. |
| EXCEPT coverage invariant alone admits false negatives (planner over-counting in `Tp` is invisible to coverage) | Med | High | Ship the paired EXCEPT pairwise-disjointness invariants (`DISTINCT(Tp) EXCEPT DISTINCT(Tnp) ≡ DISTINCT(Tp)` and symmetric variants), not just the coverage form. |
| `aggregate_functions_null_for_empty=1` × `-OrNull` / `-OrDefault` interaction produces version-dependent NULL-vs-default differences | Med | Med | Combinator-identity oracle (Unit 5) sets the setting to `=0` for `-OrNull` / `-OrDefault` identities specifically. Setting stays `=1` for set-op TLP and other identities. |
| `countIf` AST modeling asymmetry (condition in `expr` field instead of extra-args) silently breaks downstream type-aware consumers | Med | Med | Constructor-level assertion in `ClickHouseAggregate` rejects `(COUNT, expr, chain)` where `chain` contains `IF` and `expr` is not boolean-typed. Unit 3 test asserts `ClickHouseTLPHavingOracle`-style consumers handle the asymmetric shape. |
| `CompositeTestOracle` interleaving with all three new flags simultaneously on (combinator emission inside set-op queries; combinator chains in HAVING predicates) produces uncharacterized error patterns | Med | Med | Activation PRs enable one flag at a time. Combined-flag CI matrix added only after each individual flag has passed its own 100k smoke. SetOpTLP's aggregate-free-fetchColumns guard prevents the combinator-in-aggregate-in-set-op three-way interaction. |
| `INTERSECT` / `EXCEPT` invariants don't hold under multiset semantics in edge cases | Low | High | Apply DISTINCT at every leaf (Tp, Tnp, T_null_p, T_distinct). With leaf-DISTINCT, multiset and set interpretations coincide. Validate on test fixture before activation. |
| The combinator chain-rendering visitor breaks existing aggregate rendering (`SUM(x)` vs `sum(x)`) | Low | Med | Backward-compatible test in Unit 3 asserts exact byte-equivalence with the pre-change rendering for empty chains. |
| ARRAY JOIN structural plumbing accidentally activates without `enableArrayJoin` on a non-default path | Low | Low | Default empty field; generator does not populate; smoke-test the field-empty rendering matches baseline byte-for-byte. |
| Type-system v2 (`Array` constructor) slips, blocking ARRAY JOIN activation indefinitely | Med | Low (this plan absorbs) | ARRAY JOIN activation is **not part of this plan's Implementation Units** — it lives in Future Considerations. Units 1–6 land independently. |
| The new oracles need 100k iterations of regression burn-in | Med | Low | Single combined burn-in at the final PR landing (one run with all flags ON), supplemented by per-phase 10×1k yield-gate measurements. CI runs a smaller smoke (e.g., 10k) on push; the 100k burn-in is a manual pre-merge step. |

## Documentation / Operational Notes

- Each unit's activation commit ships its `ClickHouseErrors` additions with inline comments explaining *why* each substring is in the catalog (incident link, error code, or one-line "ClickHouse rejects this combinator on type X" rationale). Mirrors the convention at `src/sqlancer/clickhouse/ClickHouseErrors.java:68-92`.
- The `--oracle=SetOpTLP`, `--oracle=CombinatorTLP`, and (when unblocked) `--oracle=ArrayJoinTLP` flags are documented in `CONTRIBUTING.md` under the ClickHouse section (one line each).
- No rollback or migration plan needed — these are additive features behind default-OFF flags. Disable by removing the `--oracle=<name>` argument.
- Each new oracle's activation commit includes a fixed-seed reproducer example in the PR description (mirroring the EET plan's convention).
- No new metrics or monitoring required. The existing SQLancer reproducer-log pattern absorbs everything.

## Sources & References

- Related code:
  - `src/sqlancer/clickhouse/ast/ClickHouseAggregate.java:17-66` (current aggregate AST)
  - `src/sqlancer/clickhouse/ast/ClickHouseSelect.java` (current select AST, no set-op)
  - `src/sqlancer/clickhouse/ClickHouseVisitor.java:64-109` (visitor dispatch + asString)
  - `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java:60-125` (select + aggregate rendering)
  - `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java:144-152, 240, 453-470` (aggregate insertion points)
  - `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java:35-79` (TLP scaffold)
  - `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPAggregateOracle.java:25-85` (UNION ALL precedent)
  - `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java:42, 61` (dual SETTINGS suffix)
  - `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETIdentities.java:36-118` (identity catalog pattern)
  - `src/sqlancer/clickhouse/oracle/semr/ClickHouseSEMROracle.java:19-39` (minimal SETTINGS-suffix oracle)
  - `src/sqlancer/clickhouse/ClickHouseErrors.java:12-110` (error catalog conventions)
  - `src/sqlancer/clickhouse/ClickHouseOracleFactory.java:22-97` (oracle registration)
  - `src/sqlancer/clickhouse/ClickHouseOptions.java:14-42` (feature flag conventions)
- Related plans:
  - `docs/plans/2026-05-18-001-feat-clickhouse-eet-oracle-plan.md` (mode-enum staged delivery + dual SETTINGS suffix)
  - `docs/plans/2026-05-17-001-feat-clickhouse-semr-oracle-settings-randomization-plan.md` (substring discipline + negative-assertion test)
  - `docs/plans/2026-05-16-001-feat-clickhouse-type-system-foundation-plan.md` (Unit 7 dependency; v2 Array constructor)
- Related external references:
  - ClickHouse#12264 (referenced inline in `ClickHouseTLPHavingOracle.java:42, 61`)
  - `docs/PAPERS.md:39` (TLP overview)

## Alternative Approaches Considered

- **Single PR for all eight units.** Rejected: matches the type-system v1a/v1b precedent of structural-then-activation splits, and the 100k-iteration regression bar per oracle would otherwise compound into a single un-reviewable change.
- **Combinators as new closed-enum entries (`SUM_IF`, `COUNT_IF`).** Rejected: the user requested the full combinator matrix; closed enum entries would scale as `aggregates × combinators × chain_depth` and require renaming for chain-suffix order changes. The chain-as-list shape is the natural ClickHouse model.
- **Migrate `ClickHouseTLPAggregateOracle` and `ClickHouseTLPHavingOracle` to the new AST node as part of Unit 1.** Rejected: regression risk on two working oracles. The new AST node coexists with the string-concat path; future migration is unblocked but not committed.
- **Sample only one set-op kind per oracle invocation instead of cycling through all four.** Rejected for invariant correctness: the four invariants are independent and want independent coverage. The oracle picks one kind per `check()` (mode-enum pattern), so individual invocations only test one — but the random rotation over many invocations covers all four.
- **Pre-validate combinator type constraints at generation time** (e.g., reject `-Array` on non-aggregate-state columns before emitting). Rejected: ClickHouse's actual rules are version-sensitive and would force a parallel constraint catalog. The error-catalog absorption pattern is established convention and is cheaper to maintain.
- **Defer ARRAY JOIN entirely (don't add the structural plumbing now).** Rejected: cheap to add now, would otherwise require touching `ClickHouseSelect` and `ClickHouseToStringVisitor` again when type-system v2 lands.

## Success Metrics

1. **No-regression bar (final PR burn-in):** with all new flags OFF (defaults), 100k iterations across the CI-pinned ClickHouse image produce zero unhandled `SQLException` outside the error catalog. Same bar as the recent EET/SEMR plans.
2. **Activation smoke (final PR burn-in):** with all new flags ON simultaneously, 100k iterations against the CI image produce zero unhandled `AssertionError` (no false positives) and the error catalog is stable (no new catalog entries in the last 10k iterations). Covers the cross-flag interaction matrix (SetOpTLP × Combinators) in one run.
3. **Per-phase yield gates (internal milestones, not pass/fail):**
   - **Gate A** (post-Phase-A commits): 10×1k iterations with `enableSetOpTLP=true` vs pre-Phase-A baseline on the same branch. If unique-stack-trace count is within ±10% of baseline, record in PR description as a signal that Phase A's surface is producing no measurable new yield; consider whether Phase B's full combinator matrix is still justified or whether a narrower 4-combinator scope is appropriate.
   - **Gate B** (post-Phase-B commits): same measurement with `enableCombinators=true, enableCombinatorTLP=true` added. If within ±10% of post-Phase-A baseline, record in PR description.
   - These gates are **decision criteria, not pass/fail**. The plan ships either way; what changes is whether scope is adjusted before the final burn-in.
4. **AST coexistence preserved:** existing oracles' string-concat UNION ALL continues to work unchanged; rendering byte-equivalence verified on a fixed-seed corpus.

## Phased Delivery

All work ships as a **single PR** on `nik/clickhouse-add-pqs-cert-coddtest` targeting `fm4v/sqlancer`. The phases below are commit-level sequencing for review and bisectability — not separate PRs.

**Commit sequence on the target branch:**
1. **Phase A — Set Operations** (Units 1 + 2 commits)
2. **Phase A yield-gate measurement** (internal milestone, see Success Metrics)
3. **Phase B — Aggregate Combinators** (Units 3 + 4 + 5 commits)
4. **Phase B yield-gate measurement** (internal milestone)
5. **Phase C — ARRAY JOIN structural** (Unit 6 commit)
6. **Final 100k-iteration burn-in** of all flags combined (single CI smoke for the whole PR)

**Why one PR with commit-level phases (not three PRs):**
- The structural-then-activation split for the type-system plan was driven by the wholesale-revert risk of a foundational refactor. This plan's structural changes are additive AST nodes whose revert is a one-line constructor deletion — the wholesale-revert risk is not symmetric.
- The current branch already carries the recent EET/SEMR/CERT/CODDTest oracle series; bundling matches the established cadence of this in-flight branch.
- Velocity to bug-find signal is faster with one combined burn-in than three sequential per-PR burn-ins.

**Per-phase yield gates** (internal milestones, not blocking gates):
- After Phase A commits, run 10×1k iterations with `enableSetOpTLP=true` and measure unique-stack-trace count vs the pre-Phase-A baseline (same branch, prior commit).
- After Phase B commits, do the same with `enableCombinators=true, enableCombinatorTLP=true` added.
- If either measurement shows within ±10% of baseline (no new bugs detected), record the result in the PR description and consider whether the next phase's scope is still justified before continuing. Decision criterion, not pass/fail.
- The gates are recorded in the PR description under a "Bug-find rate measurements" subsection; if Phase A shows zero new yield, this is the team's signal to consider narrowing Phase B's scope (e.g., dropping back to the narrower 4-combinator set) before the final burn-in.

**ARRAY JOIN activation** is **not part of this PR**. See Future Considerations — depends on v2 of `docs/plans/2026-05-16-001-feat-clickhouse-type-system-foundation-plan.md` with no committed date.

## Future Considerations

- **ARRAY JOIN oracle + Array column activation** *(blocked on type-system v2)*. Once `Type.Array(inner)` exists and `ClickHouseLancerDataType.getRandom()` can emit Array columns, activate the `enableArrayJoin` flag and add `ClickHouseTLPArrayJoinOracle` validating the equivalence `SELECT arrayJoin(arr) FROM t ≡ SELECT x FROM t ARRAY JOIN arr AS x`. The structural plumbing from Unit 6 supports a list of expressions and the LEFT toggle. The v2 activation will need to decide: (a) ARRAY JOIN inside JOIN chains for queries that mix unnesting with regular joins, (b) parallel vs cartesian semantics for `ARRAY JOIN a, b`, (c) aliased forms `ARRAY JOIN a AS x, b AS y`. Files to create: `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPArrayJoinOracle.java`; modifications to `ClickHouseExpressionGenerator`, `ClickHouseOracleFactory`, `ClickHouseErrors`. Re-plan when type-system v2 is in `main`.
- **String-concat UNION ALL migration in `ClickHouseTLPAggregateOracle` and `ClickHouseTLPHavingOracle`.** Once Unit 1 lands and the new AST node is exercised by Unit 2, a follow-up PR can migrate these two oracles to build their UNION ALL via `ClickHouseSetOperation`. Net code reduction ~10 lines.
- **Aggregate function expansion.** `quantile`, `quantiles`, `groupArray`, `groupBitOr`, `argMax`, `argMin`, etc. would each enable additional combinator identities. Tracked as follow-up.
- **`-State` / `-Merge` combinators against `AggregateFunction` columns.** Once the type-system extension protocol's `AggregateFunction` lands (type-system v3 per `docs/brainstorms/clickhouse-type-system-foundation-requirements.md:194`), the combinator oracle can express the round-trip identity `sumMerge(sumState(x)) ≡ sum(x)`.
- **Allow-list of deterministic functions** to replace the deny-list approach in SetOpTLP's non-determinism guard. Eliminates the "undiscovered non-deterministic function looks identical to a real bug" structural risk.
- **Hardening existing TLP oracles** (`ClickHouseTLPAggregateOracle`, `ClickHouseTLPHavingOracle`) with the same non-determinism guard once non-deterministic function generation is added to the expression generator.
- **Multi-column result-set comparison** to remove the single-column fetchColumns constraint in SetOpTLP. Would need a new `ComparatorHelper` variant that preserves all columns in the comparison.
