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

    @Override
    public Optional<GameSnapshot> findSnapshot(UUID gameId, UUID sessionId) {
        return repository.findWithParticipantsById(gameId)
                .filter(game -> game.getParticipants().stream()
                        .anyMatch(participant -> sessionId.equals(participant.getSessionId())))
                .map(snapshotFactory::snapshot);
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
