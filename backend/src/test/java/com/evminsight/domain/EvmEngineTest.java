package com.evminsight.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reference values below were derived by hand (see docs/reference-calculations.md), independent of the code. */
class EvmEngineTest {
    private final EvmEngine engine = new EvmEngine();

    private static BigDecimal d(String s) { return new BigDecimal(s); }
    private static LocalDate date(String s) { return LocalDate.parse(s); }
    private static void num(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but metric was undefined");
        assertEquals(0, d(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }
    private static WorkPackageInput wp(String id, String b, String s, String f, String c, String ac) {
        return new WorkPackageInput(id, d(b), date(s), date(f), c == null ? null : d(c), ac == null ? null : d(ac));
    }

    private List<WorkPackageInput> reference() {
        return List.of(wp("A", "1000", "2026-01-01", "2026-01-11", "0.5", "600"),
                       wp("B", "2000", "2026-01-06", "2026-02-05", "0.25", "700"));
    }

    @Test @DisplayName("REF-1 normal case, CPI method (NFR-02)")
    void referenceCpiMethod() {
        EvmResult r = engine.calculate(date("2026-01-06"), reference(), ForecastMethod.CPI);
        num("3000", r.bac()); num("500", r.pv()); num("1000", r.ev()); num("1300", r.ac());
        num("-300", r.cv()); num("500", r.sv());
        num("0.7692307692", r.cpi()); num("2", r.spi());
        num("33.3333333333", r.completionPct()); num("43.3333333333", r.budgetConsumedPct());
        num("3900", r.eac()); num("2600", r.etc()); num("-900", r.vac());
        num("1.1764705882", r.tcpi());
        assertTrue(r.undefinedReasons().isEmpty());
        assertEquals(EvmEngine.CALCULATION_VERSION, r.calculationVersion());
    }

    @Test @DisplayName("REF-1 composite method")
    void referenceComposite() {
        EvmResult r = engine.calculate(date("2026-01-06"), reference(), ForecastMethod.COMPOSITE);
        num("2600", r.eac()); num("1300", r.etc()); num("400", r.vac());
        assertEquals(ForecastMethod.COMPOSITE, r.forecastMethod());
    }

    // ---- planned value boundaries ----
    @Test void pvBeforeStartIsZero() { num("0", EvmEngine.plannedValue(wp("A","100","2026-03-10","2026-03-20",null,null), date("2026-03-09"))); }
    @Test void pvOnStartIsZero() { num("0", EvmEngine.plannedValue(wp("A","100","2026-03-10","2026-03-20",null,null), date("2026-03-10"))); }
    @Test void pvMidpoint() { num("50", EvmEngine.plannedValue(wp("A","100","2026-03-10","2026-03-20",null,null), date("2026-03-15"))); }
    @Test void pvDayBeforeFinish() { num("90", EvmEngine.plannedValue(wp("A","100","2026-03-10","2026-03-20",null,null), date("2026-03-19"))); }
    @Test void pvOnFinishIsFull() { num("100", EvmEngine.plannedValue(wp("A","100","2026-03-10","2026-03-20",null,null), date("2026-03-20"))); }
    @Test void pvAfterFinishIsFull() { num("100", EvmEngine.plannedValue(wp("A","100","2026-03-10","2026-03-20",null,null), date("2026-06-01"))); }
    @Test void pvSameDayStartFinish() {
        WorkPackageInput w = wp("A","100","2026-03-10","2026-03-10",null,null);
        num("0", EvmEngine.plannedValue(w, date("2026-03-09")));
        num("100", EvmEngine.plannedValue(w, date("2026-03-10")));
        num("100", EvmEngine.plannedValue(w, date("2026-03-11")));
    }
    @Test void pvOneThirdIsRoundedToTenDecimals() { num("33.3333333333", EvmEngine.plannedValue(wp("A","100","2026-03-01","2026-03-04",null,null), date("2026-03-02"))); }
    @Test void pvLeapYearUsesCalendarDays() { num("50", EvmEngine.plannedValue(wp("A","100","2028-02-28","2028-03-01",null,null), date("2028-02-29"))); }

    // ---- undefined cases: never fabricate zeros ----
    @Test void zeroAcMakesCpiAndForecastUndefined() {
        EvmResult r = engine.calculate(date("2026-01-06"),
                List.of(wp("A","1000","2026-01-01","2026-01-11","0.5","0")), ForecastMethod.CPI);
        assertNull(r.cpi()); assertNull(r.eac()); assertNull(r.etc()); assertNull(r.vac());
        assertTrue(r.undefinedReasons().containsKey("CPI"));
        assertTrue(r.undefinedReasons().containsKey("EAC"));
        num("500", r.cv());
    }
    @Test void zeroEvMakesEacUndefinedButCpiIsZero() {
        EvmResult r = engine.calculate(date("2026-01-06"),
                List.of(wp("A","1000","2026-01-01","2026-01-11","0","200")), ForecastMethod.CPI);
        num("0", r.cpi()); assertNull(r.eac());
    }
    @Test void zeroPvMakesSpiUndefined() {
        EvmResult r = engine.calculate(date("2025-12-01"),
                List.of(wp("A","1000","2026-01-01","2026-01-11","0","0")), ForecastMethod.CPI);
        assertNull(r.spi()); assertTrue(r.undefinedReasons().containsKey("SPI"));
    }
    @Test void zeroBacMakesPercentagesUndefined() {
        EvmResult r = engine.calculate(date("2026-01-06"),
                List.of(wp("A","0","2026-01-01","2026-01-11","0.5","0")), ForecastMethod.CPI);
        assertNull(r.completionPct()); assertNull(r.budgetConsumedPct());
    }
    @Test void missingProgressMakesEvDependentMetricsUndefined() {
        EvmResult r = engine.calculate(date("2026-01-06"),
                List.of(wp("A","1000","2026-01-01","2026-01-11",null,"100")), ForecastMethod.CPI);
        assertNull(r.ev()); assertNull(r.cv()); assertNull(r.sv()); assertNull(r.cpi()); assertNull(r.eac());
        assertTrue(r.undefinedReasons().get("EV").contains("A"));
        num("100", r.ac());
    }
    @Test void missingCostMakesAcUndefined() {
        EvmResult r = engine.calculate(date("2026-01-06"),
                List.of(wp("A","1000","2026-01-01","2026-01-11","0.5",null)), ForecastMethod.CPI);
        assertNull(r.ac()); assertNull(r.cv()); assertNull(r.cpi()); assertNull(r.tcpi());
        num("500", r.ev());
    }
    @Test void emptyPlanHasZeroSumsAndUndefinedRatios() {
        EvmResult r = engine.calculate(date("2026-01-06"), List.of(), ForecastMethod.CPI);
        num("0", r.bac()); assertNull(r.cpi()); assertNull(r.spi()); assertNull(r.completionPct());
    }

    // ---- overspend, negative variance, completion bounds ----
    @Test void overspendMakesTcpiUndefinedAndVacNegative() {
        EvmResult r = engine.calculate(date("2026-01-11"),
                List.of(wp("A","1000","2026-01-01","2026-01-11","0.5","1200")), ForecastMethod.CPI);
        num("-700", r.cv()); assertNull(r.tcpi());
        assertTrue(r.undefinedReasons().containsKey("TCPI"));
        num("2400", r.eac()); num("-1400", r.vac());
    }
    @Test void fullCompletionAllowedAndBoundariesAccepted() {
        EvmResult r = engine.calculate(date("2026-01-11"),
                List.of(wp("A","1000","2026-01-01","2026-01-11","1","900")), ForecastMethod.CPI);
        num("100", r.completionPct()); num("100", r.cv());
        assertDoesNotThrow(() -> wp("Z","1","2026-01-01","2026-01-01","0","0"));
    }
    @Test void compositeUndefinedWhenPvZero() {
        EvmResult r = engine.calculate(date("2025-12-01"),
                List.of(wp("A","1000","2026-01-01","2026-01-11","0.2","100")), ForecastMethod.COMPOSITE);
        assertNull(r.eac()); assertEquals(ForecastMethod.COMPOSITE, r.forecastMethod());
    }
    @Test void forecastMethodNeverSilentlySwitches() {
        EvmResult r = engine.calculate(date("2025-12-01"),
                List.of(wp("A","1000","2026-01-01","2026-01-11","0.2","100")), ForecastMethod.COMPOSITE);
        assertNull(r.eac(), "composite undefined must NOT fall back to CPI method");
    }

    // ---- invalid input ----
    @Test void rejectsCompletionAboveOne() { assertThrows(IllegalArgumentException.class, () -> wp("A","1","2026-01-01","2026-01-02","1.01","0")); }
    @Test void rejectsNegativeCompletion() { assertThrows(IllegalArgumentException.class, () -> wp("A","1","2026-01-01","2026-01-02","-0.01","0")); }
    @Test void rejectsNegativeBudget() { assertThrows(IllegalArgumentException.class, () -> wp("A","-1","2026-01-01","2026-01-02","0","0")); }
    @Test void rejectsNegativeCost() { assertThrows(IllegalArgumentException.class, () -> wp("A","1","2026-01-01","2026-01-02","0","-1")); }
    @Test void rejectsFinishBeforeStart() { assertThrows(IllegalArgumentException.class, () -> wp("A","1","2026-01-02","2026-01-01","0","0")); }
    @Test void rejectsBlankId() { assertThrows(IllegalArgumentException.class, () -> wp(" ","1","2026-01-01","2026-01-02","0","0")); }
    @Test void rejectsDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(date("2026-01-06"),
                List.of(wp("A","1","2026-01-01","2026-01-02","0","0"), wp("A","1","2026-01-01","2026-01-02","0","0")), ForecastMethod.CPI));
    }
    @Test void rejectsNullStatusDateAndMethod() {
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(null, List.of(), ForecastMethod.CPI));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(date("2026-01-06"), List.of(), null));
    }
}
