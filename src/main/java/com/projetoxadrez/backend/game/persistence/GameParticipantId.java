package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.chess.Side;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class GameParticipantId implements Serializable {

    private UUID gameId;

    @Enumerated(EnumType.STRING)
    private Side side;

    protected GameParticipantId() {
    }

    public UUID getGameId() {
        return gameId;
    }

    public Side getSide() {
        return side;
    }

    @Override
    public boolean equals(Object candidate) {
        return this == candidate || candidate instanceof GameParticipantId other
                && Objects.equals(gameId, other.gameId)
                && side == other.side;
    }

    @Override
    public int hashCode() {
        return Objects.hash(gameId, side);
    }
}
