package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.AiJobStatus;
import com.projetoxadrez.backend.game.chess.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Trabalho de IA gravado (EAP 2.14.2). */
@Entity
@Table(name = "ai_job")
public class AiJobEntity {

    @Id
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(nullable = false)
    private long revision;

    @Column(name = "position_fen", nullable = false)
    private String positionFen;

    @Enumerated(EnumType.STRING)
    @Column(name = "side_to_move", nullable = false)
    private Side sideToMove;

    @Column(name = "time_budget_ms", nullable = false)
    private long timeBudgetMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiJobStatus status;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "applied_uci")
    private String appliedUci;

    @Column(name = "applied_revision")
    private Long appliedRevision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiJobEntity() {
    }

    static AiJobEntity pending(
            UUID gameId, long revision, String fen, Side sideToMove, long timeBudgetMs, Instant now) {
        AiJobEntity entity = new AiJobEntity();
        entity.id = UUID.randomUUID();
        entity.gameId = gameId;
        entity.revision = revision;
        entity.positionFen = fen;
        entity.sideToMove = sideToMove;
        entity.timeBudgetMs = timeBudgetMs;
        entity.status = AiJobStatus.PENDING;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    void assignTo(String workerId, Instant now, Instant expiresAt) {
        this.status = AiJobStatus.ASSIGNED;
        this.workerId = workerId;
        this.assignedAt = now;
        this.expiresAt = expiresAt;
        this.updatedAt = now;
    }

    void complete(String uci, long appliedRevision, Instant now) {
        this.status = AiJobStatus.COMPLETED;
        this.appliedUci = uci;
        this.appliedRevision = appliedRevision;
        this.updatedAt = now;
    }

    void cancel(Instant now) {
        this.status = AiJobStatus.CANCELLED;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public long getRevision() {
        return revision;
    }

    public String getPositionFen() {
        return positionFen;
    }

    public Side getSideToMove() {
        return sideToMove;
    }

    public long getTimeBudgetMs() {
        return timeBudgetMs;
    }

    public AiJobStatus getStatus() {
        return status;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getAppliedUci() {
        return appliedUci;
    }

    public Long getAppliedRevision() {
        return appliedRevision;
    }
}
