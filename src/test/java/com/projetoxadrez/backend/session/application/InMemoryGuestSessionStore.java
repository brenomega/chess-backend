package com.projetoxadrez.backend.session.application;

import com.projetoxadrez.backend.session.domain.GuestSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryGuestSessionStore implements GuestSessionStore {

    private final Map<String, GuestSession> sessionsByHash = new HashMap<>();
    private int saveCount;
    private GuestSession lastSaved;

    @Override
    public GuestSession save(GuestSession session) {
        sessionsByHash.put(session.recoveryTokenHash(), session);
        saveCount++;
        lastSaved = session;
        return session;
    }

    @Override
    public Optional<GuestSession> findByRecoveryTokenHash(String recoveryTokenHash) {
        return Optional.ofNullable(sessionsByHash.get(recoveryTokenHash));
    }

    public int getSaveCount() {
        return saveCount;
    }

    public GuestSession getLastSaved() {
        return lastSaved;
    }
}
