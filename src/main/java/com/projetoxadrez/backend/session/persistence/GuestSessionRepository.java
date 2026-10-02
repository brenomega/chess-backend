package com.projetoxadrez.backend.session.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestSessionRepository extends JpaRepository<GuestSessionEntity, UUID> {

    Optional<GuestSessionEntity> findByRecoveryTokenHash(String recoveryTokenHash);
}
