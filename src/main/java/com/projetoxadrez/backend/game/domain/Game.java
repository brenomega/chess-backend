package com.projetoxadrez.backend.game.domain;

import java.util.Objects;
import java.util.Optional;

public final class Game {

    private final GameId id;
    private final GameStatus status;
    private final GameVisibility visibility;
    private final GameEntryCode entryCode;

    private Game(GameId id, GameStatus status, GameVisibility visibility, GameEntryCode entryCode) {
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
    }

    public static Game create(GameVisibility visibility) {
        Objects.requireNonNull(visibility, "visibility must not be null");
        GameEntryCode entryCode = visibility == GameVisibility.PRIVATE ? GameEntryCode.newCode() : null;
        return new Game(GameId.newId(), GameStatus.WAITING, visibility, entryCode);
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
}
