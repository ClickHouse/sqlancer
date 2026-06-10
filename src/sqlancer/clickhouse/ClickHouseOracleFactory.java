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
import sqlancer.clickhouse.oracle.patch.ClickHousePatchPartConsistencyOracle;
import sqlancer.clickhouse.oracle.partition.ClickHousePartitionMirrorOracle;
import sqlancer.clickhouse.oracle.pqs.ClickHousePivotedQuerySynthesisOracle;
import sqlancer.clickhouse.oracle.projection.ClickHouseProjectionToggleOracle;
import sqlancer.clickhouse.oracle.qcc.ClickHouseQueryConditionCacheOracle;
// TEMPORARILY DISABLED with the RowPolicy enum constant below (2026-05-31):
// import sqlancer.clickhouse.oracle.rowpolicy.ClickHouseRowPolicyOracle;
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
        // Pairwise / triple-wise SEMR. Same shape as SEMR but toggles k>=2 settings per query so
        // optimizer-pass-interaction bugs (analyzer x subcolumn rewrite x filter pushdown -- the
        // #100029 / #93483 cluster) surface as a single oracle failure. The arity is controlled by
        // --semr-arity; the bisect between all-zero and all-one is then a log(k) manual step.
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
    // TEMPORARILY DISABLED (2026-05-31): the RowPolicy oracle dominates all-oracle run noise --
    // Code 49 (duplicate column in row policy actions output) / Code 162 (too-deep-subqueries) /
    // Code 306 (stack recursion) account for the bulk of every iteration's reproducers and drown
    // out genuine signal from other oracles. Code 49 is a likely real CH bug to file separately
    // (see project_clickhouse_rowpolicy_code49_candidate_bug). Re-enable after that is filed/fixed.
    // RowPolicy {
    // // Asserts that a permissive row policy USING p filters identically to an explicit WHERE p.
    // // Touches the row-policy / PREWHERE / FINAL interaction surface (ClickHouse#97076).
    // @Override
    // public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
    // return new ClickHouseRowPolicyOracle(globalState);
    // }
    // },
    SchemaRoundtrip {
        // CREATE TABLE under data_type_default_nullable={0,1} with explicit NOT NULL, then verify
        // via system.columns that the resulting column type is not Nullable. Targets ClickHouse
        // #97287 and private#53340 -- the NOT NULL modifier silently dropped under the default-
        // nullable session flag.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseSchemaRoundtripOracle(globalState);
        }
    },
    JoinAlgorithm {
        // Issues the same JOIN query under {hash, partial_merge, grace_hash} algorithms and
        // asserts equal result multisets. Targets ClickHouse#100781 (grace_hash + bucket count).
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseJoinAlgorithmOracle(globalState);
        }
    },
    Cast {
        // Asserts accurateCast(x, T) == accurateCastOrNull(x, T) on the rows where the OrNull
        // variant returned non-NULL. Catches the ClickHouse#100697 / #100471 / #101763 family
        // where the throwing-cast vs orNull semantics silently disagree.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseCastOracle(globalState);
        }
    },
    Parallelism {
        // Issues the same SELECT under three thread/chunk profiles (serial, parallel, two-level
        // GROUP BY) and asserts equal result multisets. Targets the ClickHouse#99109 / #99111
        // (sum(Float64) GROUP BY x max_threads, projection vs full-scan) family.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseParallelismOracle(globalState);
        }
    },
    PartitionMirror {
        // Differential oracle for partition pruning + physical INSERT routing. Per iteration: pick
        // a table that has a PARTITION BY clause, build a sister table with the same schema but no
        // PARTITION BY, copy data over, and diff the same SELECT against both. Catches
        // ClickHouse#90240 (toYYYYMM pruning under toWeek filter) and any future mis-routed-insert
        // bug where a row physically lands in the wrong partition.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHousePartitionMirrorOracle(globalState);
        }
    },
    KeyCondition {
        // Differential oracle for primary-key + skip-index granule pruning. Runs each generated
        // SELECT twice: once normally (KeyCondition free to prune) and once with every predicate
        // column reference wrapped in materialize() plus use_skip_indexes=0 + force_primary_key=0,
        // which forces a full scan. Any divergence is a KeyCondition bug -- ClickHouse#92492 is the
        // canonical example (regex `?` / `not` mis-pruning).
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseKeyConditionOracle(globalState);
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
    },
    FinalMerge {
        // Asserts that SELECT count() FROM t FINAL equals SELECT count() FROM t after an explicit
        // synchronous OPTIMIZE TABLE t FINAL. Targets the merge/FINAL/dedupe-engine surface --
        // the database10 LEFT-ANTI-JOIN bug class lives here. Workstream 10 of the coverage plan.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseFinalMergeOracle(globalState);
        }
    },
    AggregateStateRoundtrip {
        // Asserts the AggregateFunction round-trip identity:
        // finalizeAggregation(arrayReduce('sumState', groupArray(c))) == sum(c)
        // Workstream 5 of the coverage expansion plan. Most iterations short-circuit until
        // AggregateFunction columns are emitted by the type picker.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseAggregateStateRoundtripOracle(globalState);
        }
    },
    MaterializedViewConsistency {
        // Asserts a materialized view's incrementally-maintained aggregate (AggregatingMergeTree
        // -State columns or SummingMergeTree running sums) equals a direct aggregate over the
        // source after multi-block inserts. Targets the MV block-transform / part-merge / state-
        // serialization wrong-result surface. Workstream 3 (Unit 3.3) of the coverage plan.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseMaterializedViewConsistencyOracle(globalState);
        }
    },
    ProjectionToggle {
        // Companion to Unit 2.2: asserts a projection-matching aggregate returns identical results
        // under optimize_use_projections = 0 vs = 1. A stale / partially-materialized projection
        // that serves a wrong result diverges here. Targets the #103052 / #88350 projection
        // wrong-result family.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseProjectionToggleOracle(globalState);
        }
    },
    PatchPartConsistency {
        // Self-contained lightweight-UPDATE patch-part oracle. Creates a patch-eligible table
        // (enable_block_number_column / enable_block_offset_column), fires lightweight UPDATEs to
        // leave live patch parts, then asserts (1) a read-in-order + LIMIT read over the wide
        // non-key column returns the same result with query_plan_optimize_lazy_materialization on
        // vs off -- a regression surfaces as the untolerated NOT_FOUND_COLUMN_IN_BLOCK /
        // _part_offset crash -- and (2) on-the-fly patch apply == result after OPTIMIZE FINAL.
        // Targets CH support #7912 -> upstream #98227 / #99023 / #102904 / #103910.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHousePatchPartConsistencyOracle(globalState);
        }
    },
    DictGetVsJoin {
        // Asserts dictGet via a transient CLICKHOUSE-sourced dictionary equals a LEFT JOIN against
        // the same source table. Workstream 14 of the coverage expansion plan.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseDictGetVsJoinOracle(globalState);
        }
    },
    WindowEquivalence {
        // Asserts well-known window-function vs non-window identities (count() OVER () == count(),
        // sum(x) OVER cumulative at last-row == sum(x), etc.). Workstream 19.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseWindowEquivalenceOracle(globalState);
        }
    },
    DynamicSubcolumn {
        // Asserts dynamicElement(d, T) == CAST(d AS Nullable(T)). Short-circuits when no Dynamic
        // column exists. Workstream 6.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseDynamicSubcolumnOracle(globalState);
        }
    },
    SubqueryMaterialize {
        // Cross-statement materialization differential. Persists an inner filter step into a
        // physical Memory/Log temp table, then runs the outer predicate against the temp table and
        // diffs against the equivalent inline derived-table query. Crossing the CREATE/DROP boundary
        // disables the cross-query optimizer rewrite (OR-split / predicate-temp materialization /
        // scalar-subquery pushdown / RIGHT-JOIN default handling) so a divergence pinpoints exactly
        // that rewrite as buggy. This is the technique external fuzzer AnotherYx used to find the
        // v26.5.1.882 wrong-result cluster ClickHouse#106080 / #106082 / #106083 / #106084 /
        // #105717. It is NOT the same as KeyCondition's in-query materialize() wrap, which leaves
        // those rewrites enabled.
        @Override
        public TestOracle<ClickHouseGlobalState> create(ClickHouseGlobalState globalState) throws SQLException {
            return new ClickHouseSubqueryMaterializeOracle(globalState);
        }
    }
}
