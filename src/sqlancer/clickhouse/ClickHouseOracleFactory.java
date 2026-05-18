package sqlancer.clickhouse;

import java.sql.SQLException;

import sqlancer.OracleFactory;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.clickhouse.oracle.cert.ClickHouseCERTOracle;
import sqlancer.clickhouse.oracle.coddtest.ClickHouseCODDTestOracle;
import sqlancer.clickhouse.oracle.eet.ClickHouseEETOracle;
import sqlancer.clickhouse.oracle.pqs.ClickHousePivotedQuerySynthesisOracle;
import sqlancer.clickhouse.oracle.qcc.ClickHouseQueryConditionCacheOracle;
import sqlancer.clickhouse.oracle.rowpolicy.ClickHouseRowPolicyOracle;
import sqlancer.clickhouse.oracle.semr.ClickHouseSEMROracle;
import sqlancer.clickhouse.oracle.setop_limit.ClickHouseSortedUnionLimitByOracle;
import sqlancer.clickhouse.oracle.tablefn.ClickHouseTableFunctionINOracle;
import sqlancer.clickhouse.oracle.view.ClickHouseViewEquivalenceOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPAggregateOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPCombinatorOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPDistinctOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPGroupByOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPHavingOracle;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPSetOpOracle;
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
        // Cross-query oracle: catches bugs where one query's filter writes a poisoned entry into
        // the server's query-condition cache and a later, structurally different query reads back
        // a wrong result. See ClickHouseQueryConditionCacheOracle's class javadoc and
        // ClickHouse#104781 for the canonical bug shape.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseQueryConditionCacheOracle(globalState);
        }
    },
    SortedUnionLimitBy {
        // Asserts that wrapping UNION ALL arms in ORDER BY does not change the cardinality or
        // distinct-key set of an outer LIMIT BY / DISTINCT result. Targets ClickHouse#103231.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseSortedUnionLimitByOracle(globalState);
        }
    },
    RowPolicy {
        // Asserts that a permissive row policy USING p filters identically to an explicit WHERE p.
        // Touches the row-policy / PREWHERE / FINAL interaction surface (ClickHouse#97076).
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseRowPolicyOracle(globalState);
        }
    },
    TableFunctionIN {
        // Asserts that `WHERE number IN (...)` against a `numbers(N)` table function returns the
        // same row count as the explicit OR / AND-NOT chain over the same values. Targets the
        // relaxed-IN range-construction bug class (ClickHouse#103835).
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseTableFunctionINOracle(globalState);
        }
    },
    ViewEquivalence {
        // Asserts that reading through a normal view yields the same result as the inlined query.
        // Covers analyzer / filter-pushdown bugs along the view-expansion path
        // (ClickHouse#100390 family).
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseViewEquivalenceOracle(globalState);
        }
    }
}
