package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.AiJob;
import com.projetoxadrez.backend.game.application.AiJobState;
import com.projetoxadrez.backend.game.application.AiJobStatus;
import com.projetoxadrez.backend.game.application.AiJobStore;
import com.projetoxadrez.backend.game.chess.Side;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

@Component
public class JpaAiJobStore implements AiJobStore {

    private final AiJobRepository repository;

    public JpaAiJobStore(AiJobRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AiJobState> findOpenJob(UUID gameId) {
        return repository.findOpenByGameId(gameId).stream().findFirst().map(JpaAiJobStore::state);
    }

    @Override
    public void enqueue(UUID gameId, long revision, String fen, Side sideToMove, long timeBudgetMs, Instant now) {
        repository.saveAndFlush(AiJobEntity.pending(gameId, revision, fen, sideToMove, timeBudgetMs, now));
    }

    @Override
    public Optional<AiJob> assignNext(String workerId, Instant now, Instant expiresAt) {
        List<AiJobEntity> assignable = repository.findAssignable(now, Limit.of(1));
        if (assignable.isEmpty()) {
            return Optional.empty();
        }
        AiJobEntity job = assignable.getFirst();
        job.assignTo(workerId, now, expiresAt);
        repository.saveAndFlush(job);
        return Optional.of(new AiJob(
                job.getId(),
                job.getGameId(),
                job.getRevision(),
                job.getPositionFen(),
                job.getSideToMove(),
                job.getTimeBudgetMs(),
                job.getExpiresAt()));
    }

    @Override
    public Optional<AiJobState> find(UUID jobId) {
        return repository.findByIdForUpdate(jobId).map(JpaAiJobStore::state);
    }

    @Override
    public void complete(UUID jobId, String uci, long appliedRevision, Instant now) {
        repository.findById(jobId).ifPresent(job -> {
            job.complete(uci, appliedRevision, now);
            repository.saveAndFlush(job);
        });
    }

    @Override
    public void cancel(UUID jobId, Instant now) {
        repository.findById(jobId).ifPresent(job -> {
            job.cancel(now);
            repository.saveAndFlush(job);
        });
    }

    @Override
    public void cancelOpenJobs(UUID gameId, Instant now) {
        List<AiJobEntity> open = repository.findOpenByGameId(gameId);
        if (open.isEmpty()) {
            return;
        }
        open.forEach(job -> job.cancel(now));
        repository.saveAllAndFlush(open);
    }

    private static AiJobState state(AiJobEntity job) {
        return new AiJobState(
                job.getId(),
                job.getGameId(),
                job.getRevision(),
                job.getSideToMove(),
                job.getStatus(),
                job.getWorkerId(),
                job.getExpiresAt(),
                job.getAppliedUci(),
                job.getAppliedRevision());
    }
}
