package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.chess.ChessPosition;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameClock;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
class GameSnapshotFactory {

    private final Clock clock;

    GameSnapshotFactory(Clock clock) {
        this.clock = clock;
    }

    GameSnapshot snapshot(GameEntity game) {
        GameClock.Reading clockReading = new GameClock(
                        game.getStatus(),
                        new TimeControl(game.getInitialTimeMs(), game.getIncrementMs()),
                        game.getWhiteRemainingMs(),
                        game.getBlackRemainingMs(),
                        game.getActiveSide(),
                        game.getClockUpdatedAt())
                .applyElapsed(clock.instant());
        return new GameSnapshot(
                game.getId(),
                clockReading.gameStatus(),
                game.getVisibility(),
                game.getEntryCode(),
                game.getRevision(),
                game.getParticipants().stream()
                        .map(participant -> new GameSnapshot.Player(participant.getSide(), participant.getKind()))
                        .toList(),
                new GameSnapshot.Position(
                        game.getPositionFen(),
                        ChessPosition.fromFen(game.getPositionFen()).sideToMove(),
                        game.getLastMoveUci()),
                new GameSnapshot.Clock(
                        clockReading.whiteRemainingMs(),
                        clockReading.blackRemainingMs(),
                        clockReading.activeSide()),
                clockReading.timedOut() || game.getDrawOfferSide() == null
                        ? null
                        : new GameSnapshot.DrawOffer(game.getDrawOfferSide()),
                clockReading.timedOut()
                        ? new GameSnapshot.Result(timeoutOutcome(clockReading.timeoutWinner()), "TIMEOUT")
                        : game.getResultOutcome() == null
                        ? null
                        : new GameSnapshot.Result(game.getResultOutcome(), game.getResultReason()));
    }

    private static String timeoutOutcome(Side winner) {
        return winner == Side.WHITE ? "WHITE_WIN" : "BLACK_WIN";
    }
}
