---
title: 'feat: ClickHouse EET (Equivalent Expression Transformation) oracle'
type: feat
status: completed
date: 2026-05-18
---

# feat: ClickHouse EET (Equivalent Expression Transformation) oracle

## Overview

Add a new `EET` test oracle to the ClickHouse SQLancer fork. EET is the companion to CODDTest from the SIGMOD '25 paper (Zhang & Rigger, [DOI 10.1145/3709674](https://doi.org/10.1145/3709674)); it attacks the same constant-folding/short-circuiting/partial-evaluation surface from the opposite direction. Where CODDTest replaces a sub-expression with its pre-computed result and asserts the query is unchanged, EET *injects* expressions that should fold to a fixed value (tautology, contradiction, or algebraic identity) and asserts the query result is unchanged (or empty, for contradictions).

The v1 oracle covers four rewrite modes, picked uniformly per `check()`:

1. **WHERE injection** -- inject `e OR NOT e OR e IS NULL` (3VL tautology) or `e AND NOT e AND e IS NOT NULL` (3VL contradiction) into the WHERE predicate. Tautology must leave the result unchanged; contradiction must produce the empty set.
2. **HAVING injection** -- same shapes injected into the HAVING clause of an aggregated SELECT.
3. **Expression-position rewriting** -- rewrite a SELECT-list value `x` as `if(taut, x, x)`, `multiIf(taut, x, junk, x)`, or `CASE WHEN taut THEN x ELSE x END` (and the contradiction-negated form `if(contra, junk, x)`). Asserts result equality column-by-column.
4. **Algebraic identity rewriting** -- substitute a typed sub-expression `x` with one of `x + 0`, `x * 1`, `concat(x, '')`, `coalesce(x, x)`, `if(true, x, x)`. Asserts result equality.

All four modes inherit the CODDTest oracle's failure-attribution pattern: the original query, the transformed query, and the failing assertion are logged together so the reproducer is self-contained.

## Problem Frame

The CODDTest paper explicitly calls out EET as a documented gap -- not yet implemented for any DBMS in this fork. CODDTest attacks the optimizer's constant-folding surface from the "fold real expressions to their precomputed result" angle. EET attacks the same surface from the inverse angle: "inject an expression that should fold to `true`/`false`/`x` and assert the optimizer recognises it." Both target the same bug class -- wrong-result regressions in the expression-engine, short-circuit, and partial-evaluation paths -- but along orthogonal axes.

**Honest framing.** The oracle's contract is `Q' == Q` (tautology) or `Q'' == empty` (contradiction). When that fails, the cause is one of: (a) a real ClickHouse bug in the rewrite pipeline; (b) a "tautology" that isn't actually a tautology under ClickHouse's coercion rules (e.g., `x + 0 != x` for `Decimal(P, S)` if intermediate widening loses scale, or `concat(x, '')` returning a different type than `x` for `FixedString`); (c) Float NaN / Inf edge cases. The plan budgets explicit safety nets and a `skip-on-typed-mismatch` escape for (b) and (c), modeled on CODDTest's per-row type-stability check.

There is no upstream brainstorm document. The user supplied a complete feature description (confidence 80%, complexity Medium) and confirmed v1 scope during planning: all four modes ship together, full injection surface (WHERE + HAVING + if/multiIf/CASE), no v2-deferred scope reduction.

## Requirements Trace

- **R1.** A new `EET` oracle factory constant is selectable via `--oracle EET` and exercises the four modes (WHERE, HAVING, if/multiIf/CASE, algebraic identities) with a per-check uniform mode picker.
- **R2.** WHERE-mode tautology shape is `e OR NOT e OR e IS NULL`; contradiction shape is `e AND NOT e AND e IS NOT NULL`. The injection is wrapped as `pred AND (taut)` or `pred AND (contra)` so the test exercises the optimizer's predicate-folding through compound `AND` structure, which is where the paper's bug pattern often lives.
- **R3.** HAVING-mode reuses the same tautology/contradiction shapes injected into an aggregation's HAVING clause. The base query is a `SELECT k, agg(x) FROM t GROUP BY k HAVING h`.
- **R4.** Expression-position mode rewrites a SELECT-list expression `x` as `if(taut, x, x)`, `multiIf(taut, x, junk, x)`, or `CASE WHEN taut THEN x ELSE x END`. The contradiction form rewrites as `if(contra, junk, x)`. Both branches share the type of `x` (no type widening), and the rewritten expression is wrapped in `cast(..., 'TypeName(x)')` defensively.
- **R5.** Algebraic-identity mode rewrites a typed sub-expression `x` with one of the safe identities listed in a static typed-identity table. Each identity is annotated with the safe-type predicate (e.g., `x + 0` for integer/Decimal only; `concat(x, '')` for String only; `coalesce(x, x)` for any Nullable). Float types are excluded from `x + 0` / `x * 1` (NaN/-0.0 edge cases). The rewritten expression is wrapped in `cast(..., 'TypeName(x)')` defensively, modeling the CODDTest dependent-phi pattern.
- **R6.** All four modes follow CODDTest's failure-logging convention: the assertion message names the mode, the original query, the transformed query, and (for row mismatches) the diff. State logging via `auxiliaryQueryString` / `originalQueryString` / `foldedQueryString` (or new EET-specific analogs) makes the reproducer self-contained.
- **R7.** Expected-error coverage absorbs the false-positive families surfaced by EET injection: (a) tautology evaluating to an unintended type (e.g., `e` is `Decimal` and the OR-chain yields a coerced `Bool`); (b) `multiIf` arg-list parse errors when junk-branch type drifts; (c) constraint violations when an injected predicate makes ClickHouse choose a different code path that surfaces a latent type rejection. Catalog additions live in `ClickHouseErrors.java`.
- **R8.** Existing oracle behavior is unchanged when `--oracle EET` is not selected. CI green is preserved.
- **R9.** EET is one-of-many oracle. The factory constant slots into the existing `CompositeTestOracle` selection logic; running it alongside other oracles (e.g., `--oracle EET,TLPWhere`) does not require any cross-oracle coordination.

## Scope Boundaries

- **Out of scope (v2):** Injection into ARRAY JOIN, JOIN ON, or PREWHERE positions. v1 ships WHERE, HAVING, and SELECT-list-expression positions (if/multiIf/CASE).
- **Out of scope (v2):** Mode mixing within one query (e.g., inject tautology in WHERE *and* rewrite an identity in SELECT in the same iteration). v1 picks exactly one mode per `check()`. The composition isn't free -- two simultaneous transformations make failure attribution harder.
- **Out of scope:** Cross-oracle algebraic identity sharing (a shared `common/oracle/EETIdentities` class for SQLite, DuckDB, etc.). v1 keeps the identity table ClickHouse-specific because the safe-type predicates (especially `concat`, `coalesce`, and Float exclusion) are dialect-specific.
- **Out of scope:** Operator-controlled mode masking via CLI flag (e.g., `--eet-modes WHERE,HAVING`). All four modes always run with uniform probability in v1. Add a flag back when a concrete need surfaces.
- **Out of scope:** Tautology shapes beyond the paper-canonical `e OR NOT e OR e IS NULL`. The paper's contribution is the 3VL-aware form; alternative shapes (`(e AND x) OR (e AND NOT x) OR e IS NULL` etc.) are not in v1. Adding them is a candidate v2 enhancement once the v1 false-positive rate is measured.
- **Out of scope:** Float `x * 1` and `x + 0`. These are unsafe under ClickHouse's float coercion (NaN handling differs across analyzer modes; `-0.0` vs `+0.0` formatting can differ in results). The typed-identity table excludes Float types from these identities.
- **Out of scope:** Decimal scale-preserving identities. `x + 0` for `Decimal(P, S)` is *probably* safe in 24.3.1.2672 but the paper flags arithmetic over `Decimal` as a source of false alarms; v1 conservatively excludes Decimal from `x + 0` and `x * 1` and re-evaluates after the regression run.
- **Out of scope (v1):** Wide-column-list SELECTs. The expression-position rewrite mode (R4) rewrites at most one SELECT-list expression per iteration to keep failure attribution clean. Multi-expression rewrite is a v2 enhancement.

## Context & Research

### Relevant Code and Patterns

- `src/sqlancer/clickhouse/oracle/coddtest/ClickHouseCODDTestOracle.java` -- direct architectural precedent for EET. Same `Phi`-style placeholder substitution, same `runComparison` pattern, same `EvalResult` / `renderLiteral` / `cast(..., 'TypeName')` defensive type-preservation machinery, same `IgnoreMeException` escape hatches for unsupported types and NULL-propagation. EET reuses the same scaffolding but with reversed substitution direction (inject vs fold).
- `src/sqlancer/common/oracle/CODDTestBase.java` -- minimal base class holding `state`, `errors`, `auxiliaryQueryString`, `foldedQueryString`, `originalQueryString` for failure logging. EET should mirror this with `EETBase` (or reuse `CODDTestBase` -- decision in Key Technical Decisions).
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java` -- canonical pattern for picking a non-empty table, generating column refs and a base SELECT, registering expression errors. EET's per-mode base query construction borrows directly from here, especially `gen.addColumns(columns)` and `select.setFromClause(table)`.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java:30-66` -- canonical HAVING-position oracle. Demonstrates `select.setFetchColumns(... generateAggregateExpressionWithColumns ...)`, `select.setGroupByClause(...)`, and the `enable_optimize_predicate_expression=0` / `aggregate_functions_null_for_empty=1` SETTINGS suffix needed to dodge ClickHouse#12264. EET's HAVING mode follows this directly and applies the same SETTINGS suffix.
- `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java`:
  - `generateExpressionWithColumns(columns, depth)` -- v1 EET source of the sub-expression `e` for WHERE/HAVING injection. Returns an arbitrary expression that ClickHouse coerces to bool.
  - `generateBooleanExpression()` -- TLPWhere's source; returns a depth-5 random expression. EET uses this for `e` to keep injected predicates rich.
  - `negatePredicate(e)` -- wraps in `NOT(e)`; reused as-is.
  - `isNull(e)` -- wraps in `e IS NULL`; reused as-is. EET's 3VL guard composes these.
- `src/sqlancer/clickhouse/ast/ClickHouseUnaryPostfixOperation.java` -- `IS_NULL` and `IS_NOT_NULL` operators already present; both `negate` semantics handled.
- `src/sqlancer/clickhouse/ast/ClickHouseUnaryPrefixOperation.java` -- `NOT` operator and `MINUS` operator.
- `src/sqlancer/clickhouse/ast/ClickHouseBinaryLogicalOperation.java` -- `AND` / `OR` operators with full 3VL `apply` semantics. EET uses these directly to build the tautology/contradiction chains.
- `src/sqlancer/clickhouse/ast/ClickHouseBinaryFunctionOperation.java` -- existing `intDiv`, `gcd`, `lcm`, `max2`, `min2`, `pow`. None of these is an algebraic identity; the identity table emits raw SQL strings rather than AST nodes to keep the implementation simple. (Decision in Key Technical Decisions.)
- `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` -- enum implementing `OracleFactory<ClickHouseGlobalState>`. Adding an `EET` constant exposes `--oracle EET` to the CLI; no other plumbing.
- `src/sqlancer/clickhouse/ClickHouseErrors.java` -- substring catalog. `getExpectedExpressionErrors()` already covers the bulk of EET's expected churn (type coercion, illegal type, ambiguous identifier from join expansion). v1 may need to add 1-2 specific entries for EET; the catalog is conservative and substring-only.
- `src/sqlancer/clickhouse/ClickHouseToStringVisitor.java` -- the SQL-text emitter EET stringifies its modified `ClickHouseSelect` through. No changes needed; EET produces its own SQL fragments for the placeholder substitution (same approach as CODDTest's `PHI_TOKEN`).
- `test/sqlancer/dbms/TestClickHouse.java` -- T15, T16, T17 taken (SEMR, randomized-session-settings, mutual-exclusion). EET test gets **T18_**.

### Institutional Learnings

- `docs/solutions/` does not exist in this repo. The CODDTest plan's note still stands: seed via `compound-engineering:ce-compound` once the first false-positive catalog gap surfaces from this oracle's regression run.
- The CODDTest oracle's per-row type-stability check (`if (!expectedType.equals(typeText)) { return null; }` at `ClickHouseCODDTestOracle.java:247-253`) is load-bearing institutional knowledge: it prevents false positives where the auxiliary's per-row result type differs from the folded predicate's operand type, which ClickHouse's CASE supertype coercion silently masks. EET's algebraic-identity mode adopts the same defensive `cast(..., 'TypeName')` mechanic for the same reason.
- CODDTest's `MAX_CASE_BRANCHES = 64` (`ClickHouseCODDTestOracle.java:97`) and `MAX_EXPR_DEPTH = 4` constants are tuned to balance generator coverage against query size. EET reuses these conventions for its own `MAX_INJECTED_EXPR_DEPTH = 4`.
- TLPHaving's `enable_optimize_predicate_expression=0, aggregate_functions_null_for_empty=1` SETTINGS suffix (`ClickHouseTLPHavingOracle.java:42, 61`) is required to dodge [ClickHouse#12264](https://github.com/ClickHouse/ClickHouse/issues/12264). EET's HAVING mode must apply the same suffix to both sides of the comparison; not doing so produces false positives indistinguishable from the bug-class we want to find.

### External References

- Skipped intentionally. The user supplied the paper-canonical shapes directly in the feature description (3VL tautology `e OR NOT e OR e IS NULL`, contradiction `e AND NOT e AND e IS NOT NULL`, identity list `x+0`/`x*1`/`concat(x,'')`/`coalesce(x,x)`/`if(true,x,y)=x`). The CODDTest paper (SIGMOD '25, [DOI 10.1145/3709674](https://doi.org/10.1145/3709674)) is the canonical source but is paywalled; the v1 design grounds itself in the user's description plus the in-repo CODDTest precedent rather than re-fetching the paper text. If the v1 regression run produces an unclear false-positive pattern, fetching the paper at that point will be cheaper than now.

## Key Technical Decisions

- **Reuse `CODDTestBase<S>` rather than creating `EETBase<S>` for v1.** Reason: the base class is six fields with no behavior (state, errors, logger, options, con, three query-string fields). EET needs exactly the same machinery for failure logging. Renaming or duplicating the base for a "the name says CODDTest" reason adds friction with no payoff. If a third oracle in this family appears, rename to `ExpressionRewriteOracleBase` then; not now. **Trade-off acknowledged:** new readers may briefly wonder why an EET oracle extends a class named for a different paper section -- this is a naming debt accepted in exchange for not duplicating six fields and a constructor.
- **Inject via SQL placeholder substitution, not via AST node insertion.** Reason: CODDTest does this and the result is significantly simpler. The `PHI_TOKEN` placeholder pattern at `ClickHouseCODDTestOracle.java:96` is reused: build a query template with a single sentinel token, stringify once, then substitute either the tautology-injected fragment or the empty-injected fragment. The cost is a single split-and-replace; the benefit is that EET doesn't need to know how `ClickHouseExpression.AND`-wraps an arbitrary sub-expression at the AST level.
- **3VL tautology is `(e) OR NOT (e) OR (e) IS NULL` with explicit parentheses around every `e`.** Reason: ClickHouse's parser binds `OR` looser than `NOT` and tighter than `AND`, so `e OR NOT e` is `(e) OR (NOT e)` and `pred AND e OR NOT e OR e IS NULL` would parse as `(pred AND e) OR (NOT e) OR (e IS NULL)` -- changing the predicate's semantics entirely. The injected fragment is always emitted as `(((<e>) OR NOT (<e>)) OR (<e>) IS NULL)` to make the tautology binding-tight regardless of where it gets injected.
- **Contradiction is `(e) AND NOT (e) AND (e) IS NOT NULL` with the same parenthesization discipline.** Reason: same.
- **WHERE injection always uses `pred AND (taut)` / `pred AND (contra)`, never bare replacement.** Reason: the paper's bug pattern targets the optimizer's recognition of compound predicates with redundant clauses. A standalone tautology-as-WHERE (`WHERE (e OR NOT e OR e IS NULL)`) is trivially folded by every reasonable optimizer; the interesting bugs are when the tautology hides inside a compound expression. The `pred AND taut` shape mirrors CODDTest's "phi inside a top-level AND with a column-only predicate" mode (`ClickHouseCODDTestOracle.java:336-337`).
- **Expression-position mode uses `x` in both branches (`if(taut, x, x)`), not a typed junk value.** Reason: forcing both branches to be the same expression avoids any type-coercion surface. The optimizer's tautology recognition is still tested -- it must fold `if(true, x, x)` to `x` -- without introducing a confounding type-widening axis. The contradiction form `if(contra, junk, x)` *must* use a junk value in the false branch (otherwise `if(false, x, x)` is trivially `x`); junk is generated by the expression generator constrained to the same type as `x` via the existing typed-leaf machinery. **Type-coercion fallback:** the rewritten expression is wrapped in `cast(..., 'TypeName(x)')` defensively, matching the CODDTest dependent-phi pattern at `ClickHouseCODDTestOracle.java:304-306`.
- **Algebraic identity table is a small static catalog with type-family predicates, not AST-level identity rules.** Reason: ClickHouse type families are an open-ended space (Nullable wrappers, LowCardinality, Decimal precision/scale, FixedString sizes, Array element types, Tuple component types). Maintaining a full AST-level rewrite rule for each identity-applicability case would explode the code. A flat catalog of `(identitySqlTemplate, isSafeForType)` predicates -- five entries in v1 -- captures the entire safe space at <100 LOC. **Pattern parallel:** the catalog mirrors the shape of `ClickHouseSessionSettings.RANDOM_SESSION_SETTINGS` (a flat `List<RandomEntry>` of typed payloads) at `src/sqlancer/clickhouse/ClickHouseSessionSettings.java:38-59`, which the SEMR plan established as the codebase convention.
- **Algebraic identity rewrite operates on a *whole SELECT-list column*, not on a sub-expression buried inside one.** Reason: keeping the rewrite at the column granularity makes `toTypeName(x)` evaluation cheap (one query, one row) and the cast wrapping straightforward. Sub-expression-internal rewriting (`SELECT a + (b + 0) FROM t`) is a v2 enhancement; the bug yield from column-granularity rewriting is already meaningful and the implementation is one-tenth the code.
- **Float types are uniformly excluded from `x + 0` and `x * 1`.** Reason: `NaN + 0 = NaN` (technically safe) but `+0.0 vs -0.0` formatting differs across ClickHouse JDBC driver versions; the canonicalization rule in `ComparatorHelper.canonicalizeResultValue` normalises one direction but not the other in some upstream commits. The risk of false-positive noise outweighs the marginal coverage gain. The typed-identity table's `isSafeForType` predicate returns false for `Float32` and `Float64` for these two identities.
- **Decimal types are conservatively excluded from `x + 0` and `x * 1` in v1.** Reason: intermediate widening rules for `Decimal(P, S)` arithmetic in ClickHouse can change scale; v1 prefers a false negative (skip Decimal) over a false positive (assertion fire from rounding). Re-include Decimal in v2 after the regression run measures the rate.
- **Mode picker is uniform over the four modes per `check()`.** Reason: matches CODDTest's three-mode uniform picker at `ClickHouseCODDTestOracle.java:118-130`. Operators who want to bias toward a specific mode patch the picker; no CLI flag in v1.
- **HAVING-mode SETTINGS suffix.** Reason: the HAVING mode reuses TLPHaving's `aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0` suffix. Without it, ClickHouse#12264 produces false positives indistinguishable from EET findings.
- **Two-direction result comparison, Java-side sort.** Reason: matches CODDTest's `collectRows` at `ClickHouseCODDTestOracle.java:517-538`. Java-side sort avoids relying on an SQL `ORDER BY` (which would itself be subject to the constant-folding pipeline EET is testing).

## Open Questions

### Resolved During Planning

- **v1 mode scope.** All four modes (WHERE, HAVING, if/multiIf/CASE, algebraic identities) ship in v1 per user choice.
- **Algebraic identities in v1.** Yes, as a fourth mode within the same oracle, per user choice.
- **Base class.** Reuse `CODDTestBase<S>` rather than introduce `EETBase<S>` -- naming debt accepted to avoid mechanical duplication.
- **3VL shape.** `(e) OR NOT (e) OR (e) IS NULL` and `(e) AND NOT (e) AND (e) IS NOT NULL` with binding-tight parenthesization.
- **Injection mechanism.** SQL placeholder substitution via a sentinel token, modeled on CODDTest's `PHI_TOKEN`.
- **Expression-position branch parity.** Both `if`/`multiIf`/`CASE` branches use `x` (or typed junk wrapped in `cast(..., 'TypeName(x)')`) to eliminate the type-coercion axis.
- **Float and Decimal exclusion.** Both excluded from `x + 0` and `x * 1` in v1; re-evaluate Decimal in v2 after regression-run measurement.
- **HAVING SETTINGS suffix.** Reused from TLPHaving; mandatory for HAVING mode to avoid ClickHouse#12264 false positives.
- **Mode picker.** Uniform; no CLI override flag in v1.

### Deferred to Implementation

- **Whether to surface mode in the assertion message.** v1 plan says yes (the four modes have meaningfully different bug-class semantics, so attribution matters). The exact format (`EET[mode=WHERE-tautology] mismatch: ...`) is implementation-time wording.
- **Final list of expected-error catalog additions.** The v1 plan budgets 1-2 additions but doesn't pre-commit which. Run the oracle for ~1000 iterations against the CI image; surface the false-positive families that don't already match `getExpectedExpressionErrors()`; add specific multi-word substrings (never bare tokens, per the SEMR plan's institutional learning).
- **Whether `multiIf` should be exercised in the if/multiIf/CASE rewrite mode.** v1 plan says yes (all three forms picked uniformly). If `multiIf` produces parse errors at a high rate, drop it; the bug yield from `if` and `CASE` alone is meaningful.
- **Junk-branch generation for the contradiction form of `if`/`multiIf`/`CASE`.** v1 plan says "generated by the expression generator constrained to the same type as `x`". The exact implementation depends on whether `ClickHouseExpressionGenerator.generateConstant(type)` exposes a type-constrained leaf generator that matches `x`'s type. If not, fall back to a typed NULL: `if(contra, cast(NULL, 'TypeName(x)'), x)`. The latter is uniformly safe across types.
- **Whether to also test `where 1=1 AND ...` and `where 1=0 OR ...` literal-tautology shapes alongside the expression-tautology shapes.** v1 ships expression-tautology only. Literal-tautology is trivial for any optimizer to fold; adding it would inflate the false-positive base rate with little bug-class coverage gain. Defer to v2 if the v1 yield is lower than hoped.
- **Whether `coalesce(x, x)` requires `x` to be Nullable.** The identity is sound for non-Nullable `x` too (just trivially -- `coalesce` short-circuits on the first non-NULL argument) but ClickHouse's analyzer may emit a warning or refuse on some versions. v1 plan applies the identity to both Nullable and non-Nullable `x`; if non-Nullable triggers errors, tighten the `isSafeForType` predicate.
- **Whether to wrap the algebraic-identity-rewritten column in a stable alias.** Without aliasing, the JDBC column name differs between the original (`x`) and rewritten (`cast(plus(x, 0), 'Int32')`) queries; this doesn't affect result equality but may affect how `ComparatorHelper.getResultSetFirstColumnAsString` handles missing-column failures. v1 plan uses a stable `AS check` alias on both sides, mirroring `ClickHouseExpressionGenerator.generateUnoptimizedQueryString` at `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java:492`.

## High-Level Technical Design

> *This illustrates the intended approach and is directional guidance for review, not implementation specification. The implementing agent should treat it as context, not code to reproduce.*

The oracle has a mode-picker over four substitutions; each substitution produces an original query Q and a transformed query T, then compares result sets:

```
                  ┌────────────────────────────────────────────────┐
                  │ ClickHouseEETOracle.check()                    │
                  │   table = pickNonEmpty();                      │
                  │   mode  = uniform(WHERE, HAVING, EXPR, ALG);   │
                  └────────────────────────────────────────────────┘
                                       │
        ┌──────────────┬───────────────┼───────────────┬──────────────┐
        ▼              ▼               ▼               ▼              ▼
   WHERE inject   HAVING inject   if/multiIf/CASE  Algebraic id    (skip)

   pred_Q  =      pred_Q  =        Q: SELECT x       Q: SELECT x
     pred           having           FROM t            FROM t
   pred_T  =      pred_T  =        T: SELECT         T: SELECT
     pred AND       having AND       if(taut,          cast(plus(x,0),
     (taut|         (taut|           x, x)             'Int32')
      contra)        contra)         FROM t            FROM t

   Q: ... WHERE   Q: ... HAVING    expression-       column-level
     pred_Q         pred_Q          position           algebraic id
   T: ... WHERE   T: ... HAVING    rewrite of one    rewrite of one
     pred_T         pred_T          SELECT-list       SELECT-list
                                    column            column
        │              │               │               │
        └──────────────┴───────┬───────┴───────────────┘
                               ▼
                  ┌────────────────────────────────────────────────┐
                  │ runComparison(Q, T, mode):                     │
                  │   originalRows = collectRows(Q)                │
                  │   transformedRows = collectRows(T)             │
                  │   if (mode is contradiction) {                 │
                  │     assert transformedRows.isEmpty()           │
                  │   } else {                                     │
                  │     assert originalRows == transformedRows     │
                  │   }                                            │
                  └────────────────────────────────────────────────┘

Failure attribution: assertion names mode, originalSql, transformedSql, diff.
                    auxiliaryQueryString = the toTypeName-probe used by EXPR/ALG
                    (mirrors CODDTest field naming).
```

Per-mode mechanical sketch (directional guidance, not implementation):

```
WHERE injection:
  e             = gen.generateExpressionWithColumns(cols, MAX_INJECTED_EXPR_DEPTH)
  taut_sql      = "(((" + eSql + ") OR NOT (" + eSql + ")) OR (" + eSql + ") IS NULL)"
  contra_sql    = "(((" + eSql + ") AND NOT (" + eSql + ")) AND (" + eSql + ") IS NOT NULL)"
  predicateQ    = randomColumnPredicate(cols)         // base predicate
  predicateT    = predicateQ + " AND " + (taut_sql | contra_sql)
  Q             = SELECT cols FROM t WHERE predicateQ
  T             = SELECT cols FROM t WHERE predicateT

HAVING injection: same shapes injected into HAVING, with TLPHaving's SETTINGS suffix.

if/multiIf/CASE rewriting:
  x             = pickFetchExpr(cols)
  typeOfX       = toTypeName(x)                       // single-row probe
  taut_sql      = same as above
  rewrite       = "if(" + taut_sql + ", " + xSql + ", " + xSql + ")"
                  | "multiIf(" + taut_sql + ", " + xSql + ", junk, " + xSql + ")"
                  | "CASE WHEN " + taut_sql + " THEN " + xSql + " ELSE " + xSql + " END"
  rewriteSql    = "cast((" + rewrite + "), '" + typeOfX + "') AS check"
  Q             = SELECT (xSql AS check) FROM t
  T             = SELECT rewriteSql FROM t

Algebraic identity:
  x             = pickFetchExpr(cols)
  typeOfX       = toTypeName(x)
  identity      = pickFromTable(typeOfX)              // typed-identity catalog
  rewriteSql    = "cast(" + identity.applyTo(xSql) + ", '" + typeOfX + "') AS check"
  Q             = SELECT (xSql AS check) FROM t
  T             = SELECT rewriteSql FROM t
```

The four modes share one `runComparison` step, one `collectRows` helper, one Java-side sort -- the same row-multiset pipeline CODDTest uses at `ClickHouseCODDTestOracle.java:362-371, 517-538`.

## Implementation Units

- [ ] **Unit 1: EET oracle skeleton + WHERE injection + factory wiring + integration test**

**Goal:** Smallest landable v0: an `EET` oracle constant selectable via `--oracle EET` that exercises only the WHERE-injection mode (tautology and contradiction). HAVING / if-family / algebraic-identity modes return `IgnoreMeException` until Units 2-4 land.

**Requirements:** R1, R2, R6, R7, R8, R9

**Dependencies:** None.

**Files:**
- Create: `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETOracle.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseErrors.java` (additive only, if needed by regression-run findings)
- Modify: `test/sqlancer/dbms/TestClickHouse.java` (add `testClickHouseEET` using prefix `T18_`)

**Approach:**
- New class `ClickHouseEETOracle extends CODDTestBase<ClickHouseGlobalState> implements TestOracle<ClickHouseGlobalState>`. Constructor seeds `ClickHouseErrors.addExpectedExpressionErrors(this.errors)`.
- Define a `Mode` enum with four constants: `WHERE_INJECT`, `HAVING_INJECT`, `EXPR_REWRITE`, `ALGEBRAIC_ID`. In v0 (Unit 1), `check()` picks uniformly; the three non-WHERE modes return `IgnoreMeException()` until their units land. This lets each subsequent unit land independently without re-wiring the mode picker.
- `check()` mechanics for WHERE injection:
  - Pick a random non-empty table from `state.getSchema().getRandomTableNonEmptyTables()`; throw `IgnoreMeException` if none. Mirrors `ClickHouseCODDTestOracle.java:107-115`.
  - Build column-reference list and an expression generator: `gen = new ClickHouseExpressionGenerator(state); gen.addColumns(...)`.
  - Generate `e` via `gen.generateExpressionWithColumns(cols, MAX_INJECTED_EXPR_DEPTH=4)`, stringify via `ClickHouseToStringVisitor.asString(e)`.
  - Build the 3VL tautology fragment as a raw SQL string with the parenthesization discipline above. Same for contradiction.
  - Build a base predicate `predQ` via the same generator (depth 3) so the test exercises folding through compound predicates.
  - Build the original query template `SELECT <cols> FROM <t> WHERE <PHI_TOKEN>` and substitute either `predQ` (original) or `predQ AND <taut_or_contra>` (transformed). Token-substitution discipline mirrors `ClickHouseCODDTestOracle.java:96, 348-355`: assert the token appears exactly once in the template.
  - For tautology: `assert collectRows(Q) == collectRows(T)`. For contradiction: `assert collectRows(T).isEmpty()`.
  - Failure assertion names the mode (`"EET[mode=WHERE-tautology] mismatch"` or `"EET[mode=WHERE-contradiction] non-empty"`), prints `originalQueryString` and `foldedQueryString` (CODDTestBase field names reused), and prints the row diff for tautology mismatches.
- Add `EET` constant to `ClickHouseOracleFactory`. Its `create` method returns `new ClickHouseEETOracle(globalState)`. No other plumbing.
- Add `testClickHouseEET` to `TestClickHouse.java` using prefix `T18_`, mirroring the existing CODDTest / SEMR test shape exactly. `--num-threads 1` to keep failure attribution clean for the first regression run, can be bumped after stability is confirmed.

**Patterns to follow:**
- `src/sqlancer/clickhouse/oracle/coddtest/ClickHouseCODDTestOracle.java` -- end-to-end shape: `Mode` switch, placeholder template, `runComparison`, `collectRows`, `maybeIgnore(SQLException)`, defensive `IgnoreMeException` throws on unsupported types.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPDistinctOracle.java` -- compact oracle reference (`~40 LOC`) for the minimal-shape factory-wired oracle case.
- `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` -- the `CODDTest`/`SEMR` enum entries are the literal pattern for new oracle wiring.

**Test scenarios:**
- *Happy path:* `testClickHouseEET` runs `Main.executeMain(... --oracle EET ...)` for `TestConfig.NUM_QUERIES` queries against the CI image and exits 0.
- *Happy path:* the per-database reproducer log contains the EET-rewritten SQL alongside the original; on failure, the assertion message names the mode (`WHERE-tautology` or `WHERE-contradiction`).
- *Edge case:* when the generated expression `e` is `NULL`-typed (the generator's `generateConstant` path can emit `NULL`), the 3VL guard `e IS NULL` still produces a tautology; the assertion passes.
- *Edge case:* when the base table is empty, `runComparison` returns two empty result sets and the tautology check trivially passes.
- *Edge case:* the placeholder token appears exactly once in the query template; double-substitution is caught by the same `split(...).length != 2` discipline used at `ClickHouseCODDTestOracle.java:350`.
- *Error path:* when the inner expression `e` produces a coercion error at server side (e.g., `Cannot convert string`), `maybeIgnore` reroutes via `IgnoreMeException`; no spurious assertion failure.
- *Error path:* when neither `--oracle EET` is selected nor `EET` appears in the factory list, the run is byte-for-byte unchanged from today.
- *Integration:* running `--oracle EET,TLPWhere` composes via `CompositeTestOracle` without cross-oracle interference; both oracles execute per iteration and both contribute to the assertion budget.

**Verification:**
- `mvn -B verify -DskipTests=true` (checkstyle/spotbugs) passes.
- `testClickHouseEET` runs locally against `docker run --rm -p 8123:8123 clickhouse/clickhouse-server:24.3.1.2672` and exits 0 for `NUM_QUERIES`.
- A run with `--oracle TLPWhere` (no EET selection) produces byte-identical CI logs to the pre-merge baseline.

---

- [ ] **Unit 2: HAVING injection mode**

**Goal:** Implement the `HAVING_INJECT` branch of the mode switch. The oracle generates a `SELECT k, agg(x) FROM t GROUP BY k HAVING h` base query, injects the same 3VL tautology/contradiction into the HAVING clause, applies TLPHaving's SETTINGS suffix to both sides of the comparison, and asserts result equality (or empty for contradiction).

**Requirements:** R1, R3, R6, R7

**Dependencies:** Unit 1 (oracle skeleton + mode switch).

**Files:**
- Modify: `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETOracle.java`

**Approach:**
- Replace the `IgnoreMeException()` placeholder for `HAVING_INJECT` with a real implementation.
- Build a base aggregated SELECT, mirroring `ClickHouseTLPHavingOracle.java:30-44`: `select.setFetchColumns(gen.generateAggregateExpressionWithColumns(cols, 3))`, `select.setSelectType(ALL)`, `select.setGroupByClause(gen.generateExpressionWithColumns(cols, 6))`. Generate a base HAVING predicate as in TLPHaving (random expression over aggregates: `gen.generateExpressionWithExpression(aggregateExprs, 6)`).
- Build the HAVING token-substitution template `... HAVING <PHI_TOKEN>` and substitute `havingQ` (original) or `havingQ AND <taut_or_contra>` (transformed).
- **Mandatory:** apply the SETTINGS suffix `aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0` to *both* `Q` and `T` query strings. This dodges [ClickHouse#12264](https://github.com/ClickHouse/ClickHouse/issues/12264); not applying it produces false positives indistinguishable from EET findings.
- The `e` injected into HAVING comes from the *same* aggregate-expression generator that TLPHaving uses (`gen.generateExpressionWithExpression(aggregateExprs, ...)`), not from the column-level `generateExpressionWithColumns`. Rationale: HAVING-clause expressions in ClickHouse must reference either aggregates or GROUP-BY keys; a bare column reference triggers `is not under aggregate function and not in GROUP BY` (already in `getExpectedExpressionErrors()`).
- Comparison logic is the same as Unit 1's `runComparison`: tautology asserts equality, contradiction asserts empty.

**Patterns to follow:**
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java:30-66` -- the canonical reference for HAVING-position oracle plumbing. Especially the SETTINGS suffix and the aggregate-expression-generator usage.
- Unit 1's `runComparison` and `collectRows` helpers (no duplication).

**Test scenarios:**
- *Happy path:* HAVING-tautology mode preserves the aggregated query's result.
- *Happy path:* HAVING-contradiction mode produces an empty result.
- *Edge case:* generated HAVING expression references zero aggregates (e.g., bare GROUP-BY key). The oracle either rejects this configuration (`IgnoreMeException`) or accepts it -- TLPHaving rejects via `aggregateExprs.isEmpty() -> IgnoreMeException` at line 49, EET should mirror that.
- *Edge case:* the SETTINGS suffix is applied to *both* sides; a test asserts the two query strings end with the same SETTINGS suffix (catches a one-side-only application regression).
- *Error path:* ClickHouse#12264 not triggered (the SETTINGS suffix is load-bearing).
- *Integration:* HAVING mode runs alongside WHERE mode in the same `--oracle EET` run without cross-mode interference; the mode-attribution message correctly names HAVING.

**Verification:**
- `testClickHouseEET` continues to exit 0 with the HAVING mode active.
- A targeted local run with `Randomly` seed forced to HAVING-only (manual code patch during dev) confirms both the tautology and contradiction shapes.

---

- [ ] **Unit 3: Expression-position rewriting (if/multiIf/CASE)**

**Goal:** Implement the `EXPR_REWRITE` branch. The oracle picks a column expression `x` from a single-table SELECT, rewrites it as one of `if(taut, x, x)`, `multiIf(taut, x, junk, x)`, or `CASE WHEN taut THEN x ELSE x END` (uniform pick), wraps the rewrite in `cast(..., 'TypeName(x)')` for type stability, and asserts column-by-column result equality. Contradiction form uses `if(contra, junk, x)` etc., requiring a typed-junk generator.

**Requirements:** R1, R4, R6, R7

**Dependencies:** Unit 1.

**Files:**
- Modify: `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETOracle.java`

**Approach:**
- Replace the `IgnoreMeException()` placeholder for `EXPR_REWRITE` with a real implementation.
- Pick one column from the table's readable columns; bind `x` to its column-reference expression. Stringify via `ClickHouseToStringVisitor.asString(x)`.
- Probe the column's runtime type via a single-row auxiliary query: `SELECT toTypeName(<xSql>) FROM <t> LIMIT 1`. Cache as `typeOfX`. Mirror `ClickHouseCODDTestOracle.evaluateSingleRow` and the `EvalResult` machinery.
- Pick a shape uniformly: `IF`, `MULTI_IF`, or `CASE_WHEN`.
- Build the tautology fragment (same shape as Unit 1) and the rewrite SQL:
  - `IF`: `if((<taut>), <xSql>, <xSql>) AS check`
  - `MULTI_IF`: `multiIf((<taut>), <xSql>, cast(NULL, '<typeOfX>'), <xSql>) AS check`
  - `CASE_WHEN`: `CASE WHEN (<taut>) THEN <xSql> ELSE <xSql> END AS check`
  - Contradiction variants: `IF` becomes `if((<contra>), cast(NULL, '<typeOfX>'), <xSql>) AS check`; analogous for `MULTI_IF` and `CASE_WHEN`. The contradiction form's "junk" is a typed NULL, picked because (a) it's uniformly safe across types, (b) it's the same junk-shape we'd use for the `multiIf` middle branch anyway, (c) it's trivially type-compatible with `x` once cast.
- Wrap the entire rewrite in `cast((<rewrite>), '<typeOfX>')` to neutralize type-coercion drift. Mirror `ClickHouseCODDTestOracle.java:304-306`.
- Original query: `SELECT (<xSql> AS check) FROM <t>`. Transformed: `SELECT <rewriteSql> FROM <t>`.
- Comparison via `collectRows` (Unit 1's helper); assertion message names mode (`EXPR_REWRITE-IF-tautology`, `EXPR_REWRITE-CASE-contradiction`, etc.).
- If `typeOfX` is non-foldable (e.g., `Map`, `Tuple`, `Array(...)` -- the type families CODDTest's `isFoldableColumnTerm` rejects), throw `IgnoreMeException`. Reuse `ClickHouseCODDTestOracle.isFoldableColumnTerm` if visible, or copy the type-family check.

**Patterns to follow:**
- `src/sqlancer/clickhouse/oracle/coddtest/ClickHouseCODDTestOracle.java:175-197` (`buildScalarSubqueryPhi`) -- shape for "evaluate a single auxiliary row to extract a value/type, then build a transformed expression".
- `src/sqlancer/clickhouse/oracle/coddtest/ClickHouseCODDTestOracle.java:304-306` -- the `cast(expr, 'TypeName')` mechanic that prevents type-widening false positives.
- `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java:492` -- the `AS check` alias precedent (used by `generateUnoptimizedQueryString`).

**Test scenarios:**
- *Happy path:* `if`-tautology rewrite preserves every row's `x` value; column comparison passes for all primitive types in the foldable set (`Int*`, `UInt*`, `String`, `Bool`).
- *Happy path:* `multiIf`-tautology and `CASE`-tautology behave identically to `if`-tautology.
- *Happy path:* `if`-contradiction rewrite picks `cast(NULL, '<typeOfX>')` in the false branch; for non-Nullable `x`, the column comparison sees the original `x` values (the contradiction branch is unreached); for Nullable `x`, the column comparison still sees `x` (the false branch is never taken because the condition is false but the truth-path is the second arg of `if(contradiction, junk, x)` -- specifically the `x` slot).
- *Edge case:* `typeOfX` is `LowCardinality(String)`. The cast back works (`cast(..., 'LowCardinality(String)')`); rows match.
- *Edge case:* `typeOfX` is `Nullable(Int32)`. The cast back preserves the wrapper; NULL rows in `x` appear as NULL in both sides.
- *Edge case:* `typeOfX` is a non-foldable type family (`Array(Int32)`, `Tuple(...)`, `Map(...)`). The oracle throws `IgnoreMeException`; the test attempt is skipped.
- *Error path:* a `multiIf` parse error from an old ClickHouse version is absorbed via `getExpectedExpressionErrors()` (`Illegal type` is already in the catalog).
- *Integration:* EXPR_REWRITE mode runs alongside WHERE and HAVING modes; the assertion's mode-attribution disambiguates which mode fired.

**Verification:**
- The `testClickHouseEET` integration test continues to exit 0.
- A regression run for `--oracle EET --num-queries 5000` against the CI image produces no false positives (or, if any, they're documented and either added to `getExpectedExpressionErrors()` or fixed in this PR).

---

- [ ] **Unit 4: Algebraic identity rewriting + typed-identity catalog**

**Goal:** Implement the `ALGEBRAIC_ID` branch. A small typed-identity catalog (`ClickHouseEETIdentities`) lists five identities with safe-type predicates; the oracle picks a SELECT-list column `x`, probes its runtime type, picks a safe identity from the catalog, rewrites the column as `cast(identity.applyTo(x), 'TypeName(x)')`, and asserts column-by-column result equality.

**Requirements:** R1, R5, R6, R7

**Dependencies:** Unit 1.

**Files:**
- Create: `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETIdentities.java`
- Modify: `src/sqlancer/clickhouse/oracle/eet/ClickHouseEETOracle.java`

**Approach:**
- `ClickHouseEETIdentities` is a `public final` static-utility class (`private constructor`), modeled on `src/sqlancer/clickhouse/ClickHouseErrors.java` and `src/sqlancer/clickhouse/ClickHouseSessionSettings.java`. Single public method: `Optional<Identity> pickIdentityForType(Randomly r, String typeName)`.
- Internal data: `List<IdentityEntry>` where `IdentityEntry` is a tiny package-private record `(String sqlTemplate, Predicate<String> isSafeForTypeName)`. `sqlTemplate` is a `%s`-formatted fragment; `applyTo(xSql)` is `String.format(sqlTemplate, xSql)`.
- v1 catalog:
  - `"plus(%s, 0)"` -- safe iff the parsed type is `Int8|Int16|Int32|Int64|Int128|Int256|UInt8|UInt16|UInt32|UInt64|UInt128|UInt256` (not Float, not Decimal in v1).
  - `"multiply(%s, 1)"` -- same safe-type predicate.
  - `"concat(%s, '')"` -- safe iff the parsed type is `String` (not `FixedString` in v1 -- ClickHouse may widen the result type).
  - `"coalesce(%s, %s)"` -- safe for any foldable primitive type (the cast-back wrap neutralizes any narrowing).
  - `"if(true, %s, %s)"` -- safe for any foldable primitive type.
- Type-family parsing reuses `ClickHouseTypeParser.parse(typeName).unwrap()` -- the same machinery CODDTest uses at `ClickHouseCODDTestOracle.java:436, 500-504`. The safe-type predicate inspects the unwrapped `Primitive.kind()`; Nullable and LowCardinality wrappers are transparent (the cast-back wrap preserves them).
- Oracle change: in the `ALGEBRAIC_ID` branch, pick a column `x`, probe `typeOfX`, call `ClickHouseEETIdentities.pickIdentityForType(rng, typeOfX)`. If the optional is empty (no safe identity for this type), throw `IgnoreMeException`. Otherwise: build the rewritten expression `cast((identity.applyTo(xSql)), '<typeOfX>') AS check`. Original and transformed query construction mirror Unit 3.
- For the `coalesce(%s, %s)` and `if(true, %s, %s)` two-slot templates: the formatter passes `xSql` for both slots, which is exactly what the identity requires.

**Patterns to follow:**
- `src/sqlancer/clickhouse/ClickHouseSessionSettings.java:38-73` -- flat-list-of-records catalog shape; record `RandomEntry(String name, List<String> candidateValues)` is the structural analog of `IdentityEntry`.
- `src/sqlancer/clickhouse/oracle/coddtest/ClickHouseCODDTestOracle.java:436-505` -- ClickHouse type parsing and primitive-kind classification.
- `src/sqlancer/clickhouse/ClickHouseErrors.java` -- static-utility class with `private` constructor, `List.of(...)` of typed records, per-entry `//` comments.

**Test scenarios:**
- *Happy path:* `pickIdentityForType(rng, "Int32")` returns one of the integer-safe identities (`plus(...)`, `multiply(...)`, `coalesce(...)`, `if(true, ..., ...)`). Returns `concat(...)` only for `String`.
- *Happy path:* End-to-end -- running EET with the random picker biased toward `ALGEBRAIC_ID` mode (by patching the picker in a local dev run) exits 0 across NUM_QUERIES against the CI image.
- *Edge case:* `pickIdentityForType(rng, "Float32")` does *not* return `plus(...)` or `multiply(...)`; only the type-agnostic identities (`coalesce`, `if(true,...)`).
- *Edge case:* `pickIdentityForType(rng, "Decimal(10,2)")` does *not* return `plus(...)` or `multiply(...)` in v1; only the type-agnostic identities.
- *Edge case:* `pickIdentityForType(rng, "Array(Int32)")` returns `Optional.empty()` because `Array` is not in the foldable-primitive set; the oracle skips the iteration.
- *Edge case:* `pickIdentityForType(rng, "Nullable(Int32)")` -- the wrapper is unwrapped; integer-safe identities apply; the cast-back preserves the Nullable.
- *Edge case:* `pickIdentityForType(rng, "LowCardinality(String)")` -- the wrapper is unwrapped; `concat(...)` applies; the cast-back preserves the LowCardinality.
- *Negative assertion:* the safe-type predicate for `plus(x, 0)` and `multiply(x, 1)` returns false for any `Float*` or `Decimal*` type; this is asserted by a unit test that locks down the v1 scope-boundary exclusion.

**Verification:**
- A new unit test `test/sqlancer/clickhouse/ClickHouseEETIdentitiesTest.java` exercises the picker for at least the type-name strings: `Int32`, `UInt64`, `String`, `Float32`, `Float64`, `Decimal(10,2)`, `Nullable(Int32)`, `LowCardinality(String)`, `Array(Int32)`, `Map(String, Int32)`, `Tuple(Int32, String)`.
- The test runs automatically under the `misc` CI job (workflow filter at `.github/workflows/main.yml:37` already picks up tests under `test/sqlancer/clickhouse/`).
- `testClickHouseEET` continues to exit 0 with all four modes active.

---

## System-Wide Impact

- **Interaction graph:** EET inherits from `CODDTestBase<ClickHouseGlobalState>` and shares the failure-attribution fields (`auxiliaryQueryString`, `originalQueryString`, `foldedQueryString`). No other oracle reads these fields; the addition is non-interfering.
- **Error propagation:** server-side coercion errors propagate as `SQLException` through `maybeIgnore(...)` (modeled on `ClickHouseCODDTestOracle.java:542-549`), which throws `IgnoreMeException` when the error matches the registered catalog. This keeps EET's contribution to the per-database run quota proportional to actual-bug rate, not type-coercion-noise rate.
- **State lifecycle risks:** EET issues SELECT-only queries; no DDL, no INSERT, no DROP. The per-database connection is shared with other oracles when `--oracle EET,X` is used; EET makes no SET-on-connection changes (no analog to SEMR's per-query SETTINGS suffix is needed for v1 EET except for the HAVING-mode TLPHaving suffix, which is bounded to the comparison-pair queries).
- **API surface parity:** `--oracle EET` is the sole new CLI surface. No new CLI flag (no `--eet-modes`, no `--eet-mode-weights`). The factory enum gains one constant; no other public API touched.
- **Integration coverage:** the integration test `testClickHouseEET` runs all four modes randomly. Coverage of each individual mode under the same seed is *not* guaranteed by a single `NUM_QUERIES` run; the v1 plan accepts this trade-off because mode-specific bugs that don't surface in the random budget can be surfaced by a follow-up forced-mode dev run (the picker is a one-line patch).
- **Unchanged invariants:** Existing oracles (TLP*, NoREC, PQS, CERT, CODDTest, SEMR) are unmodified. `--oracle EET` running alongside `--oracle CERT` does not produce false-positive CERT cardinality-monotonicity failures because EET issues no SET statements and applies no session settings -- the HAVING-mode `SETTINGS k=v` suffix is scoped to a single SQL statement, not to the connection.

## Risks & Dependencies

| Risk | Mitigation |
|------|------------|
| **Float NaN / -0.0 / +0.0 false positives** in algebraic-identity mode. | `x + 0` and `x * 1` excluded for Float types in v1. The `isSafeForType` predicate in the identity catalog encodes this. Re-evaluate in v2 with `ComparatorHelper.canonicalizeResultValue` normalization. |
| **Decimal scale-preserving false positives.** | Decimal types excluded from `x + 0` and `x * 1` in v1 (conservative). Re-include in v2 after regression-run measurement. |
| **3VL coercion in `(e) OR NOT (e) OR (e) IS NULL`.** | Strict parenthesization discipline (the planning decision). If a future regression run surfaces a coercion-driven false positive, tighten the inner `e`'s generation depth or constrain its leaf type to Boolean-like primitives. |
| **HAVING mode collides with ClickHouse#12264.** | The TLPHaving `aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0` SETTINGS suffix is applied to *both* the original and transformed query strings. A test asserts the two strings share the same suffix. |
| **Mode-attribution noise in the assertion message.** | The assertion message names the mode (e.g., `EET[mode=HAVING-tautology]`); the reproducer log includes both the original and transformed SQL. Failure triage starts from the mode. |
| **The four modes can mask each other's bugs under random picker.** | Mode-attribution in the assertion message makes the bug-class clear once a failure surfaces. If one mode dominates the assertion stream, the picker can be biased via a one-line dev patch; no CLI flag in v1. |
| **`multiIf` parse errors at high rate.** | If the v1 regression run shows `multiIf` failing more than `if` or `CASE`, drop `multiIf` from the shape picker in Unit 3 (one-line change); deferred to implementation. |
| **CODDTestBase reuse confuses readers.** | A class-level Javadoc note on `ClickHouseEETOracle` explains the reuse-not-inheritance-semantically intent and points to the planning decision. |
| **`typeOfX` probe adds an extra round-trip per `EXPR_REWRITE` and `ALGEBRAIC_ID` iteration.** | Accepted. The probe is a `LIMIT 1` query, dwarfed by the comparison-pair cost. CODDTest already pays this cost in mode 0/1; EET inherits the same overhead in two of its four modes. |
| **Generated `e` references a column whose row contains a value the inner `NOT`/`IS NULL` operators don't accept.** | `getExpectedExpressionErrors()` catalog absorbs the resulting SQLException; the iteration becomes `IgnoreMeException`. |
| **Java-side multiset comparison memory cost for large result sets.** | CODDTest already collects rows on Java side at `ClickHouseCODDTestOracle.java:517-538` with no observed memory pressure; the result sets are bounded by `--num-rows` per table. EET inherits the same bound. |

## Documentation / Operational Notes

- Update `docs/PAPERS.md` with the CODDTest paper's full citation (already present at the top of the file) noting that v1 of this fork implements both CODDTest and EET. **Defer to implementation** -- the entry is a small docs touch but should land with the PR, not now.
- No runbook or monitoring change required; EET is a SQLancer-internal oracle.
- The CI workflow at `.github/workflows/main.yml` already runs `testClickHouseEET` (once added) via the existing `clickhouse` job; no workflow change required.

## Sources & References

- **Origin document:** None. Feature description supplied directly; see Problem Frame.
- Related plans:
  - `docs/plans/2026-05-17-001-feat-clickhouse-semr-oracle-settings-randomization-plan.md` -- SEMR oracle plan; established the catalog-file pattern (`ClickHouseSessionSettings.java`), the substring-only expected-error discipline, and the `T1n_` integration-test convention.
  - `docs/plans/2026-05-16-001-feat-clickhouse-type-system-foundation-plan.md` -- the type-system foundation; relevant for the EET identity table's use of `ClickHouseTypeParser` and the foldable-primitive set.
- Related code:
  - `src/sqlancer/clickhouse/oracle/coddtest/ClickHouseCODDTestOracle.java` -- end-to-end architectural precedent.
  - `src/sqlancer/common/oracle/CODDTestBase.java` -- the reused base class.
  - `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java` -- HAVING-mode reference.
  - `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` -- expression generator entry points used by EET.
  - `src/sqlancer/clickhouse/ClickHouseErrors.java` -- expected-error catalog touched in Unit 1.
  - `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` -- factory enum.
  - `test/sqlancer/dbms/TestClickHouse.java` -- integration test wiring.
- External docs (paywalled, deferred):
  - Zhang & Rigger, "Constant Optimization Driven Database System Testing", SIGMOD '25. [DOI 10.1145/3709674](https://doi.org/10.1145/3709674). Section 4 (or equivalent) describes the EET companion oracle; consult on first unexplained false positive.
