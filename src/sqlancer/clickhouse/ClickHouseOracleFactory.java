package sqlancer.clickhouse;

import java.sql.SQLException;

import sqlancer.OracleFactory;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.clickhouse.oracle.aggstate.ClickHouseAggregateStateRoundtripOracle;
import sqlancer.clickhouse.oracle.cast.ClickHouseCastOracle;
import sqlancer.clickhouse.oracle.cert.ClickHouseCERTOracle;
import sqlancer.clickhouse.oracle.coddtest.ClickHouseCODDTestOracle;
import sqlancer.clickhouse.oracle.dict.ClickHouseDictGetVsJoinOracle;
import sqlancer.clickhouse.oracle.dynamicsub.ClickHouseDynamicSubcolumnOracle;
import sqlancer.clickhouse.oracle.eet.ClickHouseEETOracle;
import sqlancer.clickhouse.oracle.final_.ClickHouseFinalMergeOracle;
import sqlancer.clickhouse.oracle.join.ClickHouseJoinAlgorithmOracle;
import sqlancer.clickhouse.oracle.keycond.ClickHouseKeyConditionOracle;
import sqlancer.clickhouse.oracle.materialize.ClickHouseSubqueryMaterializeOracle;
import sqlancer.clickhouse.oracle.parallelism.ClickHouseParallelismOracle;
import sqlancer.clickhouse.oracle.cte.ClickHouseMaterializedCteOracle;
import sqlancer.clickhouse.oracle.join.ClickHouseJoinReorderOracle;
import sqlancer.clickhouse.oracle.jsonidx.ClickHouseJsonSkipIndexOracle;
import sqlancer.clickhouse.oracle.join.ClickHouseNaturalJoinOracle;
import sqlancer.clickhouse.oracle.mutate.ClickHouseMutationAnalyzerOracle;
import sqlancer.clickhouse.oracle.patch.ClickHousePatchPartConsistencyOracle;
import sqlancer.clickhouse.oracle.stats.ClickHouseStatsToggleOracle;
import sqlancer.clickhouse.oracle.textindex.ClickHouseTextIndexLikeOracle;
import sqlancer.clickhouse.oracle.topk.ClickHouseTopKOracle;
import sqlancer.clickhouse.oracle.partition.ClickHousePartitionMirrorOracle;
import sqlancer.clickhouse.oracle.pqs.ClickHousePivotedQuerySynthesisOracle;
import sqlancer.clickhouse.oracle.projection.ClickHouseProjectionToggleOracle;
import sqlancer.clickhouse.oracle.datetime.ClickHouseExtendedDatetimeOracle;
import sqlancer.clickhouse.oracle.join.ClickHouseJoinUseNullsOracle;
import sqlancer.clickhouse.oracle.qcc.ClickHouseQueryCacheOracle;
import sqlancer.clickhouse.oracle.qcc.ClickHouseQueryConditionCacheOracle;

import sqlancer.clickhouse.oracle.schema.ClickHouseSchemaRoundtripOracle;
import sqlancer.clickhouse.oracle.semr.ClickHouseSEMRMultiOracle;
import sqlancer.clickhouse.oracle.semr.ClickHouseSEMROracle;
import sqlancer.clickhouse.oracle.setop_limit.ClickHouseSortedUnionLimitByOracle;
import sqlancer.clickhouse.oracle.tablefn.ClickHouseTableFunctionINOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPAggregateOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPCombinatorOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPDistinctOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPGroupByOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPHavingOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPSetOpOracle;
import sqlancer.clickhouse.oracle.view.ClickHouseMaterializedViewConsistencyOracle;
import sqlancer.clickhouse.oracle.view.ClickHouseViewEquivalenceOracle;
import sqlancer.clickhouse.oracle.window.ClickHouseWindowEquivalenceOracle;
import sqlancer.common.oracle.NoRECOracle;
import sqlancer.common.oracle.TLPWhereOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public enum ClickHouseOracleFactory implements OracleFactory<ClickHouseGlobalState> {
    TLPWhere {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(globalState);
            ExpectedErrors expectedErrors = ExpectedErrors.newErrors()
                    .with(ClickHouseErrors.getExpectedExpressionErrors()).build();

            return new TLPWhereOracle<>(globalState, gen, expectedErrors);
        }
    },
    TLPDistinct {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseTLPDistinctOracle(globalState);
        }
    },
    TLPGroupBy {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseTLPGroupByOracle(globalState);
        }
    },
    TLPAggregate {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseTLPAggregateOracle(globalState);
        }
    },
    TLPHaving {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseTLPHavingOracle(globalState);
        }
    },
    NoREC {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(globalState);
            ExpectedErrors errors = ExpectedErrors.newErrors().with(ClickHouseErrors.getExpectedExpressionErrors())
                    .with("canceling statement due to statement timeout").build();

            return new NoRECOracle<>(globalState, gen, errors);
        }
    },
    PQS {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHousePivotedQuerySynthesisOracle(globalState);
        }
    },
    CERT {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseCERTOracle(globalState);
        }
    },
    CODDTest {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseCODDTestOracle(globalState);
        }
    },
    SEMR {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseSEMROracle(globalState);
        }
    },
    SEMRMulti {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseSEMRMultiOracle(globalState);
        }
    },
    EET {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseEETOracle(globalState);
        }
    },
    SetOpTLP {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseTLPSetOpOracle(globalState);
        }
    },
    CombinatorTLP {
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseTLPCombinatorOracle(globalState);
        }
    },
    QccCache {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseQueryConditionCacheOracle(globalState);
        }
    },
    SortedUnionLimitBy {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseSortedUnionLimitByOracle(globalState);
        }
    },

    SchemaRoundtrip {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseSchemaRoundtripOracle(globalState);
        }
    },
    JoinAlgorithm {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseJoinAlgorithmOracle(globalState);
        }
    },
    Cast {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseCastOracle(globalState);
        }
    },
    Parallelism {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseParallelismOracle(globalState);
        }
    },
    PartitionMirror {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHousePartitionMirrorOracle(globalState);
        }
    },
    KeyCondition {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseKeyConditionOracle(globalState);
        }
    },
    TableFunctionIN {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseTableFunctionINOracle(globalState);
        }
    },
    ViewEquivalence {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseViewEquivalenceOracle(globalState);
        }
    },
    FinalMerge {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseFinalMergeOracle(globalState);
        }
    },
    AggregateStateRoundtrip {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseAggregateStateRoundtripOracle(globalState);
        }
    },
    MaterializedViewConsistency {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseMaterializedViewConsistencyOracle(globalState);
        }
    },
    ProjectionToggle {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseProjectionToggleOracle(globalState);
        }
    },
    PatchPartConsistency {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHousePatchPartConsistencyOracle(globalState);
        }
    },
    DictGetVsJoin {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseDictGetVsJoinOracle(globalState);
        }
    },
    WindowEquivalence {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseWindowEquivalenceOracle(globalState);
        }
    },
    DynamicSubcolumn {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseDynamicSubcolumnOracle(globalState);
        }
    },
    SubqueryMaterialize {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseSubqueryMaterializeOracle(globalState);
        }
    },
    MutationAnalyzer {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseMutationAnalyzerOracle(globalState);
        }
    },
    TextIndexLike {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseTextIndexLikeOracle(globalState);
        }
    },
    TopK {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseTopKOracle(globalState);
        }
    },
    JoinReorder {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseJoinReorderOracle(globalState);
        }
    },
    NaturalJoin {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseNaturalJoinOracle(globalState);
        }
    },
    MaterializedCte {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseMaterializedCteOracle(globalState);
        }
    },
    JsonSkipIndex {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseJsonSkipIndexOracle(globalState);
        }
    },
    ExtendedDatetime {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseExtendedDatetimeOracle(globalState);
        }
    },
    JoinUseNulls {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseJoinUseNullsOracle(globalState);
        }
    },
    QueryCache {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseQueryCacheOracle(globalState);
        }
    },
    StatsToggle {

        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseStatsToggleOracle(globalState);
        }
    }
}
