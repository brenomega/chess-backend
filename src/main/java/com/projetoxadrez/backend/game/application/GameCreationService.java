package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.domain.Game;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameCreationService {

    private final GameCreationStore store;
    private final Clock clock;

    public GameCreationService(GameCreationStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public GameSnapshot create(UUID creatorSessionId, GameVisibility visibility, TimeControl timeControl) {
        if (visibility == null || timeControl == null) {
            throw new InvalidGameCreationException();
        }
        Game game = Game.create(visibility, timeControl, creatorSessionId);
        return store.create(game, clock.instant());
    }

    public static final class InvalidGameCreationException extends RuntimeException {
    }
}
