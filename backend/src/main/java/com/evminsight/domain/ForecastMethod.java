package com.evminsight.domain;

/** Explicitly selected EAC method (FR-15). The engine never switches methods silently. */
public enum ForecastMethod {
    /** EAC = BAC / CPI */
    CPI,
    /** EAC = AC + (BAC - EV) / (CPI x SPI) */
    COMPOSITE
}
