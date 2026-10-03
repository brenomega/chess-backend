CREATE TABLE game (
    id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    entry_code VARCHAR(6),
    revision BIGINT NOT NULL,
    position_fen TEXT NOT NULL,
    last_move_uci VARCHAR(5),
    white_remaining_ms BIGINT NOT NULL,
    black_remaining_ms BIGINT NOT NULL,
    active_side VARCHAR(5),
    clock_updated_at TIMESTAMPTZ,
    initial_time_ms BIGINT NOT NULL,
    increment_ms BIGINT NOT NULL,
    draw_offer_side VARCHAR(5),
    result_outcome VARCHAR(16),
    result_reason VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    CONSTRAINT game_status_valid CHECK (status IN ('WAITING', 'ACTIVE', 'FINISHED', 'ABANDONED')),
    CONSTRAINT game_visibility_valid CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT game_entry_code_visibility CHECK (
        (visibility = 'PUBLIC' AND entry_code IS NULL)
        OR (visibility = 'PRIVATE' AND entry_code ~ '^[A-HJ-NP-Z2-9]{6}$')
    ),
    CONSTRAINT game_revision_non_negative CHECK (revision >= 0),
    CONSTRAINT game_clock_non_negative CHECK (white_remaining_ms >= 0 AND black_remaining_ms >= 0),
    CONSTRAINT game_active_side_valid CHECK (active_side IS NULL OR active_side IN ('WHITE', 'BLACK')),
    CONSTRAINT game_draw_offer_side_valid CHECK (draw_offer_side IS NULL OR draw_offer_side IN ('WHITE', 'BLACK')),
    CONSTRAINT game_clock_anchor_consistent CHECK (
        (active_side IS NULL AND clock_updated_at IS NULL)
        OR (active_side IS NOT NULL AND clock_updated_at IS NOT NULL)
    )
);

CREATE INDEX game_public_waiting_lobby_idx
    ON game (created_at DESC, id DESC)
    WHERE status = 'WAITING' AND visibility = 'PUBLIC';

CREATE TABLE game_participant (
    game_id UUID NOT NULL REFERENCES game(id),
    side VARCHAR(5) NOT NULL,
    session_id UUID REFERENCES guest_session(id),
    kind VARCHAR(5) NOT NULL,
    PRIMARY KEY (game_id, side),
    CONSTRAINT game_participant_side_valid CHECK (side IN ('WHITE', 'BLACK')),
    CONSTRAINT game_participant_kind_valid CHECK (kind IN ('HUMAN', 'AI')),
    CONSTRAINT game_participant_identity_consistent CHECK (
        (kind = 'HUMAN' AND session_id IS NOT NULL)
        OR (kind = 'AI' AND session_id IS NULL)
    )
);
