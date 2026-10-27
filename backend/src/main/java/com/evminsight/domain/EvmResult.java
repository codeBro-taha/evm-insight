package com.evminsight.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Result of one EVM calculation. A null metric means UNDEFINED (never a fabricated zero);
 * the reason is in {@code undefinedReasons} keyed by metric name.
 * Ratios/divisions are unrounded to 10 decimal places (HALF_EVEN); display rounding is a presentation concern.
 */
public record EvmResult(String calculationVersion, LocalDate statusDate, ForecastMethod forecastMethod,
                        BigDecimal bac, BigDecimal pv, BigDecimal ev, BigDecimal ac,
                        BigDecimal cv, BigDecimal sv, BigDecimal cpi, BigDecimal spi,
                        BigDecimal completionPct, BigDecimal budgetConsumedPct,
                        BigDecimal eac, BigDecimal etc, BigDecimal vac, BigDecimal tcpi,
                        Map<String, String> undefinedReasons) {
}
