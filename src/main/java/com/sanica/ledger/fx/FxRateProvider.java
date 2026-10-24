package com.sanica.ledger.fx;

import java.math.BigDecimal;

public interface FxRateProvider {

    /** Units of {@code to} received for one unit of {@code from}. */
    BigDecimal rate(String from, String to);
}
