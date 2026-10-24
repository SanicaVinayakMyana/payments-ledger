package com.sanica.ledger.domain;

public final class NotFoundException extends LedgerException {

    public NotFoundException(String message) {
        super("NOT_FOUND", message);
    }
}
