package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameRepository extends JpaRepository<GameEntity, UUID> {

    Page<GameEntity> findByStatusAndVisibility(GameStatus status, GameVisibility visibility, Pageable pageable);

    @EntityGraph(attributePaths = "participants")
    Optional<GameEntity> findWithParticipantsById(UUID id);

    /**
     * Carrega a partida com trava de escrita, para que duas ações concorrentes na mesma partida
     * sejam serializadas em vez de competirem pela revisão (EAP 2.11).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GameEntity g where g.id = :id")
    Optional<GameEntity> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByEntryCodeAndStatusAndVisibility(
            String entryCode, GameStatus status, GameVisibility visibility);

    @EntityGraph(attributePaths = "participants")
    Optional<GameEntity> findByEntryCodeAndStatusAndVisibility(
            String entryCode, GameStatus status, GameVisibility visibility);
}
