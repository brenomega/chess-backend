package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.chess.Side;
import java.time.Instant;
import java.util.UUID;

/** Trabalho como está gravado, para validar a entrega do candidato (EAP 2.14.2). */
public record AiJobState(
        UUID jobId,
        UUID gameId,
        long revision,
        Side sideToMove,
        AiJobStatus status,
        String workerId,
        Instant expiresAt,
        String appliedUci,
        Long appliedRevision) {

    public boolean belongsTo(String otherWorkerId) {
        return workerId != null && workerId.equals(otherWorkerId);
    }

    public boolean hasExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
