package com.projetoxadrez.backend.game.domain;

import com.projetoxadrez.backend.game.chess.Side;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class Game {

    private final GameId id;
    private GameStatus status;
    private final GameVisibility visibility;
    private final GameEntryCode entryCode;
    private final TimeControl timeControl;
    private long revision;
    private final List<Participant> participants;
    private Side activeSide;
    private Instant clockUpdatedAt;

    private Game(
            GameId id,
            GameStatus status,
            GameVisibility visibility,
            GameEntryCode entryCode,
            TimeControl timeControl,
            long revision,
            List<Participant> participants,
            Side activeSide,
            Instant clockUpdatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.visibility = Objects.requireNonNull(visibility, "visibility must not be null");
        if (visibility == GameVisibility.PRIVATE && entryCode == null) {
            throw new IllegalArgumentException("Private games must have an entry code");
        }
        if (visibility == GameVisibility.PUBLIC && entryCode != null) {
            throw new IllegalArgumentException("Public games must not have an entry code");
        }
        this.entryCode = entryCode;
        this.timeControl = Objects.requireNonNull(timeControl, "timeControl must not be null");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.revision = revision;
        this.participants = new ArrayList<>(Objects.requireNonNull(participants, "participants must not be null"));
        this.activeSide = activeSide;
        this.clockUpdatedAt = clockUpdatedAt;
    }

    public static Game create(GameVisibility visibility, TimeControl timeControl, UUID creatorSessionId) {
        Objects.requireNonNull(visibility, "visibility must not be null");
        Objects.requireNonNull(timeControl, "timeControl must not be null");
        Objects.requireNonNull(creatorSessionId, "creatorSessionId must not be null");
        GameEntryCode entryCode = visibility == GameVisibility.PRIVATE ? GameEntryCode.newCode() : null;
        return new Game(
                GameId.newId(),
                GameStatus.WAITING,
                visibility,
                entryCode,
                timeControl,
                0,
                List.of(new Participant(Side.WHITE, creatorSessionId, "HUMAN")),
                null,
                null);
    }

    public static Game rehydrate(
            GameId id,
            GameStatus status,
            GameVisibility visibility,
            GameEntryCode entryCode,
            TimeControl timeControl,
            long revision,
            List<Participant> participants,
            Side activeSide,
            Instant clockUpdatedAt) {
        return new Game(
                id,
                status,
                visibility,
                entryCode,
                timeControl,
                revision,
                participants,
                activeSide,
                clockUpdatedAt);
    }

    public GameId id() {
        return id;
    }

    public GameStatus status() {
        return status;
    }

    public GameVisibility visibility() {
        return visibility;
    }

    public Optional<GameEntryCode> entryCode() {
        return Optional.ofNullable(entryCode);
    }

    public TimeControl timeControl() {
        return timeControl;
    }

    public long revision() {
        return revision;
    }

    public List<Participant> participants() {
        return List.copyOf(participants);
    }

    public Side activeSide() {
        return activeSide;
    }

    public Instant clockUpdatedAt() {
        return clockUpdatedAt;
    }

    public void join(UUID sessionId, String providedEntryCode, Instant now) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        validateEntryCode(providedEntryCode);
        if (status != GameStatus.WAITING
                || participants.size() != 1
                || participants.getFirst().side() != Side.WHITE
                || participants.stream().anyMatch(participant -> sessionId.equals(participant.sessionId()))) {
            throw new GameNotJoinableException();
        }
        participants.add(new Participant(Side.BLACK, sessionId, "HUMAN"));
        status = GameStatus.ACTIVE;
        revision++;
        activeSide = Side.WHITE;
        clockUpdatedAt = now;
    }

    public void activate() {
        transitionTo(GameStatus.ACTIVE);
    }

    public void finish() {
        transitionTo(GameStatus.FINISHED);
    }

    public void abandon() {
        transitionTo(GameStatus.ABANDONED);
    }

    private void transitionTo(GameStatus target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Cannot transition game from %s to %s".formatted(status, target));
        }
        status = target;
    }

    private void validateEntryCode(String providedEntryCode) {
        if (visibility == GameVisibility.PUBLIC) {
            if (providedEntryCode != null) {
                throw new UnexpectedEntryCodeException();
            }
            return;
        }
        if (providedEntryCode == null || providedEntryCode.isBlank()) {
            throw new EntryCodeRequiredException();
        }
        if (!entryCode.value().equals(providedEntryCode)) {
            throw new InvalidEntryCodeException();
        }
    }

    public record Participant(Side side, UUID sessionId, String kind) {

        public Participant {
            Objects.requireNonNull(side, "side must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            if (("HUMAN".equals(kind) && sessionId == null) || ("AI".equals(kind) && sessionId != null)) {
                throw new IllegalArgumentException("Participant identity is inconsistent with its kind");
            }
            if (!"HUMAN".equals(kind) && !"AI".equals(kind)) {
                throw new IllegalArgumentException("Participant kind is invalid");
            }
        }
    }

    public static final class EntryCodeRequiredException extends RuntimeException {
    }

    public static final class InvalidEntryCodeException extends RuntimeException {
    }

    public static final class UnexpectedEntryCodeException extends RuntimeException {
    }

    public static final class GameNotJoinableException extends RuntimeException {
    }
}
