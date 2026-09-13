CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    user_key VARCHAR(64) NOT NULL,
    balance_paise BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wallet_user_key UNIQUE (user_key),
    CONSTRAINT ck_wallet_balance_non_negative CHECK (balance_paise >= 0)
);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    initiated_by_user_key VARCHAR(64) NOT NULL,
    from_wallet_id UUID NOT NULL REFERENCES wallets(id),
    to_wallet_id UUID NOT NULL REFERENCES wallets(id),
    amount_paise BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_transfer_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_transfer_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT ck_transfer_wallets_different CHECK (from_wallet_id <> to_wallet_id),
    CONSTRAINT ck_transfer_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'DECLINED'))
);

CREATE INDEX idx_transfers_from_wallet ON transfers(from_wallet_id);
CREATE INDEX idx_transfers_to_wallet ON transfers(to_wallet_id);
CREATE INDEX idx_transfers_created_at ON transfers(created_at);
