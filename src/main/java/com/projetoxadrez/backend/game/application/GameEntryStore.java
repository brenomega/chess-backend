package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.domain.Game;
import java.util.Optional;
import java.util.UUID;

public interface GameEntryStore {

    Optional<Game> findById(UUID gameId);

    GameSnapshot save(Game game);
}
