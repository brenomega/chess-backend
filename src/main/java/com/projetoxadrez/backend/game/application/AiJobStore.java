package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.chess.Side;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AiJobStore {

    Optional<AiJobState> findOpenJob(UUID gameId);

    void enqueue(UUID gameId, long revision, String fen, Side sideToMove, long timeBudgetMs, Instant now);

    Optional<AiJob> assignNext(String workerId, Instant now, Instant expiresAt);

    Optional<AiJobState> findForResult(UUID jobId);

    void complete(UUID jobId, String uci, long appliedRevision, Instant now);

    void cancel(UUID jobId, Instant now);

    void cancelOpenJobs(UUID gameId, Instant now);
}
