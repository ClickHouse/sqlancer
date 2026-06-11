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

public final class ClickHouseAlterGenerator {

    private ClickHouseAlterGenerator() {
    }

    private enum AlterKind {
        ADD_COLUMN, DROP_COLUMN, MODIFY_COLUMN, RENAME_COLUMN, COMMENT_COLUMN, ADD_PROJECTION
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
        case ADD_PROJECTION:
            renderAddProjection(sb, table);
            break;
        default:
            throw new AssertionError(kind);
        }

        ExpectedErrors errors = ExpectedErrors.newErrors().with(ClickHouseErrors.getExpectedExpressionErrors())
                .with(ClickHouseErrors.getAlterErrors()).build();

        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    public static sqlancer.clickhouse.ast.ClickHouseAlterColumnStatement buildAst(ClickHouseGlobalState state) {
        SQLQueryAdapter adapter = getQuery(state);
        String sql = adapter.getQueryString();
        sqlancer.clickhouse.ast.ClickHouseAlterColumnStatement.Kind kind;
        if (sql.contains(" ADD COLUMN ")) {
            kind = sqlancer.clickhouse.ast.ClickHouseAlterColumnStatement.Kind.ADD_COLUMN;
        } else if (sql.contains(" DROP COLUMN ")) {
            kind = sqlancer.clickhouse.ast.ClickHouseAlterColumnStatement.Kind.DROP_COLUMN;
        } else if (sql.contains(" MODIFY COLUMN ")) {
            kind = sqlancer.clickhouse.ast.ClickHouseAlterColumnStatement.Kind.MODIFY_COLUMN;
        } else if (sql.contains(" RENAME COLUMN ")) {
            kind = sqlancer.clickhouse.ast.ClickHouseAlterColumnStatement.Kind.RENAME_COLUMN;
        } else if (sql.contains(" COMMENT COLUMN ")) {
            kind = sqlancer.clickhouse.ast.ClickHouseAlterColumnStatement.Kind.COMMENT_COLUMN;
        } else {
            kind = sqlancer.clickhouse.ast.ClickHouseAlterColumnStatement.Kind.MODIFY_COLUMN;
        }
        return new sqlancer.clickhouse.ast.ClickHouseAlterColumnStatement(kind, "", sql);
    }

    private static void renderAddColumn(StringBuilder sb, ClickHouseGlobalState state, ClickHouseTable table) {

        String name = pickFreshColumnName(table);
        ClickHouseSchema.ClickHouseLancerDataType dataType = ClickHouseSchema.ClickHouseLancerDataType.getRandom(state);
        sb.append(" ADD COLUMN IF NOT EXISTS ").append(name).append(' ').append(dataType.toString());
    }

    private static void renderDropColumn(StringBuilder sb, ClickHouseTable table) {

        ClickHouseColumn col = Randomly.fromList(table.getColumns());
        sb.append(" DROP COLUMN IF EXISTS ").append(col.getName());
    }

    private static void renderModifyColumn(StringBuilder sb, ClickHouseGlobalState state, ClickHouseTable table) {
        ClickHouseColumn col = Randomly.fromList(table.getColumns());
        ClickHouseSchema.ClickHouseLancerDataType dataType = ClickHouseSchema.ClickHouseLancerDataType.getRandom(state);

        sb.append(" MODIFY COLUMN ").append(col.getName()).append(' ').append(dataType.toString());
    }

    private static void renderRenameColumn(StringBuilder sb, ClickHouseTable table) {
        ClickHouseColumn col = Randomly.fromList(table.getColumns());
        String newName = pickFreshColumnName(table);
        sb.append(" RENAME COLUMN ").append(col.getName()).append(" TO ").append(newName);
    }

    private static void renderAddProjection(StringBuilder sb, ClickHouseTable table) {
        List<ClickHouseColumn> cols = table.getColumns();
        if (cols.isEmpty()) {
            throw new IgnoreMeException();
        }
        String name = "p_alter_" + Randomly.getNotCachedInteger(0, 1_000_000);
        sb.append(" ADD PROJECTION ").append(name).append(" (");
        if (Randomly.getBoolean()) {

            String groupCol = cols.get((int) Randomly.getNotCachedInteger(0, cols.size())).getName();
            sb.append("SELECT count() GROUP BY ").append(groupCol);
        } else {

            int subsetSize = Math.min(cols.size(), 1 + (int) Randomly.getNotCachedInteger(0, 2));
            String colList = Randomly.extractNrRandomColumns(cols, subsetSize).stream().map(ClickHouseColumn::getName)
                    .collect(java.util.stream.Collectors.joining(", "));
            sb.append("SELECT ").append(colList).append(" ORDER BY ").append(colList);
        }
        sb.append(")");
    }

    private static void renderCommentColumn(StringBuilder sb, ClickHouseTable table) {
        ClickHouseColumn col = Randomly.fromList(table.getColumns());

        sb.append(" COMMENT COLUMN ").append(col.getName()).append(" 'altered by sqlancer'");
    }

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

                }
            }
        }
        return "c" + (max + 1 + (int) Randomly.getNotCachedInteger(0, 8));
    }
}
