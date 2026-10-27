package com.evminsight.evm;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single authoritative EVM engine (SRS sections 3.4 and 6). Pure functions, no I/O, no clock.
 *
 * <p>Policies (also documented in docs/calculation-policy.md):
 * <ul>
 *   <li>Arithmetic uses BigDecimal with DECIMAL128 (34 significant digits, HALF_EVEN) for divisions.</li>
 *   <li>Planned value is linear over elapsed calendar days between planned start and finish.</li>
 *   <li>EV is undefined if any eligible work package has no recorded progress; AC is undefined if any has
 *       no actual cost. Missing values are never treated as zero.</li>
 *   <li>A ratio whose denominator is not strictly positive is undefined.</li>
 * </ul>
 */
public final class EvmCalculator {

    public static final String CALCULATION_VERSION = "evm-1.0.0";
    public static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ONE = BigDecimal.ONE;

    private EvmCalculator() {}

    public static EvmResult calculate(List<WorkPackageInput> packages, LocalDate statusDate, EvmOptions options) {
        if (packages == null) {
            throw new InvalidEvmInputException("Work packages are required.");
        }
        if (statusDate == null) {
            throw new InvalidEvmInputException("Status date is required.");
        }
        EvmOptions opts = options == null ? EvmOptions.defaults() : options;
        rejectDuplicateIds(packages);

        Metric bac;
        Metric pv;
        Metric ev;
        Metric ac;
        if (packages.isEmpty()) {
            Metric none = Metric.undefined(UndefinedReason.MISSING_INPUT,
                    "There are no eligible work packages in the selected baseline.");
            bac = none;
            pv = none;
            ev = none;
            ac = none;
        } else {
            bac = Metric.of(packages.stream().map(WorkPackageInput::approvedBudget)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            pv = Metric.of(packages.stream().map(p -> plannedValue(p, statusDate))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));

            String missingProgress = idsWhere(packages, p -> p.completionPercent() == null);
            ev = missingProgress.isEmpty()
                    ? Metric.of(packages.stream().map(EvmCalculator::earnedValue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add))
                    : Metric.undefined(UndefinedReason.MISSING_INPUT,
                            "Progress has not been recorded for: " + missingProgress + ".");

            String missingCost = idsWhere(packages, p -> p.actualCost() == null);
            ac = missingCost.isEmpty()
                    ? Metric.of(packages.stream().map(WorkPackageInput::actualCost)
                            .reduce(BigDecimal.ZERO, BigDecimal::add))
                    : Metric.undefined(UndefinedReason.MISSING_INPUT,
                            "Actual cost has not been recorded for: " + missingCost + ".");
        }

        Metric cv = difference(ev, "EV", ac, "AC");
        Metric sv = difference(ev, "EV", pv, "PV");
        Metric cpi = ratio(ev, "EV", ac, "AC", ONE);
        Metric spi = ratio(ev, "EV", pv, "PV", ONE);
        Metric completion = ratio(ev, "EV", bac, "BAC", HUNDRED);
        Metric consumed = ratio(ac, "AC", bac, "BAC", HUNDRED);
        Metric eac = ratio(bac, "BAC", cpi, "CPI", ONE);
        Metric etc = difference(eac, "EAC", ac, "AC");
        Metric vac = difference(bac, "BAC", eac, "EAC");
        Metric tcpi = ratio(
                difference(bac, "BAC", ev, "EV"), "BAC - EV",
                difference(bac, "BAC", ac, "AC"), "BAC - AC", ONE);
        Metric composite = compositeEac(opts, bac, ev, ac, cpi, spi);

        return new EvmResult(CALCULATION_VERSION, statusDate, bac, pv, ev, ac, cv, sv, cpi, spi,
                completion, consumed, eac, etc, vac, tcpi, composite);
    }

    /** Linear planned value of one work package at a status date. Exposed for testing and explanations. */
    public static BigDecimal plannedValue(WorkPackageInput p, LocalDate statusDate) {
        if (statusDate.isBefore(p.plannedStart())) {
            return BigDecimal.ZERO;
        }
        if (!statusDate.isBefore(p.plannedFinish())) {
            return p.approvedBudget();
        }
        long elapsed = ChronoUnit.DAYS.between(p.plannedStart(), statusDate);
        long total = ChronoUnit.DAYS.between(p.plannedStart(), p.plannedFinish());
        return p.approvedBudget().multiply(BigDecimal.valueOf(elapsed)).divide(BigDecimal.valueOf(total), MC);
    }

    /** Earned value of one work package: approved budget x completion fraction. */
    public static BigDecimal earnedValue(WorkPackageInput p) {
        return p.approvedBudget().multiply(p.completionPercent()).divide(HUNDRED, MC);
    }

    private static Metric compositeEac(EvmOptions opts, Metric bac, Metric ev, Metric ac, Metric cpi, Metric spi) {
        if (!opts.compositeEac()) {
            return Metric.undefined(UndefinedReason.NOT_SELECTED,
                    "Composite EAC was not selected as the forecast method.");
        }
        String[] names = {"BAC", "EV", "AC", "CPI", "SPI"};
        Metric[] inputs = {bac, ev, ac, cpi, spi};
        for (int i = 0; i < inputs.length; i++) {
            if (!inputs[i].isDefined()) {
                return Metric.undefined(UndefinedReason.DEPENDS_ON_UNDEFINED,
                        "Depends on " + names[i] + ", which is undefined.");
            }
        }
        BigDecimal product = cpi.value().multiply(spi.value(), MC);
        if (product.signum() <= 0) {
            return Metric.undefined(UndefinedReason.ZERO_DENOMINATOR,
                    "CPI x SPI must be greater than 0 (is " + product.toPlainString() + ").");
        }
        BigDecimal remaining = bac.value().subtract(ev.value()).divide(product, MC);
        return Metric.of(ac.value().add(remaining));
    }

    private static Metric difference(Metric a, String aName, Metric b, String bName) {
        Metric blocked = dependencyBlock(a, aName, b, bName);
        if (blocked != null) {
            return blocked;
        }
        return Metric.of(a.value().subtract(b.value()));
    }

    /** (numerator x scale) / denominator, defined only when the denominator is strictly positive. */
    private static Metric ratio(Metric num, String numName, Metric den, String denName, BigDecimal scale) {
        Metric blocked = dependencyBlock(num, numName, den, denName);
        if (blocked != null) {
            return blocked;
        }
        if (den.value().signum() <= 0) {
            return Metric.undefined(UndefinedReason.ZERO_DENOMINATOR,
                    denName + " must be greater than 0 (is " + den.value().toPlainString() + ").");
        }
        return Metric.of(num.value().multiply(scale).divide(den.value(), MC));
    }

    private static Metric dependencyBlock(Metric a, String aName, Metric b, String bName) {
        if (!a.isDefined()) {
            return Metric.undefined(UndefinedReason.DEPENDS_ON_UNDEFINED, "Depends on " + aName + ", which is undefined.");
        }
        if (!b.isDefined()) {
            return Metric.undefined(UndefinedReason.DEPENDS_ON_UNDEFINED, "Depends on " + bName + ", which is undefined.");
        }
        return null;
    }

    private static void rejectDuplicateIds(List<WorkPackageInput> packages) {
        Set<String> seen = new HashSet<>();
        for (WorkPackageInput p : packages) {
            Objects.requireNonNull(p, "work package");
            if (!seen.add(p.id())) {
                throw new InvalidEvmInputException("Duplicate work package id: " + p.id() + ".");
            }
        }
    }

    private static String idsWhere(List<WorkPackageInput> packages,
                                   java.util.function.Predicate<WorkPackageInput> test) {
        List<String> ids = packages.stream().filter(test).map(WorkPackageInput::id).collect(Collectors.toList());
        if (ids.size() <= 5) {
            return String.join(", ", ids);
        }
        return String.join(", ", ids.subList(0, 5)) + " and " + (ids.size() - 5) + " more";
    }
}
