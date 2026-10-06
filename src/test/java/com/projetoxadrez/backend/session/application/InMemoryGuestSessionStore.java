package com.projetoxadrez.backend.session.application;

import com.projetoxadrez.backend.session.persistence.GuestSessionEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryGuestSessionStore implements GuestSessionStore {

    private final Map<String, GuestSessionEntity> sessionsByHash = new HashMap<>();
    private int saveCount;
    private GuestSessionEntity lastSaved;

    @Override
    public GuestSessionEntity save(GuestSessionEntity session) {
        sessionsByHash.put(session.getRecoveryTokenHash(), session);
        saveCount++;
        lastSaved = session;
        return session;
    }

    @Override
    public Optional<GuestSessionEntity> findByRecoveryTokenHash(String recoveryTokenHash) {
        return Optional.ofNullable(sessionsByHash.get(recoveryTokenHash));
    }

    public int getSaveCount() {
        return saveCount;
    }

    public GuestSessionEntity getLastSaved() {
        return lastSaved;
    }
}
