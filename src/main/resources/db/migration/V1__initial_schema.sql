-- ---------------------------------------------------------------------------
-- Distributed Banking Transaction Service :: initial schema
-- ---------------------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- --------------------------------------------------------------------------
-- Users (JWT principals)
-- --------------------------------------------------------------------------
CREATE TABLE app_user (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(120) NOT NULL,
    full_name     VARCHAR(160) NOT NULL,
    roles         VARCHAR(255) NOT NULL DEFAULT 'ROLE_USER',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_app_user_username UNIQUE (username)
);

-- --------------------------------------------------------------------------
-- Accounts. `version` drives JPA optimistic locking; balance is never
-- updated without a version bump, so two concurrent transfers touching the
-- same account can never produce a lost update.
-- --------------------------------------------------------------------------
CREATE TABLE account (
    id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number VARCHAR(24)    NOT NULL,
    owner_id       UUID           NOT NULL REFERENCES app_user (id),
    currency       VARCHAR(3)     NOT NULL,
    balance        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    status         VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_account_number   UNIQUE (account_number),
    CONSTRAINT ck_account_balance  CHECK (balance >= 0),
    CONSTRAINT ck_account_status   CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE INDEX idx_account_owner ON account (owner_id);

-- --------------------------------------------------------------------------
-- Transfers. One row per money movement request.
-- --------------------------------------------------------------------------
CREATE TABLE transfer (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    reference         VARCHAR(40)    NOT NULL,
    source_account_id UUID           NOT NULL REFERENCES account (id),
    target_account_id UUID           NOT NULL REFERENCES account (id),
    amount            NUMERIC(19, 4) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    status            VARCHAR(16)    NOT NULL,
    description       VARCHAR(255),
    failure_reason    VARCHAR(255),
    idempotency_key   VARCHAR(120),
    initiated_by      VARCHAR(64)    NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    CONSTRAINT uk_transfer_reference UNIQUE (reference),
    CONSTRAINT ck_transfer_amount    CHECK (amount > 0),
    CONSTRAINT ck_transfer_accounts  CHECK (source_account_id <> target_account_id),
    CONSTRAINT ck_transfer_status    CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_transfer_source  ON transfer (source_account_id, created_at DESC);
CREATE INDEX idx_transfer_target  ON transfer (target_account_id, created_at DESC);
CREATE INDEX idx_transfer_created ON transfer (created_at DESC);

-- --------------------------------------------------------------------------
-- Double-entry ledger. Every completed transfer writes exactly two rows
-- (one DEBIT, one CREDIT) whose signed amounts sum to zero.
-- --------------------------------------------------------------------------
CREATE TABLE ledger_entry (
    id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id   UUID           NOT NULL REFERENCES transfer (id),
    account_id    UUID           NOT NULL REFERENCES account (id),
    direction     VARCHAR(6)     NOT NULL,
    amount        NUMERIC(19, 4) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_amount    CHECK (amount > 0),
    CONSTRAINT uk_ledger_leg       UNIQUE (transfer_id, account_id, direction)
);

CREATE INDEX idx_ledger_account ON ledger_entry (account_id, created_at DESC);

-- --------------------------------------------------------------------------
-- Idempotency. The UNIQUE constraint is the concurrency primitive: two
-- simultaneous requests carrying the same key race to INSERT, exactly one
-- wins, the loser is told the request is already in flight.
-- --------------------------------------------------------------------------
CREATE TABLE idempotency_record (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(120) NOT NULL,
    username        VARCHAR(64)  NOT NULL,
    endpoint        VARCHAR(160) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    state           VARCHAR(16)  NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_idempotency UNIQUE (idempotency_key, username),
    CONSTRAINT ck_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_idempotency_expiry ON idempotency_record (expires_at);

-- --------------------------------------------------------------------------
-- Transactional outbox. Events are written in the same transaction as the
-- balance change, then relayed to Kafka by a poller, so a transfer can
-- never commit without its event, and an event can never exist without its
-- transfer.
-- --------------------------------------------------------------------------
CREATE TABLE outbox_event (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(500)
);

CREATE INDEX idx_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

-- --------------------------------------------------------------------------
-- Audit log. Append-only record of every security- or money-relevant action.
-- --------------------------------------------------------------------------
CREATE TABLE audit_log (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    actor       VARCHAR(64) NOT NULL,
    action      VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id   VARCHAR(64),
    outcome     VARCHAR(16) NOT NULL,
    details     JSONB,
    client_ip   VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX idx_audit_actor  ON audit_log (actor, created_at DESC);
CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id);
