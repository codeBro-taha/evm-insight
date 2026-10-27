package com.evminsight.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single authoritative EVM engine (SRS section 6, NFR-09). Pure domain logic: no I/O, no framework.
 * Linear planned-value profile over elapsed calendar days (documented simplification).
 */
public final class EvmEngine {
    public static final String CALCULATION_VERSION = "evm-1.0.0";
    static final int SCALE = 10;
    static final RoundingMode RM = RoundingMode.HALF_EVEN;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public EvmResult calculate(LocalDate statusDate, List<WorkPackageInput> packages, ForecastMethod method) {
        if (statusDate == null) throw new IllegalArgumentException("Status date is required");
        if (packages == null) throw new IllegalArgumentException("Work packages are required");
        if (method == null) throw new IllegalArgumentException("Forecast method must be selected explicitly");

        Set<String> ids = new HashSet<>();
        for (WorkPackageInput wp : packages)
            if (!ids.add(wp.id())) throw new IllegalArgumentException("Duplicate work package id: " + wp.id());

        Map<String, String> why = new LinkedHashMap<>();

        BigDecimal bac = BigDecimal.ZERO;
        BigDecimal pv = BigDecimal.ZERO;
        BigDecimal ev = BigDecimal.ZERO;
        BigDecimal ac = BigDecimal.ZERO;
        List<String> noProgress = new java.util.ArrayList<>();
        List<String> noCost = new java.util.ArrayList<>();

        for (WorkPackageInput wp : packages) {
            bac = bac.add(wp.budget());
            pv = pv.add(plannedValue(wp, statusDate));
            if (wp.completion() == null) noProgress.add(wp.id()); else ev = ev.add(wp.budget().multiply(wp.completion()));
            if (wp.actualCost() == null) noCost.add(wp.id()); else ac = ac.add(wp.actualCost());
        }

        BigDecimal evV = ev, acV = ac;
        if (!noProgress.isEmpty()) {
            evV = null;
            why.put("EV", "No progress recorded for: " + String.join(", ", noProgress));
        }
        if (!noCost.isEmpty()) {
            acV = null;
            why.put("AC", "No actual cost available for: " + String.join(", ", noCost));
        }

        BigDecimal cv = null, sv = null, cpi = null, spi = null, compl = null, consumed = null;
        BigDecimal eac = null, etc = null, vac = null, tcpi = null;

        if (evV != null && acV != null) cv = evV.subtract(acV); else why.put("CV", "Requires EV and AC");
        if (evV != null) sv = evV.subtract(pv); else why.put("SV", "Requires EV");

        if (evV == null || acV == null) why.put("CPI", "Requires EV and AC");
        else if (acV.signum() <= 0) why.put("CPI", "AC is zero; CPI = EV / AC is undefined");
        else cpi = evV.divide(acV, SCALE, RM);

        if (evV == null) why.put("SPI", "Requires EV");
        else if (pv.signum() <= 0) why.put("SPI", "PV is zero; SPI = EV / PV is undefined");
        else spi = evV.divide(pv, SCALE, RM);

        if (evV == null) why.put("completionPct", "Requires EV");
        else if (bac.signum() <= 0) why.put("completionPct", "BAC is zero; undefined");
        else compl = evV.multiply(HUNDRED).divide(bac, SCALE, RM);

        if (acV == null) why.put("budgetConsumedPct", "Requires AC");
        else if (bac.signum() <= 0) why.put("budgetConsumedPct", "BAC is zero; undefined");
        else consumed = acV.multiply(HUNDRED).divide(bac, SCALE, RM);

        // Forecast: computed from exact quantities (BAC*AC/EV == BAC/CPI) to avoid compounding rounded CPI.
        if (method == ForecastMethod.CPI) {
            if (evV == null || acV == null) why.put("EAC", "CPI method requires EV and AC");
            else if (evV.signum() <= 0 || acV.signum() <= 0)
                why.put("EAC", "CPI method requires CPI > 0 (EV and AC must both be > 0)");
            else eac = bac.multiply(acV).divide(evV, SCALE, RM);
        } else {
            if (evV == null || acV == null) why.put("EAC", "Composite method requires EV and AC");
            else if (evV.signum() <= 0 || acV.signum() <= 0 || pv.signum() <= 0)
                why.put("EAC", "Composite method requires CPI > 0 and SPI > 0 (EV, AC and PV must all be > 0)");
            else eac = acV.add(bac.subtract(evV).multiply(acV).multiply(pv).divide(evV.multiply(evV), SCALE, RM));
        }
        if (eac != null) {
            etc = eac.subtract(acV);
            vac = bac.subtract(eac);
        } else {
            why.put("ETC", "Requires EAC");
            why.put("VAC", "Requires EAC");
        }

        if (evV == null || acV == null) why.put("TCPI", "Requires EV and AC");
        else if (bac.subtract(acV).signum() <= 0) why.put("TCPI", "BAC - AC is not positive (budget exhausted or overspent)");
        else tcpi = bac.subtract(evV).divide(bac.subtract(acV), SCALE, RM);

        return new EvmResult(CALCULATION_VERSION, statusDate, method, bac, pv, evV, acV, cv, sv, cpi, spi,
                compl, consumed, eac, etc, vac, tcpi, Map.copyOf(why));
    }

    /** Linear PV contribution of one work package at the status date (SRS section 6). */
    static BigDecimal plannedValue(WorkPackageInput wp, LocalDate d) {
        if (d.isBefore(wp.start())) return BigDecimal.ZERO;
        if (!d.isBefore(wp.finish())) return wp.budget();   // on/after finish (also covers start == finish)
        long total = ChronoUnit.DAYS.between(wp.start(), wp.finish());   // > 0 here
        long elapsed = ChronoUnit.DAYS.between(wp.start(), d);
        return wp.budget().multiply(BigDecimal.valueOf(elapsed)).divide(BigDecimal.valueOf(total), SCALE, RM);
    }
}
