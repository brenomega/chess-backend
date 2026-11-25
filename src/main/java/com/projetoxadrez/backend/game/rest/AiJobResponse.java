package com.projetoxadrez.backend.game.rest;

import com.projetoxadrez.backend.game.application.AiJob;
import com.projetoxadrez.backend.game.chess.Side;
import java.time.Instant;
import java.util.UUID;

/** Trabalho devolvido ao worker, no formato de docs/contracts/backend-ai.md. */
public record AiJobResponse(
        UUID jobId, UUID gameId, long revision, Position position, long timeBudgetMs, Instant expiresAt) {

    public record Position(String fen, Side sideToMove) {
    }

    static AiJobResponse from(AiJob job) {
        return new AiJobResponse(
                job.jobId(),
                job.gameId(),
                job.revision(),
                new Position(job.fen(), job.sideToMove()),
                job.timeBudgetMs(),
                job.expiresAt());
    }
}
