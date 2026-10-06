package com.projetoxadrez.backend.session.application;

import com.projetoxadrez.backend.session.persistence.GuestSessionEntity;
import java.util.Optional;

public interface GuestSessionStore {

    GuestSessionEntity save(GuestSessionEntity session);

    Optional<GuestSessionEntity> findByRecoveryTokenHash(String recoveryTokenHash);
}
