package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.GameEntryService;
import com.projetoxadrez.backend.game.application.GameEntryStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JpaGameEntryStore implements GameEntryStore {

    private final Function<UUID, Optional<GameEntity>> games;
    private final GameSnapshotFactory snapshotFactory;

    @Autowired
    public JpaGameEntryStore(GameRepository repository, GameSnapshotFactory snapshotFactory) {
        this(repository::findWithParticipantsById, snapshotFactory);
    }

    JpaGameEntryStore(Function<UUID, Optional<GameEntity>> games, GameSnapshotFactory snapshotFactory) {
        this.games = games;
        this.snapshotFactory = snapshotFactory;
    }

    @Override
    public GameSnapshot join(UUID gameId, UUID sessionId, String entryCode, Instant now) {
        GameEntity game = games.apply(gameId)
                .orElseThrow(GameEntryService.GameNotFoundException::new);
        validateEntryCode(game, entryCode);
        try {
            game.join(sessionId, now);
        } catch (IllegalStateException exception) {
            throw new GameEntryService.GameNotJoinableException();
        }
        return snapshotFactory.snapshot(game);
    }

    private static void validateEntryCode(GameEntity game, String entryCode) {
        if (game.getVisibility() == GameVisibility.PUBLIC) {
            if (entryCode != null) {
                throw new GameEntryService.UnexpectedEntryCodeException();
            }
            return;
        }
        if (entryCode == null || entryCode.isBlank()) {
            throw new GameEntryService.EntryCodeRequiredException();
        }
        if (!entryCode.equals(game.getEntryCode())) {
            throw new GameEntryService.InvalidEntryCodeException();
        }
    }
}
