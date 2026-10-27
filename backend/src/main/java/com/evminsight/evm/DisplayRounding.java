package com.evminsight.evm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Display rounding only. Calculations keep full DECIMAL128 precision; values are rounded
 * for presentation and export, never fed back into formulas.
 */
public final class DisplayRounding {

    private DisplayRounding() {}

    /** Money is rounded to the ISO 4217 minor-unit digits of the currency (JPY 0, USD 2, KWD 3), HALF_UP. */
    public static BigDecimal money(BigDecimal value, String iso4217Code) {
        int digits = fractionDigits(iso4217Code);
        return value.setScale(digits, RoundingMode.HALF_UP);
    }

    /** Indices (CPI, SPI, TCPI) are shown to 2 decimal places, HALF_UP. */
    public static BigDecimal index(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Percentages are shown to 1 decimal place, HALF_UP. */
    public static BigDecimal percent(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP);
    }

    public static int fractionDigits(String iso4217Code) {
        Currency currency;
        try {
            currency = Currency.getInstance(iso4217Code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidEvmInputException("Unknown ISO 4217 currency code.");
        }
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new InvalidEvmInputException("Currency " + iso4217Code + " has no defined minor unit.");
        }
        return digits;
    }
}
