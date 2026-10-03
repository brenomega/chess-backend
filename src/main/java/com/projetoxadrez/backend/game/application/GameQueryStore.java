package com.projetoxadrez.backend.game.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface GameQueryStore {

    Page<LobbyGame> findPublicWaitingGames(Pageable pageable);

    Optional<GameSnapshot> findSnapshot(UUID gameId, UUID sessionId);

    boolean existsById(UUID gameId);
}
