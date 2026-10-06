package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<GameEntity, UUID> {

    Page<GameEntity> findByStatusAndVisibility(GameStatus status, GameVisibility visibility, Pageable pageable);

    @EntityGraph(attributePaths = "participants")
    Optional<GameEntity> findWithParticipantsById(UUID id);

    boolean existsByEntryCodeAndStatusAndVisibility(
            String entryCode, GameStatus status, GameVisibility visibility);

    @EntityGraph(attributePaths = "participants")
    Optional<GameEntity> findByEntryCodeAndStatusAndVisibility(
            String entryCode, GameStatus status, GameVisibility visibility);
}
