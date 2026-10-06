package com.projetoxadrez.backend.session.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "guest_session")
public class GuestSessionEntity {

    @Id
    private UUID id;

    @Column(name = "recovery_token_hash", nullable = false, unique = true, length = 64)
    private String recoveryTokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected GuestSessionEntity() {
    }

    public GuestSessionEntity(
            UUID id,
            String recoveryTokenHash,
            Instant expiresAt,
            Instant createdAt,
            Instant lastSeenAt) {
        this.id = id;
        this.recoveryTokenHash = recoveryTokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.lastSeenAt = lastSeenAt;
    }

    public UUID getId() {
        return id;
    }

    public String getRecoveryTokenHash() {
        return recoveryTokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void updateLastSeenAt(Instant instant) {
        lastSeenAt = instant;
    }
}
