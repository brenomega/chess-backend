package com.projetoxadrez.backend.session.rest;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.UUID;

public record GuestSessionResponse(
        UUID sessionId,
        String recoveryToken,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt) {
}
