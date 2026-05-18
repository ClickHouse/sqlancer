package sqlancer.clickhouse.oracle.tlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseOracleFactory;
import sqlancer.clickhouse.ast.ClickHouseSetOperation.SetOpKind;

class ClickHouseTLPSetOpOracleSmokeTest {

    @Test
    void factoryEnumExposesSetOpTLPSlot() {
        ClickHouseOracleFactory slot = ClickHouseOracleFactory.valueOf("SetOpTLP");
        assertNotNull(slot);
        assertEquals("SetOpTLP", slot.name());
    }

    @Test
    void setOpKeywordsAreExplicitNotBare() {
        // The oracle relies on explicit ALL/DISTINCT keywords. A bare INTERSECT or EXCEPT keyword
        // depends on the version-sensitive *_default_mode settings -- the oracle deliberately
        // avoids those forms. This locks down the keyword set.
        assertEquals("UNION ALL", SetOpKind.UNION_ALL.getKeyword());
        assertEquals("UNION DISTINCT", SetOpKind.UNION_DISTINCT.getKeyword());
        assertEquals("INTERSECT ALL", SetOpKind.INTERSECT_ALL.getKeyword());
        assertEquals("INTERSECT DISTINCT", SetOpKind.INTERSECT_DISTINCT.getKeyword());
        assertEquals("EXCEPT ALL", SetOpKind.EXCEPT_ALL.getKeyword());
        assertEquals("EXCEPT DISTINCT", SetOpKind.EXCEPT_DISTINCT.getKeyword());
    }

    @Test
    void setOpErrorCatalogIsMultiWord() {
        // Per institutional learning: bare single-word substrings would absorb unrelated errors.
        // Assert every entry contains a space (multi-word) or matches a documented error code.
        for (String pattern : ClickHouseErrors.getSetOpErrors()) {
            boolean multiWord = pattern.contains(" ");
            boolean codeLike = pattern.equals(pattern.toUpperCase()) && pattern.contains("_");
            assertTrue(multiWord || codeLike,
                    "set-op error pattern must be multi-word or a snake-cased error code, got: " + pattern);
        }
    }

    @Test
    void setOpErrorCatalogExcludesUnknownSetting() {
        // Settings-probe errors must stay out of the oracle's error catalog -- adding them would
        // mask the probe's UNKNOWN_SETTING signal and let setting-name drift go undetected.
        for (String pattern : ClickHouseErrors.getSetOpErrors()) {
            assertTrue(
                    !pattern.contains("UNKNOWN_SETTING") && !pattern.contains("union_default_mode")
                            && !pattern.contains("intersect_default_mode") && !pattern.contains("except_default_mode"),
                    "set-op error catalog must not include settings-probe patterns; offender: " + pattern);
        }
    }
}
