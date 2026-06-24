package sqlancer.clickhouse.gen;

import java.util.List;

import sqlancer.Randomly;

public final class ClickHouseVariantPredicateFactory {

    private ClickHouseVariantPredicateFactory() {
    }

    static boolean gateOpen(boolean variantWhereEmission, boolean lowProbabilityRoll) {
        return variantWhereEmission && lowProbabilityRoll;
    }

    static String renderVariantElementEquals(String intExpr, String intLiteral) {
        return "(variantElement(CAST((" + intExpr + ") AS Variant(Int64, String)), 'Int64') = " + intLiteral + ")";
    }

    static String renderVariantTypeEquals(String innerExpr, String activeTypeName) {
        return "(variantType(CAST((" + innerExpr + ") AS Variant(Int64, String))) = '" + activeTypeName + "')";
    }

    static String renderVariantEquality(String leftIntExpr, String rightIntExpr) {
        return "(CAST((" + leftIntExpr + ") AS Variant(Int64, String)) = CAST((" + rightIntExpr
                + ") AS Variant(Int64, String)))";
    }

    static String renderNullVariantIsNull() {
        return "(variantElement(CAST(NULL AS Variant(Int64, String)), 'Int64') IS NULL)";
    }

    static String renderRandomFragment(List<String> intExprs, List<String> strExprs) {

        String fallback = "toInt64(42)";
        String intExpr = intExprs.isEmpty() ? fallback : Randomly.fromList(intExprs);
        switch ((int) Randomly.getNotCachedInteger(0, 4)) {
        case 0:
            return renderVariantElementEquals(intExpr, String.valueOf(Randomly.getNotCachedInteger(-128, 128)));
        case 1:
            boolean strArm = !strExprs.isEmpty() && Randomly.getBoolean();
            return renderVariantTypeEquals(strArm ? Randomly.fromList(strExprs) : intExpr,
                    strArm ? "String" : "Int64");
        case 2:
            return renderVariantEquality(intExpr, intExprs.isEmpty() ? fallback : Randomly.fromList(intExprs));
        default:
            return renderNullVariantIsNull();
        }
    }
}
