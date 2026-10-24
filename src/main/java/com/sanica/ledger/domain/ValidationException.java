package com.sanica.ledger.domain;

public final class ValidationException extends LedgerException {

    public ValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
