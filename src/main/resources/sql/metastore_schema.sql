CREATE TABLE IF NOT EXISTS message_schema (
    id BIGSERIAL PRIMARY KEY,
    schema_hash CHAR(64) NOT NULL,
    hash_algo TEXT NOT NULL DEFAULT 'sha256',
    canonical_schema JSONB NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    seen_count BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_message_schema_hash_version UNIQUE (schema_hash, hash_algo, schema_version)
);

CREATE TABLE IF NOT EXISTS message_schema_binding (
    id BIGSERIAL PRIMARY KEY,
    schema_id BIGINT NOT NULL REFERENCES message_schema(id),
    binding_type TEXT NOT NULL CHECK (binding_type IN ('message_id', 'fingerprint')),
    message_id TEXT NULL,
    message_fingerprint CHAR(64) NULL,
    source TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_message_schema_binding_schema_id
    ON message_schema_binding (schema_id);

CREATE INDEX IF NOT EXISTS idx_message_schema_binding_message_id
    ON message_schema_binding (message_id);

CREATE INDEX IF NOT EXISTS idx_message_schema_binding_message_fingerprint
    ON message_schema_binding (message_fingerprint);

CREATE UNIQUE INDEX IF NOT EXISTS uq_message_schema_binding_message_id
    ON message_schema_binding (binding_type, message_id)
    WHERE message_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_message_schema_binding_fingerprint
    ON message_schema_binding (binding_type, message_fingerprint, source)
    WHERE message_fingerprint IS NOT NULL;
