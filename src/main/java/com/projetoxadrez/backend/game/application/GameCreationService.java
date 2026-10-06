package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.domain.Game;
import com.projetoxadrez.backend.game.domain.GameEntryCode;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameCreationService {

    private final GameCreationStore store;
    private final GameEntryCodeGenerator entryCodeGenerator;
    private final Clock clock;

    public GameCreationService(GameCreationStore store, GameEntryCodeGenerator entryCodeGenerator, Clock clock) {
        this.store = store;
        this.entryCodeGenerator = entryCodeGenerator;
        this.clock = clock;
    }

    @Transactional
    public GameSnapshot create(UUID creatorSessionId, GameVisibility visibility, TimeControl timeControl) {
        if (visibility == null || timeControl == null) {
            throw new InvalidGameCreationException();
        }
        GameEntryCode entryCode = visibility == GameVisibility.PRIVATE ? availableEntryCode() : null;
        Game game = Game.create(visibility, timeControl, creatorSessionId, entryCode);
        return store.create(game, clock.instant());
    }

    private GameEntryCode availableEntryCode() {
        GameEntryCode entryCode;
        do {
            entryCode = entryCodeGenerator.generate();
        } while (store.existsPrivateWaitingByEntryCode(entryCode.value()));
        return entryCode;
    }

    public static final class InvalidGameCreationException extends RuntimeException {
    }
}
