package sqlancer.clickhouse.gen;

import java.util.Arrays;
import java.util.List;

import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseType;

public enum ClickHouseGeoFunction {

    POINT_IN_POLYGON("pointInPolygon", ArgShape.POINT_POLYGON, false),
    POLYGON_AREA_CARTESIAN("polygonAreaCartesian", ArgShape.POLYGON, false),
    POLYGON_AREA_SPHERICAL("polygonAreaSpherical", ArgShape.POLYGON, true),
    POLYGONS_DISTANCE_CARTESIAN("polygonsDistanceCartesian", ArgShape.POLYGON_POLYGON, true),
    POLYGONS_DISTANCE_SPHERICAL("polygonsDistanceSpherical", ArgShape.POLYGON_POLYGON, true),
    POLYGONS_WITHIN_CARTESIAN("polygonsWithinCartesian", ArgShape.POLYGON_POLYGON, true);

    public enum ArgShape {
        POINT_POLYGON, POLYGON, POLYGON_POLYGON
    }

    private final String name;
    private final ArgShape shape;
    private final boolean cpuHeavy;

    ClickHouseGeoFunction(String name, ArgShape shape, boolean cpuHeavy) {
        this.name = name;
        this.shape = shape;
        this.cpuHeavy = cpuHeavy;
    }

    public String getName() {
        return name;
    }

    public ArgShape getShape() {
        return shape;
    }

    public boolean isCpuHeavy() {
        return cpuHeavy;
    }

    public static List<ClickHouseGeoFunction> matching(ClickHouseType colType) {

        ClickHouseType u = colType.unwrap();
        ArgShape want;
        if (u instanceof ClickHouseType.Point) {
            want = ArgShape.POINT_POLYGON;
        } else if (u instanceof ClickHouseType.Polygon) {
            want = ArgShape.POLYGON;
        } else {
            return List.of();
        }
        return Arrays.stream(values()).filter(f -> f.shape == want || f.shape == ArgShape.POLYGON_POLYGON).toList();
    }

    public static ClickHouseGeoFunction pickFor(ClickHouseType colType, Randomly r) {
        List<ClickHouseGeoFunction> candidates = matching(colType);
        if (candidates.isEmpty()) {
            return null;
        }
        return Randomly.fromList(candidates);
    }
}
