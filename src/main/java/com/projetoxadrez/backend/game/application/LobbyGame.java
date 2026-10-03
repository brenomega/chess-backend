package com.projetoxadrez.backend.game.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LobbyGame(
        UUID gameId,
        GameStatus status,
        GameVisibility visibility,
        TimeControl timeControl,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt) {

    public LobbyGame {
        Objects.requireNonNull(gameId, "gameId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        Objects.requireNonNull(timeControl, "timeControl must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
