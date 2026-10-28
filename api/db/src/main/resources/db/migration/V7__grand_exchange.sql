CREATE TABLE ge_offers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('BUY', 'SELL')),
    item_id INTEGER NOT NULL,
    quantity INTEGER NOT NULL,
    remaining INTEGER NOT NULL,
    price_per INTEGER NOT NULL,
    gold_escrow INTEGER NOT NULL DEFAULT 0,
    gold_spent INTEGER NOT NULL DEFAULT 0,
    proceeds_gold INTEGER NOT NULL DEFAULT 0,
    items_filled INTEGER NOT NULL DEFAULT 0,
    tax_paid INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'COMPLETED', 'CANCELLED', 'COLLECTED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ge_offers_item_state ON ge_offers(item_id, state);

CREATE TABLE ge_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id INTEGER NOT NULL,
    quantity INTEGER NOT NULL,
    price_per INTEGER NOT NULL,
    gross INTEGER NOT NULL,
    tax INTEGER NOT NULL,
    buyer_account_id INTEGER NOT NULL,
    seller_account_id INTEGER NOT NULL,
    traded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ge_history_item_time ON ge_history(item_id, traded_at DESC);
