package sqlancer.questdb;

import java.util.ArrayList;
import java.util.List;

import sqlancer.common.query.ExpectedErrors;

public final class QuestDBErrors {

    private QuestDBErrors() {
    }

    public static List<String> getExpressionErrors() {
        ArrayList<String> errors = new ArrayList<>();

        errors.add("unexpected argument for function: ");
        errors.add("unexpected token:");
        errors.add("boolean expression expected");
        errors.add("Column name expected");
        errors.add("too few arguments for 'in'");
        errors.add("cannot compare TIMESTAMP with type");
        errors.add("constant expected");

        return errors;
    }

    public static void addExpressionErrors(ExpectedErrors errors) {
        errors.addAll(getExpressionErrors());
    }

    public static List<String> getGroupByErrors() {

        return new ArrayList<>();
    }

    public static void addGroupByErrors(ExpectedErrors errors) {
        errors.addAll(getGroupByErrors());
    }

    public static List<String> getInsertErrors() {
        ArrayList<String> errors = new ArrayList<>();

        errors.add("Invalid column");
        errors.add("inconvertible types:");
        errors.add("inconvertible value:");

        return errors;
    }

    public static void addInsertErrors(ExpectedErrors errors) {
        errors.addAll(getInsertErrors());
    }
}
