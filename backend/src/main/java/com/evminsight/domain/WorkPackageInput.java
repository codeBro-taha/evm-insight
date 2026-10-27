package com.evminsight.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One eligible LEAF work package in the selected baseline, as of one status date.
 * Parent WBS items must never be passed in (prevents double counting).
 *
 * @param completion  fraction 0..1 inclusive, or null if no progress was reported (EV then undefined)
 * @param actualCost  qualifying actual cost through the status date, or null if unknown (AC then undefined)
 */
public record WorkPackageInput(String id, BigDecimal budget, LocalDate start, LocalDate finish,
                               BigDecimal completion, BigDecimal actualCost) {
    public WorkPackageInput {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Work package id is required");
        if (budget == null || budget.signum() < 0)
            throw new IllegalArgumentException("Budget must be present and >= 0 for " + id);
        if (start == null || finish == null)
            throw new IllegalArgumentException("Planned start and finish are required for " + id);
        if (finish.isBefore(start))
            throw new IllegalArgumentException("Planned finish is before planned start for " + id);
        if (completion != null && (completion.signum() < 0 || completion.compareTo(BigDecimal.ONE) > 0))
            throw new IllegalArgumentException("Completion must be between 0 and 1 inclusive for " + id);
        if (actualCost != null && actualCost.signum() < 0)
            throw new IllegalArgumentException("Actual cost must be >= 0 for " + id);
    }
}
