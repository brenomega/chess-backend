package com.projetoxadrez.backend.game.domain;

import com.projetoxadrez.backend.game.chess.Side;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class GameClock {

    private final GameStatus gameStatus;
    private final TimeControl timeControl;
    private final long whiteRemainingMs;
    private final long blackRemainingMs;
    private final Side activeSide;
    private final Instant updatedAt;

    public GameClock(
            GameStatus gameStatus,
            TimeControl timeControl,
            long whiteRemainingMs,
            long blackRemainingMs,
            Side activeSide,
            Instant updatedAt) {
        this.gameStatus = Objects.requireNonNull(gameStatus, "gameStatus must not be null");
        this.timeControl = Objects.requireNonNull(timeControl, "timeControl must not be null");
        if (whiteRemainingMs < 0 || blackRemainingMs < 0) {
            throw new IllegalArgumentException("remaining time must not be negative");
        }
        if ((activeSide == null) != (updatedAt == null)) {
            throw new IllegalArgumentException("active side and clock update instant must both be present or absent");
        }
        this.whiteRemainingMs = whiteRemainingMs;
        this.blackRemainingMs = blackRemainingMs;
        this.activeSide = activeSide;
        this.updatedAt = updatedAt;
    }

    public Reading applyElapsed(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (gameStatus != GameStatus.ACTIVE || activeSide == null || timeControl.isInfinite()) {
            return new Reading(gameStatus, whiteRemainingMs, blackRemainingMs, activeSide, null);
        }

        long elapsedMs = Math.max(0, Duration.between(updatedAt, now).toMillis());
        long currentWhiteMs = whiteRemainingMs;
        long currentBlackMs = blackRemainingMs;
        if (activeSide == Side.WHITE) {
            currentWhiteMs = Math.max(0, currentWhiteMs - elapsedMs);
        } else {
            currentBlackMs = Math.max(0, currentBlackMs - elapsedMs);
        }

        boolean timedOut = activeSide == Side.WHITE ? currentWhiteMs == 0 : currentBlackMs == 0;
        return new Reading(
                timedOut ? GameStatus.FINISHED : gameStatus,
                currentWhiteMs,
                currentBlackMs,
                timedOut ? null : activeSide,
                timedOut ? activeSide.opposite() : null);
    }

    public record Reading(
            GameStatus gameStatus,
            long whiteRemainingMs,
            long blackRemainingMs,
            Side activeSide,
            Side timeoutWinner) {

        public boolean timedOut() {
            return timeoutWinner != null;
        }
    }
}
