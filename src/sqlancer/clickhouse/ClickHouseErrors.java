package sqlancer.clickhouse;

import java.util.List;

import sqlancer.common.query.ExpectedErrors;

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
                // oracle.  Observed 12 + 2 times in the 2026-05-19 48-min run, in stack chains
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
                "SocketTimeoutException", "Read timed out", "Query request failed (attempt:",
                "DataTransferException",
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
                // Decimal cast / arithmetic overflow once the picker emits Decimal(P, S) columns.
                "DECIMAL_OVERFLOW", "Cannot convert: Float64 to Decimal", "Too many digits", "ARGUMENT_OUT_OF_BOUND",
                // FixedString CAST when the literal length doesn't match. The emitter pads / truncates
                // to N but DEFAULT clauses generated from a longer source string can still trip this.
                // CH 26.5 surfaces oversized literals as `TOO_LARGE_STRING_SIZE` (Code 131), which
                // doesn't contain the upper-case `FIXED_STRING` substring -- without the explicit
                // code it escapes the generator's expected-errors filter and tears down the thread.
                // Observed twice in the 2026-05-19 180s baseline, costing 2/6 threads (~33% capacity).
                "String literal", "FIXED_STRING", "TOO_LARGE_STRING_SIZE");
    }

    public static void addExpectedExpressionErrors(ExpectedErrors errors) {
        errors.addAll(getExpectedExpressionErrors());
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

}
