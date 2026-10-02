package com.projetoxadrez.backend.session.persistence;

import com.projetoxadrez.backend.session.application.GuestSessionStore;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JpaGuestSessionStore implements GuestSessionStore {

    private final GuestSessionRepository repository;

    public JpaGuestSessionStore(GuestSessionRepository repository) {
        this.repository = repository;
    }

    @Override
    public GuestSessionEntity save(GuestSessionEntity session) {
        return repository.save(session);
    }

    @Override
    public Optional<GuestSessionEntity> findByRecoveryTokenHash(String recoveryTokenHash) {
        return repository.findByRecoveryTokenHash(recoveryTokenHash);
    }
}
