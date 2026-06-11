package sqlancer.presto.ast;

import sqlancer.presto.PrestoSchema.PrestoCompositeDataType;
import sqlancer.presto.PrestoSchema.PrestoDataType;

public enum PrestoDateFunction implements PrestoFunction {

    CURRENT_DATE("current_date", PrestoDataType.DATE),

    CURRENT_TIME("current_time", PrestoDataType.TIME_WITH_TIME_ZONE),

    CURRENT_TIMESTAMP("current_timestamp", PrestoDataType.TIMESTAMP_WITH_TIME_ZONE),

    CURRENT_TIMEZONE("current_timezone", PrestoDataType.VARCHAR),

    DATE("date", PrestoDataType.DATE, PrestoDataType.DATE, PrestoDataType.INT, PrestoDataType.VARCHAR),

    LAST_DAY_OF_MONTH("last_day_of_month", PrestoDataType.DATE, PrestoDataType.DATE),

    FROM_ISO8601_TIMESTAMP("from_iso8601_timestamp", PrestoDataType.TIMESTAMP_WITH_TIME_ZONE, PrestoDataType.VARCHAR),

    FROM_ISO8601_DATE("from_iso8601_date", PrestoDataType.DATE, PrestoDataType.VARCHAR),

    FROM_UNIXTIME("from_unixtime", PrestoDataType.TIMESTAMP, PrestoDataType.INT),

    FROM_UNIXTIME_TIMEZONE("from_unixtime", PrestoDataType.TIMESTAMP_WITH_TIME_ZONE, PrestoDataType.INT,
            PrestoDataType.VARCHAR) {
        @Override
        public boolean shouldPreserveOrderOfArguments() {
            return true;
        }
    },

    FROM_UNIXTIME_HOURS_MINUTES("from_unixtime", PrestoDataType.TIMESTAMP_WITH_TIME_ZONE, PrestoDataType.INT,
            PrestoDataType.INT) {
        @Override
        public boolean shouldPreserveOrderOfArguments() {
            return true;
        }
    },

    LOCALTIME("localtime", PrestoDataType.TIME),

    LOCALTIMESTAMP("localtimestamp", PrestoDataType.TIMESTAMP),

    NOW("now", PrestoDataType.TIMESTAMP_WITH_TIME_ZONE),

    TO_ISO8601("to_iso8601", PrestoDataType.VARCHAR, PrestoDataType.DATE, PrestoDataType.TIMESTAMP,
            PrestoDataType.TIMESTAMP_WITH_TIME_ZONE),

    TO_MILLISECONDS("to_milliseconds", PrestoDataType.INT, PrestoDataType.INTERVAL_DAY_TO_SECOND),
    TO_MILLISECONDS_2("to_milliseconds", PrestoDataType.INT, PrestoDataType.INTERVAL_YEAR_TO_MONTH),

    TO_UNIXTIME("to_unixtime", PrestoDataType.FLOAT, PrestoDataType.TIMESTAMP),
    TO_UNIXTIME_2("to_unixtime", PrestoDataType.FLOAT, PrestoDataType.TIMESTAMP_WITH_TIME_ZONE),

    CURRENT_DATE_NA("current_date", PrestoDataType.DATE) {
        @Override
        public boolean isStandardFunction() {
            return false;
        }
    },

    CURRENT_TIME_NA("current_time", PrestoDataType.TIME) {
        @Override
        public boolean isStandardFunction() {
            return false;
        }
    },

    CURRENT_TIMESTAMP_NA("current_timestamp", PrestoDataType.TIMESTAMP) {
        @Override
        public boolean isStandardFunction() {
            return false;
        }
    },

    LOCALTIME_NA("localtime", PrestoDataType.TIME) {
        @Override
        public boolean isStandardFunction() {
            return false;
        }
    },

    LOCALTIMESTAMP_NA("localtimestamp", PrestoDataType.TIMESTAMP) {
        @Override
        public boolean isStandardFunction() {
            return false;
        }
    },

    DATE_TRUNC_1("date_trunc", PrestoDataType.TIMESTAMP, PrestoDataType.VARCHAR, PrestoDataType.TIMESTAMP),
    DATE_TRUNC_2("date_trunc", PrestoDataType.TIMESTAMP_WITH_TIME_ZONE, PrestoDataType.VARCHAR,
            PrestoDataType.TIMESTAMP_WITH_TIME_ZONE),
    DATE_TRUNC_3("date_trunc", PrestoDataType.DATE, PrestoDataType.VARCHAR, PrestoDataType.DATE),
    DATE_TRUNC_4("date_trunc", PrestoDataType.TIME, PrestoDataType.VARCHAR, PrestoDataType.TIME);

    private final PrestoDataType returnType;
    private final PrestoDataType[] argumentTypes;
    private final String functionName;

    PrestoDateFunction(String functionName, PrestoDataType returnType, PrestoDataType... argumentTypes) {
        this.functionName = functionName;
        this.returnType = returnType;
        this.argumentTypes = argumentTypes.clone();
    }

    @Override
    public String getFunctionName() {
        return functionName;
    }

    @Override
    public boolean isCompatibleWithReturnType(PrestoCompositeDataType returnType) {
        return this.returnType == returnType.getPrimitiveDataType();
    }

    @Override
    public PrestoDataType[] getArgumentTypes(PrestoCompositeDataType returnType) {
        return argumentTypes.clone();
    }

}
