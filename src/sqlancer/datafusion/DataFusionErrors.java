package sqlancer.datafusion;

import static sqlancer.datafusion.DataFusionUtil.dfAssert;

import java.util.ArrayList;
import java.util.List;

import sqlancer.common.query.ExpectedErrors;

public final class DataFusionErrors {
    private DataFusionErrors() {
        dfAssert(false, "Utility class cannot be instantiated");
    }

    public static List<String> getExpectedExecutionErrors() {
        ArrayList<String> errors = new ArrayList<>();

        errors.add("Error building plan");
        errors.add("Error during planning");
        errors.add("Execution error");
        errors.add("Overflow happened");
        errors.add("overflow");
        errors.add("Unsupported data type");
        errors.add("Divide by zero");

        errors.add("to type Int64");
        errors.add("bitwise");
        errors.add("NestedLoopJoinExec");

        errors.add("Physical plan does not support logical expression AggregateFunction");

        return errors;
    }

    public static void registerExpectedExecutionErrors(ExpectedErrors errors) {
        errors.addAll(getExpectedExecutionErrors());
    }
}
