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

/**
 * Partida em andamento: posição, relógio e resultado.
 *
 * <p>Enquanto {@link Game} cobre a criação e a entrada de jogadores, este agregado concentra as
 * ações executadas durante a partida (EAP 2.9), o relógio autoritativo (2.10), a revisão das ações
 * (2.11.1) e a autorização dos participantes (2.13.1).
 *
 * <p>Toda ação devolve um estado novo e incrementa a revisão. Ações recebidas com revisão
 * desatualizada são rejeitadas, de modo que uma repetição atrasada não altera a partida.
 */
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

    /** Instante em que a partida terminou, ou nulo enquanto ela não terminou. */
    public Instant endedAt() {
        return endedAt;
    }

    /** Instante da última ação recebida, para registrar a atualização da partida. */
    public Instant lastActionAt() {
        return lastActionAt;
    }

    public List<Game.Participant> participants() {
        return participants;
    }

    /** Lado do participante com essa sessão, se ele joga esta partida (EAP 2.13.1). */
    public Optional<Side> sideOf(UUID sessionId) {
        return participants.stream()
                .filter(participant -> Objects.equals(participant.sessionId(), sessionId))
                .map(Game.Participant::side)
                .findFirst();
    }

    /**
     * Aplica um lance da sessão informada (EAP 2.9.1).
     *
     * <p>A ordem das verificações importa: autorização, estado, vez e revisão vêm antes das regras
     * de xadrez, para que um lance rejeitado nunca altere a partida.
     */
    public void applyMove(UUID sessionId, long expectedRevision, Move move, Instant now) {
        lastActionAt = now;
        Side side = requireParticipant(sessionId);
        requireActive(now);
        requireTurn(side);
        requireRevision(expectedRevision);

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
        addIncrement(side);
        activeSide = position.sideToMove();
        clockUpdatedAt = now;
        revision++;

        TERMINAL_STATE.result(position).ifPresent(this::finishWith);
    }

    /** Abandono voluntário: o oponente vence (EAP 2.9.2). */
    public void abandon(UUID sessionId, Instant now) {
        lastActionAt = now;
        Side side = requireParticipant(sessionId);
        requireActive(now);
        if (status != GameStatus.ACTIVE) {
            return; // já encerrada pelo relógio
        }
        finish(side.opposite() == Side.WHITE ? "WHITE_WIN" : "BLACK_WIN", "RESIGNATION", now);
    }

    /**
     * Encerra a partida se o tempo do lado da vez acabou (EAP 2.10.2). Devolve true quando o
     * relógio decidiu a partida nesta chamada.
     */
    public boolean settleTimeout(Instant now) {
        lastActionAt = now;
        if (status != GameStatus.ACTIVE || timeControl.isInfinite() || activeSide == null) {
            return false;
        }
        chargeElapsed(now);
        return finishedByTimeout();
    }

    /** Desconta do lado da vez o tempo decorrido desde a última atualização (EAP 2.10.1). */
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

    /**
     * Encerra a partida. O relógio para junto: sem lado da vez não há âncora de tempo, e a tabela
     * exige que os dois sejam nulos ao mesmo tempo.
     */
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

    private Side requireParticipant(UUID sessionId) {
        return participants.stream()
                .filter(participant -> Objects.equals(participant.sessionId(), sessionId))
                .map(Game.Participant::side)
                .findFirst()
                .orElseThrow(NotAParticipantException::new);
    }

    /** A partida precisa estar em andamento; o relógio pode encerrá-la aqui. */
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

    public static final class GameTimedOutException extends RuntimeException {
    }
}
