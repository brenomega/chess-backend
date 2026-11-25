package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.AiJob;
import com.projetoxadrez.backend.game.application.AiJobState;
import com.projetoxadrez.backend.game.application.AiJobStatus;
import com.projetoxadrez.backend.game.application.AiJobStore;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaAiJobStore implements AiJobStore {

    private final AiJobRepository repository;
    private final GameRepository gameRepository;

    public JpaAiJobStore(AiJobRepository repository, GameRepository gameRepository) {
        this.repository = repository;
        this.gameRepository = gameRepository;
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
        Optional<UUID> lockedGameId = repository.lockNextGame(now);
        if (lockedGameId.isEmpty()) {
            return Optional.empty();
        }

        UUID gameId = lockedGameId.orElseThrow();
        Optional<AiJobEntity> assignable = repository.findOpenByGameId(gameId).stream()
                .filter(job -> isAssignable(job, now))
                .findFirst();
        if (assignable.isEmpty()) {
            return Optional.empty();
        }

        AiJobEntity job = assignable.orElseThrow();
        GameEntity game = gameRepository.findById(gameId).orElseThrow();
        if (!matchesCurrentTurn(job, game)) {
            job.cancel(now);
            return Optional.empty();
        }

        job.assignTo(workerId, now, expiresAt);
        return Optional.of(toJob(job));
    }

    @Override
    public Optional<AiJobState> findForResult(UUID jobId) {
        return repository.findGameId(jobId)
                .flatMap(gameRepository::findByIdForUpdate)
                .flatMap(game -> repository.findById(jobId))
                .map(JpaAiJobStore::state);
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

    private static boolean isAssignable(AiJobEntity job, Instant now) {
        return job.getStatus() == AiJobStatus.PENDING
                || job.getStatus() == AiJobStatus.ASSIGNED
                        && job.getExpiresAt() != null
                        && !now.isBefore(job.getExpiresAt());
    }

    private static boolean matchesCurrentTurn(AiJobEntity job, GameEntity game) {
        return game.getStatus() == GameStatus.ACTIVE
                && game.getRevision() == job.getRevision()
                && game.getPositionFen().equals(job.getPositionFen())
                && game.getActiveSide() == job.getSideToMove()
                && game.getParticipants().stream()
                        .anyMatch(participant -> participant.getSide() == job.getSideToMove()
                                && "AI".equals(participant.getKind()));
    }

    private static AiJob toJob(AiJobEntity job) {
        return new AiJob(
                job.getId(),
                job.getGameId(),
                job.getRevision(),
                job.getPositionFen(),
                job.getSideToMove(),
                job.getTimeBudgetMs(),
                job.getExpiresAt());
    }
}
