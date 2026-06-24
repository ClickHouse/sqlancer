package sqlancer.clickhouse.oracle.geo;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseGeoMetamorphicOracle implements TestOracle<ClickHouseGlobalState> {

    private static final double TOLERANCE = 1e-6;

    enum Mode {
        DISTANCE_SAME_POINT,
        POLYGON_AREA_NONNEGATIVE,
        UNIT_SQUARE_AREA,
        POINT_IN_POLYGON_INTERIOR,
        POINT_IN_POLYGON_OUTSIDE,
        SELF_INTERSECTION_AREA
    }

    private final ClickHouseGlobalState state;
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseGeoMetamorphicOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addSessionSettingsErrors(readErrors);

        readErrors.add("UNKNOWN_FUNCTION");
        readErrors.add("Unknown function");
        readErrors.add("ILLEGAL_TYPE_OF_ARGUMENT");
        readErrors.add("Illegal type");
        readErrors.add("NOT_IMPLEMENTED");
        readErrors.add("SUPPORT_IS_DISABLED");
        readErrors.add("geometry");
        readErrors.add("Polygon");
        readErrors.add("BAD_ARGUMENTS");

        readErrors.add("(MEMORY_LIMIT_EXCEEDED)");
        readErrors.add("memory limit exceeded");
        readErrors.add("(TIMEOUT_EXCEEDED)");
        readErrors.add("Timeout exceeded");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().geoMetamorphicOracle) {
            throw new IgnoreMeException();
        }
        Mode mode = Mode.values()[(int) Randomly.getNotCachedInteger(0, Mode.values().length)];
        switch (mode) {
        case DISTANCE_SAME_POINT:
            checkDistanceSamePoint();
            break;
        case POLYGON_AREA_NONNEGATIVE:
            checkPolygonAreaNonNegative();
            break;
        case UNIT_SQUARE_AREA:
            checkUnitSquareArea();
            break;
        case POINT_IN_POLYGON_INTERIOR:
            checkPointInPolygonInterior();
            break;
        case POINT_IN_POLYGON_OUTSIDE:
            checkPointInPolygonOutside();
            break;
        case SELF_INTERSECTION_AREA:
            checkSelfIntersectionArea();
            break;
        default:
            throw new AssertionError(mode);
        }
    }

    private void checkDistanceSamePoint() throws SQLException {
        Randomly r = state.getRandomly();
        int lon = (int) r.getInteger(-179, 179);
        int lat = (int) r.getInteger(-89, 89);
        String expr = "greatCircleDistance(" + lon + ", " + lat + ", " + lon + ", " + lat + ")";
        double observed = readDouble("SELECT toString(" + expr + ")");
        assertWithinTolerance(expr, 0.0, observed);
    }

    private void checkPolygonAreaNonNegative() throws SQLException {
        Randomly r = state.getRandomly();
        int w = 1 + (int) r.getInteger(0, 20);
        int h = 1 + (int) r.getInteger(0, 20);
        int ox = (int) r.getInteger(-10, 10);
        int oy = (int) r.getInteger(-10, 10);
        String polygon = rectanglePolygon(ox, oy, w, h);
        String expr = "polygonAreaCartesian(" + polygon + ")";
        double observed = readDouble("SELECT toString(" + expr + ")");
        if (observed < -TOLERANCE) {
            throw new AssertionError(String.format(
                    "geo polygonAreaCartesian negative: expr %s expected >= 0 but got %s", expr, observed));
        }
    }

    private void checkUnitSquareArea() throws SQLException {
        String polygon = rectanglePolygon(0, 0, 10, 10);
        String expr = "polygonAreaCartesian(" + polygon + ")";
        double observed = readDouble("SELECT toString(" + expr + ")");
        assertWithinTolerance(expr, 100.0, observed);
    }

    private void checkPointInPolygonInterior() throws SQLException {
        Randomly r = state.getRandomly();
        int ox = (int) r.getInteger(-10, 10);
        int oy = (int) r.getInteger(-10, 10);
        int side = 10;
        String ring = rectangleRing(ox, oy, side, side);
        int px = ox + 1 + (int) r.getInteger(0, side - 2);
        int py = oy + 1 + (int) r.getInteger(0, side - 2);
        String expr = "pointInPolygon((" + px + ", " + py + "), " + ring + ")";
        double observed = readDouble("SELECT toString(" + expr + ")");
        assertWithinTolerance(expr, 1.0, observed);
    }

    private void checkPointInPolygonOutside() throws SQLException {
        Randomly r = state.getRandomly();
        int ox = (int) r.getInteger(-10, 10);
        int oy = (int) r.getInteger(-10, 10);
        int side = 10;
        String ring = rectangleRing(ox, oy, side, side);
        int px = ox + side + 5 + (int) r.getInteger(0, 5);
        int py = oy + side + 5 + (int) r.getInteger(0, 5);
        String expr = "pointInPolygon((" + px + ", " + py + "), " + ring + ")";
        double observed = readDouble("SELECT toString(" + expr + ")");
        assertWithinTolerance(expr, 0.0, observed);
    }

    private void checkSelfIntersectionArea() throws SQLException {
        Randomly r = state.getRandomly();
        int w = 1 + (int) r.getInteger(0, 20);
        int h = 1 + (int) r.getInteger(0, 20);
        int ox = (int) r.getInteger(-10, 10);
        int oy = (int) r.getInteger(-10, 10);
        String polygon = rectanglePolygon(ox, oy, w, h);
        String areaExpr = "polygonAreaCartesian(" + polygon + ")";
        String intersectExpr = "polygonAreaCartesian(polygonsIntersectionCartesian(" + polygon + ", " + polygon + "))";
        String query = "SELECT toString(" + areaExpr + ") AS a, toString(" + intersectExpr + ") AS b";
        List<List<String>> rows = readRows(query, 2);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        double area = parseDouble(rows.get(0).get(0));
        double intersectionArea = parseDouble(rows.get(0).get(1));
        if (Math.abs(area - intersectionArea) > TOLERANCE * Math.max(1.0, Math.abs(area))) {
            throw new AssertionError(String.format(
                    "geo self-intersection area mismatch: %s == %s expected equal but area=%s intersection=%s",
                    areaExpr, intersectExpr, area, intersectionArea));
        }
    }

    private static String rectangleRing(int ox, int oy, int w, int h) {
        return "[(" + ox + ", " + oy + "), (" + ox + ", " + (oy + h) + "), (" + (ox + w) + ", " + (oy + h) + "), ("
                + (ox + w) + ", " + oy + "), (" + ox + ", " + oy + ")]";
    }

    private static String rectanglePolygon(int ox, int oy, int w, int h) {
        return "[" + rectangleRing(ox, oy, w, h) + "]";
    }

    private void assertWithinTolerance(String expr, double expected, double observed) {
        if (Math.abs(expected - observed) > TOLERANCE) {
            throw new AssertionError(String.format(
                    "geo metamorphic mismatch: expr %s expected %s but got %s (tolerance %s)", expr, expected, observed,
                    TOLERANCE));
        }
    }

    private double readDouble(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        return parseDouble(rows.get(0));
    }

    private List<List<String>> readRows(String query, int columns) throws SQLException {
        logStmt(query);
        List<List<String>> result = new ArrayList<>();
        try (java.sql.Statement s = state.getConnection().createStatement();
                java.sql.ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                List<String> row = new ArrayList<>(columns);
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getString(i));
                }
                result.add(row);
            }
        } catch (SQLException ex) {
            if (ex.getMessage() != null && readErrors.errorIsExpected(ex.getMessage())) {
                throw new IgnoreMeException();
            }
            throw ex;
        }
        return result;
    }

    private static double parseDouble(String s) {
        if (s == null) {
            throw new IgnoreMeException();
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            throw new IgnoreMeException();
        }
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
