package sqlancer.presto;

import java.util.ArrayList;
import java.util.List;

import sqlancer.common.query.ExpectedErrors;

public final class PrestoErrors {

    private PrestoErrors() {
    }

    public static List<String> getExpressionErrors() {
        ArrayList<String> errors = new ArrayList<>();

        errors.addAll(getFunctionErrors());

        errors.add("cannot be applied to");
        errors.add("LIKE expression must evaluate to a varchar");
        errors.add("JOIN ON clause must evaluate to a boolean");

        errors.add("Decimal overflow");
        errors.add("long overflow");
        errors.add("multiplication overflow");
        errors.add("addition overflow");
        errors.add("subtraction overflow");

        errors.add("Value cannot be cast to");
        errors.add("Cannot cast DECIMAL");
        errors.add("Cannot cast BIGINT");
        errors.add("Cannot cast INTEGER");

        errors.add("io.airlift.slice.Slice cannot be cast to java.lang.Number");
        errors.add("class io.airlift.slice.Slice cannot be cast to class java.lang.Number");
        if (PrestoBugs.bug23324) {
            errors.add("Cannot cast java.lang.Long to io.airlift.slice.Slice");
        }
        errors.add("Cannot cast java.lang.String to java.util.List");
        errors.add("Unexpected subquery expression in logical plan");
        if (PrestoBugs.bugVerifyError) {
            errors.add("VerifyError");
        }
        if (PrestoBugs.bugCompilerFailed) {
            errors.add("Compiler failed");
            errors.add("Error processing class definition");
        }

        errors.add("Invalid numeric literal");

        errors.add("Division by zero");
        errors.add("/ by zero");

        errors.add("Cannot subtract hour, minutes or seconds from a date");
        errors.add("Cannot add hour, minutes or seconds to a date");

        errors.add("DECIMAL scale must be in range");
        errors.add("IN value and list items must be the same type");
        errors.add("is not a valid timestamp literal");
        errors.add("Unknown time-zone ID");
        errors.add("GROUP BY position");

        errors.add("Unknown type: ARRAY");

        errors.add("WHERE clause must evaluate to a boolean");
        errors.add("HAVING clause must evaluate to a boolean");
        errors.add("not yet implemented");

        errors.add("Value expression and result of subquery must be of the same type for quantified comparison");
        errors.add("All IN list values must be the same type");
        errors.add("All CASE results must be the same type");
        errors.add("Mismatched types");
        errors.add("CASE operand type does not match WHEN clause operand type");
        errors.add("Subquery result type must be orderable");
        errors.add("Escape character must be followed by '%', '_' or the escape character itself");
        errors.add("Types are not comparable with NULLIF");
        errors.add("not of the same type");

        if (PrestoBugs.bug23613) {
            errors.add("at index 1");
        }

        return errors;
    }

    public static void addExpressionErrors(ExpectedErrors errors) {
        errors.addAll(getExpressionErrors());
    }

    private static List<String> getRegexErrors() {
        ArrayList<String> errors = new ArrayList<>();

        errors.add("missing ]");
        errors.add("missing )");
        errors.add("invalid escape sequence");
        errors.add("no argument for repetition operator: ");
        errors.add("bad repetition operator");
        errors.add("trailing \\");
        errors.add("invalid perl operator");
        errors.add("invalid character class range");
        errors.add("width is not integer");

        return errors;
    }

    private static List<String> getFunctionErrors() {
        ArrayList<String> errors = new ArrayList<>();

        errors.add("SUBSTRING cannot handle negative lengths");
        errors.add("is undefined outside [-1,1]");
        errors.add("invalid type specifier");
        errors.add("argument index out of range");
        errors.add("invalid format string");
        errors.add("number is too big");
        errors.add("Like pattern must not end with escape character!");
        errors.add("Could not choose a best candidate function for the function call \"date_part");
        errors.add("extract specifier");
        errors.add("not recognized");
        errors.add("not supported");
        errors.add("Failed to cast");
        errors.add("Conversion Error");
        errors.add("Could not cast value");
        errors.add("Insufficient padding in RPAD");
        errors.add("Could not choose a best candidate function for the function call");
        errors.add("expected a numeric precision field");
        errors.add("with non-constant precision is not supported");
        errors.add("Unexpected parameters");
        errors.add("not registered");
        errors.add("Expected: least(E) E:orderable");
        errors.add("Expected: greatest(E) E:orderable");
        errors.add("Expected: max_by(V, K) K:orderable, V, max_by(V, K, bigint) V, K:orderable");
        errors.add("Expected: min_by(V, K) K:orderable, V, min_by(V, K, bigint) V, K:orderable");
        return errors;
    }

    public static List<String> getInsertErrors() {
        ArrayList<String> errors = new ArrayList<>();

        errors.addAll(getRegexErrors());
        errors.addAll(getExpressionErrors());

        errors.add("NOT NULL constraint failed");
        errors.add("PRIMARY KEY or UNIQUE constraint violated");
        errors.add("duplicate key");
        errors.add("can't be cast because the value is out of range for the destination type");
        errors.add("Could not convert string");
        errors.add("Unimplemented type for cast");
        errors.add("field value out of range");
        errors.add("CHECK constraint failed");
        errors.add("Cannot explicitly insert values into rowid column");
        errors.add(" Column with name rowid does not exist!");

        errors.add("Could not cast value");
        errors.add("create unique index, table contains duplicate data");
        errors.add("Failed to cast");

        errors.add("Values rows have mismatched types");
        errors.add("Mismatch at column");
        errors.add("This connector does not support updates or deletes");
        errors.add("Values rows have mismatched types");
        errors.add("Invalid numeric literal");

        return errors;
    }

    public static void addInsertErrors(ExpectedErrors errors) {
        errors.addAll(getInsertErrors());
    }

    public static List<String> getGroupByErrors() {
        ArrayList<String> errors = new ArrayList<>();

        errors.add("must be an aggregate expression or appear in GROUP BY clause");

        return errors;
    }

    public static void addGroupByErrors(ExpectedErrors errors) {
        errors.addAll(getGroupByErrors());
    }

}
