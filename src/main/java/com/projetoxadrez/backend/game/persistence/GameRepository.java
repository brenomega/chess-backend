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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GameEntity g where g.id = :id")
    Optional<GameEntity> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByEntryCodeAndStatusAndVisibility(
            String entryCode, GameStatus status, GameVisibility visibility);

    @Query("""
            select g.id from GameEntity g
            where g.entryCode = :entryCode and g.status = :status and g.visibility = :visibility
            """)
    Optional<UUID> findIdByEntryCodeAndStatusAndVisibility(
            @Param("entryCode") String entryCode,
            @Param("status") GameStatus status,
            @Param("visibility") GameVisibility visibility);
}
