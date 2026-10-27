package com.evminsight.evm;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One eligible leaf work package as seen by the EVM engine, for one baseline and one reporting date.
 *
 * @param id                stable work-package identifier
 * @param approvedBudget    approved budget from the selected baseline (>= 0)
 * @param plannedStart      planned start date (date-only)
 * @param plannedFinish     planned finish date, not before the start
 * @param completionPercent recorded completion 0..100 inclusive, or null when no progress was reported
 * @param actualCost        cumulative actual cost through the status date (>= 0), or null when not reported
 */
public record WorkPackageInput(
        String id,
        BigDecimal approvedBudget,
        LocalDate plannedStart,
        LocalDate plannedFinish,
        BigDecimal completionPercent,
        BigDecimal actualCost) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public WorkPackageInput {
        if (id == null || id.isBlank()) {
            throw new InvalidEvmInputException("Work package id is required.");
        }
        if (approvedBudget == null || approvedBudget.signum() < 0) {
            throw new InvalidEvmInputException("Work package " + id + ": approved budget must be zero or greater.");
        }
        if (plannedStart == null || plannedFinish == null) {
            throw new InvalidEvmInputException("Work package " + id + ": planned start and finish dates are required.");
        }
        if (plannedFinish.isBefore(plannedStart)) {
            throw new InvalidEvmInputException("Work package " + id + ": planned finish must not be before planned start.");
        }
        if (completionPercent != null
                && (completionPercent.signum() < 0 || completionPercent.compareTo(HUNDRED) > 0)) {
            throw new InvalidEvmInputException("Work package " + id + ": completion must be between 0 and 100 inclusive.");
        }
        if (actualCost != null && actualCost.signum() < 0) {
            throw new InvalidEvmInputException("Work package " + id + ": actual cost must be zero or greater.");
        }
    }
}
