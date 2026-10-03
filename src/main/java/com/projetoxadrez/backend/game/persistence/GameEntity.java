package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "game")
public class GameEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GameStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GameVisibility visibility;

    @Column(name = "entry_code")
    private String entryCode;

    @Column(nullable = false)
    private long revision;

    @Column(name = "position_fen", nullable = false)
    private String positionFen;

    @Column(name = "last_move_uci")
    private String lastMoveUci;

    @Column(name = "white_remaining_ms", nullable = false)
    private long whiteRemainingMs;

    @Column(name = "black_remaining_ms", nullable = false)
    private long blackRemainingMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "active_side")
    private Side activeSide;

    @Column(name = "draw_offer_side")
    @Enumerated(EnumType.STRING)
    private Side drawOfferSide;

    @Column(name = "result_outcome")
    private String resultOutcome;

    @Column(name = "result_reason")
    private String resultReason;

    @Column(name = "initial_time_ms", nullable = false)
    private long initialTimeMs;

    @Column(name = "increment_ms", nullable = false)
    private long incrementMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL)
    @OrderBy("id.side")
    private List<GameParticipantEntity> participants = new ArrayList<>();

    protected GameEntity() {
    }

    public UUID getId() {
        return id;
    }

    public GameStatus getStatus() {
        return status;
    }

    public GameVisibility getVisibility() {
        return visibility;
    }

    public String getEntryCode() {
        return entryCode;
    }

    public long getRevision() {
        return revision;
    }

    public String getPositionFen() {
        return positionFen;
    }

    public String getLastMoveUci() {
        return lastMoveUci;
    }

    public long getWhiteRemainingMs() {
        return whiteRemainingMs;
    }

    public long getBlackRemainingMs() {
        return blackRemainingMs;
    }

    public Side getActiveSide() {
        return activeSide;
    }

    public Side getDrawOfferSide() {
        return drawOfferSide;
    }

    public String getResultOutcome() {
        return resultOutcome;
    }

    public String getResultReason() {
        return resultReason;
    }

    public long getInitialTimeMs() {
        return initialTimeMs;
    }

    public long getIncrementMs() {
        return incrementMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<GameParticipantEntity> getParticipants() {
        return participants;
    }
}
