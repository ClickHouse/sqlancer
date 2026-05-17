# ClickHouse Type-System Foundation Expansion — Requirements

**Date:** 2026-05-16
**Author:** brainstorm with Nikita Fomichev
**Status:** Draft requirements; v2 after document review
**Scope:** `src/sqlancer/clickhouse/` only

---

## Problem

`ClickHouseLancerDataType.getRandom()` in `src/sqlancer/clickhouse/ClickHouseSchema.java:44-47` picks from `{Int32, String}` only. Every ClickHouse oracle (TLP×5, NoREC, PQS, CERT, CODDTest) therefore exercises a 2-type universe, and several places hardcode that assumption:

- `ClickHouseSchema.getConstant` (`ClickHouseSchema.java:98-168`) — `AssertionError` for ~25 non-Int32/String types
- `ClickHouseExpressionGenerator.generateConstant` (`src/sqlancer/clickhouse/gen/ClickHouseExpressionGenerator.java:309-330`) — `AssertionError` default
- `ClickHouseCreateConstant.createIntConstant` — `AssertionError` default for non-integers
- `ClickHouseCast.castToInt`, `castToReal`, `castToText`, `isTrue`, `convertInternal` — `AssertionError` default for anything outside `{Int32, Float64, String, Nothing}`
- `ClickHouseCODDTestOracle.java:175-177` and `:208-210` — explicit `aggType != Int32 && aggType != String → return null`
- `ClickHouseCERTOracle.java:288-298` (`generatorExprFor`) — falls through to `toInt32(number - 25000)` for any non-float/string type, silently miscasting
- `ClickHouseSchema.getColumnType` (`:200-202`) — stores verbatim type string but `ClickHouseDataType.of(textRepr)` normalises away parameters (`Decimal(9, 2)` → `Decimal`), so reflection is lossy

Three-valued logic is also structurally absent today because no column can be `Nullable`. TLP partitions operate on a 2-valued universe.

## Goal

Build a **durable type-grammar foundation** for ClickHouse in SQLancer that:

1. Replaces the flat `(ClickHouseDataType, String)` representation with a recursive ADT capable of encoding every ClickHouse type, including parameters and wrappers.
2. Ships with a **capability layer** so the rewrite call sites enumerated in v1 Scope dispatch on type properties (e.g. `isNumeric()`, `supportsLiteralEmission()`, `hasNullSemantics()`) rather than enumerating type identities. Auto-widening is scoped: it covers the rewritten sites; remaining type-touching code in TLP/PQS/NoREC and the AST family is migrated incrementally in later phases.
3. Separates **core ADT** types (primitives, parameterised scalars, composable wrappers) from a smaller **extension protocol** that hosts the structurally different families (open-schema, aggregate-state, geo).
4. Phases the rollout so each PR exercises one new class of complexity at a time.

Non-goal: a single PR that lands every ClickHouse type. Non-goal: changing oracle semantics. Non-goal: cross-DBMS abstraction (this lives in the ClickHouse module).

## Alternatives Considered

Before choosing the recursive-ADT approach, two structurally different paths were considered:

**Alternative A: Capability shim on the existing flat enum.** Keep `ClickHouseDataType` as the storage type. Add capability predicates as a sidecar `EnumMap<ClickHouseDataType, Capabilities>` or static helpers. Replace each `AssertionError` fall-through with a capability gate. Adding a new type means appending an enum value plus its capability row. **Why rejected:** the flat enum cannot encode parameters (`Decimal(P,S)`, `FixedString(N)`, `Enum8` members) or wrapper combinations (`Nullable(LowCardinality(String))`). v1 can survive without parameters, but v1.1's Decimal requires P/S — at which point the shim either grows a parallel parameter-storage system or pivots to an ADT under cost. Better to pay the ADT cost in v1 when there are 3 constructors than in v1.1 when there are 9.

**Alternative B: Minimal pass — widen `getRandom()` to all primitives, leave everything else alone.** Add Int8/16/64, UInt*, Float32, Bool, Date, etc. to the random pool. Patch the AssertionError fall-throughs case-by-case. **Why rejected:** doesn't address the lossy reflection (`ClickHouseDataType.of(textRepr)` normalises `Decimal(9,2)` → `Decimal`), so reading existing tables stays broken. Doesn't enable Nullable (no wrapper representation), so TVL stays absent. Patches the symptoms without addressing the structural cause; each oracle then accumulates its own ad-hoc dispatch logic, the opposite of a foundation.

The recursive-ADT path costs more upfront but is the only path where Decimal/Enum parameters, Nullable wrappers, and reflection round-trip share one design rather than three.

## Design — Tiered Type System

### Core ADT (recursive)

The ADT shape declared in v1:

```
Type ::=
  | Primitive(kind)            -- no parameters
  | Nullable(inner: Type)
  | LowCardinality(inner: Type)
  | Unknown(raw: String)       -- reflection fallback for unrecognised type strings
```

`Primitive.kind` in v1 covers: `Int8, Int16, Int32, Int64, Int128, Int256, UInt8, UInt16, UInt32, UInt64, UInt128, UInt256, Float32, Float64, Bool, String, UUID, Date, Date32, IPv4, IPv6`.

**Constructors added in later phases:**

```
-- v1.1
| FixedString(n: int)                              -- n ≥ 1
| Decimal(precision: int, scale: int)              -- 1..76, 0..precision; bit-class bounds at 9/18/38/76
-- v1.2
| Enum8(members: Map<String, Int8>)
| Enum16(members: Map<String, Int16>)
-- v1.3
| DateTimeTz(tz: Option<String>)                   -- DateTime + optional tz; subsumes DateTime32 with tz='UTC' or appropriate normalisation
| DateTime64(precision: int, tz: Option<String>)   -- 0..9
-- v2
| Array(inner: Type)
| Map(key: Type, value: Type)
| Tuple(fields: List<NamedOrPositional<Type>>)
| Nested(fields: List<Named<Type>>)
```

Each later-phase constructor lands together with its emission code, capability declarations, reflection-parser support, and oracle widenings. They are NOT declared as stubs in v1.

### Extension protocol (smaller `SpecialType` interface)

Three families with structurally different semantics live behind their own dispatch in v3:

- **Open-schema:** `JSON`, `Variant(T1,...,Tn)`, `Dynamic`, deprecated `Object('json')`. Per-row typing — no single column-level literal grammar.
- **Aggregate-state:** `AggregateFunction(name, args...)`, `SimpleAggregateFunction(name, T)`. Values are intermediate states, not directly constructible as literals.
- **Geo:** `Point, Ring, LineString, Polygon, MultiPolygon, MultiLineString`. Could be modelled as constrained composites later, but tracked as a deferred family.

The `SpecialType` interface is **not declared in v1.** It is introduced in v3 when its first member lands. Until then, the reflection parser maps any type string matching an extension family to `Type.Unknown(raw)` and oracles skip those columns. This preserves the v1 surface and avoids committing to an interface shape before any consumer exists.

### Capability layer

v1 ships only the capability predicates consumed by v1 rewrite call sites:

- `isNumeric()` — consumed by CERT `generatorExprFor` and CODDTest filters
- `supportsLiteralEmission()` — consumed by `generateConstant` and `getConstant`
- `hasNullSemantics()` — true iff the type is `Nullable(_)`; consumed by CODDTest and (if `enableNullable=true`) TLP TVL partitioning
- `wrapperRules.canWrap(inner: Type): boolean` — declared on `Nullable` and `LowCardinality` constructors

**Capabilities deferred to the phase that consumes them:**

- `isInteger()`, `isFloat()`, `isDecimal()` — v1.1 with `Decimal`
- `isOrdered()`, `hasTotalOrder()` — v2 with composites (current oracles assume total order trivially)
- `supportsArithmetic()`, `supportsLike()`, `supportsRegex()` — when an oracle gates on them
- `supportsAggregate(fn)` — v3 with `AggregateFunction`. Open question on per-function vs grouped granularity is **deferred to v3**, not v1

Wrapper-validity rules in v1's `canWrap`:

- `Nullable.canWrap(inner)`: false for `Nullable(_)`, `LowCardinality(_)`. In v2 this extends to false for `Array`/`Map`/`Tuple`/`Nested`.
- `LowCardinality.canWrap(inner)`: true for `Primitive(String|Int*|UInt*|Date|Date32|IPv4|IPv6)` and `Nullable` of those. False for `Primitive(Float32|Float64|Bool|UUID)`. In v1.1 extends to `FixedString`. Float and Decimal remain false.
- Composite key restrictions (`Map(K,V)` hashable-K, etc.) land in v2 with the composite constructors. v1 does not encode rules for types that don't exist in v1.

`hasNullSemantics()` is defined precisely: returns `true` iff the type term is `Nullable(_)` or (in v3) a `SpecialType` whose value domain includes a NULL representation. For unwrapped primitives it returns `false` — column values cannot be NULL unless the column type is `Nullable`. This resolves the earlier ambiguity around "types that propagate NULL implicitly."

### Reflection parser

`getColumnType(typeString)` currently delegates to `ClickHouseDataType.of(textRepr)`, which is lossy. The new reflection path is a hand-written parser whose v1 scope recognises:

- All v1 `Primitive.kind` names
- `Nullable(...)`, `LowCardinality(...)`, including `LowCardinality(Nullable(<primitive>))`
- Codec / DEFAULT / ALIAS / MATERIALIZED suffixes stripped before type-string parsing (consumed at the DESCRIBE row level, not embedded in the type term)

Anything outside this set parses to `Type.Unknown(raw)`. Oracles skip columns of `Unknown` type. This makes reflection robust against (a) parameterised scalars in user tables before v1.1 lands, (b) version-dependent type aliases (e.g. `Bool` ↔ `UInt8` on older ClickHouse builds, normalised by the server when reading back), (c) types introduced in future ClickHouse versions that v1 has never heard of.

Later phases extend the parser's recognised set without changing its public API: each new constructor adds a parse case; unknown remains the fallback.

### Legacy AST coexistence boundary

`com.clickhouse.data.ClickHouseDataType` is referenced from ~28 files in the module: every `ClickHouseConstant` subclass in `ast/constant/`, `ClickHouseCast`, `ClickHouseAggregate`, comparison/arithmetic operations, and the JDBC reflection path. A full sweep replacing it with the new `Type` ADT is out of scope for v1 — it would balloon the PR beyond reviewability.

The v1 coexistence strategy:

- **`ClickHouseLancerDataType` is kept as a thin wrapper** around the new `Type` value. Its existing accessor `getType(): ClickHouseDataType` is preserved (returning the *root* `ClickHouseDataType` of the underlying `Type` — e.g. `Nullable(Int32).getType()` returns `Int32`). A new accessor `getTypeTerm(): Type` exposes the full ADT for v1's new dispatch sites. This preserves the generic parameter on `AbstractTableColumn<ClickHouseTable, ClickHouseLancerDataType>` and `TypedExpressionGenerator<…, ClickHouseLancerDataType>` — no edits to common base classes.
- **`ClickHouseConstant.getDataType()` continues to return `ClickHouseDataType`** (the flat enum) for compatibility with `ClickHouseConstant.applyEquals` and `ClickHouseCast.*`. For `Nullable(T)` constants the returned enum is the *inner* type, with NULL constants continuing to return `Nothing` as today.
- **`ClickHouseCast`'s `AssertionError` defaults must be removed in v1** because `getRandom()` now emits primitives outside `{Int32, Float64, String}` that flow into `negatePredicate` and boolean coercion at runtime. The replacement: extend each switch to handle every v1 `Primitive.kind`, with a graceful "unsupported coercion" path (returns an explicit error constant that oracles treat as a skip signal) rather than crashing the run.

This is the load-bearing scope boundary: anything *outside* it (broader replacement of `getDataType()` consumers, eventual retirement of the flat enum) is acknowledged as future work and not committed to v1.

## v1 Scope (first landing)

**Default plan: v1 ships as two PRs (formerly the v1a/v1b "fallback").** v1a lands the foundation with flags OFF; v1b enables the flags and migrates the oracle filters. This makes v1a reviewable on its own merits (no oracle behaviour changes) and v1b a small targeted activation. The single-PR alternative remains acceptable if size stays manageable.

### v1a: foundation, flags OFF

- `Type` ADT with `Primitive`, `Nullable`, `LowCardinality`, `Unknown` constructors only.
- Capability layer with the 4 predicates listed above (`isNumeric`, `supportsLiteralEmission`, `hasNullSemantics`, `canWrap`).
- Reflection parser supporting the v1 type set; everything else parses to `Unknown`.
- `ClickHouseLancerDataType.getRandom()` extended to emit all v1 `Primitive.kind` values. Wrappers gated by feature flags (both OFF by default in v1a).
- `ClickHouseSchema.getConstant`, `ClickHouseExpressionGenerator.generateConstant`, `ClickHouseCreateConstant.createIntConstant` rewritten to dispatch on the ADT for the v1 type set.
- `ClickHouseCast.castToInt`/`castToReal`/`castToText`/`isTrue`/`convertInternal` extended for every v1 `Primitive.kind`. `AssertionError` defaults are replaced with an explicit skip-signal value.
- `ClickHouseInsertGenerator` literal emission extended for the v1 type set.
- `ClickHouseColumnBuilder` DEFAULT-clause literal emission extended for the v1 type set.
- `ClickHouseErrors` expanded with the new error patterns expected for v1 types.
- Feature flags live on `ClickHouseOptions` (consumed by `ClickHouseProvider` / `ClickHouseGlobalState`), not on `ClickHouseExpressionGenerator`. Threaded into `ClickHouseSchema.ClickHouseLancerDataType.getRandom()` and `ClickHouseColumnBuilder.createColumn`. The `allowNullLiterals` precedent is per-instance and does not transfer to schema-construction sites.

### v1b: flag activation + oracle widening

- Flip `enableNullable` and `enableLowCardinality` defaults to ON.
- `ClickHouseCODDTestOracle` filters at `:175-177` and `:208-210` rewritten to capability checks using `isNumeric()` / `supportsLiteralEmission()`.
- `ClickHouseCERTOracle.generatorExprFor` rewritten to capability-driven dispatch (returns `toString(number)` for String capabilities, `toFloat64(number)` for Float, etc.).
- CODDTest's existing `baseTypeName` / `parseType` / `renderLiteral` string-parsing logic is migrated to use the new reflection parser, eliminating the dual-implementation drift.
- TLP three-valued logic in `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java` and its subclasses: when `enableNullable=true` and the partitioned predicate involves a Nullable column, the third partition adds `... IS NULL` disjunctively. If the cost of this change is too high for v1b, an explicit fallback is acceptable: keep `enableNullable=false` in v1b and defer TVL plumbing to a v1.0.5 — but the requirement is to make the decision explicitly, not silently produce non-exhaustive partitions.
- `ClickHouseTableGenerator` PARTITION BY / SAMPLE BY / ORDER BY: the current code generates arbitrary expression trees via `generateExpressionWithColumns`. v1b adds a capability-gated post-generation validation step (or a single-column-reference mode for these clauses) that rejects expressions whose root type is not in the capability-eligible set for that clause. Naming clarified: this is **expression-validation**, not "column selection."

### Explicitly deferred from v1

- All parameterised scalars, landing per phase:
  - v1.1: `FixedString(N)`, `Decimal(P,S)` and its bit variants
  - v1.2: `Enum8`, `Enum16`
  - v1.3: `DateTime`, `DateTime32`, `DateTime64` (with timezone and precision)
- All composites (Array, Map, Tuple, Nested) — v2
- All extension-protocol types (JSON, Variant, Dynamic, AggregateFunction, geo) — v3+
- `ClickHouseExpressionGenerator.generateColumn` type-equality semantics for wrapped types: today filters via `getType().name().equals(...)`. v1 keeps the existing root-type equality (treats `Nullable(Int32)` as equal to `Int32` for column-selection purposes); structural or capability-subset equality is a v1.1 decision when more wrappers exist.
- Retiring `ClickHouseDataType` from `ClickHouseConstant.getDataType()` and the AST family — future work, not committed.

### Why this boundary

- **Strictly larger than "primitives + Nullable only"**: adding `LowCardinality` forces the `canWrap` capability to encode a non-vacuous rule (LC has restricted base types in v1 — String, integers, Date, Nullable-of-those, but not Float/UUID/Bool). Nullable alone doesn't, because composites don't exist yet to refuse.
- **Strictly smaller than "+ parameterised scalars"**: separates three independent failure modes (parameter encoding, wrapper interaction, oracle widening) into separate PRs so each is cheap to debug.
- **TVL story lands in v1 via `Nullable`** (subject to the v1b TLP plumbing decision).
- **LowCardinality has its own rich ClickHouse bug surface** (dispatch correctness, LC↔non-LC coercion, distinct-counting), so v1 has real bug-finding potential rather than being foundation-only.

## Phasing After v1

| Phase | Adds | Notes |
|-------|------|-------|
| v1a | Recursive `Type` ADT (Primitive/Nullable/LowCardinality/Unknown), 4-predicate capability API, reflection parser, all `Primitive.kind` values, cast/coercion extension | Foundation; flags OFF; no oracle behaviour change |
| v1b | Flag flip to ON, oracle widening (CODDTest, CERT, TLP TVL, TableGenerator expression validation), CODDTest legacy parser migration | First activation; bug-find rate measurement starts here |
| v1.1 | `FixedString(N)`, `Decimal(P,S)` + bit variants; capability additions `isInteger`, `isFloat`, `isDecimal` | Largest parameter-encoding load; shared scalar emission machinery |
| v1.2 | `Enum8`, `Enum16`; member-set capability | Member-set capability; smaller |
| v1.3 | `DateTime`, `DateTime32`, `DateTime64` | Timezone awareness, precision handling |
| v2 | `Array(T)`, `Map(K,V)`, `Tuple(T*)`, `Nested(...)`; capability additions `isOrdered`, `hasTotalOrder`, composite-key rules | Composite literal emission + oracle reasoning about composite columns |
| v3 | `SpecialType` interface introduced; `JSON`, `Variant`, `Dynamic`, `AggregateFunction`, geo; capability `supportsAggregate(fn)` decided | Each as its own sub-phase via `SpecialType` |

Each phase is a separate PR. Phase boundaries match natural failure-mode boundaries. **Re-evaluation gate:** if v1b ships and the first 200k oracle iterations under flag-ON produce a bug-find rate within ±10% of baseline (i.e. no measurable new yield), pause before starting v1.1 and reassess whether the durable-foundation framing is paying for itself or whether work should pivot to ClickHouse-specific bug-density targeting (see Strategic Context).

## Success Criteria

1. **No-crash regression (v1a):** with feature flags OFF, run the existing oracle suite for at least 100k iterations across fixed-seed reproducers from the pre-change baseline. Acceptance: zero unhandled `AssertionError`, zero `SQLException` outside `ClickHouseErrors.getExpectedExpressionErrors()`. Bug-find rate is not measured here because `getRandom()` distribution has changed by design.
2. **Activation (v1b):** with `enableNullable=true` and `enableLowCardinality=true`, schema generation produces tables containing at least one Nullable and one LowCardinality column per N tables (N = small constant; verified by a probe test). CODDTest does not `return null` for Nullable columns of supported inner types. If the v1b TLP-TVL decision is to defer, the requirement is satisfied by a documented v1b-defers-TVL note plus a tracked v1.0.5 follow-up.
3. **Bug-yield re-evaluation (post-v1b):** measured over 200k iterations on the targeted ClickHouse version range. If the new bug-find rate is within ±10% of baseline, trigger the re-evaluation gate above. This is a decision criterion, not a pass/fail bar — v1 succeeds either way; what changes is whether v1.1 starts.
4. **API stability (v1.x):** v1.1, v1.2 do not require breaking changes to the v1 `Type` ADT public API or the v1 capability predicate signatures — only additions. (Claim restricted to v1.x; v2 composites may force capability-API extensions, and v3 SpecialType is a deliberate API addition.)
5. **Reflection round-trip (v1 types only):** for every type that v1's `getRandom()` emits, generating a CREATE TABLE, running DESCRIBE, and parsing the result back yields an equal `Type` value, targeted against ClickHouse 24.x and the version range pinned in the test container. Round-trip is *not* a requirement for `Type.Unknown(raw)` — those are the explicit graceful-degradation cases.
6. **Error catalog discipline:** v1 ships with expected ClickHouse error patterns recorded in `ClickHouseErrors`. No new false-positive bug reports attributable to unhandled error strings during v1's first 100k oracle iterations. Each later phase PR ships its own error-pattern additions reviewed alongside type code.

## Risks and Mitigations

- **Risk:** capability declarations drift from actual ClickHouse semantics. **Mitigation:** capability tests against real ClickHouse for each declared capability × in-scope type. Probes use positive assertions where ClickHouse's permissive coercion would otherwise hide drift: `toTypeName(<expr>::<TargetType>)` for cast feasibility, error-pattern matching on the JDBC exception for declared-false capabilities (e.g. `supportsArithmetic(String) == false` is validated by checking the *error* of `SELECT toInt32('abc') + 1`, not by hoping ClickHouse rejects `'abc' + 1`). Probe matrix sized for v1 (~30 combinations); designed to extend per phase, not assumed cheap forever.
- **Risk:** error-catalog explosion. **Mitigation:** each phase PR includes its expected-error additions; error patterns reviewed during PR alongside type code.
- **Risk:** reflection parser drifts from ClickHouse's actual type-string grammar across versions (codec suffixes, `LowCardinality(Nullable(...))` echo behaviour, etc.). **Mitigation:** unknown type strings degrade to `Type.Unknown(raw: String)`. Oracles skip `Unknown` columns. The round-trip success criterion (#5) is scoped to types v1 emits — it is explicitly not a contract for arbitrary user tables.
- **Risk:** existing reproducer logs / seeds become invalid because `getRandom()` distribution changes. **Mitigation:** acknowledged tradeoff; SQLancer reproducers depend on deterministic seeds within a code version, not across grammar revisions. v1a's no-crash regression criterion uses pre-change seeds against pre-change code paths as the comparison baseline.
- **Risk:** v1 PR is too large. **Mitigation adopted as default:** v1a/v1b split (above). Single-PR remains acceptable if size stays manageable.
- **Risk:** ClickHouse-only ADT + capability layer diverges from the rest of SQLancer's per-DBMS flat-enum pattern, raising review cost and complicating any future common-module lift. **Mitigation:** the ADT and capability API stay inside `src/sqlancer/clickhouse/`; no edits to `sqlancer.common.*`. If a future cross-DBMS lift becomes worthwhile, it is a separate refactor with no architectural prerequisite from this work.

## Upstream-Merge Stance

This work targets the ClickHouse internal fork (`nik/clickhouse-add-pqs-cert-coddtest` branch). Upstream-merge intent for v1 is **deferred**: v1 ships to the fork first, runs against ClickHouse CI, and the upstream-merge question is reopened after v1b's bug-yield re-evaluation. If upstream SQLancer accepts the pattern, later phases target upstream directly; if not, the fork carries the divergence intentionally with each phase's diff documented for eventual back-port. The fork-divergence cost is real but bounded — the change set stays inside one module.

## Out of Scope (deliberately)

- Cross-DBMS reuse — this stays in the ClickHouse module.
- Retiring `ClickHouseDataType` from `ClickHouseConstant.getDataType()` and the AST family — future work; v1 coexists.
- Settings/SQL-dialect option exploration tied to type behaviour (e.g. `allow_suspicious_low_cardinality_types`) — interesting follow-up, separate work.
- Functional expansion (new functions, new operators) — separate from type expansion.
- Performance-optimising the generator — current generator perf is fine for SQLancer's loop.
- Replacing the existing `ClickHouseExpression.TypeAffinity.isNumeric()` predicate (`ClickHouseExpression.java:22-27`). v1's new `isNumeric()` lives on `Type`; the AST-level `TypeAffinity` continues to exist for AST-level checks. A future cleanup may unify them; that is not v1's job.

## Open Questions for Planning

1. Where should timezone-aware `DateTime` values (v1.3) get their tz pool — hardcoded list of common tz names, randomised per type, or pulled from `system.time_zones`? (v1.3 question; non-blocking for v1.)
2. Does v1 include the `Nothing` type (currently the result of NULL constants) as an explicit ADT node, or keep it implicit? Recommended: keep implicit in v1 — `ClickHouseNullConstant.getDataType()` continues to return `Nothing` for compatibility; the ADT does not need a `Nothing` constructor because `Nullable(T)` carries the nullability information at the type level.
3. For `Nullable(T)` columns in v1b, what's the default NULL-probability in literal emission? Recommended: follow the existing `allowNullLiterals` style (`Randomly.getBooleanWithSmallProbability()`) for the initial landing; tunable per-column ratio is a planning-time refinement.
4. Should the v1a/v1b split correspond to two separate PRs against `main`, or one PR with two commits where v1b is held until v1a's CI passes a fixed-seed regression run? (Process question; either works.)
