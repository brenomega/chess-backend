package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.chess.Side;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "game_participant")
public class GameParticipantEntity {

    @EmbeddedId
    private GameParticipantId id;

    @MapsId("gameId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    private GameEntity game;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(nullable = false)
    private String kind;

    protected GameParticipantEntity() {
    }

    public Side getSide() {
        return id.getSide();
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getKind() {
        return kind;
    }
}
