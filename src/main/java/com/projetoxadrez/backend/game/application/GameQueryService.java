package com.projetoxadrez.backend.game.application;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameQueryService {

    private final GameQueryStore store;

    public GameQueryService(GameQueryStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public LobbyPage lobby(Pageable pageable) {
        Page<LobbyGame> games = store.findPublicWaitingGames(pageable);
        return new LobbyPage(
                games.getContent(),
                games.getNumber(),
                games.getSize(),
                games.getTotalElements(),
                games.getTotalPages());
    }

    @Transactional(readOnly = true)
    public GameSnapshot game(UUID gameId, UUID sessionId) {
        return store.findSnapshot(gameId, sessionId).orElseThrow(() -> {
            if (store.existsById(gameId)) {
                return new NotAGameParticipantException();
            }
            return new GameNotFoundException();
        });
    }

    public static final class GameNotFoundException extends RuntimeException {
    }

    public static final class NotAGameParticipantException extends RuntimeException {
    }
}
