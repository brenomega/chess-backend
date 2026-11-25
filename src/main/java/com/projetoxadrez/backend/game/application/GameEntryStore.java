package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.domain.Game;
import java.util.Optional;
import java.util.UUID;

public interface GameEntryStore {

    Optional<Game> findByIdForUpdate(UUID gameId);

    Optional<UUID> findPrivateWaitingIdByEntryCode(String entryCode);

    GameSnapshot save(Game game);
}
