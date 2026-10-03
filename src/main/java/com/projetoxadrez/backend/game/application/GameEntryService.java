package com.projetoxadrez.backend.game.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameEntryService {

    private final GameEntryStore store;
    private final Clock clock;

    public GameEntryService(GameEntryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public GameSnapshot join(UUID gameId, UUID sessionId, String entryCode) {
        return store.join(gameId, sessionId, entryCode, clock.instant());
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
