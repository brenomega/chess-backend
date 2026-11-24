package com.projetoxadrez.backend.game.persistence;

import com.projetoxadrez.backend.game.application.AiJobStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface AiJobRepository extends JpaRepository<AiJobEntity, UUID> {

    @Query("select j from AiJobEntity j where j.gameId = :gameId and j.status in "
            + "(com.projetoxadrez.backend.game.application.AiJobStatus.PENDING, "
            + "com.projetoxadrez.backend.game.application.AiJobStatus.ASSIGNED)")
    List<AiJobEntity> findOpenByGameId(@Param("gameId") UUID gameId);

    /**
     * Próximo trabalho a entregar: os pendentes e os cujo prazo venceu, do mais antigo para o mais
     * novo. A trava com {@code skipLocked} deixa dois workers pedirem trabalho ao mesmo tempo sem
     * um esperar pelo outro nem os dois levarem o mesmo.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select j from AiJobEntity j where j.status = "
            + "com.projetoxadrez.backend.game.application.AiJobStatus.PENDING "
            + "or (j.status = com.projetoxadrez.backend.game.application.AiJobStatus.ASSIGNED "
            + "and j.expiresAt <= :now) order by j.createdAt asc")
    List<AiJobEntity> findAssignable(@Param("now") Instant now, Limit limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AiJobEntity j where j.id = :id")
    Optional<AiJobEntity> findByIdForUpdate(@Param("id") UUID id);

    long countByGameIdAndStatus(UUID gameId, AiJobStatus status);
}
