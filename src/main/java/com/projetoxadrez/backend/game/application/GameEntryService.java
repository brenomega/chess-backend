package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.domain.Game;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameEntryService {

    private final GameEntryStore store;
    private final GameStatePublisher statePublisher;
    private final Clock clock;

    public GameEntryService(GameEntryStore store, GameStatePublisher statePublisher, Clock clock) {
        this.store = store;
        this.statePublisher = statePublisher;
        this.clock = clock;
    }

    @Transactional
    public GameSnapshot join(UUID gameId, UUID sessionId, String entryCode) {
        Game game = store.findById(gameId).orElseThrow(GameNotFoundException::new);
        try {
            game.join(sessionId, entryCode, clock.instant());
        } catch (Game.EntryCodeRequiredException exception) {
            throw new EntryCodeRequiredException();
        } catch (Game.InvalidEntryCodeException exception) {
            throw new InvalidEntryCodeException();
        } catch (Game.UnexpectedEntryCodeException exception) {
            throw new UnexpectedEntryCodeException();
        } catch (Game.GameNotJoinableException exception) {
            throw new GameNotJoinableException();
        }
        GameSnapshot snapshot = store.save(game);
        statePublisher.publishAfterCommit(snapshot);
        return snapshot;
    }

    public static final class GameNotFoundException extends RuntimeException {
    }

    public static final class EntryCodeRequiredException extends RuntimeException {
    }

    public static final class InvalidEntryCodeException extends RuntimeException {
    }

    public static final class UnexpectedEntryCodeException extends RuntimeException {
    }

    public static final class GameNotJoinableException extends RuntimeException {
    }
}
