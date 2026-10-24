package com.sanica.ledger.domain;

public enum AccountKind {
    /** Customer account; balance is materialised and may never go negative. */
    USER,
    /** Per-currency counterparty for money entering the ledger (card top-ups, bank transfers in). */
    EXTERNAL,
    /** Per-currency treasury pool that buys and sells currency for cross-currency transfers. */
    FX_POOL
}
