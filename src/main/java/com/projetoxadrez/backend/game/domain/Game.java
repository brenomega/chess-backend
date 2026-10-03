package com.projetoxadrez.backend.game.domain;

import java.util.Objects;
import java.util.Optional;

public final class Game {

    private final GameId id;
    private GameStatus status;
    private final GameVisibility visibility;
    private final GameEntryCode entryCode;
    private final TimeControl timeControl;

    private Game(
            GameId id,
            GameStatus status,
            GameVisibility visibility,
            GameEntryCode entryCode,
            TimeControl timeControl) {
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
    }

    public static Game create(GameVisibility visibility, TimeControl timeControl) {
        Objects.requireNonNull(visibility, "visibility must not be null");
        Objects.requireNonNull(timeControl, "timeControl must not be null");
        GameEntryCode entryCode = visibility == GameVisibility.PRIVATE ? GameEntryCode.newCode() : null;
        return new Game(GameId.newId(), GameStatus.WAITING, visibility, entryCode, timeControl);
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
}
