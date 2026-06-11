package sqlancer.clickhouse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.common.query.ExpectedErrors;

class ClickHouseSessionSettingsErrorsTest {

    @Test
    void containsExpectedSettingSubstrings() {
        assertTrue(ClickHouseErrors.getSessionSettingsErrors().contains("Unknown setting"));
        assertTrue(ClickHouseErrors.getSessionSettingsErrors().contains("UNKNOWN_SETTING"));
    }

    @Test
    void absorbsSettingValidationErrors() {
        ExpectedErrors errors = ExpectedErrors.newErrors().build();
        ClickHouseErrors.addSessionSettingsErrors(errors);
        assertTrue(errors.errorIsExpected("Code: 115. DB::Exception: Unknown setting nonexistent_flag."));
        assertTrue(errors.errorIsExpected("Setting is neither a builtin setting nor a custom setting"));
        assertTrue(errors.errorIsExpected("Cannot parse setting value '999' for max_threads"));
        assertTrue(errors.errorIsExpected("Setting value out of range for max_block_size"));
        assertTrue(errors.errorIsExpected("Code: 115. (UNKNOWN_SETTING)"));
    }

    @Test
    void doesNotAbsorbUnrelatedErrors() {

        ExpectedErrors errors = ExpectedErrors.newErrors().build();
        ClickHouseErrors.addSessionSettingsErrors(errors);
        assertFalse(errors.errorIsExpected("Cannot convert string"));
        assertFalse(errors.errorIsExpected("Setting up the JOIN graph"));
        assertFalse(errors.errorIsExpected("Value is out of range for type Int32"));
        assertFalse(errors.errorIsExpected("DECIMAL_OVERFLOW"));
    }

}
