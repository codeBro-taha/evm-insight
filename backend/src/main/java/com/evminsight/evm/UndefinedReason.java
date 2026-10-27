package com.evminsight.evm;

/** Why a metric has no value. Undefined metrics are never replaced by zero (SRS section 6). */
public enum UndefinedReason {
    /** A required input (progress, actual cost, work packages) is missing. */
    MISSING_INPUT,
    /** A denominator is zero or not positive, so the formula is not defined. */
    ZERO_DENOMINATOR,
    /** The metric depends on another metric that is itself undefined. */
    DEPENDS_ON_UNDEFINED,
    /** An optional method (for example composite EAC) was not selected. */
    NOT_SELECTED
}
