package com.projetoxadrez.backend.session.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GuestSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T15:00:00Z");
    private static final String TOKEN = "Q4qGEqpQ6TBGKs-b0bma-DqXYw-zIr1nR2TQPr3qLxA";

    private InMemoryGuestSessionStore store;
    private GuestSessionService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryGuestSessionStore();
        service = new GuestSessionService(
                store,
                () -> TOKEN,
                new GuestSessionProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsGuestSessionWithOpaqueRecoveryTokenAndHashedPersistence() {
        GuestSessionResult result = service.create();

        assertThat(result.sessionId()).isNotNull();
        assertThat(result.recoveryToken()).isEqualTo(TOKEN);
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(store.getLastSaved().getRecoveryTokenHash()).isNotEqualTo(TOKEN);
        assertThat(store.getLastSaved().getRecoveryTokenHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void recoversExistingValidSessionWithoutCreatingAnotherOne() {
        GuestSessionResult created = service.create();

        GuestSessionResult recovered = service.recover(TOKEN);

        assertThat(recovered.sessionId()).isEqualTo(created.sessionId());
        assertThat(recovered.recoveryToken()).isEqualTo(TOKEN);
        assertThat(recovered.expiresAt()).isEqualTo(created.expiresAt());
        assertThat(store.getSaveCount()).isEqualTo(1);
        assertThat(store.getLastSaved().getLastSeenAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsUnknownAndExpiredRecoveryTokens() {
        assertThatThrownBy(() -> service.recover(TOKEN))
                .isInstanceOf(InvalidGuestSessionException.class);

        GuestSessionProperties shortLivedProperties = new GuestSessionProperties();
        shortLivedProperties.setTtl(Duration.ofSeconds(1));
        GuestSessionService expiredSessionCreator = new GuestSessionService(
                store,
                () -> TOKEN,
                shortLivedProperties,
                Clock.fixed(NOW.minusSeconds(2), ZoneOffset.UTC));
        expiredSessionCreator.create();

        assertThatThrownBy(() -> service.recover(TOKEN))
                .isInstanceOf(ExpiredGuestSessionException.class);
        assertThat(store.getSaveCount()).isEqualTo(1);
    }

    @Test
    void rejectsBlankRecoveryTokenBeforeLookingUpASession() {
        assertThatThrownBy(() -> service.recover(" "))
                .isInstanceOf(InvalidSessionRequestException.class);
        assertThat(store.getSaveCount()).isZero();
    }
}
