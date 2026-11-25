package com.projetoxadrez.backend.game.application;

import com.projetoxadrez.backend.game.domain.Game;
import com.projetoxadrez.backend.game.domain.GameEntryCode;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
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
        Game game = store.findByIdForUpdate(gameId).orElseThrow(GameNotFoundException::new);
        return join(game, sessionId, entryCode);
    }

    @Transactional
    public GameSnapshot joinByCode(UUID sessionId, String entryCode) {
        if (entryCode == null || entryCode.isBlank()) {
            throw new EntryCodeRequiredException();
        }
        GameEntryCode code;
        try {
            code = new GameEntryCode(entryCode);
        } catch (IllegalArgumentException exception) {
            throw new InvalidEntryCodeException();
        }
        Game game = store.findPrivateWaitingIdByEntryCode(code.value())
                .flatMap(store::findByIdForUpdate)
                .filter(candidate -> candidate.visibility() == GameVisibility.PRIVATE
                        && candidate.status() == GameStatus.WAITING
                        && candidate.entryCode().filter(code::equals).isPresent())
                .orElseThrow(InvalidEntryCodeException::new);
        return join(game, sessionId, code.value());
    }

    private GameSnapshot join(Game game, UUID sessionId, String entryCode) {
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
