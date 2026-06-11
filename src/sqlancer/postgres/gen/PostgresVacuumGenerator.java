package sqlancer.postgres.gen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.PostgresSchema.PostgresTable;

public final class PostgresVacuumGenerator {

    private PostgresVacuumGenerator() {
    }

    public static SQLQueryAdapter create(PostgresGlobalState globalState) {
        PostgresTable table = globalState.getSchema().getRandomTable();
        StringBuilder sb = new StringBuilder("VACUUM ");
        if (Randomly.getBoolean()) {

            sb.append("(");
            for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
                ArrayList<String> opts = new ArrayList<>(Arrays.asList("FULL", "FREEZE", "ANALYZE", "VERBOSE",
                        "DISABLE_PAGE_SKIPPING", "SKIP_LOCKED", "INDEX_CLEANUP", "TRUNCATE"));
                String option = Randomly.fromList(opts);
                if (i != 0) {
                    sb.append(", ");
                }
                sb.append(option);
                if (Randomly.getBoolean()) {
                    sb.append(" ");
                    sb.append(Randomly.fromOptions(1, 0));
                }
            }
            sb.append(")");
            if (Randomly.getBoolean()) {
                addTableAndColumns(table, sb);
            }
        } else {
            String firstOption = Randomly.fromOptions("FULL", "FREEZE", "VERBOSE");
            sb.append(firstOption);
            if (Randomly.getBoolean()) {
                sb.append(" ANALYZE");
                addTableAndColumns(table, sb);
            } else {
                if (Randomly.getBoolean()) {
                    sb.append(" ");
                    sb.append(table.getName());
                }
            }
        }
        ExpectedErrors errors = new ExpectedErrors();
        errors.add("VACUUM cannot run inside a transaction block");
        errors.add("deadlock");

        errors.add("ERROR: ANALYZE option must be specified when a column list is provided");
        errors.add("VACUUM option DISABLE_PAGE_SKIPPING cannot be used with FULL");
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static void addTableAndColumns(PostgresTable table, StringBuilder sb) {
        sb.append(" ");
        sb.append(table.getName());
        if (Randomly.getBoolean()) {
            sb.append("(");
            sb.append(table.getRandomNonEmptyColumnSubset().stream().map(c -> c.getName())
                    .collect(Collectors.joining(", ")));
            sb.append(")");
        }
    }

}
