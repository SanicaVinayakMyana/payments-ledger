package com.sanica.ledger.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of checking the two ledger invariants:
 * every currency nets to zero across the journal, and every materialised user balance equals the
 * sum of its entries.
 */
public record ReconciliationReport(Map<String, BigDecimal> netByCurrency, List<UUID> mismatchedAccounts) {

    public boolean balanced() {
        return mismatchedAccounts.isEmpty()
                && netByCurrency.values().stream().allMatch(net -> net.signum() == 0);
    }
}
