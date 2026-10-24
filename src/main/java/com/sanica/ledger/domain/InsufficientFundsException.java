package com.sanica.ledger.domain;

import java.util.UUID;

public final class InsufficientFundsException extends LedgerException {

    public InsufficientFundsException(UUID accountId) {
        super("INSUFFICIENT_FUNDS", "Account " + accountId + " has insufficient funds");
    }
}
