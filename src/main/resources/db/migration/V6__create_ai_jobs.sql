-- Trabalhos entregues ao worker de IA (EAP 2.14.2).
CREATE TABLE ai_job (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES game (id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    position_fen TEXT NOT NULL,
    side_to_move VARCHAR(5) NOT NULL,
    time_budget_ms BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    worker_id VARCHAR(64),
    assigned_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    applied_uci VARCHAR(5),
    applied_revision BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ai_job_status_valid CHECK (status IN ('PENDING', 'ASSIGNED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ai_job_side_valid CHECK (side_to_move IN ('WHITE', 'BLACK')),
    CONSTRAINT ai_job_revision_non_negative CHECK (revision >= 0),
    CONSTRAINT ai_job_time_budget_positive CHECK (time_budget_ms > 0),
    CONSTRAINT ai_job_assignment_consistent CHECK (
        (status = 'ASSIGNED' AND worker_id IS NOT NULL AND assigned_at IS NOT NULL AND expires_at IS NOT NULL)
        OR (status <> 'ASSIGNED')
    ),
    CONSTRAINT ai_job_completion_consistent CHECK (
        (status = 'COMPLETED' AND applied_uci IS NOT NULL AND applied_revision IS NOT NULL)
        OR (status <> 'COMPLETED')
    )
);

-- No máximo um trabalho aberto por partida: é o que impede a IA de jogar duas vezes
-- na mesma vez, mesmo com dois workers pedindo trabalho ao mesmo tempo.
CREATE UNIQUE INDEX ai_job_single_open_per_game
    ON ai_job (game_id)
    WHERE status IN ('PENDING', 'ASSIGNED');

-- Fila de atendimento: os abertos, do mais antigo para o mais novo.
CREATE INDEX ai_job_queue_idx
    ON ai_job (created_at)
    WHERE status IN ('PENDING', 'ASSIGNED');
