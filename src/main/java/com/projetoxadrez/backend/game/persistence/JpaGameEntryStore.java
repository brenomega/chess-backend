package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.GameEntryStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.domain.Game;
import com.projetoxadrez.backend.game.domain.GameEntryCode;
import com.projetoxadrez.backend.game.domain.GameId;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaGameEntryStore implements GameEntryStore {

    private final GameRepository repository;
    private final GameSnapshotFactory snapshotFactory;

    public JpaGameEntryStore(GameRepository repository, GameSnapshotFactory snapshotFactory) {
        this.repository = repository;
        this.snapshotFactory = snapshotFactory;
    }

    @Override
    public Optional<Game> findById(UUID gameId) {
        return repository.findWithParticipantsById(gameId).map(JpaGameEntryStore::toDomain);
    }

    @Override
    public Optional<Game> findPrivateWaitingByEntryCode(String entryCode) {
        return repository.findByEntryCodeAndStatusAndVisibility(
                        entryCode, GameStatus.WAITING, GameVisibility.PRIVATE)
                .map(JpaGameEntryStore::toDomain);
    }

    @Override
    public GameSnapshot save(Game game) {
        GameEntity entity = repository.findWithParticipantsById(game.id().value())
                .orElseThrow(() -> new IllegalStateException("Game disappeared while joining"));
        entity.applyEntryState(game);
        repository.flush();
        return snapshotFactory.snapshot(entity);
    }

    private static Game toDomain(GameEntity entity) {
        return Game.rehydrate(
                new GameId(entity.getId()),
                entity.getStatus(),
                entity.getVisibility(),
                entity.getEntryCode() == null ? null : new GameEntryCode(entity.getEntryCode()),
                new TimeControl(entity.getInitialTimeMs(), entity.getIncrementMs()),
                entity.getRevision(),
                entity.getParticipants().stream()
                        .map(participant -> new Game.Participant(
                                participant.getSide(), participant.getSessionId(), participant.getKind()))
                        .toList(),
                entity.getActiveSide(),
                entity.getClockUpdatedAt());
    }
}
