package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.chess.Side;
import java.time.Instant;
import java.util.UUID;

/** Trabalho entregue ao worker: a posição a resolver e o prazo (EAP 2.14.2). */
public record AiJob(
        UUID jobId,
        UUID gameId,
        long revision,
        String fen,
        Side sideToMove,
        long timeBudgetMs,
        Instant expiresAt) {
}
