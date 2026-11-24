-- Admin console API (content/other/admin-api).
--
-- admin_tokens: bearer tokens for the in-client admin console. Only the SHA-256 of a token is
--   stored; the raw token is shown once when it is issued. Each token acts as its account, and the
--   account's current mod level is read on every request, so demotion takes effect immediately.
-- admin_audit: one row per admin action, written by the API for every write, including refusals.
CREATE TABLE admin_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    label TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE INDEX idx_admin_tokens_account ON admin_tokens(account_id);

CREATE TABLE admin_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    actor_account_id INTEGER,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT,
    detail TEXT,
    ok BOOLEAN NOT NULL DEFAULT 1
);

CREATE INDEX idx_admin_audit_created ON admin_audit(created_at);
