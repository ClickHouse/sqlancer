---
title: 'feat: ClickHouse SEMR oracle + session-level settings randomization'
type: feat
status: completed
date: 2026-05-17
deepened: 2026-05-17
---

# feat: ClickHouse SEMR oracle + session-level settings randomization

## Overview

Add two complementary differential-testing capabilities to the ClickHouse SQLancer fork:

1. **SEMR oracle** — a new `TestOracle` that picks a "should-be-result-preserving" ClickHouse optimizer setting, runs the same generated `SELECT` once with that setting forced ON and once forced OFF, and fails when the two multisets diverge.
2. **Per-session settings randomization** — a connection-setup layer that, when enabled, picks a random subset of a curated settings catalog and issues `SET key = value` on the post-CREATE database connection, so every other oracle (TLP*, NoREC, PQS, CERT, CODDTest) implicitly runs under a different setting profile each database.

Together they convert SQLancer's current "everything runs under defaults" coverage into broad setting-space exploration plus a focused oracle that targets a bug class structurally invisible to today's oracle inventory: wrong-result regressions that surface only when an optimizer setting is toggled.

## Problem Frame

Every SQLancer run today executes ClickHouse with the JDBC driver's default settings plus two hard-coded URL flags (`allow_experimental_analyzer`, `allow_suspicious_low_cardinality_types`). Any wrong-result bug that hides behind an off-by-default optimizer flag is invisible. The fork's existing oracles validate query semantics under one server configuration; bugs in cross-configuration consistency are silently missed.

**Honest framing.** The plan targets *cross-configuration consistency bugs* — wrong results that appear under one optimizer setting and not another. The claim is not that this is provably the largest ClickHouse bug class; it is that the class is structurally invisible to today's oracle inventory and has at least one concrete documented instance (`ClickHouseTLPHavingOracle.java:42` works around ClickHouse#12264). The bet is that adding the methodology surfaces additional instances of the same shape. Yield against alternative methodologies (cross-version differential, query-plan equivalence) is unknown until measured (see Success Metrics and Alternative Approaches Considered).

There is no upstream brainstorm document for this feature. The request explicitly framed two pieces (SEMR oracle + per-session randomization), confidence 85%, complexity low. Confirmed during planning: v1 catalog covers booleans, tiered numerics, and experimental flags; SEMR and random-session-settings are mutually exclusive in a single run to keep failure attribution clean.

## Requirements Trace

- **R1.** A new `SEMR` oracle factory constant is selectable via `--oracle SEMR` and runs the same generated SELECT under one curated setting forced 0 and forced 1, asserting multiset equality.
- **R2.** SEMR picks the setting to vary from a curated built-in list. The list explicitly excludes settings already hardcoded by other oracles (`enable_optimize_predicate_expression`, `aggregate_functions_null_for_empty` — see `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java:42` and `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPAggregateOracle.java:42`).
- **R3.** A new `--random-session-settings` flag, when enabled, issues `SET k = v` for a random subset of curated settings on the per-database connection, before any oracle runs. The subset excludes optimizer-rewrite settings (e.g., `enable_optimize_predicate_expression`, `query_plan_filter_push_down`) so as not to invalidate invariants assumed by CERT, CODDTest, or hardcoded-SETTINGS oracles.
- **R4.** The randomization layer respects a `--random-session-settings-budget` cap on subset size and emits an end-of-database summary counting attempted vs accepted SETs (so operators can detect catalog drift).
- **R5.** Settings applied to a database are recorded in the reproducer log (`globalState.getState().logStatement(...)`) so failing runs are replayable.
- **R6.** `--random-session-settings true` combined with `--oracle SEMR` is rejected at startup with a clear error — these features are mutually exclusive in a single run.
- **R7.** Settings catalog churn (unknown setting, out-of-range value, removed-in-version errors) does not surface as oracle failures — the expected-error catalog absorbs them.
- **R8.** Existing oracle behavior is unchanged when both new flags are off / SEMR is not selected (default state preserves current CI green).

## Scope Boundaries

- **Out of scope:** AST-level support for `SETTINGS` clauses (settings are applied via string-suffix on stringified queries and via `SET` statements, matching the existing precedent at `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java:42`).
- **Out of scope:** Multi-setting SEMR (varying ≥2 settings simultaneously in one oracle iteration). v1 varies exactly one setting per `check()`. Known limitation: this is structurally blind to multi-setting interaction bugs of the kind documented at `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java:42` (ClickHouse#12264, which required two settings together). Pairwise SEMR is a candidate v2 enhancement.
- **Out of scope:** Cross-version setting catalogs. v1 targets ClickHouse 24.3.1.2672 (CI pin); cross-version catalog detection is a deferred implementation question (see Deferred to Implementation).
- **Out of scope:** Operator override of the SEMR-eligible candidate list via CLI flag. v1 ships a single internal list; operators who need a different list patch the source. A `--oracle-semr-settings` flag can be added later when a concrete use case appears.
- **Out of scope:** Automatic catalog generation from upstream `src/Core/Settings.cpp`. v1 catalog is hand-curated; a future enhancement can add a dump-and-diff tool.
- **Out of scope:** Asymmetric-error handling stricter than today's `ComparatorHelper` default. If SEMR's ON side errors and OFF side succeeds (or vice versa) with an expected-error pattern, the iteration becomes `IgnoreMeException`; promoting that to a finding is a deferred enhancement. Known limitation: this can mask wrong-result bugs that surface as expression-level errors on only one side of a SEMR comparison (the seeded expression-error catalog absorbs them).
- **Out of scope:** Settings managed by existing `ClickHouseOptions` flags. `allow_experimental_analyzer` (controlled by `--analyzer`) and `allow_suspicious_low_cardinality_types` (implicit when `--test-lowcardinality-types true`) are explicitly excluded from both catalogs to prevent flag-fight.
- **Out of scope (catalog):** Optimizer-rewrite settings (`enable_optimize_predicate_expression`, `query_plan_filter_push_down`, `optimize_move_to_prewhere`, `optimize_read_in_order`, `optimize_use_projections`, `convert_query_to_cnf`) are SEMR-eligible but explicitly **excluded** from the random-session-settings catalog. Reason: CERT's monotonicity invariant (`src/sqlancer/clickhouse/oracle/cert/ClickHouseCERTOracle.java:65-82`) and CODDTest's constant-folding invariant (`src/sqlancer/clickhouse/oracle/coddtest/ClickHouseCODDTestOracle.java`) both depend on these rewrites; randomizing them produces false-positive oracle failures.

## Context & Research

### Relevant Code and Patterns

- `src/sqlancer/clickhouse/ClickHouseProvider.java` — `createDatabase(...)` (lines 109-154) opens the post-CREATE JDBC connection that all oracles share via `globalState.getConnection()`. The seam for `SET k=v` on connect is between `con = DriverManager.getConnection(...)` (line 149) and `return new SQLConnection(con)` (line 153). `Main.java:450-457` is where `provider.createDatabase(...)` is called and the returned connection is stored on the global state.
- `src/sqlancer/clickhouse/ClickHouseOptions.java` — JCommander option class pattern; new flags follow the same `@Parameter(names="...", description="...", arity=1)` shape as `--test-joins` and `--analyzer`.
- `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` — enum implementing `OracleFactory<ClickHouseGlobalState>`. Adding a `SEMR` constant exposes `--oracle SEMR` to the CLI with no other plumbing.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPDistinctOracle.java` — the shortest existing differential oracle (~40 LOC). Same shape SEMR needs: build one `ClickHouseSelect`, stringify twice with different decorations, compare with `ComparatorHelper.assumeResultSetsAreEqual`.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java:42, 61` and `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPAggregateOracle.java:42, 63` — precedent for appending `" SETTINGS k=v, ..."` literally to a stringified `ClickHouseSelect`. SEMR uses this technique (per-query suffix), not SET-on-connection, because the connection is shared across oracles in a `CompositeTestOracle` and SET would leak across neighbors.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java:37` — canonical `ClickHouseErrors.addExpectedExpressionErrors(errors)` seeding in the constructor; SEMR follows this and additionally adds the new session-settings error list.
- `src/sqlancer/ComparatorHelper.java` — `getResultSetFirstColumnAsString(...)` and `assumeResultSetsAreEqual(...)` are the standard fetch and compare primitives. The overload accepting a `UnaryOperator<String> canonicalizationRule` is available if a candidate setting changes value formatting (e.g., float-precision settings) and a normalization step proves necessary.
- `src/sqlancer/clickhouse/ClickHouseErrors.java` — substring-pattern catalog consumed by all oracles. Regex support exists in `src/sqlancer/common/query/ExpectedErrors.java` (`addRegex`, `withRegex`, `addAllRegexStrings`) but is not currently used in ClickHouse; new SEMR/randomization patterns may want regex for messages like `Setting (.+) is neither a builtin setting nor a custom setting`.
- `src/sqlancer/Main.java:440-470` — the per-thread runner that calls `provider.createDatabase(state)` then `state.setConnection(con)`. Validation of mutually-exclusive flag combinations needs to fire before this loop runs; the natural place is the option-parsing or first-use seam in `ClickHouseOptions`/`ClickHouseProvider`.
- `src/sqlancer/clickhouse/gen/ClickHouseTableGenerator.java:121` — secondary precedent for literal `SETTINGS ...` suffixing on a `CREATE TABLE`.
- `test/sqlancer/dbms/TestClickHouse.java` — integration test convention: one `@Test` per oracle, `assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV))`, `Main.executeMain(...)` with `--num-threads`, `--num-queries`, `--database-prefix Tn_`, `clickhouse`, `--oracle X`. Database prefixes T1_ through T14_ are taken; new tests get T15_, T16_, T17_.

### Institutional Learnings

- `docs/solutions/` does not exist in this repo. No prior learnings captured. Recommend seeding it via `compound-engineering:ce-compound` once this lands and the first false-positive catalog gap surfaces.

### External References

- Skipped intentionally. Local SQLancer patterns are dense (9 existing oracles, 7 differential), the user provided an explicit starting list of optimizer flags, and curating the settings catalog is an implementation-time task that requires querying the running 24.3.1.2672 server. External research adds no planning value.

## Key Technical Decisions

- **SEMR uses per-query `SETTINGS k=v` suffix, not session-level `SET`.** Reason: the JDBC connection is shared across all oracles selected for a run (`CompositeTestOracle` composition in `src/sqlancer/ProviderAdapter.java:50-107`). A connection-level `SET` issued by SEMR would silently leak into neighboring oracles' queries. Per-query suffix scopes the change to exactly the SEMR comparison pair.
- **Random-session-settings uses `SET` on connect, not URL parameters.** Reason: URL parameters bypass `globalState.getState().logStatement(...)` and therefore the reproducer log. `SET` statements integrate naturally with the existing DROP/CREATE/USE logging at `ClickHouseProvider.java:127-131`. This is also extensible to non-boolean and string-valued settings, which the URL form handles awkwardly.
- **Catalog is a flat-list module modeled on `ClickHouseErrors.java`, with one small concession for `RANDOM_SESSION_SETTINGS`.** Reason: codebase pattern. `src/sqlancer/clickhouse/ClickHouseErrors.java` encodes its curated list as a static `List.of(...)` of plain strings with inline `//` comments per entry. `ClickHouseSessionSettings.java` mirrors the shape for `SEMR_SETTINGS` (a plain `List<String>` of names toggled 0/1 by SEMR) and `MANAGED_BY_OPTIONS` (a plain `Set<String>` denylist). The single exception is `RANDOM_SESSION_SETTINGS`: because the randomization layer must pick one of several candidate values per non-boolean setting (e.g., `max_threads ∈ {1, 2, 8}`), it uses `List<RandomEntry>` where `RandomEntry` is a tiny package-private record `(String name, List<String> candidateValues)`. The record exists solely to give the picker structured access; per-setting metadata (intent, version compatibility) still lives in `//` comments. Picker methods are one-liners over `Randomly.fromList(...)` / `Randomly.extractNrRandomColumns(...)`.
- **Catalog ordering is deterministic.** Reason: reproducibility under a fixed seed requires stable iteration. `List<String>` preserves insertion order across JVMs and Java updates; no `HashMap`-based representation is used anywhere in the catalog.
- **Mutual exclusion is enforced in `Main.executeMain` after JCommander parse, before any thread spawns.** Reason: per-thread enforcement inside `createDatabase` triggers `Main.java:688`'s `catch (Throwable)` block, which prints a stack trace per thread and writes a reproducer artifact per failed database — exactly the noise the gate exists to prevent. A single pre-flight check at `Main.executeMain` after `JCommander.parse(...)` (or the nearest equivalent seam where the parsed `ClickHouseOptions` is available) produces one clear error and a clean non-zero return. The check examines `clickHouseOptions.randomSessionSettings && clickHouseOptions.oracle.contains(ClickHouseOracleFactory.SEMR)`.
- **SEMR varies exactly one setting per `check()`.** Reason: matches the user's confirmed scope; preserves clean failure attribution (the violating setting is named in the assertion message). Multi-setting variation is a known scope gap (see Scope Boundaries) and a candidate v2 enhancement.
- **Expected-error patterns for settings churn use substrings only, never the bare token `Setting`.** Reason: the existing 90-entry `ClickHouseErrors.java` catalog is substring-only via `error.contains(s)` at `src/sqlancer/common/query/ExpectedErrors.java:95-110`. A bare `Setting` substring matches dozens of unrelated ClickHouse error messages (read-only-setting rejections, suggestion lines, echoed `SETTINGS` clauses in error context). Use specific multi-word substrings: `Unknown setting`, `is neither a builtin setting nor a custom setting`, `Cannot parse setting value`, `out of range`. Regex is not introduced in v1; if a future pattern needs it, the `ExpectedErrors.addRegexString` infra is available.
- **The randomization layer never randomizes settings managed by existing `ClickHouseOptions` flags or hardcoded by other oracles.** Reason: avoid flag-fight (`--analyzer false` vs randomized `allow_experimental_analyzer=1`) and avoid invalidating invariants of CERT, CODDTest, TLPHaving, and TLPAggregate. Catalog rule: anything controlled by an existing option or named in another oracle's hardcoded `SETTINGS` suffix goes on the `MANAGED_BY_OPTIONS` denylist or is omitted from `RANDOM_SESSION_SETTINGS` entirely.
- **Reproducibility relies on per-database logging.** Reason: SQLancer's per-database log already captures DROP/CREATE/USE. Adding `logStatement("SET k=v")` for each chosen setting keeps the full reproducer in the same artifact. Operators also get a one-line summary at end of database creation: `session-settings applied: M of N attempted`, so silent catalog drift is observable.

## Open Questions

### Resolved During Planning

- **Catalog scope (v1).** Booleans + tiered numerics + experimental flags, per user choice.
- **SEMR + random-session-settings interaction.** Mutually exclusive in a single run; rejected at startup (pre-thread), per user choice.
- **Catalog representation.** Flat `List<String>` constants in `ClickHouseSessionSettings.java`, modeled on `ClickHouseErrors.java`. No inner `Entry` type, no `Map`.
- **SEMR mechanism.** Per-query `SETTINGS k=v` suffix, not session-level SET (composability requirement).
- **SEMR-eligible candidate source.** Single internal list in v1; no operator override flag.
- **WHERE-clause behavior in SEMR's `check()`.** Set to `null` after `super.check()` returns. v1 SEMR compares the base SELECT under ON vs OFF without partitioning; predicate-induced query-shape variation is a candidate v2 enhancement.
- **Error-pattern style.** Substrings only (no regex in v1), matching the existing 90-entry style in `ClickHouseErrors.java`.

### Deferred to Implementation

- **Final curated catalog contents.** The exact list of settings and their candidate values requires querying the CI-pinned ClickHouse 24.3.1.2672 image (`SELECT name, default, min, max, type FROM system.settings`) and reviewing each against upstream `src/Core/Settings.cpp`. Settings whose names appear in `MANAGED_BY_OPTIONS` (controlled by `--analyzer`/`--test-lowcardinality-types`) and settings hardcoded as `SETTINGS` suffixes in `ClickHouseTLPHavingOracle.java:42` / `ClickHouseTLPAggregateOracle.java:42` (`enable_optimize_predicate_expression`, `aggregate_functions_null_for_empty`) must be excluded from `RANDOM_SESSION_SETTINGS`.
- **Whether `allow_experimental_analyzer` is itself SEMR-eligible.** Toggling the analyzer is a known result-affecting change in some upstream versions; defer to implementation-time triage during catalog seeding.
- **Verify ClickHouse JDBC v2 driver session persistence.** The plan assumes `SET k = v` on the post-CREATE JDBC connection persists to all subsequent queries on that same connection. The clickhouse-jdbc 0.9.6 v2 driver uses HTTP under the hood, and HTTP transport without an explicit `session_id` may treat each request independently — making `SET` a silent no-op. Before merging, verify via either (a) the driver's session-handling docs or (b) a smoke check that issues `SET max_threads = 7` then `SELECT value FROM system.settings WHERE name = 'max_threads'` and asserts the value is `7`. If sessions are not stable, add `&session_id=<UUID>&session_check=0` to the URL constructed in `ClickHouseProvider.createDatabase`.
- **Catalog drift detection strategy.** When the CI ClickHouse image is bumped past 24.3.1.2672, settings catalog entries may be renamed, deprecated, or removed. Candidate strategies for implementation-time triage: (a) startup self-check that filters catalog entries against `SELECT name FROM system.settings`, (b) CI step that diffs the catalog against `system.settings` for the running image and fails loudly on drift, (c) per-release manual catalog revalidation in the release checklist. Choose one.
- **Canonicalization rule, if any, for SEMR comparisons.** Whether to call `ComparatorHelper.assumeResultSetsAreEqual` with the default (which already canonicalizes `-0.0`/`-0` via `canonicalizeResultValue`) or with a custom `UnaryOperator<String>`. Defer until catalog churn produces a concrete false-positive pattern.
- **Exact wording of the mutual-exclusion error message.** Pick during implementation; should name both flags and suggest "drop `--random-session-settings true` for SEMR runs, or remove `--oracle SEMR`."
- **SEMR's interaction with TLPBase's smoke check.** `ClickHouseTLPBase.check()` (`src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java:66-67`) unconditionally runs the base SELECT once before returning. SEMR inheriting from TLPBase therefore executes the same SELECT three times per iteration (smoke + ON + OFF). The cost is acceptable but should be acknowledged in the SEMR Approach so throughput estimates are correct. Implementer may choose to override the smoke if it proves expensive.

## High-Level Technical Design

> *This illustrates the intended approach and is directional guidance for review, not implementation specification. The implementing agent should treat it as context, not code to reproduce.*

Two seams, one shared catalog:

```
                ┌──────────────────────────────────────────────────┐
                │ ClickHouseSessionSettings (flat-list module)     │
                │  - SEMR_SETTINGS:           List<String>         │
                │  - RANDOM_SESSION_SETTINGS: List<RandomEntry>    │
                │      record(name, candidateValues)               │
                │  - MANAGED_BY_OPTIONS:      Set<String> denylist │
                └──────────────────────────────────────────────────┘
                          │                                  │
                          │ pickSemrCandidate(rng)           │ pickRandomProfile(rng, budget)
                          ▼                                  ▼
        ┌──────────────────────────────┐         ┌──────────────────────────────┐
        │ ClickHouseSEMROracle.check() │         │ ClickHouseProvider           │
        │  build select (TLPBase-like) │         │  .createDatabase(state)      │
        │  setting = pickSemr()        │         │  ... after second connect    │
        │  q_on  = select + " SETTINGS │         │  if random-session-settings: │
        │           k=1"               │         │    for (k,v) in profile:     │
        │  q_off = select + " SETTINGS │         │      execute("SET k = v")    │
        │           k=0"               │         │      logStatement(...)       │
        │  compare(q_on, q_off)        │         │    log "M of N applied"      │
        └──────────────────────────────┘         │  return SQLConnection(con)   │
                                                 └──────────────────────────────┘

                          ▲                                  ▲
                          │ same connection                  │ same connection
                          └──────────────┬───────────────────┘
                                         │
                         ┌──────────────────────────────────┐
                         │ ClickHouseGlobalState.connection │
                         │ (lives for one database, then    │
                         │  --num-queries iterations)       │
                         └──────────────────────────────────┘

Mutual-exclusion gate (in Main.executeMain, after JCommander parse, before any thread spawns):
    if (opts.randomSessionSettings && opts.oracle.contains(SEMR))
        throw IllegalArgumentException("--random-session-settings true is incompatible "
                                       + "with --oracle SEMR. Remove one.")
```

Failure attribution stays clean: SEMR's assertion message names the single varied setting; random-session-settings failures trip a *different* oracle (TLPDistinct, NoREC, ...), and the per-database log lists every `SET` issued at connect time, so the reproducer is self-contained.

## Implementation Units

- [ ] **Unit 1: Curated settings catalog (`ClickHouseSessionSettings`)**

**Goal:** Create the flat-list catalog module that both the SEMR oracle and the per-session randomization layer read from.

**Requirements:** R2, R3, R4, R7 (the catalog is the data source for both the SEMR-eligible list and the randomization profile, so R3/R4's "random subset" comes from here too).

**Dependencies:** None.

**Files:**
- Create: `src/sqlancer/clickhouse/ClickHouseSessionSettings.java`
- Test: `test/sqlancer/clickhouse/ClickHouseSessionSettingsTest.java`

**Approach:**
- Public final class, package `sqlancer.clickhouse`, instance-less (private constructor). Exact codebase precedent: `src/sqlancer/clickhouse/ClickHouseErrors.java`.
- Three static constants only (no inner types, no Map):
  - `SEMR_SETTINGS`: `List<String>` of optimizer-rewrite setting names that the SEMR oracle may toggle 0/1. v1 seeds: `optimize_move_to_prewhere`, `optimize_read_in_order`, `optimize_use_projections`, `enable_optimize_predicate_expression` (commented as overlap with TLPHaving — see denylist note), `convert_query_to_cnf`, `query_plan_filter_push_down`. Each entry has a `//` comment line citing intent / known caveat.
  - `RANDOM_SESSION_SETTINGS`: `List<RandomEntry>` where `RandomEntry` is a tiny package-private record `(String name, List<String> candidateValues)`. This is the one place a typed record is justified — the picker needs structured access to each setting's candidate values, which a `List<String>` cannot provide without re-parsing. v1 seeds: tiered numerics like `max_threads ∈ {"1","2","8"}` and `max_block_size ∈ {"1024","65536"}`, plus a small set of `allow_experimental_*` flags chosen against 24.3.1.2672. Excludes every entry in `MANAGED_BY_OPTIONS` and every setting hardcoded by another oracle (`enable_optimize_predicate_expression`, `aggregate_functions_null_for_empty`). Optimizer-rewrite settings are intentionally **not** in this list — they live in `SEMR_SETTINGS` only.
  - `MANAGED_BY_OPTIONS`: `Set<String>` of `allow_experimental_analyzer`, `allow_suspicious_low_cardinality_types` (extended as new options land). The two picker methods filter against this set defensively even though the lists above already exclude these names.
- Two public selectors, each a one-liner:
  - `pickSemrCandidate(Randomly r)` — picks one name from `SEMR_SETTINGS`, returns the triple `(name, "0", "1")`. Throws `IllegalStateException` if the list is empty (configuration bug). No operator override list in v1.
  - `pickRandomProfile(Randomly r, int budget)` — returns a `LinkedHashMap<String,String>` (insertion-order stable for reproducibility) of size up to `budget` (0 means unbounded), built from a random subset of `RANDOM_SESSION_SETTINGS` with one `Randomly.fromList(entry.candidateValues)` pick per chosen entry.

**Patterns to follow:**
- File shape: `src/sqlancer/clickhouse/ClickHouseErrors.java` (static-utility class, private constructor, `List.of(...)` of plain strings with inline `//` comments).
- PRNG access: `Randomly` API (see usage in `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java:48`). Use `Randomly.fromList(...)` and the project's existing subset selection helpers.

**Test scenarios:**
- Happy path: `pickSemrCandidate(seededRandomly)` returns a name from `SEMR_SETTINGS` with values `"0"` and `"1"`.
- Happy path: `pickRandomProfile(seededRandomly, 3)` returns at most three entries, all names present in `RANDOM_SESSION_SETTINGS`, none in `MANAGED_BY_OPTIONS`.
- Edge case: `pickRandomProfile(seededRandomly, 0)` returns up to the full `RANDOM_SESSION_SETTINGS` size.
- Edge case: every `MANAGED_BY_OPTIONS` entry is filtered from `pickRandomProfile` output across 100 randomized iterations.
- Edge case: `pickRandomProfile` output across two calls with the **same seed** is byte-identical (reproducibility under fixed PRNG state).
- Edge case: `SEMR_SETTINGS` and `RANDOM_SESSION_SETTINGS` are disjoint (asserted via test): an entry cannot appear in both, which protects CERT/CODDTest invariants.
- Edge case: settings hardcoded by `ClickHouseTLPHavingOracle.java:42` and `ClickHouseTLPAggregateOracle.java:42` (`enable_optimize_predicate_expression`, `aggregate_functions_null_for_empty`) do not appear in `RANDOM_SESSION_SETTINGS`.

**Verification:**
- `ClickHouseSessionSettingsTest` runs automatically under the `misc` CI job (the workflow filter `'-Dtest=!sqlancer.dbms.**,!sqlancer.qpg.**'` at `.github/workflows/main.yml:37` already includes pure-Java tests under `test/sqlancer/clickhouse/`).
- Catalog contents documented inline in the class with one `//` comment per entry citing intent ("optimizer rewrite, SEMR-eligible" vs "execution mode, randomization-only") and version-compatibility notes where known.

---

- [ ] **Unit 2: CLI options (`ClickHouseOptions`)**

**Goal:** Add the two new CLI flags. Mutual-exclusion validation lives in `Main` pre-flight (next unit).

**Requirements:** R3, R4, R8

**Dependencies:** None at compile-time.

**Files:**
- Modify: `src/sqlancer/clickhouse/ClickHouseOptions.java`

**Approach:**
- Add two `@Parameter` fields, both defaulting to "feature off / safe defaults" so existing CI is unaffected:
  - `--random-session-settings` (boolean, `arity = 1`, default `false`) — master toggle for the SET-on-connect layer.
  - `--random-session-settings-budget` (int, default `5`, `0` = unbounded) — cap on subset size.
- Field naming follows existing public-field style (no getters): `randomSessionSettings`, `randomSessionSettingsBudget`.
- No new validation method on `ClickHouseOptions`; the class stays a thin DTO. Mutual-exclusion validation is in Unit 3.
- No `--oracle-semr-settings` flag in v1. Operators who need a different SEMR-eligible list patch `ClickHouseSessionSettings.SEMR_SETTINGS` directly. Add the flag back when a concrete external use case appears.

**Patterns to follow:**
- `src/sqlancer/clickhouse/ClickHouseOptions.java` — existing field declarations (e.g., `--test-joins`, `--analyzer`).

**Test scenarios:**
- Happy path: invoking `Main.executeMain(... "clickhouse", "--oracle", "TLPWhere", "--random-session-settings", "true", "--random-session-settings-budget", "3")` parses without error and `getClickHouseOptions()` returns `randomSessionSettings == true`, `randomSessionSettingsBudget == 3`.
- Edge case: omitting both flags leaves defaults (`false`, `5`) — regression guard for R8.

**Verification:**
- A small unit test under `test/sqlancer/clickhouse/` (e.g., `ClickHouseOptionsParseTest.java`) constructs `JCommander.newBuilder().addObject(...).build().parse(...)` and asserts field values; runs under the `misc` CI job automatically (workflow filter at `.github/workflows/main.yml:37` includes pure-Java tests under `test/sqlancer/clickhouse/`).
- `mvn -B verify -DskipTests=true` (checkstyle/spotbugs) still passes with the new fields.

---

- [ ] **Unit 3: Mutual-exclusion pre-flight + per-session SET-on-connect**

**Goal:** Reject the incompatible flag combination once at startup, and when `--random-session-settings true` is in effect, issue `SET k = v` for each chosen catalog entry on the post-CREATE connection, log every statement, and emit a per-database summary of attempted vs accepted SETs.

**Requirements:** R3, R4, R5, R6, R8

**Dependencies:** Unit 1 (catalog), Unit 2 (options fields), Unit 4 (expected-error patterns — for the SET-failure path).

**Files:**
- Modify: `src/sqlancer/Main.java` (mutual-exclusion pre-flight after JCommander parse)
- Modify: `src/sqlancer/clickhouse/ClickHouseProvider.java` (SET-on-connect, summary log)

**Approach:**
- Mutual-exclusion check **in `Main.executeMain`**, immediately after JCommander parsing completes and the provider's `DBMSSpecificOptions` is available, and **before** the thread pool spawns. Concretely: locate the seam in `src/sqlancer/Main.java` between option parsing and the per-thread loop at `Main.java:440-470`. If the parsed provider is the ClickHouse provider and `clickHouseOptions.randomSessionSettings && clickHouseOptions.oracle.contains(ClickHouseOracleFactory.SEMR)`, throw `IllegalArgumentException` with a message naming both flags and the remediation. The exception propagates out of `executeMain` as a single non-zero return — no per-thread stack-trace flood, no per-database reproducer artifact written.
- **Generics seam:** at the Main-level call site, `executorFactory.getCommand()` returns the wildcard-typed `DBMSSpecificOptions<?>`. The check uses a plain `instanceof ClickHouseOptions` cast to reach the concrete fields; this is local to one branch in `Main.executeMain` and adds no API surface. If multiple DBMS providers later need cross-field validation, promoting to a `void validate()` default method on `DBMSSpecificOptions` is the right time — not in v1.
  - Rationale: putting the check inside `ClickHouseProvider.createDatabase` (an earlier design choice) caused `N` threads to each catch the exception at `Main.java:688`'s `catch (Throwable)`, each printing a stack trace and writing a reproducer artifact under `logs/clickhouse/T17_*`. The pre-flight version produces exactly one error message.
- After the existing post-CREATE `con = DriverManager.getConnection(...)` (currently at `src/sqlancer/clickhouse/ClickHouseProvider.java:149-152`) and before `return new SQLConnection(con)`:
  - If `clickHouseOptions.randomSessionSettings`, call `ClickHouseSessionSettings.pickRandomProfile(globalState.getRandomly(), clickHouseOptions.randomSessionSettingsBudget)`.
  - Track two counters: `attempted` and `accepted`. For each `(k, v)` in the chosen profile:
    - Build the statement string `SET <k> = <v>`, call `globalState.getState().logStatement(stmt)`, then execute on a `try-with-resources Statement` on the existing `con`.
    - Increment `attempted`. On success, increment `accepted`. On `SQLException` whose message matches the new session-settings expected-error catalog (Unit 4), continue (the per-statement `logStatement` already captured the attempt). On an unexpected `SQLException`, rethrow.
  - After the loop, emit one summary log line: `globalState.getState().logStatement(String.format("-- session-settings applied: %d of %d", accepted, attempted))`. Operators reading reproducer logs can detect catalog drift immediately (e.g., `0 of 5` means the entire profile was rejected — likely a stale catalog for the running ClickHouse version).
- No change to URL-parameter handling. `--analyzer` and `--test-lowcardinality-types` continue to route through the URL because they're in the `MANAGED_BY_OPTIONS` denylist and never randomized.

**Patterns to follow:**
- Logging style: `globalState.getState().logStatement(stmt)` then `s.execute(stmt)`, matching the existing DROP/CREATE/USE block at `ClickHouseProvider.java:126-143`.
- PRNG access: `globalState.getRandomly()` (already initialized by `Main` before `createDatabase` runs — see `src/sqlancer/Main.java:446`).

**Test scenarios:**
- Happy path (integration, via Unit 6): running `--random-session-settings true` against the CI ClickHouse image completes a full `--num-queries` run with no oracle failure; the per-database reproducer log contains at least one `SET ... = ...` line and one `-- session-settings applied: M of N` summary line per database.
- Edge case: `--random-session-settings-budget 0` produces a profile of any size up to the catalog cap; the run still completes.
- Error path: `--random-session-settings true --oracle SEMR` exits non-zero with a **single** clear error message naming both flags, no per-thread stack trace flood, and no `logs/clickhouse/T17_*` reproducer artifacts.
- Error path: if `pickRandomProfile` returns an entry whose name ClickHouse 24.3.1 doesn't recognize, the SET catches an `Unknown setting` SQLException; the per-database run still completes; the summary line reports `M of N` with `M < N`, surfacing the drift.
- Integration: a TLPDistinct oracle running under a randomized profile passes — the randomization layer doesn't accidentally turn TLPDistinct into a SEMR-style oracle (the SET happens once at connect, then all oracles see the same profile).
- Integration: a CERT oracle (`--oracle CERT --random-session-settings true`) runs without false-positive cardinality-monotonicity failures, because the catalog excludes optimizer-rewrite settings that CERT's invariant depends on (R3 and the scope-boundary exclusion list).

**Verification:**
- The mutual-exclusion error fires once at startup, before any database is created (visible in stderr / `Main.executeMain` return code).
- The per-database log shows the chosen `SET` statements at the top and the summary line.
- A run with `--random-session-settings false` (default) leaves `createDatabase` byte-for-byte equivalent to today's behavior modulo the unreachable new branch — visually confirm by reading the diff.

---

- [ ] **Unit 4: Expected-error patterns for settings churn (`ClickHouseErrors`)**

**Goal:** Absorb setting-validation errors (unknown setting, out-of-range value, removed-in-version) so they don't surface as oracle failures.

**Requirements:** R7

**Dependencies:** None (logically depends on the patterns being needed by Units 3 and 5; can land in any order before those rely on it).

**Files:**
- Modify: `src/sqlancer/clickhouse/ClickHouseErrors.java`

**Approach:**
- Add a sibling list method `getSessionSettingsErrors()` returning substring patterns. v1 uses substrings only (matches the existing 90-entry style in `ClickHouseErrors.java`). Seeds, deliberately specific to avoid masking unrelated bugs:
  - `Unknown setting` (covers `Unknown setting <name>`; specific enough — the multi-word substring won't match unrelated messages).
  - `is neither a builtin setting nor a custom setting`.
  - `Cannot parse setting value`.
  - `Setting value out of range` (use this multi-word form, **not** the bare `out of range` substring, to avoid colliding with arithmetic-overflow messages from other oracles).
  - `UNKNOWN_SETTING` (ClickHouse error code label).
- **Do not** add the bare token `Setting` as a substring. ClickHouse uses the word in many unrelated messages (read-only-setting rejections, suggestion lines, echoed `SETTINGS` clauses); a bare match would silently absorb real bugs.
- Add a sibling helper `addSessionSettingsErrors(ExpectedErrors errors)` that calls `errors.addAll(getSessionSettingsErrors())`. No regex in v1; the substring set above covers known patterns. If a future error class requires regex, `ExpectedErrors.addRegexString(...)` is available.
- Do **not** modify `getExpectedExpressionErrors()`. SEMR and the randomization layer want both lists; the SEMR oracle constructor and `ClickHouseProvider.createDatabase` (for the SET-failure path) call both helpers in sequence. This keeps existing oracles' error budgets unchanged.

**Patterns to follow:**
- Existing `getExpectedExpressionErrors()` style: `List.of(...)` of substring patterns with inline `//` comments citing the upstream issue when known.

**Test scenarios:**
- Happy path (unit): `addSessionSettingsErrors` adds the expected substrings; `getSessionSettingsErrors().contains("Unknown setting")` is true.
- Edge case: a constructed `ExpectedErrors` seeded with the new list correctly classifies the string `"Code: 115. DB::Exception: Unknown setting nonexistent_flag."` as expected (via `errorIsExpected`).
- Edge case (negative assertion — load-bearing): unrelated errors such as `"Cannot convert string"`, `"Setting up the JOIN"`, and `"Value is out of range for type Int32"` are **not** absorbed by the new patterns. The negative assertion locks down the substring choice — if someone reintroduces the bare token `Setting`, this test fails.

**Verification:**
- The unit test lives at `test/sqlancer/clickhouse/ClickHouseSessionSettingsErrorsTest.java` and runs automatically under the `misc` CI job (workflow filter at `.github/workflows/main.yml:37`).
- Existing oracle tests (TLP, NoREC, CERT, PQS, CODDTest) continue to pass — the new helper is additive.

---

- [ ] **Unit 5: SEMR oracle + factory wiring**

**Goal:** Implement the SEMR oracle and register it as a selectable oracle factory constant.

**Requirements:** R1, R2, R5

**Dependencies:** Unit 1 (catalog), Unit 4 (expected-error patterns).

**Files:**
- Create: `src/sqlancer/clickhouse/oracle/semr/ClickHouseSEMROracle.java`
- Modify: `src/sqlancer/clickhouse/ClickHouseOracleFactory.java`

**Approach:**
- `ClickHouseSEMROracle` extends `ClickHouseTLPBase` (which gives it a free `ClickHouseSelect`, columns, joins, smoke check, and `errors` field seeded with the standard expression errors). The TLP base's `predicate`/`negatedPredicate`/`isNullPredicate` are constructed but unused by SEMR — initialization is cheap.
- **Cost note:** `super.check()` runs a smoke SELECT once (`src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java:66-67`), so each SEMR `check()` issues three SELECTs total: smoke + ON + OFF. The smoke catches generator hiccups before they triple-throw and is worth keeping. If profiling shows the 3x cost is meaningful, override the smoke; do not do this preemptively.
- In the constructor, after `super(state)`, call `ClickHouseErrors.addSessionSettingsErrors(errors)` so the oracle absorbs setting-validation failures alongside the standard catalog.
- `check()`:
  - Call `super.check()` to build the base `select` and run the smoke.
  - Set `select.setWhereClause(null)` explicitly. v1 SEMR compares the unfiltered base SELECT under ON vs OFF; predicate-induced query-shape variation is a candidate v2 enhancement.
  - Pick `(name, valueOff, valueOn)` via `ClickHouseSessionSettings.pickSemrCandidate(state.getRandomly())`. No operator override list in v1.
  - `String baseQuery = ClickHouseVisitor.asString(select);`
  - `String queryOff = baseQuery + " SETTINGS " + name + " = " + valueOff;`
  - `String queryOn  = baseQuery + " SETTINGS " + name + " = " + valueOn;`
  - Fetch both via `ComparatorHelper.getResultSetFirstColumnAsString(...)`.
  - Compare via `ComparatorHelper.assumeResultSetsAreEqual(resultOff, resultOn, queryOff, List.of(queryOn), state)`. The assertion message will name both query strings, including the setting and its value.
- Register a `SEMR` constant in `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` modeled on `CODDTest`:
  - `SEMR { @Override public TestOracle<ClickHouseGlobalState> create(...) { return new ClickHouseSEMROracle(state); } }`
- Naming convention check (`src/check_names.py` in CI) — file name `ClickHouseSEMROracle.java` and class name align with the `ClickHouse*Oracle` pattern; the new directory `oracle/semr/` mirrors `oracle/cert/`, `oracle/pqs/`, `oracle/coddtest/`.

**Patterns to follow:**
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPDistinctOracle.java` for the overall shape (~40 LOC, two stringified variants of one `select`, `assumeResultSetsAreEqual`).
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java:42` for the `originalQueryString += " SETTINGS ..."` precedent.
- `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java:37` for the constructor error-seeding pattern.

**Test scenarios:**
- Happy path (integration, in Unit 6): SEMR runs against the CI ClickHouse image with the default catalog and completes `--num-queries` without false-positive oracle failures. The integration test covers the full query-construction-and-compare path; no separate hermetic test for five-line string concatenation.
- Edge case: when SEMR is selected and one of the catalog's settings doesn't exist in the running ClickHouse server, the per-query SETTINGS suffix triggers an "Unknown setting" error and the iteration becomes `IgnoreMeException` rather than an oracle failure. This is the load-bearing reason Unit 4 must land first.
- Edge case: when the base SELECT references a column with formatting that the chosen setting would influence (e.g., a float-precision setting), the comparison still passes because the values on both sides go through the same `canonicalizeResultValue`. If a real-world catalog entry breaks this, the failure is the catalog's, not the oracle's.
- Error path: a syntax-illegal SELECT (generator hiccup) triggers an expected error on both sides, both sides become `IgnoreMeException`, the iteration is silently retried — no oracle failure.
- Integration: SEMR composes correctly with other oracles in `--oracle TLPDistinct --oracle SEMR` (the connection is shared; per-query SETTINGS suffix doesn't leak). Verify by checking that a TLPDistinct iteration immediately after a SEMR iteration sees the same baseline as without SEMR.
- Integration: SEMR composes correctly with `--oracle TLPHaving --oracle SEMR`. TLPHaving hardcodes `SETTINGS aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0` on its own queries; SEMR's catalog must not include those names. Test asserts: no false-positive AssertionError after `--num-queries` iterations. (Catalog exclusion is enforced by `ClickHouseSessionSettings` and verified by Unit 1's disjointness test, but the integration confirms behavior end-to-end.)

**Verification:**
- `ClickHouseSEMROracle` is selectable via `--oracle SEMR` end-to-end (verified in Unit 6).
- `src/check_names.py` passes (CI step in `.github/workflows/main.yml:43`).
- A diff of `ClickHouseOracleFactory.java` shows exactly one new enum constant and one new import; existing constants unchanged.

---

- [ ] **Unit 6: Integration tests**

**Goal:** Add end-to-end integration tests for SEMR and random-session-settings.

**Requirements:** R1, R3, R6, R8

**Dependencies:** Units 1-5.

**Files:**
- Modify: `test/sqlancer/dbms/TestClickHouse.java`

**Approach:**
- Add three new `@Test` methods to `TestClickHouse.java`, each guarded by `assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV))`:
  - `testClickHouseSEMR()` — `--oracle SEMR`, `--database-prefix T15_`, default catalog, asserts return 0.
  - `testClickHouseTLPDistinctWithRandomSessionSettings()` — `--oracle TLPDistinct --random-session-settings true --random-session-settings-budget 3`, `--database-prefix T16_`, asserts return 0. Picking TLPDistinct deliberately: it's the lightest existing differential oracle, fastest to converge.
  - `testClickHouseMutuallyExclusive()` — `--oracle SEMR --random-session-settings true`, `--database-prefix T17_`, asserts return is **non-zero** (this is the only `TestClickHouse` test that asserts failure; comment explaining the inversion).
- **No workflow changes required.** The clickhouse CI job at `.github/workflows/main.yml:64` already runs `TestClickHouse` and picks up new `@Test` methods automatically. The new pure-Java unit tests created in Units 1, 2, 4 (`test/sqlancer/clickhouse/ClickHouseSessionSettingsTest.java`, `ClickHouseOptionsParseTest.java`, `ClickHouseSessionSettingsErrorsTest.java`) are run automatically by the **misc** job at `.github/workflows/main.yml:37` via the filter `'-Dtest=!sqlancer.dbms.**,!sqlancer.qpg.**'`, which includes everything under `test/sqlancer/clickhouse/`. Do not add them to the clickhouse job's named `-Dtest=` list — that would cause them to run twice without environmental benefit.

**Patterns to follow:**
- `test/sqlancer/dbms/TestClickHouse.java` — existing `@Test` methods. Reuse `TestConfig.NUM_QUERIES`, default `--num-threads 5` for TLP variants and `--num-threads 1` for SEMR (more reproducible).

**Test scenarios:**
- Happy path: `testClickHouseSEMR` completes against `clickhouse/clickhouse-server:24.3.1.2672` in CI within the 60-second per-thread timeout. (Self-validating via `assertEquals(0, ...)`.)
- Happy path: `testClickHouseTLPDistinctWithRandomSessionSettings` completes; the per-database reproducer logs include `SET` lines and the `-- session-settings applied: M of N` summary.
- Error path: `testClickHouseMutuallyExclusive` returns non-zero — the inversion is the point. Asserts the error message is a single line (no per-thread stack-trace flood from the pre-flight gate in Unit 3).

**Verification:**
- A local `CLICKHOUSE_AVAILABLE=true mvn -Dtest=TestClickHouse test` run passes all three new methods plus the existing 14.
- Naming-convention test (`src/check_names.py`) passes against the new test classes.

## System-Wide Impact

- **Interaction graph:**
  - `ClickHouseProvider.createDatabase` is the only writer of the per-database connection; new SETs happen there before the connection is returned to `Main`. Every oracle reading `globalState.getConnection()` inherits the profile.
  - `CompositeTestOracle` (composed in `src/sqlancer/ProviderAdapter.java:50-107`) runs oracles in the order they appear in `--oracle`. Per-query SETTINGS suffixes (SEMR) and connection-level SETs (random-session-settings) are independent; only their interaction is forbidden by the mutual-exclusion pre-flight in `Main` (Unit 3).
  - **Other oracles that hardcode `SETTINGS` suffixes per query:** `ClickHouseTLPHavingOracle.java:42` and `ClickHouseTLPAggregateOracle.java:42` already pin `enable_optimize_predicate_expression=0` and `aggregate_functions_null_for_empty=1` on their own queries. SEMR must not vary those same setting names (catalog excludes them), or a `--oracle TLPHaving --oracle SEMR` run could produce confusing per-iteration suffix conflicts.
  - **Other oracles whose invariants depend on optimizer rewrites:** `ClickHouseCERTOracle.java:65-82` documents that its cardinality-monotonicity invariant depends on the HAVING / predicate pushdown rewrites. `ClickHouseCODDTestOracle` (constant-folding equivalence) depends on the optimizer recognizing pre-folded sub-expressions. Random-session-settings excludes optimizer-rewrite settings (R3) to protect these invariants.
  - The Java naming check (`src/check_names.py` in CI) walks `src/sqlancer/**` — the new `semr/` subdirectory and `ClickHouseSEMROracle` class name follow the existing convention.

- **Error propagation:**
  - SET execution failures inside `createDatabase` are caught (only for the expected-error patterns defined in Unit 4) and counted against the `attempted`/`accepted` summary. Unexpected `SQLException` rethrows and aborts the database — the summary log catches catalog-drift cases; unexpected exceptions remain fatal so genuine connectivity problems aren't masked.
  - SEMR per-query SETTINGS errors flow through `ComparatorHelper.getResultSetFirstColumnAsString` → `errorIsExpected` → `IgnoreMeException` exactly like every other oracle. No new error-propagation path.
  - The mutual-exclusion check raises `IllegalArgumentException` from `Main.executeMain` pre-flight (Unit 3); `executeMain` returns non-zero before any thread spawns. One error message, no per-thread reproducer artifacts.

- **State lifecycle risks:**
  - The chosen randomization profile is applied once per database and persists for the entire `--num-queries` budget. This is intentional — varying settings *within* a database would create a SEMR-style oracle inside every other oracle, which is not what users opted into.
  - **Session-persistence assumption:** the design assumes `SET` on the JDBC connection persists across subsequent queries on that same connection. The clickhouse-jdbc 0.9.6 v2 driver uses HTTP under the hood, which may treat each request independently without an explicit `session_id`. Verify before merging (see Deferred to Implementation). If sessions are not stable, add `&session_id=<UUID>&session_check=0` to the URL.
  - The post-CREATE connection is closed by SQLancer's normal teardown at end-of-database. No new cleanup is needed; `SET` is connection-scoped server-side.
  - SEMR's per-query SETTINGS suffix is scoped to one statement; nothing leaks to the next iteration.

- **API surface parity:**
  - The two new CLI flags follow the existing JCommander convention; help output (`Main.executeMain("--help")`) picks them up automatically with the `@Parameter(description=...)` text.
  - No other DBMS subsystem is touched. The fork is ClickHouse-only after commit `c7d98b6e`, so cross-DBMS parity is a non-issue.

- **Integration coverage:**
  - Unit-level catalog tests (Unit 1) prove the picker behaves; only end-to-end integration tests (Unit 6) prove SET-on-connect actually applies and TLP/NoREC still pass under randomized profiles. The integration tests are load-bearing.
  - The "SEMR composes with TLPDistinct" and "SEMR composes with TLPHaving" scenarios in Unit 5 are the cross-layer behaviors that unit tests cannot prove — the shared-connection semantics and the catalog-exclusion of TLPHaving's hardcoded settings both need real `CompositeTestOracle` runs.

- **Unchanged invariants:**
  - Existing `--oracle` values (`TLPWhere`, `TLPDistinct`, `TLPGroupBy`, `TLPAggregate`, `TLPHaving`, `NoREC`, `PQS`, `CERT`, `CODDTest`) keep their current semantics. None of their classes are modified; SEMR is purely additive.
  - The URL-parameter mechanism for `--analyzer` and `--test-lowcardinality-types` is unchanged. Those settings are explicitly denylisted from the randomization catalog.
  - The default-off invariant: `mvn test` with `CLICKHOUSE_AVAILABLE=true` and no new flags runs the existing 14 integration tests with the existing behavior, byte-for-byte.

## Risks & Dependencies

| Risk | Mitigation |
|------|------------|
| A setting marked SEMR-eligible is not actually result-preserving in 24.3.1, producing false-positive oracle failures. | Conservative initial catalog: only the user-supplied starter list is SEMR-eligible in v1; each entry has a one-line comment citing intent. False positives are triaged by removing the entry from `SEMR_SETTINGS`, not by patching the oracle. |
| Settings interactions (rare combinations) produce server-side errors that look like oracle failures. | Unit 4's expected-error substrings absorb the known classes (`Unknown setting`, `Setting value out of range`, `Cannot parse setting value`). Patterns are seed-conservative and explicitly avoid the bare token `Setting` to prevent absorbing unrelated bugs. |
| SEMR's chosen setting changes value formatting (e.g., float precision) but not semantics, producing string-comparison failures. | `ComparatorHelper.canonicalizeResultValue` already normalizes `-0.0`/`-0`. If a real false positive shows another pattern, the `ComparatorHelper.assumeResultSetsAreEqual` overload accepting a `UnaryOperator<String>` is the escape hatch — call it out as a deferred decision. |
| `allow_experimental_*` flags in the randomization catalog cause downstream oracle failures (the feature itself produces a wrong result, unrelated to settings). | The `MANAGED_BY_OPTIONS` denylist and per-entry curation comments keep the v1 experimental set small. CI runs on a fixed pinned image (24.3.1.2672), so the catalog can be validated once and stays stable. |
| **CERT/CODDTest invariants break under randomized optimizer-rewrite settings.** Randomizing `enable_optimize_predicate_expression`, `query_plan_filter_push_down`, `optimize_move_to_prewhere` would invalidate the pushdown invariant CERT assumes (`src/sqlancer/clickhouse/oracle/cert/ClickHouseCERTOracle.java:65-82`) and the constant-folding invariant CODDTest assumes. | These settings are explicitly **excluded** from `RANDOM_SESSION_SETTINGS` (R3 scope boundary, enforced by `ClickHouseSessionSettings` disjointness test in Unit 1). They remain SEMR-eligible — SEMR is the oracle designed to surface their result-preserving claims. |
| **SEMR composes with TLPHaving/TLPAggregate per-query SETTINGS suffixes.** Those oracles hardcode `enable_optimize_predicate_expression=0` and `aggregate_functions_null_for_empty=1` on their own SELECTs; if SEMR varied the same names, attribution would be ambiguous. | `SEMR_SETTINGS` excludes the hardcoded names. Unit 1's disjointness test asserts this; Unit 5's `--oracle TLPHaving --oracle SEMR` integration test verifies end-to-end behavior. |
| Reproducibility regression: a failing oracle now requires the full SET profile to replay. | The reproducer log already captures DROP/CREATE/USE; adding `logStatement("SET k=v")` per chosen entry plus the `-- session-settings applied: M of N` summary keeps replay self-contained. Verified in Unit 3's test scenarios. |
| Mutual-exclusion check produces per-thread stack-trace noise and unwanted reproducer artifacts. | Check is at `Main.executeMain` pre-flight (Unit 3), **before** the thread pool spawns. Single error message, no artifacts. The `testClickHouseMutuallyExclusive` integration test in Unit 6 is the regression guard. |
| **Silent SET failures hide a stale catalog.** If the JDBC v2 driver does not maintain a stable server-side session without `session_id`, every `SET` succeeds on its own HTTP request but the next query lands on a fresh session — the entire randomization layer becomes a silent no-op. | Verify before merging (see Deferred to Implementation). The `-- session-settings applied: M of N` summary partially helps (if `M = 0` always, something is wrong) but a positive verification via `SELECT value FROM system.settings WHERE name = ?` after a SET is the durable answer. If sessions are unstable, add `&session_id=<UUID>&session_check=0` to the URL. |
| Catalog drift: ClickHouse adds/removes/renames a setting in a future image bump, silently breaking SEMR or randomization. | v1 targets the CI-pinned 24.3.1.2672 image. Strategy for cross-version handling deferred to implementation (see Deferred to Implementation). The `-- session-settings applied: M of N` summary and the `Unknown setting` expected-error pattern give observable signal when drift occurs. |

## Alternative Approaches Considered

The plan deliberately chose SEMR + per-session randomization over three alternative methodologies. None of them is rejected forever; this section names them so the v1 yield evaluation can compare honestly.

- **Query-plan equivalence (EXPLAIN-based).** Run `EXPLAIN PLAN` before and after a candidate optimizer rewrite and assert plan-structure correspondence. Catches plan regressions that SEMR misses when result multisets happen to coincide on small generated data. Rejected for v1 because plan-shape equivalence rules are themselves a research problem: ClickHouse plan strings are not normalized, change format across versions, and "equivalent plans" is fuzzy. SEMR's multiset comparison is unambiguous. Reconsider in v2 if SEMR yields plateau.
- **Subquery / projection isomorphism.** Wrap a subquery in semantics-preserving transformations (`UNION ALL` with empty set, `JOIN` with single-row table, identity projection) and assert the wrapped query returns the same multiset as the bare query. No setting catalog, no curation surface. Rejected for v1 because the rewrite family is narrow — it tests the optimizer's *constant-folding* path, which CODDTest already covers more directly. Reconsider if a non-CODDTest isomorphism family is identified.
- **Cross-version differential.** Compare 24.3 against 24.8 / 25.x on the same query; mismatches indicate regressions. This is closer to the operator's actual goal (find bugs in newer ClickHouse) than SEMR's intra-version cross-flag comparison. Rejected for v1 because it requires running two ClickHouse instances in CI and a stable shared database snapshot; CI cost roughly doubles. A future plan can layer this on top of SEMR.
- **Per-setting tiny oracles instead of a catalog.** Write ~20 LOC per known result-preserving setting, modeled on `ClickHouseTLPHavingOracle.java:42`'s hardcoded suffix pattern. No `ClickHouseSessionSettings` class, no flags, no curation discipline; each oracle deletes cleanly when it stops paying off. Rejected for v1 because the user explicitly requested a generic SEMR oracle and a randomization layer; the per-setting alternative trades generality for simplicity. Worth keeping in mind: if the curated `SEMR_SETTINGS` list never grows past ~5 entries, the per-setting alternative is strictly simpler and the plan should retract the catalog abstraction. The yield evaluation at the end of v1 (see Success Metrics) is the trigger.

## Success Metrics

The v1 evaluation runs for one full month of CI green after merge. Success criteria, ordered by load-bearing weight:

- **At least 2 distinct SEMR-eligible settings each surface ≥1 previously-undiscovered wrong-result instance** against ClickHouse 24.3.1.2672 — anything reproducible and reportable upstream. The "≥2 distinct settings" bar is what justifies the generic catalog abstraction: if only one setting ever finds bugs, the per-setting tiny-oracle alternative (Alternative Approaches above) is strictly simpler and v1's generality is unearned.
- **No false-positive rate above ~5% of SEMR iterations** during catalog soak. False positives are triaged by removing the offending entry from `SEMR_SETTINGS`. If the rate stays above 5% after two catalog-trim passes, the catalog quality bar is too low and v1 is failing.
- **`session-settings applied: M of N` summary shows `M / N >= 0.9`** in steady-state runs — i.e., 90%+ of attempted SETs are accepted. A persistently low ratio indicates catalog drift or the JDBC-session-persistence problem (see Deferred to Implementation).
- **No regressions in the existing 14 integration tests** under `TestClickHouse.java` after merge — the default-off invariant (R8) holds.

**Kill triggers** (any of these schedules a follow-up plan within two weeks):
- Zero novel SEMR findings after one month → catalog is too narrow or generator coverage doesn't reach the rewrite paths. Switch to cross-version differential (Alternative Approaches).
- Findings come from only one setting after one month, OR `SEMR_SETTINGS` has not grown past 5 entries → generality is unearned. Retract the catalog abstraction in favor of per-setting tiny oracles (Alternative Approaches), keeping the per-session randomization layer as a separable feature.
- False-positive rate above 5% after two catalog-trim passes → SEMR-eligibility tagging is unreliable in practice. Either tighten the eligibility bar (require an upstream citation for each entry) or retract the methodology.

## Phased Delivery

The user confirmed during planning that SEMR and per-session randomization land together. The phasing below is for *implementation order within v1*, not for splitting the feature across releases. Phase boundaries are dependency gates, not separate ship points.

### Phase 1: Foundation (Units 1, 2, 4)
- Catalog module (`ClickHouseSessionSettings`), CLI flags, and the expected-error patterns.
- Lands first because everything else depends on the catalog and on the error-absorption seam. No behavior changes until Phase 2 wires it in.

### Phase 2: Wiring (Units 3, 5)
- Provider-side SET-on-connect + mutual-exclusion pre-flight in `Main.executeMain`; SEMR oracle and factory registration.
- Lands after the JDBC session-persistence verification (see Deferred to Implementation). If verification reveals the v2 driver does not persist sessions without `session_id`, Phase 2 also adds the URL parameter — this is a small extension, not a redesign.

### Phase 3: Integration tests (Unit 6)
- The three new `@Test` methods in `TestClickHouse.java`.
- Trivially small but lands last because it exercises Phases 1 and 2 end-to-end. Once green, the feature is shippable.

After v1 ships, the Success Metrics evaluation drives whether to keep, simplify, or replace the methodology (see Alternative Approaches Considered).

## Documentation / Operational Notes

- Update `CONTRIBUTING.md` only if it currently documents the oracle list — a quick check is needed; if it does, append SEMR with a one-line description.
- **Catalog maintenance commitment.** The hand-curated catalog has three drift surfaces — settings can be renamed/removed upstream, other oracles can introduce hardcoded `SETTINGS` suffixes that collide with `SEMR_SETTINGS` or `RANDOM_SESSION_SETTINGS`, and new `ClickHouseOptions` flags can subsume entries in `MANAGED_BY_OPTIONS`. Two cheap maintenance mechanisms to land in v1, choose at least one:
  - **Mechanical drift detection (preferred):** add a pure-Java test that scans `src/sqlancer/clickhouse/oracle/**/*.java` for occurrences of `" SETTINGS "` and asserts every setting name it finds is either in `MANAGED_BY_OPTIONS` or is itself the entire SEMR catalog (i.e., set difference is empty). New oracles introducing hardcoded settings break this test, forcing the author to update the catalog rather than silently colliding.
  - **Calendar-bound revalidation:** when the CI ClickHouse image bumps past 24.3.1.2672, a release-checklist item runs `SELECT name FROM system.settings` against the new image and diffs against `SEMR_SETTINGS ∪ RANDOM_SESSION_SETTINGS ∪ MANAGED_BY_OPTIONS`; entries missing from `system.settings` are removed or quarantined.
  - Without one of these, the `M of N` summary log is the only drift signal and it's a lagging indicator.
- The `README.md` mentions ClickHouse usage; add a sentence under the ClickHouse section noting the new flags and SEMR oracle. Defer wording to implementation.
- No new monitoring or rollout concerns — this is a research/QA tool, not a production system.
- The first 24-hour CI run after merge is the de-facto soak test for catalog correctness. Plan to triage any new false-positive patterns into `ClickHouseErrors` (or into removing catalog entries) within that window.

## Sources & References

- Related code:
  - `src/sqlancer/clickhouse/ClickHouseProvider.java` (connection seam, lines 109-154)
  - `src/sqlancer/clickhouse/ClickHouseOptions.java` (JCommander pattern)
  - `src/sqlancer/clickhouse/ClickHouseOracleFactory.java` (oracle registration)
  - `src/sqlancer/clickhouse/ClickHouseErrors.java` (expected-error catalog)
  - `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPDistinctOracle.java` (canonical short oracle)
  - `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPHavingOracle.java` (per-query SETTINGS suffix precedent)
  - `src/sqlancer/clickhouse/oracle/tlp/ClickHouseTLPBase.java` (oracle base class with error seeding)
  - `src/sqlancer/ComparatorHelper.java` (multiset comparison utilities)
  - `src/sqlancer/common/query/ExpectedErrors.java` (regex/substring matching infra)
  - `src/sqlancer/Main.java:440-470` (per-thread provider/connection wiring)
  - `src/sqlancer/ProviderAdapter.java:50-107` (composite oracle composition)
  - `test/sqlancer/dbms/TestClickHouse.java` (integration test convention)
  - `.github/workflows/main.yml` (CI pinned image, `-Dtest=` list)
- Upstream ClickHouse reference (for catalog curation, implementation-time only): `src/Core/Settings.cpp` in the ClickHouse repo, validated against the `clickhouse/clickhouse-server:24.3.1.2672` image used in CI.
- Feature description (this plan's origin): `/compound-engineering:ce-plan` invocation, 2026-05-17, "Settings-equivalence oracle (SEMR) + session-level setting randomization", confidence 85%, complexity low.
