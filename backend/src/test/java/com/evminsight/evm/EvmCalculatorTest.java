package com.evminsight.evm;

import static com.evminsight.evm.EvmTestSupport.assertUndefined;
import static com.evminsight.evm.EvmTestSupport.assertValue;
import static com.evminsight.evm.EvmTestSupport.date;
import static com.evminsight.evm.EvmTestSupport.wp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("NFR-02")
@DisplayName("EVM engine: formulas, boundaries and undefined cases")
class EvmCalculatorTest {

    private static final EvmOptions COMPOSITE = new EvmOptions(true);

    /** Hand-calculated dataset A (see docs/reference/evm-hand-calculations.md). */
    private static List<WorkPackageInput> datasetA() {
        return List.of(
                wp("WP1", "1000", "2026-01-01", "2026-01-11", "100", "1200"),
                wp("WP2", "2000", "2026-01-06", "2026-01-26", "25", "400"),
                wp("WP3", "500", "2026-02-01", "2026-02-11", "0", "0"));
    }

    @Nested
    @Tag("FR-13")
    @DisplayName("Normal case (hand-calculated)")
    class NormalCase {
        private final EvmResult r = EvmCalculator.calculate(datasetA(), date("2026-01-16"), COMPOSITE);

        @Test
        void coreMetrics() {
            assertValue("3500", r.bac());
            assertValue("2000", r.pv());
            assertValue("1500", r.ev());
            assertValue("1600", r.ac());
            assertValue("-100", r.cv());
            assertValue("-500", r.sv());
            assertValue("0.9375", r.cpi());
            assertValue("0.75", r.spi());
        }

        @Test
        void percentagesAndForecasts() {
            assertValue("42.857142857", r.completionPercent());
            assertValue("45.714285714", r.budgetConsumedPercent());
            assertValue("3733.333333333", r.eacCpi());
            assertValue("2133.333333333", r.etcCpi());
            assertValue("-233.333333333", r.vacCpi());
            assertValue("1.052631578", r.tcpiBac());
            assertValue("4444.444444444", r.eacComposite());
        }

        @Test
        void resultCarriesCalculationVersionAndStatusDate() {
            assertEquals("evm-1.0.0", r.calculationVersion());
            assertEquals(date("2026-01-16"), r.statusDate());
        }

        @Test
        void sameInputsGiveIdenticalResults() {
            EvmResult again = EvmCalculator.calculate(datasetA(), date("2026-01-16"), COMPOSITE);
            r.asMap().forEach((k, m) -> assertEquals(0, m.value().compareTo(again.asMap().get(k).value()), k));
        }
    }

    @Nested
    @DisplayName("Planned-value profile boundaries (linear, calendar days)")
    class PlannedValueBoundaries {
        private final WorkPackageInput p = wp("WP1", "1000", "2026-01-01", "2026-01-11", "0", "0"); // 10 days

        private BigDecimal pv(String status) {
            return EvmCalculator.plannedValue(p, date(status));
        }

        @Test
        void dayBeforeStartIsZero() {
            assertEquals(0, pv("2025-12-31").signum());
        }

        @Test
        void onStartDateIsZero() {
            assertEquals(0, pv("2026-01-01").signum());
        }

        @Test
        void midpointIsProrated() {
            assertEquals(0, new BigDecimal("500").compareTo(pv("2026-01-06")));
        }

        @Test
        void dayBeforeFinishIsNinetyPercent() {
            assertEquals(0, new BigDecimal("900").compareTo(pv("2026-01-10")));
        }

        @Test
        void onFinishDateIsFullBudget() {
            assertEquals(0, new BigDecimal("1000").compareTo(pv("2026-01-11")));
        }

        @Test
        void afterFinishStaysFullBudget() {
            assertEquals(0, new BigDecimal("1000").compareTo(pv("2027-06-01")));
        }

        @Test
        void sameStartAndFinishIsZeroBeforeAndFullOnOrAfter() {
            WorkPackageInput one = wp("WP9", "700", "2026-03-05", "2026-03-05", "0", "0");
            assertEquals(0, EvmCalculator.plannedValue(one, date("2026-03-04")).signum());
            assertEquals(0, new BigDecimal("700").compareTo(EvmCalculator.plannedValue(one, date("2026-03-05"))));
            assertEquals(0, new BigDecimal("700").compareTo(EvmCalculator.plannedValue(one, date("2026-03-06"))));
        }

        @Test
        void leapDayCountsAsACalendarDay() {
            WorkPackageInput leap = wp("WP8", "290", "2028-02-01", "2028-03-01", "0", "0"); // 29 days in Feb 2028
            assertEquals(0, new BigDecimal("10").compareTo(EvmCalculator.plannedValue(leap, date("2028-02-02"))));
        }
    }

    @Nested
    @DisplayName("Undefined cases: no fabricated zeroes")
    class UndefinedCases {

        @Test
        void zeroActualCostLeavesCpiUndefined() {
            EvmResult r = EvmCalculator.calculate(
                    List.of(wp("WP1", "1000", "2026-01-01", "2026-02-01", "0", "0")), date("2026-01-10"), COMPOSITE);
            assertValue("0", r.ac());
            assertUndefined(UndefinedReason.ZERO_DENOMINATOR, r.cpi());
            assertUndefined(UndefinedReason.DEPENDS_ON_UNDEFINED, r.eacCpi());
            assertUndefined(UndefinedReason.DEPENDS_ON_UNDEFINED, r.etcCpi());
            assertUndefined(UndefinedReason.DEPENDS_ON_UNDEFINED, r.vacCpi());
        }

        @Test
        void zeroPlannedValueLeavesSpiUndefined() {
            EvmResult r = EvmCalculator.calculate(
                    List.of(wp("WP1", "1000", "2026-02-01", "2026-03-01", "0", "0")), date("2026-01-10"), COMPOSITE);
            assertValue("0", r.pv());
            assertUndefined(UndefinedReason.ZERO_DENOMINATOR, r.spi());
            assertUndefined(UndefinedReason.DEPENDS_ON_UNDEFINED, r.eacComposite());
        }

        @Test
        void zeroBacLeavesPercentagesUndefined() {
            EvmResult r = EvmCalculator.calculate(
                    List.of(wp("WP1", "0", "2026-01-01", "2026-02-01", "50", "10")), date("2026-01-10"), COMPOSITE);
            assertUndefined(UndefinedReason.ZERO_DENOMINATOR, r.completionPercent());
            assertUndefined(UndefinedReason.ZERO_DENOMINATOR, r.budgetConsumedPercent());
            assertUndefined(UndefinedReason.ZERO_DENOMINATOR, r.eacCpi());
        }

        @Test
        void missingProgressMakesEvUndefinedAndNamesThePackage() {
            EvmResult r = EvmCalculator.calculate(List.of(
                    wp("WP1", "1000", "2026-01-01", "2026-02-01", null, "400"),
                    wp("WP2", "500", "2026-01-10", "2026-02-10", "50", "100")), date("2026-01-20"), COMPOSITE);
            assertUndefined(UndefinedReason.MISSING_INPUT, r.ev());
            assertTrue(r.ev().detail().contains("WP1"));
            assertValue("500", r.ac());
            assertUndefined(UndefinedReason.DEPENDS_ON_UNDEFINED, r.cv());
            assertUndefined(UndefinedReason.DEPENDS_ON_UNDEFINED, r.cpi());
            assertValue("1500", r.bac());
        }

        @Test
        void missingActualCostMakesAcUndefined() {
            EvmResult r = EvmCalculator.calculate(
                    List.of(wp("WP1", "1000", "2026-01-01", "2026-02-01", "40", null)), date("2026-01-20"), COMPOSITE);
            assertUndefined(UndefinedReason.MISSING_INPUT, r.ac());
            assertValue("400", r.ev());
            assertUndefined(UndefinedReason.DEPENDS_ON_UNDEFINED, r.cpi());
        }

        @Test
        void zeroProgressWithSpendGivesCpiZeroButNoCpiBasedForecast() {
            EvmResult r = EvmCalculator.calculate(
                    List.of(wp("WP1", "1000", "2026-01-01", "2026-02-01", "0", "250")), date("2026-01-20"), COMPOSITE);
            assertValue("0", r.cpi());
            assertUndefined(UndefinedReason.ZERO_DENOMINATOR, r.eacCpi());
            assertUndefined(UndefinedReason.ZERO_DENOMINATOR, r.eacComposite());
        }

        @Test
        void noWorkPackagesMakesEverythingUndefined() {
            EvmResult r = EvmCalculator.calculate(List.of(), date("2026-01-20"), COMPOSITE);
            r.asMap().forEach((code, metric) -> assertTrue(!metric.isDefined(), code + " should be undefined"));
            assertUndefined(UndefinedReason.MISSING_INPUT, r.bac());
        }

        @Test
        void compositeEacIsNotComputedUnlessExplicitlySelected() {
            EvmResult r = EvmCalculator.calculate(datasetA(), date("2026-01-16"), null);
            assertUndefined(UndefinedReason.NOT_SELECTED, r.eacComposite());
            assertValue("3733.333333333", r.eacCpi());
        }
    }

    @Nested
    @DisplayName("Overspend, negative variances and TCPI denominator")
    class Overspend {

        @Test
        void overspendGivesNegativeVariancesAndForecastOverrun() {
            EvmResult r = EvmCalculator.calculate(List.of(
                    wp("WP1", "1000", "2026-01-01", "2026-02-01", "80", "1500"),
                    wp("WP2", "500", "2026-01-15", "2026-03-01", "10", "300")), date("2026-02-10"), COMPOSITE);
            assertValue("850", r.ev());
            assertValue("1800", r.ac());
            assertValue("-950", r.cv());
            assertTrue(r.cpi().value().compareTo(BigDecimal.ONE) < 0);
            assertTrue(r.vacCpi().value().signum() < 0);
            assertUndefined(UndefinedReason.ZERO_DENOMINATOR, r.tcpiBac()); // BAC - AC = -300 (not positive)
        }

        @Test
        void tcpiIsUndefinedWhenAcEqualsBac() {
            EvmResult r = EvmCalculator.calculate(
                    List.of(wp("WP1", "1000", "2026-01-01", "2026-02-01", "90", "1000")), date("2026-02-01"), COMPOSITE);
            assertUndefined(UndefinedReason.ZERO_DENOMINATOR, r.tcpiBac());
        }
    }

    @Nested
    @Tag("FR-10")
    @DisplayName("Input validation")
    class Validation {

        @Test
        void completionAboveOneHundredIsRejected() {
            assertThrows(InvalidEvmInputException.class,
                    () -> wp("WP1", "100", "2026-01-01", "2026-02-01", "100.01", "0"));
        }

        @Test
        void negativeCompletionIsRejected() {
            assertThrows(InvalidEvmInputException.class,
                    () -> wp("WP1", "100", "2026-01-01", "2026-02-01", "-0.01", "0"));
        }

        @Test
        void completionBoundariesAreAccepted() {
            wp("WP1", "100", "2026-01-01", "2026-02-01", "0", "0");
            wp("WP2", "100", "2026-01-01", "2026-02-01", "100", "0");
        }

        @Test
        void negativeBudgetAndCostAreRejected() {
            assertThrows(InvalidEvmInputException.class, () -> wp("WP1", "-1", "2026-01-01", "2026-02-01", "0", "0"));
            assertThrows(InvalidEvmInputException.class, () -> wp("WP1", "1", "2026-01-01", "2026-02-01", "0", "-1"));
        }

        @Test
        void finishBeforeStartIsRejected() {
            assertThrows(InvalidEvmInputException.class, () -> wp("WP1", "1", "2026-02-01", "2026-01-01", "0", "0"));
        }

        @Test
        void duplicateIdsAreRejected() {
            List<WorkPackageInput> dup = List.of(
                    wp("WP1", "1", "2026-01-01", "2026-02-01", "0", "0"),
                    wp("WP1", "2", "2026-01-01", "2026-02-01", "0", "0"));
            assertThrows(InvalidEvmInputException.class, () -> EvmCalculator.calculate(dup, date("2026-01-10"), null));
        }

        @Test
        void missingStatusDateIsRejected() {
            assertThrows(InvalidEvmInputException.class, () -> EvmCalculator.calculate(datasetA(), null, null));
        }
    }

    @Nested
    @Tag("FR-12")
    @DisplayName("Display rounding and currency precision")
    class Rounding {

        @Test
        void minorUnitsFollowIso4217() {
            BigDecimal v = new BigDecimal("1234.56789");
            assertEquals("1234.57", DisplayRounding.money(v, "USD").toPlainString());
            assertEquals("1235", DisplayRounding.money(v, "JPY").toPlainString());
            assertEquals("1234.568", DisplayRounding.money(v, "KWD").toPlainString());
            assertEquals("1234.57", DisplayRounding.money(v, "PKR").toPlainString());
        }

        @Test
        void unknownCurrencyIsRejectedSafely() {
            assertThrows(InvalidEvmInputException.class, () -> DisplayRounding.money(BigDecimal.ONE, "ZZZ"));
            assertThrows(InvalidEvmInputException.class, () -> DisplayRounding.money(BigDecimal.ONE, "usd1"));
        }

        @Test
        void indicesAndPercentagesUseDocumentedPrecision() {
            assertEquals("0.94", DisplayRounding.index(new BigDecimal("0.9375")).toPlainString());
            assertEquals("42.9", DisplayRounding.percent(new BigDecimal("42.857142857")).toPlainString());
        }

        @Test
        void displayRoundingDoesNotChangeTheCalculatedValue() {
            EvmResult r = EvmCalculator.calculate(datasetA(), date("2026-01-16"), COMPOSITE);
            BigDecimal before = r.eacCpi().value();
            DisplayRounding.money(before, "USD");
            assertEquals(0, before.compareTo(r.eacCpi().value()));
            assertTrue(before.scale() > 2);
        }
    }
}
