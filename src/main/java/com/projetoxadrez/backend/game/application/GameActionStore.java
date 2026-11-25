package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.domain.Match;
import java.util.Optional;
import java.util.UUID;

public interface GameActionStore {

    Optional<Match> findForUpdate(UUID gameId);

    GameSnapshot save(Match match);

    GameSnapshot snapshot(UUID gameId);

    Optional<String> findActionIdentity(UUID gameId, UUID sessionId, String requestId);

    void recordAction(UUID gameId, UUID sessionId, String requestId, String identity);
}
