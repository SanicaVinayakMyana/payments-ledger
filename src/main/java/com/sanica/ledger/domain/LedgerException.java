package com.sanica.ledger.domain;

/**
 * Base type for every business rule violation. Sealed so the API layer can map each case to an
 * HTTP status with an exhaustive switch.
 */
public abstract sealed class LedgerException extends RuntimeException
        permits NotFoundException, ValidationException, InsufficientFundsException, IdempotencyConflictException {

    private final String code;

    protected LedgerException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
