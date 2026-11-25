package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.AiJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiJobRepository extends JpaRepository<AiJobEntity, UUID> {

    @Query("select j from AiJobEntity j where j.gameId = :gameId and j.status in "
            + "(com.projetoxadrez.backend.game.application.AiJobStatus.PENDING, "
            + "com.projetoxadrez.backend.game.application.AiJobStatus.ASSIGNED)")
    List<AiJobEntity> findOpenByGameId(@Param("gameId") UUID gameId);

    @Query(value = """
            SELECT g.id
            FROM {h-schema}ai_job j JOIN {h-schema}game g ON g.id = j.game_id
            WHERE j.status = 'PENDING' OR (j.status = 'ASSIGNED' AND j.expires_at <= :now)
            ORDER BY j.created_at, j.id
            LIMIT 1
            FOR UPDATE OF g SKIP LOCKED
            """, nativeQuery = true)
    Optional<UUID> lockNextGame(@Param("now") Instant now);

    @Query("select j.gameId from AiJobEntity j where j.id = :id")
    Optional<UUID> findGameId(@Param("id") UUID id);

    long countByGameIdAndStatus(UUID gameId, AiJobStatus status);
}
