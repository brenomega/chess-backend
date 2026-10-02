package com.projetoxadrez.backend.session.application;

import com.projetoxadrez.backend.session.persistence.GuestSessionEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestSessionService {

    private final GuestSessionStore store;
    private final RecoveryTokenGenerator tokenGenerator;
    private final GuestSessionProperties properties;
    private final Clock clock;

    public GuestSessionService(
            GuestSessionStore store,
            RecoveryTokenGenerator tokenGenerator,
            GuestSessionProperties properties,
            Clock clock) {
        this.store = store;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public GuestSessionResult create() {
        Instant now = clock.instant();
        String recoveryToken = tokenGenerator.generate();
        GuestSessionEntity session = new GuestSessionEntity(
                UUID.randomUUID(),
                hash(recoveryToken),
                now.plus(properties.getTtl()),
                now,
                now);
        store.save(session);
        return new GuestSessionResult(session.getId(), recoveryToken, session.getExpiresAt());
    }

    @Transactional
    public GuestSessionResult recover(String recoveryToken) {
        if (recoveryToken == null || recoveryToken.isBlank()) {
            throw new InvalidSessionRequestException();
        }

        GuestSessionEntity session = store.findByRecoveryTokenHash(hash(recoveryToken))
                .orElseThrow(InvalidGuestSessionException::new);
        Instant now = clock.instant();
        if (!session.getExpiresAt().isAfter(now)) {
            throw new ExpiredGuestSessionException();
        }

        session.markSeenAt(now);
        return new GuestSessionResult(session.getId(), recoveryToken, session.getExpiresAt());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
