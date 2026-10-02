package com.projetoxadrez.backend.session.application;

import java.time.Instant;
import java.util.UUID;

public record GuestSessionResult(UUID sessionId, String recoveryToken, Instant expiresAt) {
}
