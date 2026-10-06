package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.GameCreationStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.domain.Game;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class JpaGameCreationStore implements GameCreationStore {

    private final GameRepository repository;
    private final GameSnapshotFactory snapshotFactory;

    public JpaGameCreationStore(GameRepository repository, GameSnapshotFactory snapshotFactory) {
        this.repository = repository;
        this.snapshotFactory = snapshotFactory;
    }

    @Override
    public boolean existsPrivateWaitingByEntryCode(String entryCode) {
        return repository.existsByEntryCodeAndStatusAndVisibility(
                entryCode, GameStatus.WAITING, GameVisibility.PRIVATE);
    }

    @Override
    public GameSnapshot create(Game game, Instant createdAt) {
        GameEntity entity = repository.saveAndFlush(GameEntity.create(game, createdAt));
        return snapshotFactory.snapshot(entity);
    }
}
