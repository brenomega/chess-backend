package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.domain.Match;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comunicação com o worker de IA (EAP 2.14.2).
 *
 * <p>O worker pede trabalho e devolve um candidato. O Backend continua dono de tudo que importa:
 * o candidato só vira lance depois de passar pelas mesmas regras de um lance humano, e um worker
 * que some, atrasa ou responde besteira não altera a partida (EAP 2.14.4).
 */
@Service
public class AiJobService {

    private final AiJobStore jobStore;
    private final GameActionService actionService;
    private final AiTurnScheduler aiTurnScheduler;
    private final Clock clock;
    private final Duration lease;

    public AiJobService(
            AiJobStore jobStore,
            GameActionService actionService,
            AiTurnScheduler aiTurnScheduler,
            Clock clock,
            @Value("${chess.ai.lease-ms:15000}") long leaseMs) {
        this.jobStore = jobStore;
        this.actionService = actionService;
        this.aiTurnScheduler = aiTurnScheduler;
        this.clock = clock;
        this.lease = Duration.ofMillis(leaseMs);
    }

    /** Entrega o próximo trabalho, ou vazio quando não há nenhum. */
    @Transactional
    public Optional<AiJob> next(String workerId) {
        requireWorkerId(workerId);
        Instant now = clock.instant();
        return jobStore.assignNext(workerId, now, now.plus(lease));
    }

    /**
     * Recebe o candidato do worker e, se ele passar por todas as verificações, aplica o lance.
     *
     * <p>Uma repetição idêntica de um resultado já aplicado devolve o mesmo resultado, sem uma
     * segunda transição: o worker pode reenviar sem medo depois de uma rede ruim.
     */
    @Transactional(noRollbackFor = Match.GameTimedOutException.class)
    public AiJobOutcome result(String workerId, UUID jobId, UUID gameId, long revision, String uci) {
        requireWorkerId(workerId);
        if (jobId == null || gameId == null || uci == null || uci.isBlank()) {
            throw new InvalidResultException();
        }
        Instant now = clock.instant();
        AiJobState job = jobStore.find(jobId).orElseThrow(JobNotFoundException::new);
        if (!job.gameId().equals(gameId)) {
            throw new InvalidResultException();
        }
        if (job.status() == AiJobStatus.COMPLETED) {
            if (uci.equals(job.appliedUci())) {
                return new AiJobOutcome(gameId, job.appliedRevision());
            }
            throw new JobAlreadyCompletedException();
        }
        if (job.status() == AiJobStatus.CANCELLED) {
            // A partida andou sem este trabalho; não há vez para preencher.
            throw new JobExpiredException();
        }
        if (!job.belongsTo(workerId)) {
            throw new WorkerOwnershipConflictException();
        }
        if (job.hasExpiredAt(now)) {
            throw new JobExpiredException();
        }
        if (job.revision() != revision) {
            throw new StaleJobRevisionException();
        }

        GameSnapshot snapshot;
        try {
            snapshot = actionService.applyAiMove(gameId, job.sideToMove(), revision, uci);
        } catch (Match.GameTimedOutException exception) {
            // O relógio encerrou a partida antes do candidato chegar: o trabalho perdeu a razão.
            jobStore.cancel(jobId, now);
            throw exception;
        }
        jobStore.complete(jobId, uci, snapshot.revision(), now);
        // Numa partida entre duas IAs, a vez já é da outra.
        aiTurnScheduler.syncAiTurn(snapshot);
        return new AiJobOutcome(gameId, snapshot.revision());
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new InvalidResultException();
        }
    }

    /** Resposta de uma entrega aceita. */
    public record AiJobOutcome(UUID gameId, long revision) {
    }

    public static final class InvalidResultException extends RuntimeException {
    }

    public static final class JobNotFoundException extends RuntimeException {
    }

    public static final class JobExpiredException extends RuntimeException {
    }

    public static final class JobAlreadyCompletedException extends RuntimeException {
    }

    public static final class WorkerOwnershipConflictException extends RuntimeException {
    }

    public static final class StaleJobRevisionException extends RuntimeException {
    }
}
