package sqlancer.clickhouse;

import java.util.List;

import sqlancer.common.query.ExpectedErrors;

/**
 * Expected-error catalogue index (per-workstream blocks, plan-aligned).
 *
 * <pre>
 *   getExpectedExpressionErrors  -- always-on baseline (parser-side gaps, numeric-domain rejections,
 *                                   MATERIALIZED type-mismatch, CANNOT_PARSE_INPUT)
 *   getSessionSettingsErrors     -- SET / SETTINGS clause unknown-name and bad-value rejections
 *   getSetOpErrors               -- INTERSECT / EXCEPT column-count + type mismatches
 *   getCombinatorErrors          -- aggregate-combinator chain rejections (-If, -OrNull, etc.)
 *   getArrayJoinErrors           -- ARRAY JOIN argument-type rejections
 *   getStatisticsErrors          -- ALTER STATISTICS unknown-kind and experimental-flag-off
 *   getAlterErrors               -- ALTER TABLE column-level rejections (workstream 8)
 *   getMutationErrors            -- ALTER UPDATE/DELETE + lightweight DELETE failure modes (W9)
 *   getEnumErrors                -- Enum cross-type CAST rejections (workstream 2)
 *   getTypeExpansionErrors       -- composite / geo / nested / JSON-family / AggregateFunction
 *                                   cross-type rejections (workstreams 2/3/4/5/6/7)
 * </pre>
 *
 * <p>
 * Workstream-1 risk note: globally tolerating a substring can mask a real bug for an unrelated oracle. Per-oracle
 * scoped allowlists are tracked separately in the triage-automation plan; until that lands, additions to this catalogue
 * should err on the side of multi-word patterns so they don't absorb unrelated messages.
 */
public final class ClickHouseErrors {

    private ClickHouseErrors() {
    }

    public static List<String> getExpectedExpressionErrors() {
        return List.of("Argument at index 1 for function like must be constant",
                "Argument at index 1 for function notLike must be constant",
                "Attempt to read after eof: while converting", "Bad get: has Int64, requested UInt64",
                "Cannot convert string", "Cannot insert NULL value into a column of type",
                "Cannot parse Int32 from String, because value is too short", "Cannot parse NaN.: while converting", // https://github.com/ClickHouse/ClickHouse/issues/22710
                "Cannot parse infinity.", "Cannot parse number with a sign character but without any numeric character",
                "Cannot parse number with multiple sign (+/-) characters or intermediate sign character",
                "Cannot parse string", "Cannot read floating point value",
                "Cyclic aliases: default expression and column type are incompatible", "Directory for table data",
                "Directory not empty", "Expected one of: compound identifier, identifier, list of elements (version", // VALUES
                                                                                                                      // ()
                "Function 'like' doesn't support search with non-constant needles in constant haystack", "Illegal type",
                "Illegal value (aggregate function) for positional argument in GROUP BY",
                // ClickHouse 26+ new-analyzer variant of the same generator-induced issue:
                // a positional GROUP BY pointer (e.g., GROUP BY 1) resolving to an aggregate
                // SELECT-list column. Old analyzer error above, new analyzer error here.
                "is found in GROUP BY in query", "(ILLEGAL_AGGREGATION)",
                "Invalid escape sequence at the end of LIKE pattern", "Invalid type for filter in", "Memory limit",
                "OptimizedRegularExpression: cannot compile re2", "Partition key cannot contain constants",
                "Positional argument out of bounds", "Sampling expression must be present in the primary key",
                "Sorting key cannot contain constants", "There is no supertype for types", "argument of function",
                "but its arguments considered equal according to constraints", "does not return a value of type UInt8",
                "doesn't exist", // TODO: consecutive test runs can lead to dropped database
                "in block. There are only columns:", // https://github.com/ClickHouse/ClickHouse/issues/42399
                "invalid character class range", "invalid escape sequence",
                "is not under aggregate function and not in GROUP BY", "is not under aggregate function",
                "is violated at row 1. Expression:", // TODO: check constraint on table creation
                "is violated, because it is a constant expression returning 0. It is most likely an error in table definition",
                // CAVEAT: these tolerate generator-induced column misses (JOIN/alias gaps where the
                // analyzer can't resolve a referenced column -- a SQLancer-side gap, not a CH bug).
                // They ALSO match the lightweight-update patch-part read crash (CH support #7912 ->
                // upstream #98227: "Not found column _block_number in block ... There are only
                // columns: ... (NOT_FOUND_COLUMN_IN_BLOCK)") and the sibling _part_offset
                // LOGICAL_ERROR. That crash is a REAL bug, so it must NOT be swallowed here -- which
                // is why ClickHousePatchPartConsistencyOracle deliberately omits this whole list
                // (getExpectedExpressionErrors) and tolerates only session/mutation/UNKNOWN_TABLE,
                // letting a regression surface there. Do not add these patterns to that oracle, and
                // do not widen this list to a bare "_block_number"/"_part_offset" substring.
                "there are only columns", "there are columns", "(NOT_FOUND_COLUMN_IN_BLOCK)", "Missing columns",
                "Ambiguous column", "Must be one unsigned integer type. (ILLEGAL_TYPE_OF_COLUMN_FOR_FILTER)",
                "Floating point partition key is not supported", "Cannot get JOIN keys from JOIN ON section",
                "ILLEGAL_DIVISION", "DECIMAL_OVERFLOW",
                "Cannot convert out of range floating point value to integer type",
                "Unexpected inf or nan to integer conversion", "No such name in Block::erase", // https://github.com/ClickHouse/ClickHouse/issues/42769
                "EMPTY_LIST_OF_COLUMNS_QUERIED", // https://github.com/ClickHouse/ClickHouse/issues/43003
                "EMPTY_LIST_OF_COLUMNS_PASSED", // https://github.com/ClickHouse/ClickHouse/pull/81835
                "cannot get JOIN keys. (INVALID_JOIN_ON_EXPRESSION)", "AMBIGUOUS_IDENTIFIER", "CYCLIC_ALIASES",
                "Positional argument numeric constant expression is not representable as",
                "Positional argument must be constant with numeric type", " is out of bounds. Expected in range",
                "with constants is not supported. (INVALID_JOIN_ON_EXPRESSION)",
                "Cannot get JOIN keys from JOIN ON section", "Unexpected inf or nan to integer conversion",
                "Cannot determine join keys in", "Unsigned type must not contain",
                "Unexpected inf or nan to integer conversion",

                // The way we generate JOINs we can have ambiguous left table column without
                // alias
                // We may not count it as an issue, but it makes no sense to add more complex
                // AST generation logic
                "MULTIPLE_EXPRESSIONS_FOR_ALIAS", "AMBIGUOUS_IDENTIFIER", // https://github.com/ClickHouse/ClickHouse/issues/45389
                "AMBIGUOUS_COLUMN_NAME", // same https://github.com/ClickHouse/ClickHouse/issues/45389
                "No equality condition found in JOIN ON expression", "Cannot parse number with multiple sign",

                // JDBC driver may fail to decompress error responses under certain conditions
                "Magic is not correct",
                // clickhouse-jdbc 0.9.8 + Apache HC chunked-decoder intermittently fail with
                // these on responses ≥ ~100 KB under concurrent thread load. Compression is
                // already disabled via compress=false on the URL; the underlying chunked-transfer
                // corruption remains. Absorb so a transport-layer flake doesn't poison the
                // oracle. Observed 12 + 2 times in the 2026-05-19 48-min run, in stack chains
                // wrapped at the JDBC layer as `SQLException: Failed to read value for column X`
                // → `ClientException: Failed to read value for column X` → either of these:
                "MalformedChunkCodingException", "CRLF expected at end of chunk", "TruncatedChunkException",
                "Truncated chunk (expected size:",
                // Same family, different message — fires when the server closes the chunked
                // response stream before writing the terminating "0\r\n\r\n" closing chunk
                // (seen in post-fix run-163913: 2 occurrences in 60 s).
                "ConnectionClosedException", "Premature end of chunk coded message body",
                // Same family: under sustained concurrent load the JDBC client's per-request HTTP
                // socket occasionally trips its read timeout before the response completes. The
                // server-side cap is max_execution_time=30 (set on the URL), so this should be
                // rare, but it can still happen if the response body itself is slow to drain.
                "SocketTimeoutException", "Read timed out", "Query request failed (attempt:", "DataTransferException",
                // PQS pivot rows containing legitimate UInt64 values above Long.MAX_VALUE. The
                // sqlancer-side ClickHouseSchema.getConstant currently widens via ResultSet.getLong
                // and overflows. Mark as expected until that path is widened to BigInteger.
                "cannot be presented as long",

                // v1 type-system foundation: Nullable / LowCardinality activation patterns. These
                // are added defensively from common ClickHouse error families; the full triage is
                // recorded as a follow-up issue after the regression run.
                "ILLEGAL_TYPE_OF_ARGUMENT", // Nullable arithmetic, mixed wrapper operations
                "Conversion from LowCardinality", "Conversion to LowCardinality", "Nested type", // composite-inside-wrapper
                                                                                                 // rejections leaking
                                                                                                 // through DEFAULT
                                                                                                 // clauses
                "type cannot be inside Nullable type", "type cannot be inside LowCardinality",
                "Cannot read floating point value", // float-inside-LowCardinality DEFAULT round-trip
                "NULL value is not allowed",
                // Scalar subqueries emitted into fetch-columns (e.g. correlated-looking
                // `(SELECT c0 FROM t ORDER BY c0 DESC LIMIT 1)`) can return an empty result; when
                // the subquery's column type cannot be made Nullable (notably LowCardinality(T)),
                // CH raises "Scalar subquery returned empty result of type ... which cannot be
                // Nullable", and "returned more than one row" for the multi-row case. Both are
                // structural artifacts of the generated subquery, not wrong-results.
                "(INCORRECT_RESULT_OF_SCALAR_SUBQUERY)",
                // Fired when the JDBC URL setting hasn't propagated (e.g. test fixtures opening their
                // own connection). The runtime CREATE TABLE setting in ClickHouseProvider normally
                // makes this unreachable.
                "SUSPICIOUS_TYPE_FOR_LOW_CARDINALITY",
                // Fired when an ORDER BY / PARTITION BY / SAMPLE BY expression references a Nullable
                // column without `allow_nullable_key=1`. ClickHouseTableGenerator now sets this in
                // the MergeTree SETTINGS clause, but the catalog entry stays as a defense net.
                "Partition key contains nullable columns", "Sorting key contains nullable columns",
                "allow_nullable_key",
                // INSERTs into a column with a MATERIALIZED clause whose dependency column wasn't
                // provided -- ClickHouse plugs NULL and the cast to a non-Nullable target fails.
                // Becomes more frequent once the v1 type flags emit mixed Nullable/non-Nullable
                // columns with INSERT-projection MATERIALIZED clauses.
                "Cannot convert NULL value to non-Nullable type", "CANNOT_INSERT_NULL_IN_ORDINARY_COLUMN",
                // max_execution_time=120 is set on the JDBC URL in ClickHouseProvider to cap server-side
                // query execution; long-running random queries (heavy JOINs, large aggregations) hit this
                // cap and ClickHouse returns TIMEOUT_EXCEEDED. The multi-word "Timeout exceeded: elapsed"
                // substring is specific enough to avoid masking unrelated "timeout" errors.
                "Timeout exceeded: elapsed", "(TIMEOUT_EXCEEDED)",
                // Type-system v2 engine-arg picker: ReplacingMergeTree(ver) and SummingMergeTree(col)
                // arguments must not overlap the primary key / partition key. Since ORDER BY and
                // PARTITION BY are generated AFTER the engine args, we cannot guarantee absence of
                // overlap at emission time; ClickHouse rejects the overlap with BAD_ARGUMENTS and
                // the catalog absorbs it. Specific multi-word substrings, no bare "BAD_ARGUMENTS".
                "listed both in columns to sum and in partition key",
                "listed both in columns to sum and in sorting key", "Version column", "is in primary key",
                // Type-system v2 DateTime/Date round-trip: random temporal strings occasionally exceed
                // the column's valid range (Date: 1970..2149, Date32: 1900..2299) and ClickHouse
                // rejects the cast.
                "Cannot parse Date", "CANNOT_PARSE_DATE", "Cannot parse DateTime", "CANNOT_PARSE_DATETIME",
                // Unit 1.2 emits IPv4/IPv6/UUID columns + literals. The generic expression generator
                // composes type-incompatible comparisons like `CAST('98.20.60.72','IPv4') < '1103202675'`
                // -- CH tries to coerce the RHS string into the IP/UUID domain and fails to parse it
                // (CANNOT_PARSE_IPV4/IPV6, code 675/676). Same sqlancer-side typing gap as the
                // Int/Date/Bool cases above; the whole subexpression is invalid by CH's rules, not a
                // bug to file.
                "Cannot parse IPv4", "Cannot parse IPv6", "CANNOT_PARSE_IPV4", "CANNOT_PARSE_IPV6", "Cannot parse uuid",
                "Cannot parse UUID", "CANNOT_PARSE_UUID",
                // Generator may compose `'' < (true)` or similar `String <op> Bool` comparisons.
                // ClickHouse rejects with `CANNOT_PARSE_BOOL: Expected boolean value but get EOF`
                // (code 467). The whole comparison subexpression is invalid SQL by CH's typing
                // rules, not a bug to file. Two-word substring kept narrow to avoid masking
                // unrelated boolean-handling regressions.
                "CANNOT_PARSE_BOOL", "Expected boolean value but get",
                // Generator may emit `WHERE <numeric-expr>` (e.g. `WHERE abs(sin(c1))`) which CH
                // rightly rejects since WHERE requires UInt8/Bool. The existing
                // "Must be one unsigned integer type" catches the SAMPLE-BY variant but the
                // WHERE-filter variant uses a different message; the bare code label covers both.
                "(ILLEGAL_TYPE_OF_COLUMN_FOR_FILTER)",
                // Decimal cast / arithmetic overflow once the picker emits Decimal(P, S) columns.
                "DECIMAL_OVERFLOW", "Cannot convert: Float64 to Decimal", "Too many digits", "ARGUMENT_OUT_OF_BOUND",
                // FixedString CAST when the literal length doesn't match. The emitter pads / truncates
                // to N but DEFAULT clauses generated from a longer source string can still trip this.
                // CH 26.5 surfaces oversized literals as `TOO_LARGE_STRING_SIZE` (Code 131), which
                // doesn't contain the upper-case `FIXED_STRING` substring -- without the explicit
                // code it escapes the generator's expected-errors filter and tears down the thread.
                // Observed twice in the 2026-05-19 180s baseline, costing 2/6 threads (~33% capacity).
                "String literal", "FIXED_STRING", "TOO_LARGE_STRING_SIZE",
                // Server-side result-row cap. ClickHouseProvider pins max_result_rows=1_000_000 +
                // result_overflow_mode='throw' on every connection to keep ComparatorHelper from
                // OOMing the JVM on cartesian / many-to-many oracle shapes. Tripping the cap is
                // a "this iteration is uninformative" signal, not a wrong-result bug.
                "Limit for result exceeded", "TOO_MANY_ROWS_OR_BYTES",
                // CH 26.6 added bloom_filter type validation that rejects Decimal columns. The
                // table generator emits Decimal + bloom_filter combinations at low rate; absorb
                // the narrow message ("of bloom filter index") so a single generator-vs-catalog
                // drift doesn't kill a worker. First observed 2026-05-23 in the dev-VM 3h run
                // (database15.log of attempt-1). The substring is multi-word per the convention.
                "of bloom filter index",
                // Server-side total memory cap (--max_server_memory_usage / cgroup RSS) trips
                // mid-query under sustained 6-12 thread load on a memory-constrained CH. Not a
                // wrong-result bug; this iteration is uninformative. Observed 34 of 43 times in
                // the 2026-05-23 dev-VM 3h run when CH was capped at -m=12g.
                "(MEMORY_LIMIT_EXCEEDED)", "memory limit exceeded",
                // Generator emits short string literals (e.g. 'i', 'N-<.', 'i%( |b.}') that the
                // server tries to parse as Float64 inside expressions like (s) > 0.43 or
                // notEquals(avgOrNull(...), 'literal'). 'i'/'N' are the first chars of 'inf'/'nan'
                // and CH's float parser dies mid-token with CANNOT_PARSE_INPUT_ASSERTION_FAILED.
                // Generator-side gap, not a CH bug. Observed 3 of 43 times in the same run.
                "Cannot parse infinity", "Cannot parse NaN", "CANNOT_PARSE_INPUT_ASSERTION_FAILED",
                // MATERIALIZED expression type-mismatch with declared column type. The expression
                // generator emits expressions over other columns without checking the target
                // column's type, so e.g. `c1 FixedString(1) MATERIALIZED (c2)` where c2 is UInt32
                // is syntactically valid but rejected because CAST AS FixedString only accepts
                // String/FixedString sources. Pre-existing generator gap.
                "CAST AS FixedString is only implemented", "default expression and column type are incompatible",
                // Unit 3.2: SimpleAggregateFunction(func, T) requires T to match the aggregate's
                // result type. The picker now emits only valid (func, T) pairs, but keep this narrow
                // substring as a defense so a future func/type addition that violates the rule is
                // absorbed at CREATE time rather than tearing down a worker.
                "Incompatible data types between aggregate function", "NOT_IMPLEMENTED");
    }

    public static void addExpectedExpressionErrors(ExpectedErrors errors) {
        errors.addAll(getExpectedExpressionErrors());
        // Statistics generator emission may run against servers where the experimental flag is off
        // or run into kind/type rejections; pre-load the substrings so every oracle's expected-
        // errors set absorbs them. Same rationale as ARRAY JOIN / combinator entries above.
        errors.addAll(getStatisticsErrors());
        // Enum8/Enum16 column emission lands at ~1% in pickScalarType. Cross-type expressions
        // (enum_col + 1, enum_col * X) are emitted blindly by existing oracles and rejected by
        // CH; absorb the failure family.
        errors.addAll(getEnumErrors());
        // Type-system expansion (workstreams 2/3/4/5/6/7): composite types, geo types, nested,
        // JSON/Variant/Dynamic, Interval, AggregateFunction. Each adds a column shape that
        // existing oracles emit cross-type expressions over; the resulting rejections are
        // absorbed here.
        errors.addAll(getTypeExpansionErrors());
    }

    // Substring patterns for setting-validation errors raised either by SEMR's per-query
    // SETTINGS suffix or by random-session-settings SET-on-connect. The patterns are deliberately
    // multi-word to avoid masking unrelated bugs: a bare "Setting" token would match many
    // unrelated ClickHouse messages (read-only-setting rejections, suggestion lines, echoed
    // SETTINGS clauses in error context) and would silently absorb real findings.
    public static List<String> getSessionSettingsErrors() {
        return List.of("Unknown setting", // catalog drift: name not present in this version
                "is neither a builtin setting nor a custom setting", // same, alt message
                "Cannot parse setting value", // candidate value rejected as malformed
                "Setting value out of range", // multi-word form; not the bare "out of range"
                "UNKNOWN_SETTING"); // ClickHouse error code label
    }

    public static void addSessionSettingsErrors(ExpectedErrors errors) {
        errors.addAll(getSessionSettingsErrors());
    }

    // Substring patterns specific to set-operation queries (UNION ALL / UNION DISTINCT / INTERSECT / EXCEPT).
    // Multi-word per the institutional convention: a bare "columns" or "type" would mask far too many
    // unrelated errors. Refined empirically; the startup-probe path catches UNKNOWN_SETTING separately,
    // which is intentionally NOT in this list so setting-name drift remains visible to future audits.
    public static List<String> getSetOpErrors() {
        return List.of("Number of columns doesn't match", "Cannot find common type for tuple elements",
                "INCOMPATIBLE_COLUMNS", "Type mismatch in IN or VALUES section",
                "Column number mismatch in subqueries of intersect/except");
    }

    public static void addSetOpErrors(ExpectedErrors errors) {
        errors.addAll(getSetOpErrors());
    }

    // Substring patterns specific to aggregate-combinator emission. ClickHouse rejects ill-typed
    // combinator chains with messages from this family; the empirical-discovery convention keeps
    // entries multi-word so they don't absorb unrelated "function" or "aggregate" errors.
    public static List<String> getCombinatorErrors() {
        return List.of("Unknown aggregate function", "NUMBER_OF_ARGUMENTS_DOESNT_MATCH",
                "Combinator is only applicable for aggregate function", "is only applicable for aggregate functions",
                "Aggregate function is not implemented for", "Cannot apply combinator", "AGGREGATE_FUNCTION_THROW",
                "Nested type for combinator", "Illegal type for argument", "Illegal types of arguments");
    }

    public static void addCombinatorErrors(ExpectedErrors errors) {
        errors.addAll(getCombinatorErrors());
    }

    // Substring patterns for ARRAY JOIN. The structural plumbing in this PR does not yet emit
    // ARRAY JOIN -- these substrings exist for the future activation when Array column generation
    // lands. Kept here so the catalog grows additively rather than in a future surprise change.
    public static List<String> getArrayJoinErrors() {
        return List.of("Cannot ARRAY JOIN", "ARRAY JOIN requires array argument",
                "ILLEGAL_TYPE_OF_ARGUMENT_FOR_ARRAY_JOIN");
    }

    public static void addArrayJoinErrors(ExpectedErrors errors) {
        errors.addAll(getArrayJoinErrors());
    }

    // Substring patterns for the statistics subsystem -- young (24.5+) and gated behind several
    // experimental flags. The generator emits inline STATISTICS(...) on columns at low probability;
    // if the server hasn't enabled `allow_experimental_statistics` or rejects the kind for the
    // column type, these messages absorb the noise. Workstream 11 of the coverage expansion plan.
    public static List<String> getStatisticsErrors() {
        return List.of("Set `allow_experimental_statistics`", "allow_experimental_statistics is set to 0",
                "Statistics is not supported", "Unknown statistic kind", "Statistics of kind",
                "Unknown statistics type", // CH HEAD form for unknown kind (note 'statistics' plural)
                "STATISTICS_NOT_IMPLEMENTED", "Cannot create statistics", "SUPPORT_IS_DISABLED");
    }

    public static void addStatisticsErrors(ExpectedErrors errors) {
        errors.addAll(getStatisticsErrors());
    }

    // Substring patterns for ALTER TABLE failures the generator's ALTER COLUMN emission may
    // legitimately produce: dropping the only column, dropping a primary-key column, narrowing
    // MODIFY incompatible with existing data, RENAME collisions, COMMENT on non-existent columns,
    // and mutation-side conflicts when the table has in-flight background work.
    // Workstream 8 of the coverage expansion plan.
    public static List<String> getAlterErrors() {
        return List.of("BAD_ARGUMENTS", "Cannot drop column", "Cannot rename column", "Cannot remove column",
                "Column with name", "is part of primary key", "Cannot alter column", "ALTER of key column",
                "Algorithm not implemented", "CANNOT_DROP_INDEX", "ALTER_OF_COLUMN_IS_FORBIDDEN", "DUPLICATE_COLUMN",
                "NO_SUCH_COLUMN_IN_TABLE", "UNFINISHED", "Cannot convert column", "is currently locked for",
                "EMPTY_LIST_OF_COLUMNS_QUERIED",
                // Unit 2.2: ADD/MATERIALIZE PROJECTION rejections -- duplicate name, unsupported
                // engine (views / non-MergeTree), or a projection definition the analyzer refuses.
                "Projection with name", "NO_SUCH_PROJECTION_IN_TABLE", "ILLEGAL_PROJECTION",
                "Projection is fully supported", "projection", "Cannot add projection");
    }

    public static void addAlterErrors(ExpectedErrors errors) {
        errors.addAll(getAlterErrors());
    }

    // Substring patterns for the mutation subsystem. Background ALTER UPDATE/DELETE entries can
    // pile up in system.mutations; a slow merge thread surfaces as TIMEOUT_EXCEEDED on the
    // barrier or as half-applied snapshots in subsequent SELECTs. Lightweight DELETE FROM is
    // synchronous but rejects empty-table operations on some CH builds with
    // ATTEMPT_TO_READ_AFTER_EOF.
    // Workstream 9 of the coverage expansion plan.
    public static List<String> getMutationErrors() {
        return List.of("TIMEOUT_EXCEEDED", "Cannot UPDATE key column", "Cannot DELETE", "Mutation cannot be executed",
                "Mutations are not supported by", "UNFINISHED_MUTATION", "Cannot read from", "Lightweight DELETE",
                "_row_exists", "Background mutation", "ATTEMPT_TO_READ_AFTER_EOF", "Cannot find column",
                // A lightweight DELETE on a table that carries projections is rejected (Code 344)
                // under the default lightweight_mutation_projection_mode=throw. Now that create-time
                // projections succeed (column-list ORDER BY fix) and ALTER ADD PROJECTION runs,
                // projection-bearing tables are common, so this CH restriction surfaces -- it is a
                // documented restriction, not a bug.
                "DELETE query is not allowed", "lightweight_mutation_projection_mode",
                // Lightweight UPDATE (UPDATE ... SET, the patch-part producer) restrictions that are
                // documented engine/version limits, not bugs: the feature is gated/unsupported on
                // some engines or builds, or the experimental flag is required instead of
                // enable_lightweight_update. These are tolerated for the MUTATION generator action
                // (lightweight UPDATE on a non-patch-eligible table). NB: none of these substrings
                // match the NOT_FOUND_COLUMN_IN_BLOCK / _part_offset read crash -- that stays
                // untolerated (see ClickHousePatchPartConsistencyOracle and the caveat in
                // getExpectedExpressionErrors).
                "Lightweight update", "lightweight update", "allow_experimental_lightweight_update", "SUPPORT_IS_DISABLED",
                "is not supported for lightweight", "Lightweight updates are not supported");
    }

    public static void addMutationErrors(ExpectedErrors errors) {
        errors.addAll(getMutationErrors());
    }

    // TEMPORARY pins for known-open, already-filed ClickHouse bugs on the mutation-analyzer path.
    // Consumed ONLY by the mutation generator's expected-error build and by
    // ClickHouseMutationAnalyzerOracle's narrow set -- deliberately NOT part of getMutationErrors(),
    // which the PatchPartConsistency / FinalMerge oracles also consume on their *read* paths (the
    // pin must never leak beyond mutation statements).
    //
    // - "is already registered": ClickHouse#106649 (LOGICAL_ERROR "Column identifier <c> is already
    //   registered" when a mutation WHERE has an IN-subquery joining two derived tables that
    //   project the same column name; 26.6 regression from PR #98884). Verified still reproducing
    //   on head 26.6.1.399 on 2026-06-10 before pinning. The fix is in flight as PR #106025.
    //   REMOVAL CONDITION: delete this entry when #106025 merges and head no longer reproduces
    //   (re-check: gh issue view 106649 --repo ClickHouse/ClickHouse). The substring is the
    //   message's stable tail -- the column name sits mid-message, and a standalone
    //   "Column identifier" entry would tolerate far more than the filed signature. This is a
    //   deliberate, narrow, documented exception to the "never tolerate Code 49" rule: one filed
    //   signature, mutation path only, never the bare LOGICAL_ERROR token.
    public static List<String> getKnownOpenMutationAnalyzerBugs() {
        return List.of("is already registered");
    }

    /**
     * Walk an exception cause chain and return true if any frame's message matches a baseline- tolerated CH error. Use
     * this from oracle code paths that invoke {@link java.sql.Statement#executeQuery} or {@code execute} directly
     * (bypassing SQLQueryAdapter), to absorb the same family of expected errors that SQLQueryAdapter.checkException
     * would.
     *
     * <p>
     * Without this helper, direct-Statement errors propagate as raw SQLException up through the oracle's throws clause,
     * becoming reproducer files for runs where CH trips its memory limit, drops a table mid-run, or otherwise produces
     * a benign error during oracle setup. The 2026-05-28 6h run surfaced 344 MEMORY_LIMIT_EXCEEDED reproducers from
     * this exact path.
     *
     * @param e
     *            the throwable to inspect (its cause chain is walked)
     *
     * @return {@code true} if the throwable matches an expected/tolerated ClickHouse error, {@code false} otherwise
     */
    public static boolean isToleratedException(Throwable e) {
        ExpectedErrors errors = ExpectedErrors.newErrors().with(getExpectedExpressionErrors())
                .with(getSessionSettingsErrors()).build();
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && errors.errorIsExpected(msg)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    // Substring patterns for the type-system-expansion workstreams (2/3/4/5/6/7). Each family
    // covers tolerated rejections from generator-emitted expressions over the new column shapes.
    // Pre-loaded into addExpectedExpressionErrors below.
    public static List<String> getTypeExpansionErrors() {
        return List.of(
                // Composite / geo / nested / JSON / Variant / Dynamic / AggregateFunction
                "no overload", "is not supported for arguments of types", "Argument at index", "TYPE_MISMATCH",
                "NO_COMMON_TYPE", "is experimental, please set", "Cannot read array", "Map key cannot be Nullable",
                "Map keys must be", "Variant types are different in", "Dynamic types must be", "Cannot convert to JSON",
                // Tuple
                "Tuple type cannot be passed directly", "Wrong tuple",
                // Geo functions
                "Required cleanup", "geometry",
                // Nested
                "Nested type",
                // AggregateFunction state
                "Aggregate function", "aggregate function combinator",
                // Interval
                "Bad cast from type Interval", "Cannot determine type of literal");
    }

    public static void addTypeExpansionErrors(ExpectedErrors errors) {
        errors.addAll(getTypeExpansionErrors());
    }

    // Substring patterns for Enum8/Enum16 generator emission. The picker selects from the entry
    // set so domain violations should be structurally impossible, but cross-type expressions
    // (e.g. `enum_col + 1`, `cast(enum_col AS Int32)`) can fail. Workstream 2 of the plan.
    public static List<String> getEnumErrors() {
        return List.of("Unknown element", "UNKNOWN_ELEMENT_OF_ENUM", "Element of set in IN, VALUES or LIMIT",
                "Cannot convert NULL to Enum", "Cannot convert string", "is not a valid Enum", "Bad get: has Int",
                "Type mismatch in IN or VALUES section",
                // Cast targets that don't accept Enum8/Enum16 as a source -- accurateCast,
                // accurateCastOrNull, etc., reject Enum->DateTime / Enum->FixedString. The
                // Cast oracle emits these blindly over every column type.
                "Unsupported data type in conversion function", "CANNOT_CONVERT_TYPE",
                "Conversion from string with leading or trailing",
                // MATERIALIZED column auto-cast to Enum from DateTime / Date / numeric source --
                // rejected with 'Conversion from DateTime to Enum16(...) is not supported'.
                "Conversion from DateTime to Enum", "Conversion from Date to Enum", "Conversion from Int",
                "Conversion from UInt", "Conversion from Float", "Conversion from String to Enum",
                // Sister error from the CAST-OR-DEFAULT family when a MATERIALIZED expression
                // doesn't have a viable cast to the declared column type.
                "is not supported: In scope _CAST");
    }

    public static void addEnumErrors(ExpectedErrors errors) {
        errors.addAll(getEnumErrors());
    }

}
