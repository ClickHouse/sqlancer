package sqlancer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class ComparatorHelperTest {

    @Test
    void ulpApartSumsAreEqual() {
        assertTrue(ComparatorHelper.floatTolerantMultisetsEqual(Collections.singletonList("1336.994494222314"),
                Collections.singletonList("1336.9944942223137")));
        assertTrue(ComparatorHelper.floatTolerantMultisetsEqual(Collections.singletonList("3.644371228669842E7"),
                Collections.singletonList("3.644371228669843E7")));
        assertTrue(ComparatorHelper.floatTolerantMultisetsEqual(Collections.singletonList("8.59070260622426E9"),
                Collections.singletonList("8.59070260622424E9")));
    }

    @Test
    void genuinelyDifferentFloatsAreNotEqual() {
        assertFalse(ComparatorHelper.floatTolerantMultisetsEqual(Collections.singletonList("1336.99"),
                Collections.singletonList("1340.00")));

        assertFalse(ComparatorHelper.floatTolerantMultisetsEqual(Collections.singletonList("1336.9944"),
                Collections.singletonList("1336.9954")));
    }

    @Test
    void distinctIntegersAreNeverCollapsed() {
        assertFalse(ComparatorHelper.floatTolerantMultisetsEqual(Collections.singletonList("5"),
                Collections.singletonList("6")));
    }

    @Test
    void nonFiniteAndNonNumericMatchExactly() {

        assertTrue(
                ComparatorHelper.floatTolerantMultisetsEqual(Arrays.asList("nan", "1.0"), Arrays.asList("1.0", "nan")));
        assertFalse(ComparatorHelper.floatTolerantMultisetsEqual(Collections.singletonList("nan"),
                Collections.singletonList("1.0")));
        assertTrue(ComparatorHelper.floatTolerantMultisetsEqual(Collections.singletonList("foo"),
                Collections.singletonList("foo")));
        assertFalse(ComparatorHelper.floatTolerantMultisetsEqual(Collections.singletonList("foo"),
                Collections.singletonList("bar")));
    }

    @Test
    void sizeMismatchIsNotEqual() {
        assertFalse(ComparatorHelper.floatTolerantMultisetsEqual(Arrays.asList("1.0", "2.0"),
                Collections.singletonList("1.0")));
    }

    @Test
    void multiRowAggregatesPairWithinTolerance() {
        assertTrue(ComparatorHelper.floatTolerantMultisetsEqual(Arrays.asList("1336.994494222314", "7.26819366857713"),
                Arrays.asList("7.268193668577131", "1336.9944942223137")));
    }
}
