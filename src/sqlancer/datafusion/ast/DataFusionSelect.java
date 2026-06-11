package sqlancer.datafusion.ast;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import sqlancer.common.ast.SelectBase;
import sqlancer.common.ast.newast.Select;
import sqlancer.datafusion.DataFusionSchema.DataFusionColumn;
import sqlancer.datafusion.DataFusionSchema.DataFusionTable;
import sqlancer.datafusion.DataFusionToStringVisitor;

public class DataFusionSelect extends SelectBase<DataFusionExpression> implements DataFusionExpression,
        Select<DataFusionJoin, DataFusionExpression, DataFusionTable, DataFusionColumn> {
    public Optional<String> fetchColumnsString = Optional.empty();

    public void setFetchColumnsString(String selectExpr) {
        this.fetchColumnsString = Optional.of(selectExpr);
    }

    @Override
    public void setJoinClauses(List<DataFusionJoin> joinStatements) {
        List<DataFusionExpression> expressions = joinStatements.stream().map(e -> (DataFusionExpression) e)
                .collect(Collectors.toList());
        setJoinList(expressions);
    }

    @Override
    public List<DataFusionJoin> getJoinClauses() {
        return getJoinList().stream().map(e -> (DataFusionJoin) e).collect(Collectors.toList());
    }

    @Override
    public String asString() {
        return DataFusionToStringVisitor.asString(this);
    }
}
