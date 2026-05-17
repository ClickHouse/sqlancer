---
title: ClickHouse Type-System Foundation v1
type: feat
status: completed
date: 2026-05-16
origin: docs/brainstorms/clickhouse-type-system-foundation-requirements.md
---

# ClickHouse Type-System Foundation v1

## Overview

Replace ClickHouse's flat `(ClickHouseDataType, String)` type representation with a recursive `Type` ADT, add a capability layer, and re-route the dispatch sites that currently `AssertionError` on anything outside `{Int32, String}`. Ships as two PRs:

- **v1a** — foundation (ADT, capability layer, reflection parser, cast/coercion extension). Feature flags `enableNullable` / `enableLowCardinality` ship OFF; oracle behaviour is unchanged. Existing schema reflection becomes parameter-aware.
- **v1b** — flag activation + oracle widening. CODDTest/CERT filters dispatch via capabilities; `Nullable(T)` and `LowCardinality(T)` columns enter the schema universe. TLP three-valued-logic plumbing is **explicitly deferred to v1.0.5** (see Key Technical Decisions).

This unblocks v1.1's parameterised scalars (`Decimal`, `FixedString`, `Enum`, `DateTime*`) and v2's composites (`Array`, `Map`, `Tuple`, `Nested`) on a stable ADT contract.

## Problem Frame

`ClickHouseLancerDataType.getRandom()` (`src/sqlancer/clickhouse/ClickHouseSchema.java:44-47`) picks from `{Int32, String}` only. Every oracle (TLP×5, NoREC, PQS, CERT, CODDTest) exercises a 2-type universe. Several call sites hardcode that assumption with `AssertionError` defaults (`ClickHouseSchema.getConstant`, `ClickHouseExpressionGenerator.generateConstant`, `ClickHouseCreateConstant.createIntConstant`, `ClickHouseCast.*`). Two oracles bail out on every non-Int32/String column (`ClickHouseCODDTestOracle.java:175-177,208-210`; `ClickHouseCERTOracle.java:288-298`). Schema reflection silently drops parameters: `ClickHouseDataType.of("Decimal(9,2)")` normalises to `Decimal` (`ClickHouseSchema.java:200-202`).

Three-valued logic is structurally absent because no column can be `Nullable`. TLP partitions operate in a 2-valued universe. (See origin: `docs/brainstorms/clickhouse-type-system-foundation-requirements.md`.)

## Requirements Trace

- **R1.** Type representation lifted from flat enum to recursive ADT (`Primitive`, `Nullable`, `LowCardinality`, `Unknown` in v1)
- **R2.** Capability layer (`isNumeric`, `supportsLiteralEmission`, `hasNullSemantics`, `canWrap`) consumed by v1b oracle rewrites
- **R3.** Reflection parser supports v1-emitted types; unknown strings degrade to `Type.Unknown(raw)`
- **R4.** Two-PR delivery (v1a foundation + v1b activation) per the brainstorm's adopted default
- **R5.** No-crash regression bar: v1a with flags OFF runs ≥100k oracle iterations against fixed-seed pre-change reproducers without unhandled `AssertionError` or off-catalog `SQLException`
- **R6.** Reflection round-trip for every type v1's `getRandom()` emits (against ClickHouse 24.3.1.2672 CI pin)
- **R7.** `ClickHouseErrors` extended with new expected error patterns for v1 types
- **R8.** Bug-yield re-evaluation gate after v1b informs whether v1.1 starts immediately or pauses (±10% threshold vs. pre-v1a baseline)
- **R9.** API stability: v1.1 / v1.2 / v1.3 add constructors without breaking the v1 public API surface
- **R10.** No edits to `sqlancer.common.*` — the refactor stays inside `src/sqlancer/clickhouse/`

## Scope Boundaries

**In scope (v1):** v1a foundation (ADT shape `Primitive`/`Nullable`/`LowCardinality`/`Unknown` only; 4-predicate capability API; hand-written reflection parser; cast/coercion extension over every v1 `Primitive.kind`; `ClickHouseOptions` flag additions). v1b activation (flag defaults flipped to ON; CODDTest filter rewrite at `:175-177,208-210`; CERT `generatorExprFor` capability dispatch; CODDTest legacy `baseTypeName`/`parseType`/`renderLiteral` migration to the new parser; `ClickHouseTableGenerator` PARTITION/SAMPLE/ORDER expression-validation step; `ClickHouseInsertGenerator` + `ClickHouseColumnBuilder` DEFAULT-clause wrapper-aware literal emission; `ClickHouseErrors` additions).

**Out of scope (v1):**

- All parameterised scalars (`FixedString`, `Decimal*`, `Enum*`, `DateTime*`) — v1.1, v1.2, v1.3
- All composites (`Array`, `Map`, `Tuple`, `Nested`) — v2
- Extension-protocol tier (`JSON`, `Variant`, `Dynamic`, `AggregateFunction`, geo) — v3+
- **TLP three-valued logic** — deferred to v1.0.5 because the third partition is composed in `sqlancer.common.oracle.TernaryLogicPartitioningOracleBase` (cross-DBMS) and the brainstorm's R10 forbids common-module edits
- Retiring `ClickHouseDataType` from `ClickHouseConstant.getDataType()` and the AST family — future work; v1 coexists
- Replacing `ClickHouseExpression.TypeAffinity.isNumeric()` — v1's new `Type.isNumeric()` lives alongside; convergence is later cleanup
- Cross-DBMS abstraction — type grammar stays in the ClickHouse module

## Context & Research

### Relevant Code and Patterns

- `src/sqlancer/cockroachdb/CockroachDBSchema.java:36-171` — closest existing pattern for a recursive type model in this repo (single composite class with `dataType + size + elementType` and a constructor enforcing `ARRAY` containment). Mirror its **value-equality** discipline so `AbstractTableColumn`'s `equals`/`hashCode` continue to work.
- `src/sqlancer/postgres/PostgresCompoundDataType.java` — alternative pattern: separate compound wrapper around a flat enum. Closer to the v1 approach where `ClickHouseLancerDataType` stays as the second generic parameter on `AbstractTableColumn<ClickHouseTable, ClickHouseLancerDataType>` and wraps the new `Type`.
- `src/sqlancer/common/gen/TypedExpressionGenerator.java` — `T` is unconstrained; `canGenerateColumnOfType(T)` and `getRandomType()` are the natural capability hooks. ClickHouse currently returns `true` unconditionally (`ClickHouseExpressionGenerator.java:304-306`).
- `src/sqlancer/tidb/TiDBBugs.java` — `public static boolean bugNNNN = true;` flag pattern for known-bug suppression. Useful template if v1.1+ uncovers latent issues; v1 itself does not introduce a `ClickHouseBugs.java`.
- `src/sqlancer/cockroachdb/CockroachDBErrors.java` — multi-category error catalog pattern (`getExpressionErrors`, `getInsertErrors`, `getCommonExpressionErrors`). v1 keeps `ClickHouseErrors` as a single flat list; v1.1+ should split if it grows beyond ~100 patterns.
- `src/sqlancer/clickhouse/ClickHouseOptions.java` — feature-flag home (`testJoins`, `enableAnalyzer` precedent). Flags consumed via `globalState.getDbmsSpecificOptions()`, threaded through `ClickHouseProvider.java:120-121`.
- `src/sqlancer/clickhouse/oracle/coddtest/ClickHouseCODDTestOracle.java:455-479` — legacy `baseTypeName` / `parseType` / `renderLiteral` string parser. v1b migrates this onto the new ADT parser.
- `src/sqlancer/common/oracle/TernaryLogicPartitioningOracleBase.java:34-51` — the TLP third-partition (`isNullPredicate`) lives here, not in any ClickHouse subclass. Drives the v1.0.5 deferral for TVL plumbing.

### Repo conventions (from CONTRIBUTING.md)

- "It would be easier to review multiple smaller PRs than one PR that contains the complete implementation." (`CONTRIBUTING.md:22`) — direct support for v1a/v1b split.
- "Each class specific to a DBMS is prefixed by the DBMS name." Enforced by `src/check_names.py`. Every new file under `src/sqlancer/clickhouse/...` must start with `ClickHouse`.
- "Throw an `IgnoreMeException` to abandon a statement quietly." (`CONTRIBUTING.md:30`) — the established escape hatch for capability mismatches and untyped-coercion paths.
- "Capitalize the subject line"; "Do not end the subject line with a period"; "Use the imperative mood." (`CONTRIBUTING.md:144-146`) — commit-message conventions for v1a and v1b PRs.
- Style gate: `mvn verify -DskipTests=true` must pass without violations — Eclipse formatter + Checkstyle (severity=error) + PMD (failurePriority=2) + SpotBugs (threshold=High).
- Option-name format (`lowercase + hyphen`) is validated by `test/sqlancer/TestParameterFormat.java` via reflection. New flags must conform.
- Unit tests are **not** auto-discovered. CI enumerates them by name in `.github/workflows/main.yml:112`; new test classes must be appended there.

### Institutional Learnings

- No `docs/solutions/` directory exists. The plan is greenfield for capability dispatch, type-grammar design, and feature-flag rollout inside SQLancer.
- `82612b1d` ("Wrap readSchema workaround in bugSchemaReadIncomplete guard") — precedent for guarding reflection against version-dependent server quirks. v1's `Type.Unknown(raw)` fallback follows the same defensive philosophy and should slot **beside** existing guards, not replace them.
- Commits `cbd28478` / `7ba8bba5` / `e44eba43` (Databend Decimal-mismatch suppression) — observed precedent for "type-expansion produces an error-pattern bug family." v1.1's `Decimal(P,S)` should expect the same; the brainstorm's "error catalog explosion" risk is grounded in real history.

### External References

External research was skipped — the codebase already has strong local patterns (CockroachDB recursive composite, Postgres compound wrapper, existing `*Options.java` flag conventions). The work is a refactor inside a well-understood module, not a new framework integration.

## Key Technical Decisions

- **`ClickHouseLancerDataType` is kept as a wrapper around the new `Type`.** Adds a `getTypeTerm(): Type` accessor exposing the ADT; preserves the existing `getType(): ClickHouseDataType` (returning the *root* `ClickHouseDataType` of the underlying `Type` — e.g. `Nullable(Int32).getType()` returns `Int32`). No edits to `AbstractTableColumn` or `TypedExpressionGenerator` generic parameters. (Resolves origin Open Q #1.)

- **`ClickHouseLancerDataType.getRandom()` becomes non-static.** Takes a `ClickHouseGlobalState` (or a small `TypeGenerationContext` wrapping it) to read feature flags from `getDbmsSpecificOptions()`. `ClickHouseColumn.createDummy(String, ClickHouseTable)` is extended to accept the same context. Thread-local rejected (test-isolation hazard).

- **`Type` ADT in v1:** `Primitive(kind)`, `Nullable(inner: Type)`, `LowCardinality(inner: Type)`, `Unknown(raw: String)` — no other constructors. Deferred constructors land in their consuming phase together with emission code, capabilities, parser support, and oracle widenings. (Origin: Alternatives Considered + v1 Scope.)

- **Capability layer in v1:** four predicates only — `isNumeric()`, `supportsLiteralEmission()`, `hasNullSemantics()`, `wrapperRules.canWrap(inner)`. Deferred predicates (`isOrdered`, `hasTotalOrder`, `supportsArithmetic`, `supportsLike`, `supportsRegex`, `supportsAggregate(fn)`, `isInteger`/`isFloat`/`isDecimal`) land with their first consumer. (Origin: Capability layer section.)

- **`hasNullSemantics()` is defined precisely:** returns `true` iff the type term is `Nullable(_)` (or, in v3, a `SpecialType` whose value domain includes NULL). Unwrapped primitives return `false`. Resolves the brainstorm v1's `hasNullSemantics` ambiguity.

- **Cast/coercion skip-signal:** introduce `ClickHouseUnsupportedConstant` as the return value of `ClickHouseCast.castToInt`/`castToReal`/`castToText`/`isTrue`/`convertInternal` when the coercion is not supported. Callers (`ClickHouseConstant.applyEquals`, `ClickHouseBinaryArithmeticOperation.getExpectedValue`, etc.) throw `IgnoreMeException` on this sentinel — the established CONTRIBUTING.md pattern for abandoning a statement quietly. This replaces every `default: throw new AssertionError(...)` fall-through in `ClickHouseCast` without rewriting caller chains.

- **`Unknown(raw)` columns are skipped via `IgnoreMeException`.** Both `ClickHouseExpressionGenerator.generateColumn(type)` and `generateConstant(type)` raise `IgnoreMeException` when handed an `Unknown` type. Oracles already handle this exception path.

- **TLP three-valued logic deferred to v1.0.5.** Third-partition composition lives in `sqlancer.common.oracle.TernaryLogicPartitioningOracleBase` (`:34-51`); editing the common base violates R10. v1.0.5 is a focused follow-up that either (a) extends `TernaryLogicPartitioningOracleBase` with a `supportsTVL()` hook (cross-DBMS coordination) or (b) post-processes the predicate triple in `ClickHouseTLPBase`. Until then, v1b runs with `enableNullable=true` but the TLP partitions remain 2-valued — the activation criterion (R5/R6) is scoped to non-TLP oracles (CODDTest, CERT, PQS, NoREC) for TVL.

- **Feature flags live in `ClickHouseOptions`** as `@Parameter(names="--test-nullable-types", arity=1) public boolean enableNullable = false;` and `@Parameter(names="--test-lowcardinality-types", arity=1) public boolean enableLowCardinality = false;`. Both default `false` in v1a. v1b flips both to `true`. Read via `state.getDbmsSpecificOptions().enableNullable`. Names follow `lowercase + hyphen` (validated by `test/sqlancer/TestParameterFormat.java`).

- **`Nullable(T)` literal emission:** with probability `Randomly.getBooleanWithSmallProbability()` emit `NULL`, else recurse to inner-type constant. Probability is not user-tunable in v1; tunable parameter is a v1.1+ refinement.

- **`Nothing` stays implicit.** `ClickHouseNullConstant.getDataType()` continues to return the existing `ClickHouseDataType.Nothing` for compatibility. The new ADT does not need a `Nothing` constructor — `Nullable(T)` carries nullability at the type level. (Resolves origin Open Q #2.)

- **Reflection parser placement:** new class `ClickHouseTypeParser` in `src/sqlancer/clickhouse/` (top-level, not in `gen/`). `ClickHouseSchema.getColumnType` delegates to it. Composes via small recursive-descent over the type-string grammar (strict for v1-emitted types; everything else → `Unknown(raw)`).

- **Round-trip success criterion equivalence is structural ADT equality.** ClickHouse-version-specific wrapper-order normalisations (e.g. `LowCardinality(Nullable(String))` echo behaviour) are documented as known round-trip exceptions and explicitly excluded from R6 success measurement.

- **v1a "complete" signal for v1b start:** v1a PR merged into `main` + green CI. The 100k-iteration regression run is a **follow-up artifact** filed as a GitHub issue after merge, not a v1a merge blocker. v1b PR may be opened in parallel during v1a review.

- **v1b TableGenerator expression-validation retry policy:** post-generation validation retries up to 5 times; after 5 rejections, the clause is dropped for that table. Bounded retry prevents infinite loops when no valid column exists for the clause.

- **Bug-yield re-evaluation protocol (post-v1b, gates v1.1 start):** baseline = pre-v1a `main` SHA tag (created at v1a PR open); seeds drawn fresh per run; metric = unique-stack-trace count per 1k iterations averaged over 10 independent runs; comparison threshold ±10%; operational artifact = a GitHub issue with the numbers, posted at v1b merge + 2 weeks. If the issue shows within-threshold yield, v1.1 work pauses for re-evaluation discussion.

- **`Type.isNumeric()` vs `TypeAffinity.isNumeric()` divergence policy:** v1 dispatch sites listed in scope (CERT `generatorExprFor`, CODDTest filters, `generateConstant`, `getConstant`) use `Type.isNumeric()` exclusively. Legacy AST sites (`ClickHouseAggregate`, `ClickHouseBinaryArithmeticOperation`, etc.) continue with `TypeAffinity`. No cross-validation in v1; convergence is a later cleanup.

## Open Questions

### Resolved During Planning

- Generic-parameter strategy (wrapper vs. replace): wrapper. See Key Technical Decisions.
- `getRandom()` signature: non-static, takes context. See Key Technical Decisions.
- Cast skip-signal contract: `ClickHouseUnsupportedConstant` + `IgnoreMeException`. See Key Technical Decisions.
- TLP TVL include vs. defer: defer to v1.0.5. See Key Technical Decisions.
- `Unknown` column handling in generators: `IgnoreMeException`. See Key Technical Decisions.
- `Nullable(T)` literal emission semantics: small-probability NULL, else inner-type recursion.
- `Nothing` ADT representation: stays implicit.
- v1a complete signal: merge + green CI.
- Re-evaluation gate protocol: pre-v1a SHA tag baseline, fresh seeds, unique-stack-trace metric, GitHub issue artifact.
- Round-trip equivalence: strict structural ADT equality, version-specific normalisations excluded.
- `isNumeric()` divergence: v1 sites use `Type`; legacy AST sites use `TypeAffinity`.
- Feature-flag option names: `--test-nullable-types`, `--test-lowcardinality-types`.
- v1b TableGenerator validation retry cap: 5.

### Deferred to Implementation

- Exact internal method names inside `ClickHouseTypeParser` — depends on what reads cleanly after the recursive-descent is sketched.
- Whether `ClickHouseUnsupportedConstant.applyEquals` returns `null` (matching `ClickHouseNullConstant`) or throws — decide by running the existing AST tests after the sentinel is wired in.
- Whether the v1b CODDTest filter rewrite needs additional capability predicates beyond `isNumeric` + `supportsLiteralEmission` — surfaced as the CODDTest tests run.
- Exact set of error patterns added to `ClickHouseErrors` for v1 types — discovered by running ≥10k iterations against the CI ClickHouse pin and triaging exceptions.
- Whether `ClickHouseColumnBuilder.createColumn`'s DEFAULT-clause emission needs a separate code path for `Nullable` columns or whether `generateConstant` recursion handles it cleanly — resolved by reading what tests fail.
- Specific tz pool for DateTime types — v1.3 question, non-blocking for v1.

## High-Level Technical Design

> *This illustrates the intended approach and is directional guidance for review, not implementation specification. The implementing agent should treat it as context, not code to reproduce.*

**Type ADT (v1 surface):**

```
sealed interface Type {
    boolean isNumeric();
    boolean supportsLiteralEmission();
    boolean hasNullSemantics();
}

record Primitive(Kind kind)              implements Type { ... }
record Nullable(Type inner)              implements Type {
    static boolean canWrap(Type inner)  // false for Nullable/LowCardinality (cannot self-nest or wrap LC); in v2 false for composites
}
record LowCardinality(Type inner)        implements Type {
    static boolean canWrap(Type inner)  // true for String, Int*, UInt*, Date, Date32, IPv4, IPv6, and Nullable of those; false for Float*, Bool, UUID, IPv4/6, Decimal, composites
}
record Unknown(String raw)               implements Type { /* literal/numeric/null all false */ }
```

`Primitive.Kind` enumerates the v1 primitive set (Int8…Int256, UInt8…UInt256, Float32, Float64, Bool, String, UUID, Date, Date32, IPv4, IPv6).

**Capability dispatch flow (v1b sites):**

```
CERT.generatorExprFor(Type t):
    if (t.isNumeric() && !t.hasNullSemantics()) return "toInt32(number - 25000)"
    if (t.isNumeric() && hasNullSemantics()) return "if(rand()%10==0, NULL, toInt32(number - 25000))"
    if (t == Primitive(String))               return "toString(number)"
    if (t == Primitive(Float32|Float64))      return "toFloat64(number)"
    if (LowCardinality(inner)) recurse on inner with appropriate cast wrap
    if (Nullable(inner)) recurse on inner, wrap with small-probability NULL
    if (Unknown) throw IgnoreMeException
    else throw IgnoreMeException
```

**Cast skip-signal flow:**

```
ClickHouseCast.castToInt(value):
    switch (value.getDataType()):
        case Int8..Int256, UInt8..UInt256: return numeric path
        case Float32, Float64: return numeric-with-truncation path
        case String: existing parse-or-skip path
        case Nothing: return null (current behaviour preserved)
        default: return new ClickHouseUnsupportedConstant()
applyEquals(left, right):
    if (left or right is Unsupported) throw IgnoreMeException
    ... existing dispatch ...
```

**Reflection parser (recursive descent):**

```
parse(s):
    if s starts with "Nullable("    -> Nullable(parse(strip))
    if s starts with "LowCardinality(" -> LowCardinality(parse(strip))
    if s in PRIMITIVE_NAMES         -> Primitive(kind)
    else                            -> Unknown(s)
```

Codec / DEFAULT / ALIAS / MATERIALIZED suffixes are stripped at the DESCRIBE row level (in `getTableColumns`), not by the type parser.

**v1a → v1b boundary contract:**

- v1a: ADT, parser, capability stubs, `ClickHouseUnsupportedConstant`, flag declarations at `false`. Schema reflection now parameter-aware. No oracle behaviour change observable from CI.
- v1b: flips defaults, rewrites two oracle dispatch sites, migrates CODDTest's legacy parser, adds TableGenerator expression-validation. Activates Nullable and LowCardinality in schema generation.

## Implementation Units

### Phase v1a — Foundation (flags OFF)

- [ ] **Unit 1: Introduce `Type` ADT and `Primitive.Kind`**

**Goal:** Add the recursive type term as a new top-level type in the ClickHouse module, with structural value-equality.

**Requirements:** R1, R9

**Dependencies:** None

**Files:**
- Create: `src/sqlancer/clickhouse/ClickHouseType.java` (the sealed `Type` interface + records)
- Create: `test/sqlancer/clickhouse/ClickHouseTypeTest.java`

**Approach:**
- Use a sealed `interface ClickHouseType` (JDK 25 sealed interfaces are available — see `pom.xml` `<release>25</release>`) with four `record` implementations: `Primitive`, `Nullable`, `LowCardinality`, `Unknown`.
- `Primitive.Kind` is a regular enum with the v1 set listed in Key Technical Decisions.
- `Nullable.canWrap` / `LowCardinality.canWrap` declared as `static` predicates on the records, mirroring the pattern in `CockroachDBSchema.java`'s constructor validation but exposed for the generator to consult before constructing.
- `equals`/`hashCode` come from `record` semantics; deliberately keep `toString` matching the ClickHouse type-string spelling so existing visitor-based emission keeps working.

**Patterns to follow:**
- `src/sqlancer/cockroachdb/CockroachDBSchema.java:36-171` — value-equality discipline on a recursive composite.
- `src/sqlancer/clickhouse/ast/constant/ClickHouseInt8Constant.java` (any constant) — module style for small data classes.

**Test scenarios:**
- Happy path — Construct `Primitive(Int32)`; assert `toString()` returns `"Int32"`, `equals` is reflexive, `hashCode` matches a freshly-constructed equal value.
- Happy path — Construct `Nullable(Primitive(Int32))`; assert `toString()` returns `"Nullable(Int32)"` and equality holds against an independently-constructed equal value.
- Happy path — Construct `LowCardinality(Nullable(Primitive(String)))`; assert `toString()` round-trips to `"LowCardinality(Nullable(String))"`.
- Edge case — `Nullable.canWrap(Nullable(Primitive(Int32)))` returns `false`; `Nullable.canWrap(LowCardinality(Primitive(String)))` returns `false`.
- Edge case — `LowCardinality.canWrap(Primitive(Float32))` returns `false`; `LowCardinality.canWrap(Primitive(String))` returns `true`; `LowCardinality.canWrap(Nullable(Primitive(Int32)))` returns `true`.
- Edge case — `Unknown("Decimal(9, 2)")` has `isNumeric()=false`, `supportsLiteralEmission()=false`, `hasNullSemantics()=false`.

**Verification:**
- `mvn verify -DskipTests=true` passes (style/PMD/SpotBugs/Checkstyle).
- New unit test class added to `.github/workflows/main.yml:112` enumeration.
- The class compiles without modifying `sqlancer.common.*`.

- [ ] **Unit 2: Capability layer on `ClickHouseType`**

**Goal:** Implement the 4 v1 capability predicates on every `Type` record.

**Requirements:** R2

**Dependencies:** Unit 1

**Files:**
- Modify: `src/sqlancer/clickhouse/ClickHouseType.java`
- Modify: `test/sqlancer/clickhouse/ClickHouseTypeTest.java`

**Approach:**
- `isNumeric()`: `true` for `Primitive(Int*/UInt*/Float32/Float64)`; recurses through `Nullable`/`LowCardinality`; `false` for `Unknown`, `Primitive(String/Bool/UUID/Date*/IPv*)`.
- `supportsLiteralEmission()`: `true` for every v1 `Primitive` and for `Nullable(T)` / `LowCardinality(T)` where the inner `supportsLiteralEmission()`; `false` for `Unknown`.
- `hasNullSemantics()`: `true` iff outer term is `Nullable(_)`. Not transitive — `LowCardinality(Nullable(String)).hasNullSemantics()` is `false` (the LowCardinality wrapper is the outer term).
- `canWrap` rules are static methods on the wrapper records, not capability-interface methods.

**Patterns to follow:**
- `src/sqlancer/clickhouse/ast/ClickHouseExpression.java:16-27` — existing `TypeAffinity.isNumeric()`. Don't merge with it; mirror the spirit (small enum-shaped predicate set).

**Test scenarios:**
- Happy path — Each capability predicate produces the expected truth value on every v1 `Primitive.Kind` (table-driven test, one assertion per kind).
- Happy path — `Nullable(Primitive(Int32)).isNumeric()` is `true`; `Nullable(Primitive(String)).isNumeric()` is `false`.
- Edge case — `hasNullSemantics()` returns `false` for `LowCardinality(Nullable(String))` (outer term is LowCardinality, not Nullable).
- Edge case — `Unknown("Decimal(9,2)").isNumeric()` returns `false` even though the raw string names a numeric ClickHouse type. The capability layer never inspects raw strings.

**Verification:**
- Predicates exercised by Unit-1 test class extension. No new test class; same file.

- [ ] **Unit 3: Reflection parser (`ClickHouseTypeParser`)**

**Goal:** Hand-written recursive-descent parser converting ClickHouse type strings to `ClickHouseType`. Unknown strings degrade to `Unknown(raw)`.

**Requirements:** R3, R6

**Dependencies:** Unit 1

**Files:**
- Create: `src/sqlancer/clickhouse/ClickHouseTypeParser.java`
- Create: `test/sqlancer/clickhouse/ClickHouseTypeParserTest.java`

**Approach:**
- Single-pass, no external parser library. Recognise the v1 surface: every `Primitive.Kind` name (case-sensitive, matching ClickHouse's spelling), `Nullable(...)`, `LowCardinality(...)`, and `Nullable(LowCardinality(...))` / `LowCardinality(Nullable(...))` combinations.
- Anything outside the recognised set → `Unknown(raw)`. This is the contract — do not throw on unrecognised input.
- Tolerate ambient whitespace between `(` and inner type (ClickHouse's `DESCRIBE` is usually compact but not guaranteed).
- The parser is **type-string-only**. Codec / DEFAULT / ALIAS / MATERIALIZED suffixes belong to row-level processing in `getTableColumns` (Unit 5) and never reach the parser.

**Patterns to follow:**
- Defensive philosophy of commit `82612b1d` — guard, don't crash. Slot beside the existing `bugSchemaReadIncomplete` mechanism, not replacing it.

**Test scenarios:**
- Happy path — `parse("Int32")` returns `Primitive(Int32)`. Repeat for every v1 `Primitive.Kind`.
- Happy path — `parse("Nullable(Int32)")` returns `Nullable(Primitive(Int32))`.
- Happy path — `parse("LowCardinality(String)")` returns `LowCardinality(Primitive(String))`.
- Happy path — `parse("LowCardinality(Nullable(String))")` returns `LowCardinality(Nullable(Primitive(String)))`.
- Edge case — `parse("Decimal(9, 2)")` returns `Unknown("Decimal(9, 2)")` (not `Unknown("Decimal")`; preserve full raw text).
- Edge case — `parse("Array(Int32)")` returns `Unknown("Array(Int32)")` (composites are out-of-scope in v1).
- Edge case — `parse("Nullable(Decimal(9,2))")` returns `Unknown("Nullable(Decimal(9,2))")` (inner type unparseable cascades to whole-string unknown — explicit decision, simplest behaviour for v1).
- Edge case — `parse("UnknownTypeName123")` returns `Unknown("UnknownTypeName123")`.
- Edge case — `parse("")` returns `Unknown("")`.
- Round-trip — For every type the v1 generator emits (test programmatic), parsing its `toString()` produces an `equals` value.

**Verification:**
- All test scenarios pass.
- The parser does not throw on any input — every code path produces a `ClickHouseType`.

- [ ] **Unit 4: `ClickHouseUnsupportedConstant` sentinel and `ClickHouseCast` extension**

**Goal:** Replace every `default: throw new AssertionError(...)` in `ClickHouseCast` with the sentinel return path. Extend numeric/text/boolean coercion to every v1 `Primitive.Kind`.

**Requirements:** R1, R5

**Dependencies:** Unit 1

**Files:**
- Create: `src/sqlancer/clickhouse/ast/constant/ClickHouseUnsupportedConstant.java`
- Modify: `src/sqlancer/clickhouse/ast/ClickHouseCast.java`
- Modify: `src/sqlancer/clickhouse/ast/ClickHouseConstant.java` (only if `applyEquals` needs sentinel handling)
- Create: `test/sqlancer/clickhouse/ast/ClickHouseCastExtensionTest.java`

**Approach:**
- `ClickHouseUnsupportedConstant` extends `ClickHouseConstant`. `getDataType()` returns a sentinel value (proposal: `ClickHouseDataType.Nothing` is taken by NULL — instead reuse `Nothing` but add an `isUnsupported()` boolean on the constant class so callers distinguish. Final shape decided in implementation; the contract is: detectable, propagable, never silently equal to NULL).
- `castToInt(value)`: extend switch to every signed/unsigned integer kind + Float32/Float64 (numeric coercion) + Bool (0/1) + String (existing parse). Unknown / Unsupported → return Unsupported sentinel.
- `castToReal(value)`: extend symmetrically.
- `castToText(value)`: every kind has a `toString` representation; treat Unsupported → Unsupported.
- `isTrue(value)`: numeric ≠ 0 → true; Unsupported → empty Optional (treat as unknown, existing behaviour for Nothing).
- `convertInternal(value, targetType)`: extend with the same logic; Unsupported propagates.
- Callers (`ClickHouseConstant.applyEquals`, `ClickHouseBinaryArithmeticOperation.getExpectedValue`) detect Unsupported and throw `IgnoreMeException`. **Only edit these caller chains if leaving them unchanged produces test failures** — minimise legacy-AST surface area.

**Patterns to follow:**
- `IgnoreMeException` usage in `src/sqlancer/clickhouse/oracle/cert/ClickHouseCERTOracle.java:100` and elsewhere.
- `ClickHouseNullConstant` for the small-data-class style.

**Test scenarios:**
- Happy path — `castToInt(Int8Constant(5))` returns equivalent `Int32Constant(5)` or whatever the existing numeric path returns. One scenario per primitive kind.
- Happy path — `castToReal(Float32Constant(1.5f))` returns matching Float64.
- Edge case — `castToInt(StringConstant("not a number"))` returns the existing parse-failure path (don't regress this).
- Error path — `castToInt(BooleanConstant(true))` returns Int32Constant(1); `false` returns 0.
- Error path — `castToInt(UnsupportedConstant)` returns UnsupportedConstant (idempotence).
- Integration — `applyEquals(Int32Constant(5), UnsupportedConstant)` throws `IgnoreMeException`.
- Integration — `isTrue(UnsupportedConstant)` returns `Optional.empty()` (same as `Nothing`).

**Verification:**
- No `AssertionError` thrown from `ClickHouseCast` over a 10k-iteration synthetic input run against random `ClickHouseConstant` instances.
- Existing tests in `test/sqlancer/clickhouse/ast/ClickHouseBinaryComparisonOperationTest.java` and `ClickHouseOperatorsVisitorTest.java` still pass.

- [ ] **Unit 5: `ClickHouseLancerDataType` becomes a wrapper; `ClickHouseSchema.getColumnType` uses the new parser**

**Goal:** Wire the new ADT into the existing schema representation without changing generic parameters on `AbstractTableColumn` / `TypedExpressionGenerator`. Schema reflection becomes parameter-aware.

**Requirements:** R1, R3, R10

**Dependencies:** Unit 1, Unit 3

**Files:**
- Modify: `src/sqlancer/clickhouse/ClickHouseSchema.java`
- Modify: `test/sqlancer/clickhouse/ClickHouseTypeParserTest.java` (integration extension)

**Approach:**
- Add a private `ClickHouseType typeTerm` field to `ClickHouseLancerDataType`. Existing constructor `ClickHouseLancerDataType(String textRepr)` parses via `ClickHouseTypeParser` to populate it.
- Existing constructor `ClickHouseLancerDataType(ClickHouseDataType type)` constructs `Primitive(Kind.from(type))`; unrecognised kinds become `Unknown(type.name())`.
- Add `getTypeTerm(): ClickHouseType` returning `typeTerm`. Existing `getType(): ClickHouseDataType` returns the *root* `ClickHouseDataType` (unwrap `Nullable`/`LowCardinality`/`Unknown` to inner, then map to flat enum — `Unknown` maps to `Nothing` for legacy callers, with a code comment naming the lossy compatibility).
- `getColumnType(typeString)`: existing one-line delegate updated to construct via the parser path so reflection is parameter-aware. Existing call sites in `fromConnection` and `getTableColumns` strip codec/DEFAULT/ALIAS/MATERIALIZED suffixes (these come from the `default_type` column already) before the type string reaches the parser.

**Patterns to follow:**
- Existing `ClickHouseLancerDataType` constructors — don't change visibility or signatures unnecessarily.

**Test scenarios:**
- Happy path — `new ClickHouseLancerDataType("Int32").getTypeTerm()` equals `Primitive(Int32)`.
- Happy path — `new ClickHouseLancerDataType("Nullable(Int32)").getTypeTerm()` equals `Nullable(Primitive(Int32))`; `.getType()` returns `ClickHouseDataType.Int32` (root unwrap).
- Edge case — `new ClickHouseLancerDataType("Decimal(9,2)").getTypeTerm()` equals `Unknown("Decimal(9,2)")`; `.getType()` returns `ClickHouseDataType.Nothing` (lossy compatibility — documented).
- Integration — Build a table with `Int32` and `Nullable(Int32)` columns via SQL, call `ClickHouseSchema.fromConnection`, verify both columns' `getTypeTerm()` parse correctly.

**Verification:**
- Integration test in `test/sqlancer/dbms/TestClickHouse.java` produces tables with the v1 schema and reflection still works (no `AssertionError`s).
- Existing `TestClickHouse` integration tests still pass under `CLICKHOUSE_AVAILABLE=true`.

- [ ] **Unit 6: `ClickHouseOptions` feature flags + `getRandom()` becomes context-aware**

**Goal:** Add `enableNullable` and `enableLowCardinality` flags (default `false`). Thread them into `ClickHouseLancerDataType.getRandom()` and the surrounding callers.

**Requirements:** R1, R4

**Dependencies:** Unit 5

**Files:**
- Modify: `src/sqlancer/clickhouse/ClickHouseOptions.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseSchema.java`
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseColumnBuilder.java`
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java` (where `createDummy` is called)
- Modify: `test/sqlancer/clickhouse/ClickHouseTypeGenerationTest.java` (new file for the generation surface)
- Create: `test/sqlancer/clickhouse/ClickHouseTypeGenerationTest.java`

**Approach:**
- Add `@Parameter(names = "--test-nullable-types", arity = 1) public boolean enableNullable = false;` and `@Parameter(names = "--test-lowcardinality-types", arity = 1) public boolean enableLowCardinality = false;` to `ClickHouseOptions`.
- Make `ClickHouseLancerDataType.getRandom(ClickHouseGlobalState state)` non-static. The static no-arg form is removed; callers thread the global state in.
- `ClickHouseColumn.createDummy(String name, ClickHouseTable table, ClickHouseGlobalState state)` is extended; updated at every call site (`ClickHouseTableGenerator.java:58` and any test fixture).
- Random selection logic: always pick a `Primitive.Kind` first; then with small probability and `state.getDbmsSpecificOptions().enableNullable`, wrap in `Nullable`; then with small probability and `enableLowCardinality && LowCardinality.canWrap(currentType)`, wrap in `LowCardinality`. Result respects `canWrap`.
- v1a tests this with flags OFF — output is always a `Primitive(Kind)`.

**Patterns to follow:**
- `ClickHouseOptions.testJoins` for `@Parameter` shape.
- `ClickHouseExpressionGenerator.java:279` for flag read pattern (`state.getDbmsSpecificOptions().testJoins`).

**Execution note:** This unit is signature-changing; landing it requires updating every static `getRandom()` call site in the same commit.

**Test scenarios:**
- Happy path — With flags OFF, `getRandom()` over 1000 calls returns only `Primitive` (no `Nullable`, no `LowCardinality`). Distribution covers every `Primitive.Kind`.
- Happy path — With `enableNullable=true`, `enableLowCardinality=false`, over 1000 calls produces some `Nullable(Primitive(_))` but no `LowCardinality`. No `Nullable(Nullable(_))` (canWrap enforced).
- Happy path — With both flags ON, over 1000 calls produces some `LowCardinality(Primitive(String))`, some `Nullable(Primitive(Int32))`, some `LowCardinality(Nullable(Primitive(String)))`. No `LowCardinality(Primitive(Float64))` (canWrap rejects).
- Edge case — `--test-nullable-types` and `--test-lowcardinality-types` parameter names pass `test/sqlancer/TestParameterFormat.java`'s validation.
- Integration — Run a small `Main.executeMain` smoke with `CLICKHOUSE_AVAILABLE=true` and flags OFF; verify schema generation completes (no `AssertionError`).

**Verification:**
- The flag parameter-format test still passes.
- v1a smoke run: 1k iterations with flags OFF and no `AssertionError`, no off-catalog `SQLException`.

- [ ] **Unit 7: `generateConstant` / `getConstant` / `createIntConstant` dispatch on the ADT (v1a — wrapper-aware)**

**Goal:** Re-route the three constant emitters through ADT dispatch. With flags OFF in v1a, only `Primitive` paths fire; the wrapper paths are exercised by Unit-6 tests (flags-ON manual run) but inert in production.

**Requirements:** R1, R5

**Dependencies:** Unit 5, Unit 4

**Files:**
- Modify: `src/sqlancer/clickhouse/ClickHouseSchema.java` (`getConstant`)
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` (`generateConstant`)
- Modify: `src/sqlancer/clickhouse/ast/constant/ClickHouseCreateConstant.java` (`createIntConstant`)
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseInsertGenerator.java` (verify INSERT literal path)
- Modify: `test/sqlancer/clickhouse/ClickHouseTypeGenerationTest.java`

**Approach:**
- `generateConstant(ClickHouseLancerDataType)`:
  - Dispatch on `lancerType.getTypeTerm()`:
    - `Primitive(kind)` — existing per-kind constant emission (extended to cover every v1 `Primitive.Kind`).
    - `Nullable(inner)` — with `Randomly.getBooleanWithSmallProbability()` emit `ClickHouseNullConstant`; else recurse on `inner`.
    - `LowCardinality(inner)` — recurse on `inner` (LC is transparent to literal emission).
    - `Unknown(raw)` — throw `IgnoreMeException`.
- `getConstant(ResultSet, columnIndex, ClickHouseDataType)`: switch extends to every v1 primitive (`Int8` → `getInt(columnIndex)`, etc.). The signature stays unchanged because `ResultSet` returns Java values keyed on the JDBC type; the new `ClickHouseType` is consulted at a higher layer (PQS captures `c.getType()` which already returns `ClickHouseDataType`).
- `createIntConstant(ClickHouseDataType, long)`: the integer/UInt fall-throughs already exist (lines 92-117); the `default: throw new AssertionError(type)` stays as a true error (non-int callers should not reach this method).

**Patterns to follow:**
- Existing dispatch in `ClickHouseExpressionGenerator.generateConstant` (`:309-330`) — extend, don't replace.

**Test scenarios:**
- Happy path — `generateConstant(LancerType("Int8"))` produces a `ClickHouseInt8Constant`. Repeat for every v1 primitive.
- Happy path — `generateConstant(LancerType("Nullable(Int32)"))` produces either `ClickHouseNullConstant` (small prob) or `ClickHouseInt32Constant`. Run 1000 times; assert NULL frequency in [0%, 30%].
- Happy path — `generateConstant(LancerType("LowCardinality(String)"))` produces a `ClickHouseStringConstant` (LC transparent).
- Error path — `generateConstant(LancerType("Decimal(9,2)"))` (parses to `Unknown`) throws `IgnoreMeException`.
- Integration — PQS row capture: insert a row into a table with `Nullable(Int32)` column, read it back via `getConstant`, verify the round-trip produces an equality-matching `ClickHouseConstant`.

**Verification:**
- All existing `AssertionError`s from `ClickHouseSchema.getConstant` and `ClickHouseExpressionGenerator.generateConstant` are unreachable for the v1 type set.

- [ ] **Unit 8: `ClickHouseErrors` v1 additions**

**Goal:** Extend the expected-error catalog with patterns produced by the v1 type set (LowCardinality dispatch, Nullable arithmetic, integer-width coercion).

**Requirements:** R7

**Dependencies:** Unit 7

**Files:**
- Modify: `src/sqlancer/clickhouse/ClickHouseErrors.java`

**Approach:**
- Run a 10k-iteration v1a smoke (flags OFF — primitives only) and a 10k-iteration manual run with flags ON, against ClickHouse 24.3.1.2672 (the CI pin). Triage every `SQLException` not already in `getExpectedExpressionErrors()`. Add patterns for the genuine-not-bug ones; raise the rest as ClickHouse issues (per CONTRIBUTING.md's "real bugs should be reported").
- Keep the single flat list shape for v1; defer the multi-category split (per `CockroachDBErrors`) to v1.1 if the list grows beyond ~100 entries.
- Document each new pattern with a short comment naming the type-family that triggers it.

**Patterns to follow:**
- Existing entries in `ClickHouseErrors.java:12-63` — partial-match substrings, no anchoring.

**Execution note:** Characterization-first — run the iteration before editing the file; do not pre-invent error strings.

**Test scenarios:**
- Test expectation: none — error catalog discipline is verified by the regression run (R5/Unit-11), not by unit tests. Adding a unit test that asserts specific error strings would couple the catalog to the test and rot quickly.

**Verification:**
- v1a 100k-iteration regression run produces no off-catalog `SQLException`.

### Phase v1b — Activation + oracle widening (flags ON)

- [ ] **Unit 9: CODDTest filter + legacy parser migration**

**Goal:** Rewrite the two `aggType != Int32 && aggType != String` filters at `:175-177` and `:208-210` as capability checks. Migrate CODDTest's local `baseTypeName` / `parseType` / `renderLiteral` string parser onto `ClickHouseTypeParser`.

**Requirements:** R2

**Dependencies:** Unit 7 (constant emission), Unit 3 (parser), Unit 6 (flags)

**Files:**
- Modify: `src/sqlancer/clickhouse/oracle/coddtest/ClickHouseCODDTestOracle.java`
- Create: `test/sqlancer/clickhouse/oracle/ClickHouseCODDTestFilterTest.java`

**Approach:**
- Replace `aggType != Int32 && aggType != String` at `:175-177` (and `:208-210` for key types) with:
  ```
  if (!keyType.getTypeTerm().supportsLiteralEmission()) return null;
  if (!keyType.getTypeTerm().isNumeric() && keyType.getTypeTerm() != Primitive(String)) return null;
  ```
  (The exact predicate may need adjustment after the test run — CODDTest's CASE-folding depends on Java-side equality, not just literal emission. If `LowCardinality` columns produce incorrect folding, add a capability predicate in implementation.)
- Migrate `baseTypeName` → `ClickHouseTypeParser.parse(typeText).rootKind()` (a small helper). `parseType` deleted; callers use the new parser. `renderLiteral` keys off `Type` term shape instead of base name strings.

**Patterns to follow:**
- Existing CODDTest structure — preserve the Phi machinery; only the type-eligibility filter and the literal renderer change.

**Execution note:** Test-first — add the `ClickHouseCODDTestFilterTest` cases for both Int32 and `Nullable(Int32)` first, see CODDTest's existing behaviour, then port.

**Test scenarios:**
- Happy path — On a table with a `Nullable(Int32)` column, CODDTest's phi-builder returns a non-null result (the old filter would return `null`).
- Happy path — On a table with a `Float32` column, CODDTest's phi-builder returns a non-null result for `min`/`max` aggregates (existing filter rejected non-Int32/String).
- Edge case — On a table where every column is `Unknown` (parser fallback), CODDTest's phi-builder returns `null` and the oracle invocation is skipped via `IgnoreMeException`.
- Error path — On a table with a `LowCardinality(Float32)` column, behaviour is correct iff `canWrap` was respected during generation; capability predicate must reject if literal folding can't be done.
- Integration — Existing `TestClickHouse.testClickHouseCODDTest*` continues to pass with both flags ON.

**Verification:**
- CODDTest produces non-null phi for at least one Nullable/LowCardinality column type over 1k iterations with flags ON.
- No `AssertionError` from `baseTypeName` / `parseType` removal.

- [ ] **Unit 10: CERT `generatorExprFor` capability dispatch**

**Goal:** Rewrite `generatorExprFor` to dispatch via capabilities, supporting Nullable and LowCardinality inner types.

**Requirements:** R2

**Dependencies:** Unit 7, Unit 6

**Files:**
- Modify: `src/sqlancer/clickhouse/oracle/cert/ClickHouseCERTOracle.java`
- Create: `test/sqlancer/clickhouse/oracle/ClickHouseCERTGeneratorTest.java`

**Approach:**
- Replace the switch at `:288-298` with a capability-driven dispatch:
  - `Primitive(String)` or `LowCardinality(Primitive(String))` → `toString(number)`.
  - `Primitive(Float32|Float64)` or wrappers thereof → `toFloat64(number)`.
  - Numeric primitive → `toInt32(number - 25000)`.
  - `Nullable(inner)` → wrap inner generator with `if(rand() % 10 = 0, NULL, <inner generator>)`.
  - `LowCardinality(inner)` → inner generator unchanged (LC transparent at insertion).
  - `Unknown` → throw `IgnoreMeException`.
- The actual SQL expressions matter; they're SQL strings inserted into `INSERT INTO ... SELECT ... FROM numbers(N)`. Validate by running CERT and catching `SQLException`s.

**Patterns to follow:**
- Existing structure of `generatorExprFor` — extension, not rewrite.

**Test scenarios:**
- Happy path — `generatorExprFor(Primitive(Int8))` returns `"toInt32(number - 25000)"` (cast to Int32 is intentional — ClickHouse coerces; the test asserts the SQL string, not the typed result).
- Happy path — `generatorExprFor(LowCardinality(Primitive(String)))` returns `"toString(number)"`.
- Happy path — `generatorExprFor(Nullable(Primitive(Int32)))` returns a string containing both `NULL` and `toInt32(number - 25000)`.
- Edge case — `generatorExprFor(Unknown("Decimal(9,2)"))` throws `IgnoreMeException`.
- Integration — CERT runs over 1k iterations with flags ON; INSERTs succeed (no off-catalog `SQLException`).

**Verification:**
- CERT generates non-empty data into Nullable and LowCardinality columns.

- [ ] **Unit 11: Flag flip + v1b smoke pass**

**Goal:** Default `enableNullable` and `enableLowCardinality` to `true`. Run a 1k-iteration smoke against ClickHouse 24.3.1.2672 to surface activation-time issues before the larger regression.

**Requirements:** R4, R5

**Dependencies:** Unit 9, Unit 10

**Files:**
- Modify: `src/sqlancer/clickhouse/ClickHouseOptions.java` (flip defaults to `true`)
- Modify: `src/sqlancer/clickhouse/ClickHouseErrors.java` (incremental additions surfaced during smoke)

**Approach:**
- Flip both flag defaults in `ClickHouseOptions`.
- Run a 1k-iteration smoke for each oracle locally with `CLICKHOUSE_AVAILABLE=true`; triage exceptions; either add to `ClickHouseErrors` (genuine catalogue gap) or file as ClickHouse issues (real bug).
- Add `TestClickHouse` test methods exercising flag-ON behaviour, gated on the env var.

**Patterns to follow:**
- Existing `TestClickHouse` test methods.

**Test scenarios:**
- Integration — `TestClickHouse.testClickHouseTLPWhere` with default options passes under `CLICKHOUSE_AVAILABLE=true`.
- Integration — Each of the five oracles (TLP×5, NoREC, PQS, CERT, CODDTest) runs over at least 100 iterations and produces no `AssertionError`.

**Verification:**
- `TestClickHouse` integration tests pass under `CLICKHOUSE_AVAILABLE=true` with v1b defaults.

- [ ] **Unit 12: `TableGenerator` expression-validation step + retry policy**

**Goal:** Add capability-gated post-generation validation for `PARTITION BY` / `SAMPLE BY` / `ORDER BY` clauses. Drop the clause if validation fails 5 times.

**Requirements:** R2, R4

**Dependencies:** Unit 6

**Files:**
- Modify: `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java`
- Create: `test/sqlancer/clickhouse/gen/ClickHouseTableGeneratorTest.java`

**Approach:**
- After generating an expression for one of these clauses, walk the expression tree and check the root type:
  - `PARTITION BY`: reject if root type's capability set includes `isFloat` (existing ClickHouse error `"Floating point partition key is not supported"`).
  - `ORDER BY`: reject if root type is a constant or contains only constants (existing error `"Sorting key cannot contain constants"`).
  - `SAMPLE BY`: reject if root type is not in the primary key (existing error `"Sampling expression must be present in the primary key"`).
- Retry up to 5 times. After 5 failures, omit the clause entirely (the existing error catalog already catches occasional emission of bad clauses; the validation just reduces the noise rate).
- This is **not** a correctness fix — it's a noise reduction that makes v1b's bug-yield measurement cleaner.

**Patterns to follow:**
- Existing PARTITION/SAMPLE/ORDER emission in `ClickHouseTableGenerator.java:80-100`.

**Test scenarios:**
- Happy path — `PARTITION BY` clause generation with a primary-key column eventually emits a valid expression within 5 retries on tables that have a non-Float numeric column.
- Edge case — Table with only Float columns: after 5 retries, `PARTITION BY` is omitted; CREATE TABLE still succeeds.
- Edge case — Single-column table: `ORDER BY` falls back to `tuple()` after retries (existing fallback path preserved).
- Integration — `TestClickHouse` integration tests still pass; rate of `"Floating point partition key"` errors in the error catalog drops measurably (track in PR description).

**Verification:**
- Iteration logs show retry-exhaustion paths exercised.
- Error-catalog noise reduced (qualitatively visible in PR).

### Cross-phase

- [ ] **Unit 13: CI test enumeration + workflow update**

**Goal:** Register the new unit-test classes in CI so they're actually run.

**Requirements:** R5 (regression depends on tests running)

**Dependencies:** Units 1-12

**Files:**
- Modify: `.github/workflows/main.yml:112`

**Approach:**
- Append new test classes to the `-Dtest=...` enumeration: `ClickHouseTypeTest`, `ClickHouseTypeParserTest`, `ClickHouseCastExtensionTest`, `ClickHouseTypeGenerationTest`, `ClickHouseCODDTestFilterTest`, `ClickHouseCERTGeneratorTest`, `ClickHouseTableGeneratorTest`.
- Verify locally that `mvn -Dtest=...,ClickHouseTypeTest test` runs the new class.

**Patterns to follow:**
- Existing enumerated list at `.github/workflows/main.yml:112`.

**Test scenarios:**
- Test expectation: none — CI configuration; functional coverage is in the underlying test classes.

**Verification:**
- GitHub Actions run on the v1a PR exercises every new test class (visible in CI logs).

## System-Wide Impact

- **Interaction graph:** `ClickHouseLancerDataType.getRandom()` signature change ripples into `ClickHouseColumn.createDummy`, `ClickHouseTableGenerator.start`, and any test fixture constructing columns directly. The wrapper-on-existing-type design keeps `AbstractTableColumn`'s generic parameter unchanged.
- **Error propagation:** `IgnoreMeException` continues to be the abandon-statement escape hatch. `ClickHouseUnsupportedConstant` is a new in-band sentinel for cast paths; callers either throw `IgnoreMeException` (oracles) or propagate (intermediate AST nodes).
- **State lifecycle risks:** `--reuse-tables` runs (if used) may encounter tables produced by a previous code version whose type strings don't parse. The `Unknown(raw)` fallback handles this; oracles will skip those columns. No data corruption risk.
- **API surface parity:** None — ADT and capabilities are confined to `src/sqlancer/clickhouse/`. No edits to `sqlancer.common.*`.
- **Integration coverage:** `TestClickHouse` (the integration suite) is the highest-signal test; it exercises every oracle against a live ClickHouse 24.3.1.2672 container in CI. Per-oracle changes in Units 9-12 are validated end-to-end there, not just by unit tests.
- **Unchanged invariants:** `ClickHouseConstant.getDataType()` continues to return `com.clickhouse.data.ClickHouseDataType` for all 17 existing constant subclasses + the new `ClickHouseUnsupportedConstant`. `ClickHouseExpression.TypeAffinity` is not modified. `ClickHouseProvider`, `ClickHouseOracleFactory`, `ClickHouseToStringVisitor`, `ClickHouseVisitor` are not touched in v1.

## Risks & Dependencies

| Risk | Mitigation |
|------|------------|
| `ClickHouseUnsupportedConstant` propagates to a non-oracle caller that doesn't expect it and crashes | Search all callers of `ClickHouseCast.*` and `ClickHouseConstant.applyEquals` during Unit 4; either guard or convert callers to throw `IgnoreMeException` |
| v1a's static→non-static `getRandom()` signature change touches every test fixture | Grep for `ClickHouseLancerDataType.getRandom(` and `ClickHouseColumn.createDummy(` before Unit 6; update all call sites in the same commit |
| Reflection parser silently accepts a malformed `LowCardinality(Nullable(Float32))` and `canWrap` rule fires too late | Parser is permissive by design; the *generator* respects `canWrap`. The parser produces a `LowCardinality(Nullable(Float32))` Type term that `Nullable.canWrap`'s rule would have rejected at generation time. Add a v1.0-known-exception note: ClickHouse-emitted type strings that violate canWrap are accepted on reflection (treat as foreign data) but never generated |
| Error-catalog drift: a real ClickHouse bug gets suppressed by a too-broad catalog entry added in Unit 8 | Per CONTRIBUTING.md: "syntax errors should not be ignored." Each Unit 8 addition must include a comment naming the trigger family. Reviewer reads each new pattern and asks "is this hiding a real bug?" |
| v1b activates flags but TLP TVL stays 2-valued; oracles report false-positive disagreements when a Nullable column produces NULL on one TLP partition but not another | The activation criterion (R5/R6) explicitly excludes TLP from TVL coverage. v1b's smoke pass (Unit 11) will show TLP false-positive rate; if rate exceeds historical baseline, v1.0.5 priority is raised |
| Bug-yield re-evaluation gate measurement is noisier than ±10% | The gate is a discussion trigger, not a hard pass/fail. The artifact (GitHub issue) starts a conversation; if noise is too high, refine the metric (e.g. switch from stack-trace count to oracle-disagreement count) before drawing conclusions |
| `.github/workflows/main.yml` test enumeration is forgotten | Unit 13 explicitly addresses this; PR template should remind reviewers to verify CI runs new tests |

## Documentation / Operational Notes

- **v1a PR description** should include: the wrapper-strategy decision, the static→non-static signature change, the `ClickHouseUnsupportedConstant` sentinel, the explicit "flags OFF, no oracle behaviour change" claim, and a link to the regression artifact (created as a follow-up issue).
- **v1b PR description** should include: which oracles were widened, the CODDTest legacy-parser migration, the TableGenerator validation retry policy, and the explicit TLP TVL deferral note pointing to a tracking issue for v1.0.5.
- **v1.0.5 tracking issue** (filed at v1b PR open): "ClickHouse TLP three-valued logic plumbing — choose between extending `sqlancer.common.oracle.TernaryLogicPartitioningOracleBase` with a per-oracle TVL hook OR post-processing the predicate triple in `ClickHouseTLPBase`. Decision criteria + benchmark needed."
- **Bug-yield re-evaluation issue** (filed 2 weeks after v1b merge): see protocol in Key Technical Decisions.
- **Pre-v1a `main` SHA tag**: created at v1a PR open, named `pre-clickhouse-type-foundation-baseline`. Used as the regression baseline reference.
- No external user docs change — SQLancer is an internal-use tool.

## Alternative Approaches Considered

Carried forward from the requirements document; not relitigated here.

- **Capability shim on the existing flat enum** (rejected): can't encode parameters that v1.1's Decimal will require.
- **Minimal pass — widen `getRandom()` only** (rejected): doesn't fix lossy reflection; doesn't enable Nullable.
- **All-constructors-stubbed ADT** (rejected during brainstorm refinement): speculative complexity; `Unknown(raw)` graceful fallback eliminates the need.
- **Edit `sqlancer.common.oracle.TernaryLogicPartitioningOracleBase` for TVL in v1b** (rejected here): violates R10 (no common-module edits). Deferred to v1.0.5 with a cross-DBMS decision.

## Success Metrics

- **v1a:** zero unhandled `AssertionError` over 100k oracle iterations with flags OFF, against fixed-seed reproducers from the pre-v1a `main` tag. Zero off-catalog `SQLException`.
- **v1b:** at least one Nullable and one LowCardinality column per N tables (N = small constant, verified by probe test). CODDTest does not return `null` for Nullable columns of supported inner types over 1k iterations.
- **Post-v1b (gates v1.1):** unique-stack-trace count over 10 × 1k-iteration runs falls within ±10% of pre-v1a baseline. Outside that band, pause and re-evaluate.

## Phased Delivery

### Phase v1a — Foundation
- Units 1-8 land in a single PR (or, if size pressure, Units 1-5 + 6-8 as two sequential PRs against `nik/clickhouse-add-pqs-cert-coddtest`).
- Flags default OFF. No oracle behaviour change observable in CI.
- Merge criterion: green CI + reviewer approval.
- Follow-up artifact (post-merge): 100k-iteration regression issue.

### Phase v1b — Activation
- Units 9-12 land in a single PR. Unit 13 lands with whichever PR adds new test classes (likely both v1a and v1b).
- Flags default ON. CODDTest and CERT oracle filters dispatch via capabilities. CODDTest's local string parser migrated. TableGenerator clause validation added.
- TLP TVL deferred to v1.0.5 with tracking issue.
- Merge criterion: green CI + reviewer approval + the v1b smoke (Unit 11) completed locally with `CLICKHOUSE_AVAILABLE=true`.

### Phase v1.0.5 — TLP TVL plumbing
- Single focused PR. Either common-base extension (cross-DBMS coordination required) or ClickHouse-specific predicate post-processing.

### Phase v1.1, v1.2, v1.3, v2, v3
- Per the brainstorm phasing table. Each is a separate planning effort.

## Operational / Rollout Notes

- No production deployment — SQLancer runs as a developer-invoked CLI / CI step. "Rollout" = merge to `nik/clickhouse-add-pqs-cert-coddtest`, then CI exercises the new code automatically.
- Feature flags ship as `@Parameter` options; users can override at the command line for either v1a (flags ON for advance testing) or v1b (flags OFF if a regression is found post-flip).
- The `pre-clickhouse-type-foundation-baseline` git tag is the rollback reference. Reverting v1b requires only flipping the flag defaults back to `false`; reverting v1a requires reverting the merge commit.

## Sources & References

- **Origin document:** [docs/brainstorms/clickhouse-type-system-foundation-requirements.md](../brainstorms/clickhouse-type-system-foundation-requirements.md)
- Related code:
  - `src/sqlancer/clickhouse/ClickHouseSchema.java` — current type representation, reflection seam
  - `src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java` — current `generateConstant` dispatch
  - `src/sqlancer/clickhouse/ast/ClickHouseCast.java` — current `AssertionError` defaults
  - `src/sqlancer/clickhouse/oracle/coddtest/ClickHouseCODDTestOracle.java:175-177,208-210,455-479` — current type filters and legacy parser
  - `src/sqlancer/clickhouse/oracle/cert/ClickHouseCERTOracle.java:288-298` — current `generatorExprFor`
  - `src/sqlancer/cockroachdb/CockroachDBSchema.java:36-171` — pattern template
  - `src/sqlancer/common/oracle/TernaryLogicPartitioningOracleBase.java:34-51` — TLP third-partition home (driver of v1.0.5 deferral)
- Related branches: `nik/clickhouse-add-pqs-cert-coddtest` (target)
- Repo conventions: `CONTRIBUTING.md` (commit style, naming, IgnoreMeException, PR cadence)
