-- Accounts hold money in exactly one currency.
-- USER balances are materialised (updated in the same transaction as their entries) for O(1) reads;
-- EXTERNAL and FX_POOL system balances are always derived from the journal to avoid hot-row contention.
CREATE TABLE accounts (
    id          UUID                     NOT NULL,
    owner_id    VARCHAR(64)              NOT NULL,
    currency    CHAR(3)                  NOT NULL,
    kind        VARCHAR(16)              NOT NULL,
    balance     NUMERIC(19, 4)           NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT accounts_pk PRIMARY KEY (id),
    CONSTRAINT accounts_kind_chk CHECK (kind IN ('USER', 'EXTERNAL', 'FX_POOL')),
    -- Last line of defence: a user account can never be overdrawn, even if application checks are bypassed.
    CONSTRAINT accounts_user_non_negative_chk CHECK (kind <> 'USER' OR balance >= 0)
);

-- One row per business event (deposit, transfer). Immutable once written.
CREATE TABLE ledger_transactions (
    id                     UUID                     NOT NULL,
    type                   VARCHAR(16)              NOT NULL,
    source_account_id      UUID                     NOT NULL,
    destination_account_id UUID                     NOT NULL,
    source_amount          NUMERIC(19, 4)           NOT NULL,
    source_currency        CHAR(3)                  NOT NULL,
    destination_amount     NUMERIC(19, 4)           NOT NULL,
    destination_currency   CHAR(3)                  NOT NULL,
    fx_rate                NUMERIC(19, 8)           NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ledger_transactions_pk PRIMARY KEY (id),
    CONSTRAINT ledger_transactions_type_chk CHECK (type IN ('DEPOSIT', 'TRANSFER')),
    CONSTRAINT ledger_transactions_source_fk FOREIGN KEY (source_account_id) REFERENCES accounts (id),
    CONSTRAINT ledger_transactions_destination_fk FOREIGN KEY (destination_account_id) REFERENCES accounts (id)
);

-- Double-entry journal. Signed amounts: positive credits the account, negative debits it.
-- For every transaction, entries of each currency sum to exactly zero.
CREATE TABLE ledger_entries (
    id             UUID                     NOT NULL,
    -- Strictly increasing insertion order; wall-clock timestamps can collide and are not monotonic.
    seq            BIGINT                   GENERATED ALWAYS AS IDENTITY,
    transaction_id UUID                     NOT NULL,
    line_no        INTEGER                  NOT NULL,
    account_id     UUID                     NOT NULL,
    amount         NUMERIC(19, 4)           NOT NULL,
    currency       CHAR(3)                  NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ledger_entries_pk PRIMARY KEY (id),
    CONSTRAINT ledger_entries_transaction_line_uq UNIQUE (transaction_id, line_no),
    CONSTRAINT ledger_entries_amount_chk CHECK (amount <> 0),
    CONSTRAINT ledger_entries_transaction_fk FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (id),
    CONSTRAINT ledger_entries_account_fk FOREIGN KEY (account_id) REFERENCES accounts (id)
);

-- Serves "latest entries for an account" (statement view) as a single backward index range scan.
CREATE INDEX ledger_entries_account_seq_idx ON ledger_entries (account_id, seq DESC);

-- Source of truth for idempotent POSTs. Redis only caches these rows.
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(128)             NOT NULL,
    request_hash    VARCHAR(64)              NOT NULL,
    status_code     INTEGER                  NOT NULL,
    response_body   TEXT                     NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT idempotency_keys_pk PRIMARY KEY (idempotency_key)
);
