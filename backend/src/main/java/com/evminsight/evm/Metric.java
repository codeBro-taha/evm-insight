package com.evminsight.evm;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A calculated metric: either a defined value, or an explicit "undefined" state with a reason.
 * Exactly one of value / reason is present.
 */
public record Metric(BigDecimal value, UndefinedReason reason, String detail) {

    public Metric {
        if ((value == null) == (reason == null)) {
            throw new IllegalArgumentException("A metric must have either a value or an undefined reason.");
        }
    }

    public static Metric of(BigDecimal value) {
        return new Metric(Objects.requireNonNull(value), null, null);
    }

    public static Metric undefined(UndefinedReason reason, String detail) {
        return new Metric(null, Objects.requireNonNull(reason), detail);
    }

    public boolean isDefined() {
        return value != null;
    }
}
