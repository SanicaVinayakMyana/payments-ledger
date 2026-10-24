package com.sanica.ledger.fx;

import com.sanica.ledger.domain.Currencies;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Fixed reference rates quoted against EUR; cross rates are derived from them.
 * A production system would swap this for a live, time-stamped rate feed behind the same interface.
 */
public final class StaticFxRateProvider implements FxRateProvider {

    public static final int RATE_SCALE = 8;

    private static final Map<String, BigDecimal> UNITS_PER_EUR = Map.of(
            "EUR", BigDecimal.ONE,
            "USD", new BigDecimal("1.08"),
            "GBP", new BigDecimal("0.85"),
            "PLN", new BigDecimal("4.30"),
            "INR", new BigDecimal("90.50"),
            "JPY", new BigDecimal("162.00"));

    @Override
    public BigDecimal rate(String from, String to) {
        Currencies.requireSupported(from);
        Currencies.requireSupported(to);
        if (from.equals(to)) {
            return BigDecimal.ONE.setScale(RATE_SCALE);
        }
        return UNITS_PER_EUR.get(to).divide(UNITS_PER_EUR.get(from), RATE_SCALE, RoundingMode.HALF_EVEN);
    }
}
