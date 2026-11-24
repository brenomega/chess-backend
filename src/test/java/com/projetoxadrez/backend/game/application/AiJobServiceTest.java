package com.projetoxadrez.backend.game.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projetoxadrez.backend.game.chess.ChessPosition;
import com.projetoxadrez.backend.game.chess.MoveHistory;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.Game;
import com.projetoxadrez.backend.game.domain.GameId;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.Match;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * EAP 2.14.2 e 2.14.4: entrega de trabalho ao worker e recusa de candidato inválido, atrasado ou
 * de outro worker — sempre sem corromper a partida.
 */
class AiJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T15:00:00Z");
    private static final TimeControl TEN_MINUTES = new TimeControl(600_000, 0);
    private static final UUID HUMAN_SESSION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long LEASE_MS = 15_000;

    private final UUID gameId = UUID.randomUUID();
    private InMemoryAiJobStore jobs;
    private InMemoryActionStore games;
    private AiJobService service;
    private AiJobTurnScheduler scheduler;

    @BeforeEach
    void setUp() {
        jobs = new InMemoryAiJobStore();
        games = new InMemoryActionStore();
        games.match = afterWhiteOpening();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        scheduler = new AiJobTurnScheduler(jobs, clock, 1_000);
        GameActionService actions = new GameActionService(games, snapshot -> {}, snapshot -> {}, clock);
        service = new AiJobService(jobs, actions, scheduler, clock, LEASE_MS);
    }

    /** Partida humano (brancas) contra IA (pretas), com as brancas já tendo jogado 1.e4. */
    private Match afterWhiteOpening() {
        ChessPosition position = ChessPosition.fromFen(
                "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1");
        return Match.rehydrate(
                new GameId(gameId),
                GameStatus.ACTIVE,
                TEN_MINUTES,
                4,
                List.of(
                        new Game.Participant(Side.WHITE, HUMAN_SESSION, "HUMAN"),
                        new Game.Participant(Side.BLACK, null, "AI")),
                position,
                MoveHistory.empty(),
                "e2e4",
                600_000,
                600_000,
                Side.BLACK,
                NOW,
                null,
                null,
                null);
    }

    private AiJob enqueueAndAssign(String workerId) {
        scheduler.syncAiTurn(games.snapshotOf(games.match));
        return service.next(workerId).orElseThrow();
    }

    // ---------- 2.14.2 entrega de trabalho ----------

    @Test
    void handsOutNothingWhenThereIsNoJob() {
        assertThat(service.next("worker-1")).isEmpty();
    }

    @Test
    void handsOutTheJobForTheSideTheAiMustPlay() {
        AiJob job = enqueueAndAssign("worker-1");

        assertThat(job.gameId()).isEqualTo(gameId);
        assertThat(job.revision()).isEqualTo(4);
        assertThat(job.sideToMove()).isEqualTo(Side.BLACK);
        assertThat(job.fen()).startsWith("rnbqkbnr/pppppppp/8/8/4P3");
        assertThat(job.expiresAt()).isEqualTo(NOW.plusMillis(LEASE_MS));
    }

    @Test
    void doesNotHandOutTheSameJobTwice() {
        enqueueAndAssign("worker-1");

        assertThat(service.next("worker-2")).isEmpty();
    }

    @Test
    void appliesTheCandidateAndAdvancesTheGame() {
        AiJob job = enqueueAndAssign("worker-1");

        AiJobService.AiJobOutcome outcome = service.result("worker-1", job.jobId(), gameId, 4, "e7e5");

        assertThat(outcome.gameId()).isEqualTo(gameId);
        assertThat(outcome.revision()).isEqualTo(5);
        assertThat(games.match.lastMoveUci()).isEqualTo("e7e5");
        assertThat(games.match.activeSide()).isEqualTo(Side.WHITE);
        assertThat(jobs.find(job.jobId()).orElseThrow().status()).isEqualTo(AiJobStatus.COMPLETED);
    }

    // ---------- 2.14.4 falha, atraso e candidato inválido ----------

    @Test
    void rejectsCandidateFromAnotherWorkerWithoutTouchingTheGame() {
        AiJob job = enqueueAndAssign("worker-1");

        assertThatThrownBy(() -> service.result("worker-2", job.jobId(), gameId, 4, "e7e5"))
                .isInstanceOf(AiJobService.WorkerOwnershipConflictException.class);
        assertThat(games.match.lastMoveUci()).isEqualTo("e2e4");
        assertThat(jobs.find(job.jobId()).orElseThrow().status()).isEqualTo(AiJobStatus.ASSIGNED);
    }

    @Test
    void rejectsCandidateThatArrivesAfterTheLease() {
        AiJob job = enqueueAndAssign("worker-1");
        AiJobService late = lateService(NOW.plus(Duration.ofSeconds(30)));

        assertThatThrownBy(() -> late.result("worker-1", job.jobId(), gameId, 4, "e7e5"))
                .isInstanceOf(AiJobService.JobExpiredException.class);
        assertThat(games.match.lastMoveUci()).isEqualTo("e2e4");
    }

    @Test
    void handsAnExpiredJobToAnotherWorkerInsteadOfStalling() {
        enqueueAndAssign("worker-1");
        AiJobService late = lateService(NOW.plus(Duration.ofSeconds(30)));

        Optional<AiJob> recovered = late.next("worker-2");

        assertThat(recovered).isPresent();
        assertThat(recovered.orElseThrow().revision()).isEqualTo(4);
    }

    @Test
    void rejectsCandidateForARevisionThatAlreadyPassed() {
        AiJob job = enqueueAndAssign("worker-1");

        assertThatThrownBy(() -> service.result("worker-1", job.jobId(), gameId, 3, "e7e5"))
                .isInstanceOf(AiJobService.StaleJobRevisionException.class);
        assertThat(games.match.lastMoveUci()).isEqualTo("e2e4");
    }

    @Test
    void rejectsIllegalCandidateWithoutCorruptingTheGame() {
        AiJob job = enqueueAndAssign("worker-1");

        assertThatThrownBy(() -> service.result("worker-1", job.jobId(), gameId, 4, "e7e4"))
                .isInstanceOf(Match.IllegalMoveException.class);
        assertThat(games.match.lastMoveUci()).isEqualTo("e2e4");
        assertThat(games.match.revision()).isEqualTo(4);
    }

    @Test
    void rejectsCandidateThatIsNotUciNotation() {
        AiJob job = enqueueAndAssign("worker-1");

        assertThatThrownBy(() -> service.result("worker-1", job.jobId(), gameId, 4, "cavalo pra frente"))
                .isInstanceOf(GameActionService.InvalidMoveFormatException.class);
    }

    @Test
    void rejectsResultForAJobThatDoesNotExist() {
        assertThatThrownBy(() -> service.result("worker-1", UUID.randomUUID(), gameId, 4, "e7e5"))
                .isInstanceOf(AiJobService.JobNotFoundException.class);
    }

    @Test
    void repeatingAnAppliedResultReturnsTheSameOutcomeOnce() {
        AiJob job = enqueueAndAssign("worker-1");
        AiJobService.AiJobOutcome first = service.result("worker-1", job.jobId(), gameId, 4, "e7e5");

        AiJobService.AiJobOutcome repeated = service.result("worker-1", job.jobId(), gameId, 4, "e7e5");

        assertThat(repeated).isEqualTo(first);
        assertThat(games.match.history().uciMoves()).containsExactly("e7e5");
    }

    @Test
    void refusesADifferentCandidateForAJobAlreadyApplied() {
        AiJob job = enqueueAndAssign("worker-1");
        service.result("worker-1", job.jobId(), gameId, 4, "e7e5");

        assertThatThrownBy(() -> service.result("worker-1", job.jobId(), gameId, 4, "e7e6"))
                .isInstanceOf(AiJobService.JobAlreadyCompletedException.class);
    }

    @Test
    void rejectsRequestsWithoutAWorkerId() {
        assertThatThrownBy(() -> service.next(" ")).isInstanceOf(AiJobService.InvalidResultException.class);
        assertThatThrownBy(() -> service.result(null, UUID.randomUUID(), gameId, 4, "e7e5"))
                .isInstanceOf(AiJobService.InvalidResultException.class);
    }

    // ---------- 2.14.3 fila alinhada com a partida ----------

    @Test
    void cancelsOpenJobsOnceTheGameIsOver() {
        AiJob job = enqueueAndAssign("worker-1");
        games.match.abandon(HUMAN_SESSION, NOW);

        scheduler.syncAiTurn(games.snapshotOf(games.match));

        assertThat(jobs.find(job.jobId()).orElseThrow().status()).isEqualTo(AiJobStatus.CANCELLED);
        assertThat(service.next("worker-1")).isEmpty();
    }

    @Test
    void replacesAJobWhoseRevisionNoLongerMatches() {
        AiJob stale = enqueueAndAssign("worker-1");
        // O humano oferece empate enquanto a IA pensa: a revisão anda sem um lance.
        games.match.offerDraw(HUMAN_SESSION, games.match.revision(), NOW);

        scheduler.syncAiTurn(games.snapshotOf(games.match));

        assertThat(jobs.find(stale.jobId()).orElseThrow().status()).isEqualTo(AiJobStatus.CANCELLED);
        AiJob fresh = service.next("worker-1").orElseThrow();
        assertThat(fresh.jobId()).isNotEqualTo(stale.jobId());
        assertThat(fresh.revision()).isEqualTo(games.match.revision());
    }

    private AiJobService lateService(Instant later) {
        Clock clock = Clock.fixed(later, ZoneOffset.UTC);
        return new AiJobService(
                jobs,
                new GameActionService(games, snapshot -> {}, snapshot -> {}, clock),
                new AiJobTurnScheduler(jobs, clock, 1_000),
                clock,
                LEASE_MS);
    }

    // ---------- dublês ----------

    private final class InMemoryActionStore implements GameActionStore {

        private Match match;

        @Override
        public Optional<Match> findForUpdate(UUID requestedGameId) {
            return Optional.ofNullable(match).filter(held -> held.id().value().equals(requestedGameId));
        }

        @Override
        public GameSnapshot save(Match savedMatch) {
            match = savedMatch;
            return snapshotOf(savedMatch);
        }

        GameSnapshot snapshotOf(Match held) {
            return new GameSnapshot(
                    gameId,
                    held.status(),
                    GameVisibility.PUBLIC,
                    null,
                    held.revision(),
                    List.of(
                            new GameSnapshot.Player(Side.WHITE, "HUMAN"),
                            new GameSnapshot.Player(Side.BLACK, "AI")),
                    new GameSnapshot.Position(
                            held.position().toFen(), held.position().sideToMove(), held.lastMoveUci()),
                    new GameSnapshot.Clock(held.whiteRemainingMs(), held.blackRemainingMs(), held.activeSide()),
                    held.drawOfferSide() == null ? null : new GameSnapshot.DrawOffer(held.drawOfferSide()),
                    held.resultOutcome() == null
                            ? null
                            : new GameSnapshot.Result(held.resultOutcome(), held.resultReason()));
        }
    }

    /** Fila em memória com a mesma regra da tabela: um só trabalho aberto por partida. */
    private static final class InMemoryAiJobStore implements AiJobStore {

        private final List<Row> rows = new ArrayList<>();

        private record Row(
                UUID jobId,
                UUID gameId,
                long revision,
                String fen,
                Side sideToMove,
                long timeBudgetMs,
                Instant createdAt,
                AiJobStatus status,
                String workerId,
                Instant expiresAt,
                String appliedUci,
                Long appliedRevision) {
        }

        @Override
        public Optional<AiJobState> findOpenJob(UUID gameId) {
            return rows.stream()
                    .filter(row -> row.gameId().equals(gameId) && row.status().isOpen())
                    .findFirst()
                    .map(InMemoryAiJobStore::state);
        }

        @Override
        public void enqueue(
                UUID gameId, long revision, String fen, Side sideToMove, long timeBudgetMs, Instant now) {
            if (findOpenJob(gameId).isPresent()) {
                throw new IllegalStateException("there is already an open job for this game");
            }
            rows.add(new Row(
                    UUID.randomUUID(),
                    gameId,
                    revision,
                    fen,
                    sideToMove,
                    timeBudgetMs,
                    now,
                    AiJobStatus.PENDING,
                    null,
                    null,
                    null,
                    null));
        }

        @Override
        public Optional<AiJob> assignNext(String workerId, Instant now, Instant expiresAt) {
            Optional<Row> assignable = rows.stream()
                    .filter(row -> row.status() == AiJobStatus.PENDING
                            || (row.status() == AiJobStatus.ASSIGNED && !now.isBefore(row.expiresAt())))
                    .min(Comparator.comparing(Row::createdAt));
            return assignable.map(row -> {
                replace(row, new Row(
                        row.jobId(),
                        row.gameId(),
                        row.revision(),
                        row.fen(),
                        row.sideToMove(),
                        row.timeBudgetMs(),
                        row.createdAt(),
                        AiJobStatus.ASSIGNED,
                        workerId,
                        expiresAt,
                        null,
                        null));
                return new AiJob(
                        row.jobId(),
                        row.gameId(),
                        row.revision(),
                        row.fen(),
                        row.sideToMove(),
                        row.timeBudgetMs(),
                        expiresAt);
            });
        }

        @Override
        public Optional<AiJobState> find(UUID jobId) {
            return row(jobId).map(InMemoryAiJobStore::state);
        }

        @Override
        public void complete(UUID jobId, String uci, long appliedRevision, Instant now) {
            row(jobId).ifPresent(row -> replace(row, new Row(
                    row.jobId(),
                    row.gameId(),
                    row.revision(),
                    row.fen(),
                    row.sideToMove(),
                    row.timeBudgetMs(),
                    row.createdAt(),
                    AiJobStatus.COMPLETED,
                    row.workerId(),
                    row.expiresAt(),
                    uci,
                    appliedRevision)));
        }

        @Override
        public void cancel(UUID jobId, Instant now) {
            row(jobId).ifPresent(row -> replace(row, new Row(
                    row.jobId(),
                    row.gameId(),
                    row.revision(),
                    row.fen(),
                    row.sideToMove(),
                    row.timeBudgetMs(),
                    row.createdAt(),
                    AiJobStatus.CANCELLED,
                    row.workerId(),
                    row.expiresAt(),
                    row.appliedUci(),
                    row.appliedRevision())));
        }

        @Override
        public void cancelOpenJobs(UUID gameId, Instant now) {
            List.copyOf(rows).stream()
                    .filter(row -> row.gameId().equals(gameId) && row.status().isOpen())
                    .forEach(row -> cancel(row.jobId(), now));
        }

        private Optional<Row> row(UUID jobId) {
            return rows.stream().filter(candidate -> candidate.jobId().equals(jobId)).findFirst();
        }

        private void replace(Row previous, Row updated) {
            rows.set(rows.indexOf(previous), updated);
        }

        private static AiJobState state(Row row) {
            return new AiJobState(
                    row.jobId(),
                    row.gameId(),
                    row.revision(),
                    row.sideToMove(),
                    row.status(),
                    row.workerId(),
                    row.expiresAt(),
                    row.appliedUci(),
                    row.appliedRevision());
        }
    }
}
