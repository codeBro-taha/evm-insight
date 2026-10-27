package com.evminsight.evm;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Complete, versioned result of one EVM calculation. */
public record EvmResult(
        String calculationVersion,
        LocalDate statusDate,
        Metric bac,
        Metric pv,
        Metric ev,
        Metric ac,
        Metric cv,
        Metric sv,
        Metric cpi,
        Metric spi,
        Metric completionPercent,
        Metric budgetConsumedPercent,
        Metric eacCpi,
        Metric etcCpi,
        Metric vacCpi,
        Metric tcpiBac,
        Metric eacComposite) {

    /** Metrics keyed by their stable code, in display order. Used by the API, exports and tests. */
    public Map<String, Metric> asMap() {
        Map<String, Metric> m = new LinkedHashMap<>();
        m.put("BAC", bac);
        m.put("PV", pv);
        m.put("EV", ev);
        m.put("AC", ac);
        m.put("CV", cv);
        m.put("SV", sv);
        m.put("CPI", cpi);
        m.put("SPI", spi);
        m.put("COMPLETION_PCT", completionPercent);
        m.put("BUDGET_CONSUMED_PCT", budgetConsumedPercent);
        m.put("EAC_CPI", eacCpi);
        m.put("ETC_CPI", etcCpi);
        m.put("VAC_CPI", vacCpi);
        m.put("TCPI_BAC", tcpiBac);
        m.put("EAC_COMPOSITE", eacComposite);
        return m;
    }
}
