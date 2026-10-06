package com.projetoxadrez.backend.session.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class GuestSession {

    private final UUID id;
    private final String recoveryTokenHash;
    private final Instant expiresAt;
    private final Instant createdAt;
    private Instant lastSeenAt;

    public GuestSession(
            UUID id,
            String recoveryTokenHash,
            Instant expiresAt,
            Instant createdAt,
            Instant lastSeenAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.recoveryTokenHash = Objects.requireNonNull(recoveryTokenHash, "recoveryTokenHash must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
    }

    public UUID id() {
        return id;
    }

    public String recoveryTokenHash() {
        return recoveryTokenHash;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
    }

    public void markSeenAt(Instant instant) {
        lastSeenAt = Objects.requireNonNull(instant, "instant must not be null");
    }
}
