package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.GameQueryStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.application.LobbyGame;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
public class JpaGameQueryStore implements GameQueryStore {

    private final GameRepository repository;
    private final Clock clock;

    public JpaGameQueryStore(GameRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Page<LobbyGame> findPublicWaitingGames(Pageable pageable) {
        return repository.findByStatusAndVisibility(GameStatus.WAITING, GameVisibility.PUBLIC, pageable)
                .map(this::lobbyGame);
    }

    @Override
    public Optional<GameSnapshot> findSnapshot(UUID gameId, UUID sessionId) {
        return repository.findWithParticipantsById(gameId)
                .filter(game -> game.getParticipants().stream()
                        .anyMatch(participant -> sessionId.equals(participant.getSessionId())))
                .map(this::snapshot);
    }

    @Override
    public boolean existsById(UUID gameId) {
        return repository.existsById(gameId);
    }

    private LobbyGame lobbyGame(GameEntity game) {
        return new LobbyGame(
                game.getId(),
                game.getStatus(),
                game.getVisibility(),
                new TimeControl(game.getInitialTimeMs(), game.getIncrementMs()),
                game.getCreatedAt());
    }

    GameSnapshot snapshot(GameEntity game) {
        ClockSnapshot clockSnapshot = clockSnapshot(game);
        return new GameSnapshot(
                game.getId(),
                clockSnapshot.status(),
                game.getVisibility(),
                game.getEntryCode(),
                game.getRevision(),
                game.getParticipants().stream()
                        .map(participant -> new GameSnapshot.Player(participant.getSide(), participant.getKind()))
                        .toList(),
                new GameSnapshot.Position(
                        game.getPositionFen(), sideToMove(game.getPositionFen()), game.getLastMoveUci()),
                new GameSnapshot.Clock(
                        clockSnapshot.whiteRemainingMs(),
                        clockSnapshot.blackRemainingMs(),
                        clockSnapshot.activeSide()),
                clockSnapshot.timedOut() || game.getDrawOfferSide() == null
                        ? null
                        : new GameSnapshot.DrawOffer(game.getDrawOfferSide()),
                clockSnapshot.timedOut()
                        ? new GameSnapshot.Result(timeoutOutcome(game.getActiveSide()), "TIMEOUT")
                        : game.getResultOutcome() == null
                        ? null
                        : new GameSnapshot.Result(game.getResultOutcome(), game.getResultReason()));
    }

    private ClockSnapshot clockSnapshot(GameEntity game) {
        if (game.getStatus() != GameStatus.ACTIVE
                || game.getActiveSide() == null
                || game.getInitialTimeMs() == 0) {
            return new ClockSnapshot(
                    game.getStatus(),
                    game.getWhiteRemainingMs(),
                    game.getBlackRemainingMs(),
                    game.getActiveSide(),
                    false);
        }
        long elapsedMs = Math.max(0, Duration.between(game.getClockUpdatedAt(), clock.instant()).toMillis());
        long whiteRemainingMs = game.getWhiteRemainingMs();
        long blackRemainingMs = game.getBlackRemainingMs();
        if (game.getActiveSide() == Side.WHITE) {
            whiteRemainingMs = Math.max(0, whiteRemainingMs - elapsedMs);
        } else {
            blackRemainingMs = Math.max(0, blackRemainingMs - elapsedMs);
        }
        boolean timedOut = game.getActiveSide() == Side.WHITE
                ? whiteRemainingMs == 0
                : blackRemainingMs == 0;
        return new ClockSnapshot(
                timedOut ? GameStatus.FINISHED : game.getStatus(),
                whiteRemainingMs,
                blackRemainingMs,
                timedOut ? null : game.getActiveSide(),
                timedOut);
    }

    private static String timeoutOutcome(Side timedOutSide) {
        return timedOutSide == Side.WHITE ? "BLACK_WIN" : "WHITE_WIN";
    }

    private static Side sideToMove(String fen) {
        String[] parts = fen.split(" ");
        if (parts.length < 2) {
            throw new IllegalStateException("Stored game position is invalid");
        }
        return switch (parts[1]) {
            case "w" -> Side.WHITE;
            case "b" -> Side.BLACK;
            default -> throw new IllegalStateException("Stored game position is invalid");
        };
    }

    private record ClockSnapshot(
            GameStatus status,
            long whiteRemainingMs,
            long blackRemainingMs,
            Side activeSide,
            boolean timedOut) {
    }
}
