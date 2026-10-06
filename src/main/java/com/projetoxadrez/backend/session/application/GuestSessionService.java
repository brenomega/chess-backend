package com.projetoxadrez.backend.session.application;

import com.projetoxadrez.backend.session.domain.GuestSession;
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
        GuestSession session = new GuestSession(
                UUID.randomUUID(),
                hash(recoveryToken),
                now.plus(properties.getTtl()),
                now,
                now);
        store.save(session);
        return new GuestSessionResult(session.id(), recoveryToken, session.expiresAt());
    }

    @Transactional
    public GuestSessionResult recover(String recoveryToken) {
        if (recoveryToken == null || recoveryToken.isBlank()) {
            throw new InvalidSessionRequestException();
        }

        GuestSession session = store.findByRecoveryTokenHash(hash(recoveryToken))
                .orElseThrow(InvalidGuestSessionException::new);
        Instant now = clock.instant();
        if (!session.expiresAt().isAfter(now)) {
            throw new ExpiredGuestSessionException();
        }

        session.markSeenAt(now);
        store.save(session);
        return new GuestSessionResult(session.id(), recoveryToken, session.expiresAt());
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
