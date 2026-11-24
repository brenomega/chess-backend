package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.chess.Side;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AiJobStore {

    /** Trabalho ainda aberto desta partida, se houver. */
    Optional<AiJobState> findOpenJob(UUID gameId);

    void enqueue(UUID gameId, long revision, String fen, Side sideToMove, long timeBudgetMs, Instant now);

    /**
     * Entrega o próximo trabalho ao worker, com trava de linha.
     *
     * <p>Também recupera trabalho cujo prazo venceu: se o worker anterior sumiu, o trabalho volta
     * à fila em vez de prender a partida (EAP 2.14.4).
     */
    Optional<AiJob> assignNext(String workerId, Instant now, Instant expiresAt);

    Optional<AiJobState> find(UUID jobId);

    void complete(UUID jobId, String uci, long appliedRevision, Instant now);

    void cancel(UUID jobId, Instant now);

    void cancelOpenJobs(UUID gameId, Instant now);
}
