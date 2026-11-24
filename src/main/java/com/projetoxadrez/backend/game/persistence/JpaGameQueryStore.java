package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.GameQueryStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.application.LobbyGame;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
public class JpaGameQueryStore implements GameQueryStore {

    private final GameRepository repository;
    private final GameSnapshotFactory snapshotFactory;

    public JpaGameQueryStore(GameRepository repository, GameSnapshotFactory snapshotFactory) {
        this.repository = repository;
        this.snapshotFactory = snapshotFactory;
    }

    @Override
    public Page<LobbyGame> findPublicWaitingGames(Pageable pageable) {
        return repository.findByStatusAndVisibility(GameStatus.WAITING, GameVisibility.PUBLIC, pageable)
                .map(this::lobbyGame);
    }

    /**
     * Partida visível para a sessão: quem joga, sempre; qualquer um, quando a partida é entre duas
     * IAs, que existe justamente para ser assistida (EAP 2.14.1).
     */
    @Override
    public Optional<GameSnapshot> findSnapshot(UUID gameId, UUID sessionId) {
        return repository.findWithParticipantsById(gameId)
                .filter(game -> plays(game, sessionId) || isSpectatable(game))
                .map(snapshotFactory::snapshot);
    }

    private static boolean plays(GameEntity game, UUID sessionId) {
        return game.getParticipants().stream()
                .anyMatch(participant -> sessionId.equals(participant.getSessionId()));
    }

    private static boolean isSpectatable(GameEntity game) {
        return game.getParticipants().stream()
                .noneMatch(participant -> "HUMAN".equals(participant.getKind()));
    }

    @Override
    public boolean existsById(UUID gameId) {
        return repository.existsById(gameId);
    }

    private LobbyGame lobbyGame(GameEntity game) {
        return new LobbyGame(
                game.getId(),
                game.getStatus(),
                game.getVisibility(),
                new TimeControl(game.getInitialTimeMs(), game.getIncrementMs()),
                game.getCreatedAt());
    }

    GameSnapshot snapshot(GameEntity game) {
        return snapshotFactory.snapshot(game);
    }

}
