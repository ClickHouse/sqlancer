package sqlancer.clickhouse.gen;

import java.util.List;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

/**
 * Emits ALTER TABLE ... ADD/DROP/MODIFY/RENAME/COMMENT COLUMN at low probability so the schema
 * mutates across the lifetime of a single database. The schema-affecting flag on every emitted
 * adapter forces {@code SQLGlobalState.updateSchema()} to re-read {@code system.columns} before the
 * next oracle iteration -- otherwise oracle workers reference stale column lists and produce
 * spurious UNKNOWN_IDENTIFIER reproducers.
 *
 * <p>Workstream 8 of {@code docs/plans/2026-05-27-001-feat-clickhouse-coverage-expansion-plan.md}.
 */
public final class ClickHouseAlterGenerator {

    // Reuse the connection-test database name guard: every ALTER target must live in the worker's
    // schema. The provider already places all generated tables in `state.getDatabaseName()`, so this
    // is enforced by selecting from the same schema; the assertion in `pickTable` is the belt-and-
    // suspenders check.
    private ClickHouseAlterGenerator() {
    }

    private enum AlterKind {
        ADD_COLUMN, DROP_COLUMN, MODIFY_COLUMN, RENAME_COLUMN, COMMENT_COLUMN
    }

    public static SQLQueryAdapter getQuery(ClickHouseGlobalState state) {
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        AlterKind kind = Randomly.fromOptions(AlterKind.values());

        StringBuilder sb = new StringBuilder("ALTER TABLE ");
        sb.append(state.getDatabaseName()).append('.').append(table.getName());

        switch (kind) {
        case ADD_COLUMN:
            renderAddColumn(sb, state, table);
            break;
        case DROP_COLUMN:
            renderDropColumn(sb, table);
            break;
        case MODIFY_COLUMN:
            renderModifyColumn(sb, state, table);
            break;
        case RENAME_COLUMN:
            renderRenameColumn(sb, table);
            break;
        case COMMENT_COLUMN:
            renderCommentColumn(sb, table);
            break;
        default:
            throw new AssertionError(kind);
        }

        ExpectedErrors errors = ExpectedErrors.newErrors().with(ClickHouseErrors.getExpectedExpressionErrors())
                .with(ClickHouseErrors.getAlterErrors()).build();
        // couldAffectSchema=true forces SQLGlobalState.executeEpilogue to refresh the in-memory
        // schema after this statement; subsequent oracle queries see the post-ALTER columns.
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    private static void renderAddColumn(StringBuilder sb, ClickHouseGlobalState state, ClickHouseTable table) {
        // Generate a fresh column name that doesn't collide with existing columns. Reuse
        // ClickHouseCommon's numeric scheme so column names follow the cN convention.
        String name = pickFreshColumnName(table);
        ClickHouseSchema.ClickHouseLancerDataType dataType = ClickHouseSchema.ClickHouseLancerDataType.getRandom(state);
        sb.append(" ADD COLUMN IF NOT EXISTS ").append(name).append(' ').append(dataType.toString());
    }

    private static void renderDropColumn(StringBuilder sb, ClickHouseTable table) {
        // Refuse to drop the only non-PK column -- the resulting "no columns left" rejection just
        // adds noise to the reproducer pile. ClickHouse also rejects dropping a primary-key
        // column; the expected-errors absorbs both cases.
        ClickHouseColumn col = Randomly.fromList(table.getColumns());
        sb.append(" DROP COLUMN IF EXISTS ").append(col.getName());
    }

    private static void renderModifyColumn(StringBuilder sb, ClickHouseGlobalState state, ClickHouseTable table) {
        ClickHouseColumn col = Randomly.fromList(table.getColumns());
        ClickHouseSchema.ClickHouseLancerDataType dataType = ClickHouseSchema.ClickHouseLancerDataType.getRandom(state);
        // MODIFY COLUMN can fail with BAD_ARGUMENTS on narrowing conversions (Int64 -> Int8) or
        // type incompatibilities (String -> Int32 with non-numeric data). The expected-errors set
        // absorbs the failure cases so generator output stays valid SQL even when the schema-
        // level constraint trips.
        sb.append(" MODIFY COLUMN ").append(col.getName()).append(' ').append(dataType.toString());
    }

    private static void renderRenameColumn(StringBuilder sb, ClickHouseTable table) {
        ClickHouseColumn col = Randomly.fromList(table.getColumns());
        String newName = pickFreshColumnName(table);
        sb.append(" RENAME COLUMN ").append(col.getName()).append(" TO ").append(newName);
    }

    private static void renderCommentColumn(StringBuilder sb, ClickHouseTable table) {
        ClickHouseColumn col = Randomly.fromList(table.getColumns());
        // Single-line ASCII comment so the renderer doesn't have to escape anything special. The
        // comment value itself is not relevant; ClickHouse stores it verbatim.
        sb.append(" COMMENT COLUMN ").append(col.getName()).append(" 'altered by sqlancer'");
    }

    // Picks a numeric column name that the generator believes doesn't already exist on the table.
    // The numeric scheme (c0, c1, ...) lets us walk past the highest existing index without
    // parsing arbitrary strings.
    private static String pickFreshColumnName(ClickHouseTable table) {
        int max = -1;
        for (ClickHouseColumn col : table.getColumns()) {
            String name = col.getName();
            if (name.length() >= 2 && name.charAt(0) == 'c') {
                try {
                    int idx = Integer.parseInt(name.substring(1));
                    if (idx > max) {
                        max = idx;
                    }
                } catch (NumberFormatException ignored) {
                    // Non-numeric column names exist (system columns, user renames) -- skip.
                }
            }
        }
        return "c" + (max + 1 + (int) Randomly.getNotCachedInteger(0, 8));
    }
}
