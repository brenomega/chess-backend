package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.domain.Game;
import java.time.Instant;

public interface GameCreationStore {

    GameSnapshot create(Game game, Instant createdAt);
}
