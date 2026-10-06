package com.projetoxadrez.backend.session.persistence;

import com.projetoxadrez.backend.session.application.GuestSessionStore;
import com.projetoxadrez.backend.session.domain.GuestSession;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JpaGuestSessionStore implements GuestSessionStore {

    private final GuestSessionRepository repository;

    public JpaGuestSessionStore(GuestSessionRepository repository) {
        this.repository = repository;
    }

    @Override
    public GuestSession save(GuestSession session) {
        GuestSessionEntity entity = repository.findById(session.id())
                .orElseGet(() -> new GuestSessionEntity(
                        session.id(),
                        session.recoveryTokenHash(),
                        session.expiresAt(),
                        session.createdAt(),
                        session.lastSeenAt()));
        entity.updateLastSeenAt(session.lastSeenAt());
        return toDomain(repository.save(entity));
    }

    @Override
    public Optional<GuestSession> findByRecoveryTokenHash(String recoveryTokenHash) {
        return repository.findByRecoveryTokenHash(recoveryTokenHash).map(JpaGuestSessionStore::toDomain);
    }

    private static GuestSession toDomain(GuestSessionEntity entity) {
        return new GuestSession(
                entity.getId(),
                entity.getRecoveryTokenHash(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getLastSeenAt());
    }
}
