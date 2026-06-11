package sqlancer.tidb.ast;

import java.util.List;

import sqlancer.Randomly;

public class TiDBFunctionCall implements TiDBExpression {

    private TiDBFunction function;
    private List<TiDBExpression> args;

    public enum TiDBFunction {

        POW(2),
        POWER(2),
        EXP(1),
        SQRT(1),
        LN(1),
        LOG(1),
        LOG2(1),
        LOG10(1),
        PI(0),
        TAN(1),
        COT(1),
        SIN(1),
        COS(1),
        ATAN(1),
        ATAN2(2),
        ACOS(1),
        RADIANS(1),
        DEGREES(1),
        MOD(2),
        ABS(1),
        CEIL(1),
        CEILING(1),
        FLOOR(1),
        ROUND(1),

        SIGN(1),

        CRC32(1),

        BIT_COUNT(1),

        CONNECTION_ID(0),
        CURRENT_USER(0),
        DATABASE(0),

        SCHEMA(0),
        SESSION_USER(0),
        SYSTEM_USER(0),
        USER(0),
        VERSION(0),

        TIDB_VERSION(0),

        IF(3),
        IFNULL(2),
        NULLIF(2),

        ASCII(1),
        BIN(1),
        BIT_LENGTH(1),
        CHAR(1),
        CHAR_LENGTH(1),
        CHARACTER_LENGTH(1),
        CONCAT(1, true),
        CONCAT_WS(2, true),
        ELT(2, true),
        EXPORT_SET(0) {
            @Override
            public int getNrArgs() {
                return Randomly.fromOptions(3, 4, 5);
            }
        },

        FIELD(2, true),
        FIND_IN_SET(2),
        FORMAT(2),
        FROM_BASE64(1),
        HEX(1),
        INSERT(4),
        INSTR(2),

        REPLACE(3),
        REVERSE(1),
        RIGHT(2),

        RTRIM(1),
        SPACE(1),
        STRCMP(2),
        SUBSTRING(2),
        SUBSTRING_INDEX(3),
        TO_BASE64(1),
        TRIM(1),
        UCASE(1),
        UNHEX(1),
        UPPER(1),

        COALESCE(1, true),

        INET_ATON(1),
        INET_NTOA(1),
        INET6_ATON(1),
        INET6_NTOA(1),
        IS_IPV4(1),
        IS_IPV4_COMPAT(1),
        IS_IPV4_MAPPED(1),
        IS_IPV6(1),

        DATE_FORMAT(2),

        DEFAULT(-1);

        private int nrArgs;
        private boolean isVariadic;

        TiDBFunction(int nrArgs) {
            this.nrArgs = nrArgs;
        }

        TiDBFunction(int nrArgs, boolean isVariadic) {
            this.nrArgs = nrArgs;
            this.isVariadic = true;
        }

        public static TiDBFunction getRandom() {
            while (true) {
                TiDBFunction func = Randomly.fromOptions(values());
                if (func.getNrArgs() != -1) {

                    return func;
                }
            }
        }

        public int getNrArgs() {
            return nrArgs + (isVariadic() ? Randomly.smallNumber() : 0);
        }

        public boolean isVariadic() {
            return isVariadic;
        }

    }

    public TiDBFunctionCall(TiDBFunction function, List<TiDBExpression> args) {
        this.function = function;
        this.args = args;
    }

    public List<TiDBExpression> getArgs() {
        return args;
    }

    public TiDBFunction getFunction() {
        return function;
    }

}
