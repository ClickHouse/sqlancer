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
                "Cannot parse Int32 from String, because value is too short", "Cannot parse NaN.: while converting",

                "from String, because value is too short",

                "Unknown element '",
                "Cannot parse infinity.", "Cannot parse number with a sign character but without any numeric character",
                "Cannot parse number with multiple sign (+/-) characters or intermediate sign character",
                "Cannot parse string", "Cannot read floating point value",
                "Cyclic aliases: default expression and column type are incompatible", "Directory for table data",
                "Directory not empty", "Expected one of: compound identifier, identifier, list of elements (version",

                "Function 'like' doesn't support search with non-constant needles in constant haystack", "Illegal type",
                "Illegal value (aggregate function) for positional argument in GROUP BY",

                "is found in GROUP BY in query", "(ILLEGAL_AGGREGATION)",
                "Invalid escape sequence at the end of LIKE pattern", "Invalid type for filter in", "Memory limit",
                "OptimizedRegularExpression: cannot compile re2", "Partition key cannot contain constants",
                "Positional argument out of bounds", "Sampling expression must be present in the primary key",
                "Sorting key cannot contain constants", "There is no supertype for types", "argument of function",
                "but its arguments considered equal according to constraints", "does not return a value of type UInt8",
                "doesn't exist",
                "in block. There are only columns:",
                "invalid character class range", "invalid escape sequence",
                "is not under aggregate function and not in GROUP BY", "is not under aggregate function",
                "is violated at row 1. Expression:",
                "is violated, because it is a constant expression returning 0. It is most likely an error in table definition",

                "there are only columns", "there are columns", "(NOT_FOUND_COLUMN_IN_BLOCK)", "Missing columns",
                "Ambiguous column", "Must be one unsigned integer type. (ILLEGAL_TYPE_OF_COLUMN_FOR_FILTER)",
                "Floating point partition key is not supported", "Cannot get JOIN keys from JOIN ON section",
                "ILLEGAL_DIVISION", "DECIMAL_OVERFLOW",
                "Cannot convert out of range floating point value to integer type",
                "Unexpected inf or nan to integer conversion", "No such name in Block::erase",
                "EMPTY_LIST_OF_COLUMNS_QUERIED",
                "EMPTY_LIST_OF_COLUMNS_PASSED",
                "cannot get JOIN keys. (INVALID_JOIN_ON_EXPRESSION)", "AMBIGUOUS_IDENTIFIER", "CYCLIC_ALIASES",
                "Positional argument numeric constant expression is not representable as",
                "Positional argument must be constant with numeric type", " is out of bounds. Expected in range",
                "with constants is not supported. (INVALID_JOIN_ON_EXPRESSION)",
                "Cannot get JOIN keys from JOIN ON section", "Unexpected inf or nan to integer conversion",
                "Cannot determine join keys in", "Unsigned type must not contain",
                "Unexpected inf or nan to integer conversion",

                "MULTIPLE_EXPRESSIONS_FOR_ALIAS", "AMBIGUOUS_IDENTIFIER",
                "AMBIGUOUS_COLUMN_NAME",
                "No equality condition found in JOIN ON expression", "Cannot parse number with multiple sign",

                "Magic is not correct",

                "MalformedChunkCodingException", "CRLF expected at end of chunk", "TruncatedChunkException",
                "Truncated chunk (expected size:",

                "ConnectionClosedException", "Premature end of chunk coded message body",

                "SocketTimeoutException", "Read timed out", "Query request failed (attempt:", "DataTransferException",

                "cannot be presented as long",

                "ILLEGAL_TYPE_OF_ARGUMENT",
                "Conversion from LowCardinality", "Conversion to LowCardinality", "Nested type",

                "type cannot be inside Nullable type", "type cannot be inside LowCardinality",
                "Cannot read floating point value",
                "NULL value is not allowed",

                "(INCORRECT_RESULT_OF_SCALAR_SUBQUERY)",

                "SUSPICIOUS_TYPE_FOR_LOW_CARDINALITY",

                "Partition key contains nullable columns", "Sorting key contains nullable columns",
                "allow_nullable_key",

                "Cannot convert NULL value to non-Nullable type", "CANNOT_INSERT_NULL_IN_ORDINARY_COLUMN",

                "Timeout exceeded: elapsed", "(TIMEOUT_EXCEEDED)",

                "listed both in columns to sum and in partition key",
                "listed both in columns to sum and in sorting key", "Version column", "is in primary key",

                "Cannot parse Date", "CANNOT_PARSE_DATE", "Cannot parse DateTime", "CANNOT_PARSE_DATETIME",

                "Cannot parse IPv4", "Cannot parse IPv6", "CANNOT_PARSE_IPV4", "CANNOT_PARSE_IPV6", "Cannot parse uuid",
                "Cannot parse UUID", "CANNOT_PARSE_UUID",

                "CANNOT_PARSE_BOOL", "Expected boolean value but get",

                "(ILLEGAL_TYPE_OF_COLUMN_FOR_FILTER)",

                "DECIMAL_OVERFLOW", "Cannot convert: Float64 to Decimal", "Too many digits", "ARGUMENT_OUT_OF_BOUND",

                "String literal", "FIXED_STRING", "TOO_LARGE_STRING_SIZE",

                "Limit for result exceeded", "TOO_MANY_ROWS_OR_BYTES",

                "of bloom filter index",

                "(MEMORY_LIMIT_EXCEEDED)", "memory limit exceeded",

                "Cannot parse infinity", "Cannot parse NaN", "CANNOT_PARSE_INPUT_ASSERTION_FAILED",

                "CAST AS FixedString is only implemented", "default expression and column type are incompatible",

                "Incompatible data types between aggregate function", "NOT_IMPLEMENTED");
    }

    public static void addExpectedExpressionErrors(ExpectedErrors errors) {
        errors.addAll(getExpectedExpressionErrors());

        errors.addAll(getStatisticsErrors());

        errors.addAll(getEnumErrors());

        errors.addAll(getTypeExpansionErrors());
    }

    public static List<String> getSessionSettingsErrors() {
        return List.of("Unknown setting",
                "is neither a builtin setting nor a custom setting",
                "Cannot parse setting value",
                "Setting value out of range",
                "UNKNOWN_SETTING");
    }

    public static void addSessionSettingsErrors(ExpectedErrors errors) {
        errors.addAll(getSessionSettingsErrors());
    }

    public static List<String> getSetOpErrors() {
        return List.of("Number of columns doesn't match", "Cannot find common type for tuple elements",
                "INCOMPATIBLE_COLUMNS", "Type mismatch in IN or VALUES section",
                "Column number mismatch in subqueries of intersect/except");
    }

    public static void addSetOpErrors(ExpectedErrors errors) {
        errors.addAll(getSetOpErrors());
    }

    public static List<String> getCombinatorErrors() {
        return List.of("Unknown aggregate function", "NUMBER_OF_ARGUMENTS_DOESNT_MATCH",
                "Combinator is only applicable for aggregate function", "is only applicable for aggregate functions",
                "Aggregate function is not implemented for", "Cannot apply combinator", "AGGREGATE_FUNCTION_THROW",
                "Nested type for combinator", "Illegal type for argument", "Illegal types of arguments");
    }

    public static void addCombinatorErrors(ExpectedErrors errors) {
        errors.addAll(getCombinatorErrors());
    }

    public static List<String> getArrayJoinErrors() {
        return List.of("Cannot ARRAY JOIN", "ARRAY JOIN requires array argument",
                "ILLEGAL_TYPE_OF_ARGUMENT_FOR_ARRAY_JOIN");
    }

    public static void addArrayJoinErrors(ExpectedErrors errors) {
        errors.addAll(getArrayJoinErrors());
    }

    public static List<String> getStatisticsErrors() {
        return List.of("Set `allow_experimental_statistics`", "allow_experimental_statistics is set to 0",
                "Statistics is not supported", "Unknown statistic kind", "Statistics of kind",
                "Unknown statistics type",
                "STATISTICS_NOT_IMPLEMENTED", "Cannot create statistics", "SUPPORT_IS_DISABLED");
    }

    public static void addStatisticsErrors(ExpectedErrors errors) {
        errors.addAll(getStatisticsErrors());
    }

    public static List<String> getAlterErrors() {
        return List.of("BAD_ARGUMENTS", "Cannot drop column", "Cannot rename column", "Cannot remove column",
                "Column with name", "is part of primary key", "Cannot alter column", "ALTER of key column",
                "Algorithm not implemented", "CANNOT_DROP_INDEX", "ALTER_OF_COLUMN_IS_FORBIDDEN", "DUPLICATE_COLUMN",
                "NO_SUCH_COLUMN_IN_TABLE", "UNFINISHED", "Cannot convert column", "is currently locked for",
                "EMPTY_LIST_OF_COLUMNS_QUERIED",

                "Projection with name", "NO_SUCH_PROJECTION_IN_TABLE", "ILLEGAL_PROJECTION",
                "Projection is fully supported", "projection", "Cannot add projection");
    }

    public static void addAlterErrors(ExpectedErrors errors) {
        errors.addAll(getAlterErrors());
    }

    public static List<String> getMutationErrors() {
        return List.of("TIMEOUT_EXCEEDED", "Cannot UPDATE key column", "Cannot DELETE", "Mutation cannot be executed",

                "affects MATERIALIZED column",
                "Mutations are not supported by", "UNFINISHED_MUTATION", "Cannot read from", "Lightweight DELETE",
                "_row_exists", "Background mutation", "ATTEMPT_TO_READ_AFTER_EOF", "Cannot find column",

                "DELETE query is not allowed", "lightweight_mutation_projection_mode",

                "Lightweight update", "lightweight update", "allow_experimental_lightweight_update", "SUPPORT_IS_DISABLED",
                "is not supported for lightweight", "Lightweight updates are not supported");
    }

    public static void addMutationErrors(ExpectedErrors errors) {
        errors.addAll(getMutationErrors());
    }

    public static List<String> getKnownOpenMutationAnalyzerBugs() {
        return List.of("is already registered");
    }

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

    public static List<String> getTypeExpansionErrors() {
        return List.of(

                "no overload", "is not supported for arguments of types", "Argument at index", "TYPE_MISMATCH",
                "NO_COMMON_TYPE", "is experimental, please set", "Cannot read array", "Map key cannot be Nullable",
                "Map keys must be", "Variant types are different in", "Dynamic types must be", "Cannot convert to JSON",

                "Tuple type cannot be passed directly", "Wrong tuple",

                "Required cleanup", "geometry",

                "Nested type",

                "Aggregate function", "aggregate function combinator",

                "Bad cast from type Interval", "Cannot determine type of literal");
    }

    public static void addTypeExpansionErrors(ExpectedErrors errors) {
        errors.addAll(getTypeExpansionErrors());
    }

    public static List<String> getEnumErrors() {
        return List.of("Unknown element", "UNKNOWN_ELEMENT_OF_ENUM", "Element of set in IN, VALUES or LIMIT",
                "Cannot convert NULL to Enum", "Cannot convert string", "is not a valid Enum", "Bad get: has Int",
                "Type mismatch in IN or VALUES section",

                "Unsupported data type in conversion function", "CANNOT_CONVERT_TYPE",
                "Conversion from string with leading or trailing",

                "Conversion from DateTime to Enum", "Conversion from Date to Enum", "Conversion from Int",
                "Conversion from UInt", "Conversion from Float", "Conversion from String to Enum",

                "is not supported: In scope _CAST");
    }

    public static void addEnumErrors(ExpectedErrors errors) {
        errors.addAll(getEnumErrors());
    }

}
