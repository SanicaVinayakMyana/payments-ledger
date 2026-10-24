package com.sanica.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Set;

/** Supported currencies and minor-unit rules (2 decimals for EUR, 0 for JPY, ...). */
public final class Currencies {

    public static final Set<String> SUPPORTED = Set.of("EUR", "USD", "GBP", "PLN", "INR", "JPY");

    /** Upper bound for a single movement; keeps every balance well inside NUMERIC(19,4). */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    private Currencies() {
    }

    public static String requireSupported(String code) {
        if (code == null || !SUPPORTED.contains(code)) {
            throw new ValidationException("Unsupported currency: " + code + ". Supported: " + SUPPORTED);
        }
        return code;
    }

    public static int scale(String code) {
        return Currency.getInstance(requireSupported(code)).getDefaultFractionDigits();
    }

    /**
     * Validates a client-supplied amount and returns it at the currency's exact scale.
     * Never rounds: an amount with more precision than the currency allows is rejected.
     */
    public static BigDecimal requirePositiveAmount(BigDecimal amount, String currency) {
        if (amount == null) {
            throw new ValidationException("amount is required");
        }
        if (amount.signum() <= 0) {
            throw new ValidationException("amount must be positive");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new ValidationException("amount must not exceed " + MAX_AMOUNT.toPlainString());
        }
        int scale = scale(currency);
        if (amount.stripTrailingZeros().scale() > scale) {
            throw new ValidationException("amount has more than " + scale + " decimal places for " + currency);
        }
        return amount.setScale(scale, RoundingMode.UNNECESSARY);
    }

    /** Renders a stored amount at the currency's scale, e.g. 10.5000 EUR -> "10.50". */
    public static String format(BigDecimal amount, String currency) {
        return amount.setScale(scale(currency), RoundingMode.UNNECESSARY).toPlainString();
    }
}
