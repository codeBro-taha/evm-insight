package com.evminsight.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** ISO 4217 handling without assuming two decimals (FR-12). */
public final class Money {
    private Money() { }

    public static boolean isValidCurrency(String code) {
        if (code == null || !code.matches("[A-Z]{3}")) return false;
        try { return Currency.getInstance(code).getDefaultFractionDigits() >= 0; } catch (IllegalArgumentException e) { return false; }
    }

    public static int digits(String code) { return Currency.getInstance(code).getDefaultFractionDigits(); }

    /** True if the amount has no more decimals than the currency allows. */
    public static boolean fits(BigDecimal v, String code) { return v.stripTrailingZeros().scale() <= digits(code); }

    /** Normalise to the currency's scale (e.g. 300 -> 300.00 for USD, 300 for JPY). Call only after {@link #fits}. */
    public static BigDecimal normalise(BigDecimal v, String code) { return v.setScale(digits(code), RoundingMode.UNNECESSARY); }
}
