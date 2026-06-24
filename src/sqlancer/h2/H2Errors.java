package sqlancer.h2;

import java.util.ArrayList;
import java.util.List;

import sqlancer.common.query.ExpectedErrors;

public final class H2Errors {

    private H2Errors() {
    }

    public static List<String> getInsertErrors() {
        ArrayList<String> errors = new ArrayList<>();
        errors.add("NULL not allowed for column");
        errors.add("Unique index or primary key violation");
        errors.add("Data conversion error");
        errors.add("Generated column");
        errors.add("Value too long for column");
        errors.add("Referential integrity constraint violation");
        errors.add("Check constraint invalid");
        errors.add("Check constraint violation");
        return errors;
    }

    public static void addInsertErrors(ExpectedErrors errors) {
        errors.addAll(getInsertErrors());
    }

    public static List<String> getExpressionErrors() {
        ArrayList<String> errors = new ArrayList<>();
        errors.add("java.lang.ArithmeticException: BigInteger would overflow supported range");
        errors.add("Value too long for column");
        errors.add("Numeric value out of range");
        errors.add("are not comparable");
        errors.add("Data conversion error converting");
        errors.add("Feature not supported");

        errors.add("must be in the GROUP BY list");
        errors.add("must be in the result list in this case");
        errors.add("Division by zero");

        errors.add("Unclosed group near index");
        errors.add("Error in LIKE ESCAPE");

        errors.add("Invalid value" );

        errors.add("String format error");
        errors.add( "must be between");
        errors.add("Cannot parse \"TIMESTAMP\" constant");
        errors.add("Invalid parameter count for \"TRUNC\", expected count: \"1\"");
        return errors;
    }

    public static void addExpressionErrors(ExpectedErrors errors) {
        errors.addAll(getExpressionErrors());
    }

    public static List<String> getDeleteErrors() {
        ArrayList<String> errors = new ArrayList<>();
        errors.add("No default value is set for column");
        errors.add("Referential integrity constraint violation");
        errors.add("NULL not allowed for column");
        return errors;
    }

    public static void addDeleteErrors(ExpectedErrors errors) {
        errors.addAll(getDeleteErrors());
    }

}
