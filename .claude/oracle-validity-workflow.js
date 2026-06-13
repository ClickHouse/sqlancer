export const meta = {
  name: 'oracle-validity-audit',
  description: 'Judge each ClickHouse oracle\'s generated-query validity from per-oracle query_log error dumps',
  phases: [
    { title: 'Judge', detail: 'one agent per oracle classifies its error distribution + inspects sample failing queries' },
  ],
}

// Self-contained: hardcoded so it does not depend on args plumbing.
const VAL_DIR = '/home/nik/work/sqlancer-fork/val'
const ORACLES = [
  'TLPWhere', 'TLPDistinct', 'TLPGroupBy', 'TLPAggregate', 'TLPHaving', 'NoREC', 'PQS', 'CERT',
  'CODDTest', 'SEMR', 'SEMRMulti', 'EET', 'SetOpTLP', 'CombinatorTLP', 'QccCache', 'SortedUnionLimitBy',
  'SchemaRoundtrip', 'JoinAlgorithm', 'Cast', 'Parallelism', 'PartitionMirror', 'KeyCondition',
  'TableFunctionIN', 'ViewEquivalence', 'AggregateStateRoundtrip', 'MaterializedViewConsistency',
  'FinalMerge', 'ProjectionToggle', 'PatchPartConsistency', 'DictGetVsJoin', 'WindowEquivalence',
  'DynamicSubcolumn', 'SubqueryMaterialize', 'MutationAnalyzer', 'TextIndexLike', 'TopK', 'JoinReorder',
  'NaturalJoin', 'JsonSkipIndex', 'MaterializedCte', 'StatsToggle', 'ExtendedDatetime', 'JoinUseNulls',
  'QueryCache', 'TextIndexPreprocessor', 'TextIndexContainer', 'TextIndexLifecycle',
]

const VERDICT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['oracle', 'startedOk', 'totalQueries', 'failPct', 'verdict', 'generatorFaultCodes', 'summary'],
  properties: {
    oracle: { type: 'string' },
    startedOk: { type: 'boolean', description: 'false if the oracle failed to launch (STARTUP_ERROR / 0 queries)' },
    totalQueries: { type: 'integer', description: 'queries= value from the query_log totals line (0 if unknown)' },
    failPct: { type: 'number', description: 'fail_pct value from the totals line' },
    verdict: { type: 'string', enum: ['clean', 'minor_warts', 'broken', 'did_not_run'],
      description: 'clean = only data-dependent/by-design runtime failures; minor_warts = a few low-rate generator warts; broken = frequent malformed-SQL (syntax/identifier/arg-count) or oracle failed to run or internal LOGICAL_ERROR from the oracle itself' },
    generatorFaultCodes: { type: 'array', items: { type: 'string' },
      description: 'error codes judged to be the GENERATOR emitting invalid SQL (e.g. "62 SYNTAX_ERROR", "47 UNKNOWN_IDENTIFIER", "46 UNKNOWN_FUNCTION", "42 NUMBER_OF_ARGUMENTS_DOESNT_MATCH"). Empty if none.' },
    sampleBadQuery: { type: 'string', description: 'one representative malformed query text, or "" if none' },
    summary: { type: 'string', description: '1-3 sentence verdict rationale' },
  },
}

const RUBRIC = `You are auditing whether a SQLancer ClickHouse oracle's GENERATED QUERIES are well-formed SQL.

A differential SQL fuzzer INTENTIONALLY generates queries that fail at RUNTIME for data reasons -- that is healthy and expected, NOT a generator bug. Treat these as BY-DESIGN / data-dependent (do NOT count as generator faults):
- 153 ILLEGAL_DIVISION (intDiv/modulo by zero), 69 ARGUMENT_OUT_OF_BOUND, 407 DECIMAL_OVERFLOW, overflow/range
- 27 CANNOT_PARSE_INPUT_ASSERTION_FAILED, 72 CANNOT_PARSE_*, "value is too short", "Cannot parse * from String" (random string literals coerced to numbers)
- 241 MEMORY_LIMIT_EXCEEDED, 396/158 TOO_MANY_ROWS/result-overflow, 159 TIMEOUT
- 36 BAD_ARGUMENTS / 80 INCORRECT_QUERY on CREATE TABLE (the generator over-decorates schema then settles -- expected retries)
- 60 UNKNOWN_TABLE for "TRUNCATE TABLE system.query_log" (that is the harness, ignore it) or for transient DROP/rename races
- 70 CANNOT_CONVERT_TYPE / 43 ILLEGAL_TYPE_OF_ARGUMENT / 386 NO_COMMON_TYPE in ORDER BY / WHERE over deliberately-mixed random expressions -- borderline; only flag if pervasive

COUNT AS GENERATOR FAULTS (the generator emitted SQL that should never have been emitted):
- 62 SYNTAX_ERROR (malformed SQL grammar)
- 47 UNKNOWN_IDENTIFIER referencing a column/alias that should exist (NOT a deliberately-dropped SEMI/ANTI alias)
- 46 UNKNOWN_FUNCTION, 42 NUMBER_OF_ARGUMENTS_DOESNT_MATCH, 43 when a fixed-arity builtin is called wrong
- 10 NOT_FOUND_COLUMN_IN_BLOCK from the oracle's own projection, 184 ILLEGAL_AGGREGATION the oracle itself built
- Any 49 LOGICAL_ERROR / internal error the ORACLE'S queries trigger that is not a known-filed CH bug -> note it (could be a real CH bug OR an oracle building illegal SQL)

Also flag startedOk=false and verdict=did_not_run if the file shows STARTUP_ERROR or queries=0.

Read the sample failing-query texts to decide: is the failing query MALFORMED (generator fault) or a VALID query that failed for data/runtime reasons (by-design)? Base generatorFaultCodes on what the SAMPLE QUERIES actually show, not just the code name.

Be calibrated: most healthy oracles are "clean" or "minor_warts" with single-digit fail_pct dominated by div-by-zero / parse / type-coercion. Reserve "broken" for genuine malformed-SQL patterns, an oracle that didn't run, or oracle-built internal errors.`

const results = await parallel(ORACLES.map((o) => () =>
  agent(
    `${RUBRIC}\n\nRead the file ${VAL_DIR}/${o}.txt (a per-oracle query_log validity dump for oracle "${o}"). ` +
    `It contains: a header (version/exit/reproducers/progress, possibly STARTUP_ERROR), query_log totals (queries/failures/fail_pct), ` +
    `the error-code distribution, and up to 3 sample failing-query texts per code. ` +
    `Classify the failures and return your verdict for oracle "${o}".`,
    { schema: VERDICT_SCHEMA, phase: 'Judge', label: `judge:${o}` }
  ).catch(() => null)
))

const ok = results.filter(Boolean)
const byVerdict = { broken: [], did_not_run: [], minor_warts: [], clean: [] }
for (const r of ok) (byVerdict[r.verdict] || (byVerdict[r.verdict] = [])).push(r)

return {
  total: ORACLES.length,
  judged: ok.length,
  counts: Object.fromEntries(Object.entries(byVerdict).map(([k, v]) => [k, v.length])),
  broken: byVerdict.broken,
  did_not_run: byVerdict.did_not_run,
  minor_warts: byVerdict.minor_warts,
  clean: byVerdict.clean.map((r) => r.oracle),
  all: ok,
}
