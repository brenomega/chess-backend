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

    @Transactional
    public Optional<AiJob> next(String workerId) {
        requireWorkerId(workerId);
        Instant now = clock.instant();
        return jobStore.assignNext(workerId, now, now.plus(lease));
    }

    @Transactional(noRollbackFor = Match.GameTimedOutException.class)
    public AiJobOutcome result(String workerId, UUID jobId, UUID gameId, long revision, String uci) {
        requireWorkerId(workerId);
        if (jobId == null || gameId == null || uci == null || uci.isBlank()) {
            throw new InvalidResultException();
        }
        AiJobState job = jobStore.findForResult(jobId).orElseThrow(JobNotFoundException::new);
        if (!job.belongsTo(workerId)) {
            throw new WorkerOwnershipConflictException();
        }
        if (!job.gameId().equals(gameId)) {
            throw new InvalidResultException();
        }
        if (job.status() == AiJobStatus.COMPLETED) {
            if (revision == job.revision() && uci.equals(job.appliedUci())) {
                return new AiJobOutcome(gameId, job.appliedRevision());
            }
            throw new JobAlreadyCompletedException();
        }
        Instant now = clock.instant();
        if (job.status() == AiJobStatus.CANCELLED || job.hasExpiredAt(now)) {
            throw new JobExpiredException();
        }
        if (job.revision() != revision) {
            throw new StaleJobRevisionException();
        }

        GameSnapshot snapshot;
        try {
            snapshot = actionService.applyAiMove(gameId, job.sideToMove(), revision, uci);
        } catch (Match.GameTimedOutException exception) {
            jobStore.cancel(jobId, now);
            throw exception;
        }
        jobStore.complete(jobId, uci, snapshot.revision(), now);
        aiTurnScheduler.syncAiTurn(snapshot);
        return new AiJobOutcome(gameId, snapshot.revision());
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new InvalidResultException();
        }
    }

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
