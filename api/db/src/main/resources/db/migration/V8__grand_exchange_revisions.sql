-- Offers are persisted asynchronously on a multi-threaded worker pool, so writes for the same
-- offer can land out of order or be retried. `revision` lets the upsert reject stale snapshots.
ALTER TABLE ge_offers ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;

-- A buy/sell pair can only ever match once (each fill exhausts one side), so the pair uniquely
-- identifies a trade and lets retried history inserts be ignored instead of duplicated.
-- Rows written before this migration keep NULL ids; SQLite treats NULLs as distinct.
ALTER TABLE ge_history ADD COLUMN buy_offer_id INTEGER;
ALTER TABLE ge_history ADD COLUMN sell_offer_id INTEGER;

CREATE UNIQUE INDEX idx_ge_history_offer_pair ON ge_history(buy_offer_id, sell_offer_id);

CREATE INDEX idx_ge_offers_account_state ON ge_offers(account_id, state);
