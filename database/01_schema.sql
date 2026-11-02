-- Struktur tabel dan function layanan transfer.
-- Dijalankan otomatis oleh container postgres saat volume masih kosong.

CREATE TABLE customers (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_number VARCHAR(20)   NOT NULL UNIQUE,
    customer_id    BIGINT        NOT NULL REFERENCES customers (id),
    balance        NUMERIC(18,2) NOT NULL DEFAULT 0,
    status         VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Lapisan terakhir: saldo tidak mungkin minus walau ada bug di aplikasi
    CONSTRAINT chk_accounts_balance CHECK (balance >= 0),
    CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

CREATE INDEX idx_accounts_customer ON accounts (customer_id);

CREATE TABLE transfers (
    id                         UUID          PRIMARY KEY,
    customer_id                BIGINT        NOT NULL REFERENCES customers (id),
    idempotency_key            VARCHAR(64)   NOT NULL,
    request_hash               CHAR(64)      NOT NULL,
    source_account_number      VARCHAR(20)   NOT NULL REFERENCES accounts (account_number),
    destination_account_number VARCHAR(20)   NOT NULL REFERENCES accounts (account_number),
    amount                     NUMERIC(18,2) NOT NULL,
    description                VARCHAR(140),
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Penjaga utama duplicate request: satu key hanya bisa dipakai sekali per nasabah
    CONSTRAINT uq_transfers_idempotency UNIQUE (customer_id, idempotency_key),
    CONSTRAINT chk_transfers_amount CHECK (amount > 0),
    CONSTRAINT chk_transfers_accounts CHECK (source_account_number <> destination_account_number)
);

CREATE INDEX idx_transfers_source ON transfers (source_account_number, created_at);
CREATE INDEX idx_transfers_destination ON transfers (destination_account_number, created_at);

-- Jejak audit: setiap transfer menghasilkan tepat satu DEBIT dan satu CREDIT
CREATE TABLE account_mutations (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id    UUID          NOT NULL REFERENCES transfers (id),
    account_number VARCHAR(20)   NOT NULL REFERENCES accounts (account_number),
    type           VARCHAR(6)    NOT NULL,
    amount         NUMERIC(18,2) NOT NULL,
    balance_after  NUMERIC(18,2) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_mutations_transfer_type UNIQUE (transfer_id, type),
    CONSTRAINT chk_mutations_type CHECK (type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_mutations_amount CHECK (amount > 0)
);

CREATE INDEX idx_mutations_account ON account_mutations (account_number, created_at);

-- Function trigger agar updated_at selalu terisi, termasuk saat data diubah di luar aplikasi
CREATE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
