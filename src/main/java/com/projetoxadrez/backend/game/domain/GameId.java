package com.projetoxadrez.backend.game.domain;

import java.util.Objects;
import java.util.UUID;

public record GameId(UUID value) {

    public GameId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static GameId newId() {
        return new GameId(UUID.randomUUID());
    }
}
