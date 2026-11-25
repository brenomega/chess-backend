CREATE TABLE game_action_receipt (
    game_id UUID NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES guest_session(id),
    request_id VARCHAR(128) COLLATE "C" NOT NULL,
    action_identity TEXT NOT NULL,
    PRIMARY KEY (game_id, session_id, request_id)
);
