CREATE TABLE guest_session (
    id UUID PRIMARY KEY,
    recovery_token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT guest_session_recovery_token_hash_unique UNIQUE (recovery_token_hash),
    CONSTRAINT guest_session_recovery_token_hash_format
        CHECK (recovery_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT guest_session_expiration_after_creation
        CHECK (expires_at > created_at),
    CONSTRAINT guest_session_last_seen_after_creation
        CHECK (last_seen_at >= created_at)
);
