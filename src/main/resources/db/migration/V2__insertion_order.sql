-- Strictly increasing insertion order for "newest first" lists. Timestamps can collide (coarse clocks) and are not
-- monotonic, the same reason ledger_entries has seq.
ALTER TABLE accounts ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE ledger_transactions ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;

CREATE INDEX accounts_seq_idx ON accounts (seq DESC);
CREATE INDEX ledger_transactions_seq_idx ON ledger_transactions (seq DESC);
