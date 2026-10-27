package com.evminsight.evm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

final class EvmTestSupport {

    private EvmTestSupport() {}

    static final BigDecimal TOLERANCE = new BigDecimal("1e-9");

    static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }

    static WorkPackageInput wp(String id, String budget, String start, String finish, String percent, String cost) {
        return new WorkPackageInput(id, new BigDecimal(budget), date(start), date(finish),
                percent == null ? null : new BigDecimal(percent),
                cost == null ? null : new BigDecimal(cost));
    }

    static void assertValue(String expected, Metric actual) {
        assertTrue(actual.isDefined(), "expected a defined metric but it was undefined: " + actual.detail());
        BigDecimal diff = actual.value().subtract(new BigDecimal(expected)).abs();
        assertTrue(diff.compareTo(TOLERANCE) < 0,
                "expected " + expected + " but was " + actual.value().toPlainString());
    }

    static void assertUndefined(UndefinedReason reason, Metric actual) {
        assertTrue(!actual.isDefined(), "expected an undefined metric but it was " + actual.value());
        assertEquals(reason, actual.reason());
    }
}
