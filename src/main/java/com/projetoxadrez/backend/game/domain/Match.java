package com.projetoxadrez.backend.game.domain;

import com.projetoxadrez.backend.game.chess.ChessPosition;
import com.projetoxadrez.backend.game.chess.ChessResult;
import com.projetoxadrez.backend.game.chess.Move;
import com.projetoxadrez.backend.game.chess.MoveHistory;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.chess.TerminalStateService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class Match {

    private static final TerminalStateService TERMINAL_STATE = new TerminalStateService();

    private final GameId id;
    private final TimeControl timeControl;
    private final List<Game.Participant> participants;

    private GameStatus status;
    private long revision;
    private ChessPosition position;
    private MoveHistory history;
    private String lastMoveUci;
    private long whiteRemainingMs;
    private long blackRemainingMs;
    private Side activeSide;
    private Instant clockUpdatedAt;
    private Side drawOfferSide;
    private String resultOutcome;
    private String resultReason;
    private Instant endedAt;
    private Instant lastActionAt;

    private Match(
            GameId id,
            GameStatus status,
            TimeControl timeControl,
            long revision,
            List<Game.Participant> participants,
            ChessPosition position,
            MoveHistory history,
            String lastMoveUci,
            long whiteRemainingMs,
            long blackRemainingMs,
            Side activeSide,
            Instant clockUpdatedAt,
            Side drawOfferSide,
            String resultOutcome,
            String resultReason) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.timeControl = Objects.requireNonNull(timeControl, "timeControl must not be null");
        this.revision = revision;
        this.participants = List.copyOf(Objects.requireNonNull(participants, "participants must not be null"));
        this.position = Objects.requireNonNull(position, "position must not be null");
        this.history = Objects.requireNonNull(history, "history must not be null");
        this.lastMoveUci = lastMoveUci;
        this.whiteRemainingMs = whiteRemainingMs;
        this.blackRemainingMs = blackRemainingMs;
        this.activeSide = activeSide;
        this.clockUpdatedAt = clockUpdatedAt;
        this.drawOfferSide = drawOfferSide;
        this.resultOutcome = resultOutcome;
        this.resultReason = resultReason;
    }

    public static Match rehydrate(
            GameId id,
            GameStatus status,
            TimeControl timeControl,
            long revision,
            List<Game.Participant> participants,
            ChessPosition position,
            MoveHistory history,
            String lastMoveUci,
            long whiteRemainingMs,
            long blackRemainingMs,
            Side activeSide,
            Instant clockUpdatedAt,
            Side drawOfferSide,
            String resultOutcome,
            String resultReason) {
        return new Match(
                id,
                status,
                timeControl,
                revision,
                participants,
                position,
                history,
                lastMoveUci,
                whiteRemainingMs,
                blackRemainingMs,
                activeSide,
                clockUpdatedAt,
                drawOfferSide,
                resultOutcome,
                resultReason);
    }

    public GameId id() {
        return id;
    }

    public GameStatus status() {
        return status;
    }

    public long revision() {
        return revision;
    }

    public ChessPosition position() {
        return position;
    }

    public MoveHistory history() {
        return history;
    }

    public String lastMoveUci() {
        return lastMoveUci;
    }

    public long whiteRemainingMs() {
        return whiteRemainingMs;
    }

    public long blackRemainingMs() {
        return blackRemainingMs;
    }

    public Side activeSide() {
        return activeSide;
    }

    public Instant clockUpdatedAt() {
        return clockUpdatedAt;
    }

    public Side drawOfferSide() {
        return drawOfferSide;
    }

    public String resultOutcome() {
        return resultOutcome;
    }

    public String resultReason() {
        return resultReason;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public Instant lastActionAt() {
        return lastActionAt;
    }

    public List<Game.Participant> participants() {
        return participants;
    }

    public Optional<Side> sideOf(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return participants.stream()
                .filter(participant -> "HUMAN".equals(participant.kind()) && sessionId.equals(participant.sessionId()))
                .map(Game.Participant::side)
                .findFirst();
    }

    public void applyMove(UUID sessionId, long expectedRevision, Move move, Instant now) {
        lastActionAt = now;
        applyMoveFrom(requireParticipant(sessionId), expectedRevision, () -> move, now);
    }

    public void applyAiMove(Side side, long expectedRevision, Move move, Instant now) {
        applyAiCandidate(side, expectedRevision, () -> move, now);
    }

    public void applyAiCandidate(Side side, long expectedRevision, Supplier<Move> candidate, Instant now) {
        lastActionAt = now;
        requireAiParticipant(side);
        applyMoveFrom(side, expectedRevision, candidate, now);
    }

    private void applyMoveFrom(Side side, long expectedRevision, Supplier<Move> candidate, Instant now) {
        requireActive(now);
        requireTurn(side);
        requireRevision(expectedRevision);

        Move move = candidate.get();
        if (!position.legalMoves().contains(move)) {
            throw new IllegalMoveException();
        }

        chargeElapsed(now);
        if (finishedByTimeout()) {
            return;
        }

        position = position.apply(move);
        history = history.append(move);
        lastMoveUci = move.toUci();
        drawOfferSide = null;
        addIncrement(side);
        activeSide = position.sideToMove();
        clockUpdatedAt = now;
        revision++;

        TERMINAL_STATE.result(position).ifPresent(this::finishWith);
    }

    public void abandon(UUID sessionId, Instant now) {
        lastActionAt = now;
        Side side = requireParticipant(sessionId);
        requireActive(now);
        if (status != GameStatus.ACTIVE) {
            return;
        }
        finish(side.opposite() == Side.WHITE ? "WHITE_WIN" : "BLACK_WIN", "RESIGNATION", now);
    }

    public void offerDraw(UUID sessionId, long expectedRevision, Instant now) {
        lastActionAt = now;
        Side side = requireParticipant(sessionId);
        requireActive(now);
        requireRevision(expectedRevision);
        if (drawOfferSide == side) {
            return;
        }
        if (drawOfferSide == side.opposite()) {
            acceptDraw(now);
            return;
        }
        drawOfferSide = side;
        revision++;
    }

    public void respondDraw(UUID sessionId, long expectedRevision, boolean accept, Instant now) {
        lastActionAt = now;
        Side side = requireParticipant(sessionId);
        requireActive(now);
        requireRevision(expectedRevision);
        if (drawOfferSide == null || drawOfferSide == side) {
            throw new NoDrawOfferException();
        }
        if (accept) {
            acceptDraw(now);
            return;
        }
        drawOfferSide = null;
        revision++;
    }

    public boolean settleTimeout(Instant now) {
        lastActionAt = now;
        if (status != GameStatus.ACTIVE || timeControl.isInfinite() || activeSide == null) {
            return false;
        }
        chargeElapsed(now);
        return finishedByTimeout();
    }

    private void acceptDraw(Instant now) {
        drawOfferSide = null;
        finish("DRAW", "DRAW_AGREED", now);
    }

    private void chargeElapsed(Instant now) {
        if (timeControl.isInfinite() || activeSide == null || clockUpdatedAt == null) {
            return;
        }
        long elapsedMs = Math.max(0, Duration.between(clockUpdatedAt, now).toMillis());
        if (activeSide == Side.WHITE) {
            whiteRemainingMs = Math.max(0, whiteRemainingMs - elapsedMs);
        } else {
            blackRemainingMs = Math.max(0, blackRemainingMs - elapsedMs);
        }
        clockUpdatedAt = now;
    }

    private boolean finishedByTimeout() {
        if (timeControl.isInfinite() || activeSide == null) {
            return false;
        }
        long remaining = activeSide == Side.WHITE ? whiteRemainingMs : blackRemainingMs;
        if (remaining > 0) {
            return false;
        }
        Side winner = activeSide.opposite();
        Instant timeoutAt = clockUpdatedAt;
        finish(winner == Side.WHITE ? "WHITE_WIN" : "BLACK_WIN", "TIMEOUT", timeoutAt);
        return true;
    }

    private void addIncrement(Side side) {
        if (timeControl.isInfinite() || timeControl.incrementMs() <= 0) {
            return;
        }
        if (side == Side.WHITE) {
            whiteRemainingMs += timeControl.incrementMs();
        } else {
            blackRemainingMs += timeControl.incrementMs();
        }
    }

    private void finishWith(ChessResult result) {
        finish(result.outcome(), result.reason(), clockUpdatedAt);
    }

    private void finish(String outcome, String reason, Instant endInstant) {
        status = GameStatus.FINISHED;
        resultOutcome = outcome;
        resultReason = reason;
        drawOfferSide = null;
        activeSide = null;
        clockUpdatedAt = null;
        endedAt = endInstant != null ? endInstant : lastActionAt;
        revision++;
    }

    public Optional<Side> aiSideToMove() {
        if (status != GameStatus.ACTIVE || activeSide == null) {
            return Optional.empty();
        }
        return participants.stream()
                .filter(participant -> participant.side() == activeSide && "AI".equals(participant.kind()))
                .map(Game.Participant::side)
                .findFirst();
    }

    private void requireAiParticipant(Side side) {
        boolean controlledByAi = participants.stream()
                .anyMatch(participant -> participant.side() == side && "AI".equals(participant.kind()));
        if (!controlledByAi) {
            throw new NotAParticipantException();
        }
    }

    private Side requireParticipant(UUID sessionId) {
        return sideOf(sessionId).orElseThrow(NotAParticipantException::new);
    }

    private void requireActive(Instant now) {
        if (status != GameStatus.ACTIVE) {
            throw new InvalidGameStateException();
        }
        chargeElapsed(now);
        if (finishedByTimeout()) {
            throw new GameTimedOutException();
        }
    }

    private void requireTurn(Side side) {
        if (activeSide != side) {
            throw new NotYourTurnException();
        }
    }

    private void requireRevision(long expectedRevision) {
        if (expectedRevision >= 0 && expectedRevision != revision) {
            throw new StaleRevisionException();
        }
    }

    public static final class NotAParticipantException extends RuntimeException {
    }

    public static final class InvalidGameStateException extends RuntimeException {
    }

    public static final class NotYourTurnException extends RuntimeException {
    }

    public static final class StaleRevisionException extends RuntimeException {
    }

    public static final class IllegalMoveException extends RuntimeException {
    }

    public static final class NoDrawOfferException extends RuntimeException {
    }

    public static final class GameTimedOutException extends RuntimeException {
    }
}
