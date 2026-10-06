package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record GameSnapshot(
        UUID gameId,
        GameStatus status,
        GameVisibility visibility,
        String entryCode,
        long revision,
        List<Player> players,
        Position position,
        Clock clock,
        DrawOffer drawOffer,
        Result result) {

    public GameSnapshot {
        Objects.requireNonNull(gameId, "gameId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        players = List.copyOf(players);
        Objects.requireNonNull(position, "position must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
    }

    public record Player(Side side, String kind) {
        public Player {
            Objects.requireNonNull(side, "side must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }

    public record Position(String fen, Side sideToMove, String lastMoveUci) {
        public Position {
            Objects.requireNonNull(fen, "fen must not be null");
            Objects.requireNonNull(sideToMove, "sideToMove must not be null");
        }
    }

    public record Clock(long whiteRemainingMs, long blackRemainingMs, Side activeSide) {
    }

    public record DrawOffer(Side offeredBy) {
        public DrawOffer {
            Objects.requireNonNull(offeredBy, "offeredBy must not be null");
        }
    }

    public record Result(String outcome, String reason) {
        public Result {
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
