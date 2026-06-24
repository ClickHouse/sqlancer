package sqlancer.cockroachdb.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import sqlancer.Randomly;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBCompositeDataType;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBDataType;
import sqlancer.cockroachdb.gen.CockroachDBExpressionGenerator;

public enum CockroachDBFunction {

    IF(null) {
        @Override
        public boolean isCompatibleWithReturnType(CockroachDBCompositeDataType returnType) {
            return true;
        }

        @Override
        public CockroachDBDataType[] getArgumentTypes(CockroachDBCompositeDataType returnType) {
            return new CockroachDBDataType[] { CockroachDBDataType.BOOL, returnType.getPrimitiveDataType(),
                    returnType.getPrimitiveDataType() };
        }
    },
    NULLIF(null) {
        @Override
        public boolean isCompatibleWithReturnType(CockroachDBCompositeDataType returnType) {
            return true;
        }

        @Override
        public CockroachDBDataType[] getArgumentTypes(CockroachDBCompositeDataType returnType) {
            return new CockroachDBDataType[] { returnType.getPrimitiveDataType(), returnType.getPrimitiveDataType() };
        }
    },

    ILIKE_ESCAPE(CockroachDBDataType.BOOL, CockroachDBDataType.STRING, CockroachDBDataType.STRING,
            CockroachDBDataType.STRING) {

        @Override
        List<CockroachDBExpression> getArgumentsForReturnType(CockroachDBExpressionGenerator gen, int depth,
                CockroachDBDataType[] argumentTypes2, CockroachDBCompositeDataType returnType2) {
            return getLastArgAsConstantString(gen, depth, argumentTypes2, returnType2);
        }

        private List<CockroachDBExpression> getLastArgAsConstantString(CockroachDBExpressionGenerator gen, int depth,
                CockroachDBDataType[] argumentTypes2, CockroachDBCompositeDataType returnType2) {
            List<CockroachDBExpression> args = super.getArgumentsForReturnType(gen, depth, argumentTypes2, returnType2);
            args.set(2, CockroachDBConstant.createStringConstant(gen.getGlobalState().getRandomly().getChar()));
            return args;
        }
    },

    ABS_INT("ABS", CockroachDBDataType.INT, CockroachDBDataType.INT),

    ACOS(CockroachDBDataType.FLOAT, CockroachDBDataType.FLOAT),

    ASCII(CockroachDBDataType.INT, CockroachDBDataType.STRING),
    BIT_LENGTH1("BIT_LENGTH", CockroachDBDataType.INT, CockroachDBDataType.BYTES),
    BIT_LENGTH2("BIT_LENGTH", CockroachDBDataType.INT, CockroachDBDataType.STRING),
    BTRIM1("BTRIM", CockroachDBDataType.STRING, CockroachDBDataType.STRING, CockroachDBDataType.STRING),

    BTRIM2("BTRIM", CockroachDBDataType.STRING, CockroachDBDataType.STRING),
    CHAR_LENGTH1("CHAR_LENGTH", CockroachDBDataType.INT, CockroachDBDataType.BYTES),
    CHAR_LENGTH2("CHAR_LENGTH", CockroachDBDataType.INT, CockroachDBDataType.STRING),
    CHARACTER_LENGTH1("CHARACTER_LENGTH", CockroachDBDataType.INT, CockroachDBDataType.STRING),
    CHARACTER_LENGTH2("CHARACTER_LENGTH", CockroachDBDataType.INT, CockroachDBDataType.BYTES),
    CHR(CockroachDBDataType.STRING, CockroachDBDataType.INT),
    INITCAP(CockroachDBDataType.STRING, CockroachDBDataType.STRING),
    LEFT1("LEFT", CockroachDBDataType.BYTES, CockroachDBDataType.BYTES, CockroachDBDataType.INT),
    LEFT2("LEFT", CockroachDBDataType.STRING, CockroachDBDataType.STRING, CockroachDBDataType.INT),
    LENGTH1("LENGTH", CockroachDBDataType.INT, CockroachDBDataType.BYTES),
    LENGTH2("LENGTH", CockroachDBDataType.INT, CockroachDBDataType.STRING),
    LOWER(CockroachDBDataType.STRING, CockroachDBDataType.STRING),

    LTRIM(CockroachDBDataType.STRING, CockroachDBDataType.STRING, CockroachDBDataType.STRING),
    OVERLAY(CockroachDBDataType.STRING, CockroachDBDataType.STRING, CockroachDBDataType.STRING,
            CockroachDBDataType.INT),
    QUOTE_IDENT(CockroachDBDataType.STRING, CockroachDBDataType.STRING),
    QUOTE_LITERAL(CockroachDBDataType.STRING, CockroachDBDataType.STRING),
    QUOTE_NULLABLE(CockroachDBDataType.STRING, CockroachDBDataType.STRING),
    REVERSE(CockroachDBDataType.STRING, CockroachDBDataType.STRING),
    STRPOS(CockroachDBDataType.INT, CockroachDBDataType.STRING, CockroachDBDataType.STRING),
    SPLIT_PART(CockroachDBDataType.STRING, CockroachDBDataType.STRING, CockroachDBDataType.STRING,
            CockroachDBDataType.INT),
    SUBSTRING1("SUBSTRING", CockroachDBDataType.STRING, CockroachDBDataType.STRING, CockroachDBDataType.STRING),
    SUBSTRING2("SUBSTRING", CockroachDBDataType.STRING, CockroachDBDataType.STRING, CockroachDBDataType.STRING,
            CockroachDBDataType.STRING),
    SUBSTRING3("SUBSTRING", CockroachDBDataType.STRING, CockroachDBDataType.STRING, CockroachDBDataType.INT),
    SUBSTRING4("SUBSTRING", CockroachDBDataType.STRING, CockroachDBDataType.STRING, CockroachDBDataType.INT,
            CockroachDBDataType.INT),

    TO_ENGLISH(CockroachDBDataType.STRING, CockroachDBDataType.INT),
    TO_HEX1("TO_HEX", CockroachDBDataType.STRING, CockroachDBDataType.INT),
    TO_HEX("TO_HEX", CockroachDBDataType.STRING, CockroachDBDataType.BYTES),
    TO_IP(CockroachDBDataType.BYTES, CockroachDBDataType.STRING),
    TO_UUID(CockroachDBDataType.BYTES, CockroachDBDataType.STRING),
    TRANSLATE(CockroachDBDataType.STRING, CockroachDBDataType.STRING, CockroachDBDataType.STRING,
            CockroachDBDataType.STRING),
    UPPER(CockroachDBDataType.STRING, CockroachDBDataType.STRING),
    REGEXP_REPLACE(CockroachDBDataType.STRING, CockroachDBDataType.STRING, CockroachDBDataType.STRING,
            CockroachDBDataType.STRING),

    MD5(CockroachDBDataType.STRING) {
        @Override
        public CockroachDBDataType[] getArgumentTypes(CockroachDBCompositeDataType returnType) {
            int nrArgs = Randomly.smallNumber() + 1;
            CockroachDBDataType[] argTypes = new CockroachDBDataType[nrArgs];
            for (int i = 0; i < nrArgs; i++) {
                argTypes[i] = CockroachDBDataType.STRING;
            }
            return argTypes;
        }
    },

    CURRENT_DATABASE(CockroachDBDataType.STRING), CURRENT_SCHEMA(CockroachDBDataType.STRING),
    CURRENT_USER(CockroachDBDataType.STRING), VERSION(CockroachDBDataType.STRING);

    private CockroachDBDataType returnType;
    private CockroachDBDataType[] argumentTypes;
    private String functionName;

    CockroachDBFunction(CockroachDBDataType returnType, CockroachDBDataType... argumentTypes) {
        this.returnType = returnType;
        this.argumentTypes = argumentTypes.clone();
        this.functionName = toString();
    }

    CockroachDBFunction(CockroachDBDataType returnType) {
        this.returnType = returnType;
        this.argumentTypes = new CockroachDBDataType[0];
        this.functionName = toString();
    }

    public String getFunctionName() {
        return functionName;
    }

    CockroachDBFunction(String functionName, CockroachDBDataType returnType, CockroachDBDataType... argumentTypes) {
        this.functionName = functionName;
        this.returnType = returnType;
        this.argumentTypes = argumentTypes.clone();
    }

    public boolean isCompatibleWithReturnType(CockroachDBCompositeDataType returnType) {
        return this.returnType == returnType.getPrimitiveDataType();
    }

    public CockroachDBDataType[] getArgumentTypes(CockroachDBCompositeDataType returnType) {
        return argumentTypes.clone();
    }

    public CockroachDBFunctionCall getCall(CockroachDBCompositeDataType returnType, CockroachDBExpressionGenerator gen,
            int depth) {
        CockroachDBDataType[] argumentTypes2 = getArgumentTypes(returnType);
        List<CockroachDBExpression> arguments = getArgumentsForReturnType(gen, depth, argumentTypes2, returnType);
        return new CockroachDBFunctionCall(this, arguments);
    }

    List<CockroachDBExpression> getArgumentsForReturnType(CockroachDBExpressionGenerator gen, int depth,
            CockroachDBDataType[] argumentTypes2, CockroachDBCompositeDataType returnType2) {
        List<CockroachDBExpression> arguments = new ArrayList<>();

        CockroachDBCompositeDataType savedArrayType = null;
        if (returnType2.getPrimitiveDataType() == CockroachDBDataType.ARRAY) {
            savedArrayType = returnType2;
        }
        for (CockroachDBDataType arg : argumentTypes2) {
            CockroachDBCompositeDataType type;
            if (arg == CockroachDBDataType.ARRAY) {
                if (savedArrayType == null) {
                    savedArrayType = arg.get();
                }
                type = savedArrayType;
            } else {
                type = arg.get();
            }
            arguments.add(gen.generateExpression(type, depth + 1));
        }
        return arguments;
    }

    public static List<CockroachDBFunction> getFunctionsCompatibleWith(CockroachDBCompositeDataType returnType) {
        return Stream.of(values()).filter(f -> f.isCompatibleWithReturnType(returnType)).collect(Collectors.toList());
    }

}
