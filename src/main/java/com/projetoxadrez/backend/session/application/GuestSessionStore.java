package com.projetoxadrez.backend.session.application;

import com.projetoxadrez.backend.session.domain.GuestSession;
import java.util.Optional;

public interface GuestSessionStore {

    GuestSession save(GuestSession session);

    Optional<GuestSession> findByRecoveryTokenHash(String recoveryTokenHash);
}
