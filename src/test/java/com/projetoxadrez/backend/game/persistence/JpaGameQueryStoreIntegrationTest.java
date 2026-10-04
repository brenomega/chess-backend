package com.projetoxadrez.backend.game.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.application.LobbyGame;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class JpaGameQueryStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-03T15:00:00Z");
    private static final String POSITION_FEN = "8/8/8/8/8/8/8/8 w - - 0 1";

    @Container
    static final PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresql::getJdbcUrl);
        registry.add("spring.datasource.username", postgresql::getUsername);
        registry.add("spring.datasource.password", postgresql::getPassword);
    }

    @Autowired
    private GameRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JpaGameQueryStore store;

    @BeforeEach
    void setUp() {
        store = new JpaGameQueryStore(repository, new GameSnapshotFactory(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void filtersOrdersAndPagesThePublicWaitingLobby() {
        UUID oldest = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID newerLowerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID newerHigherId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        insertGame(oldest, GameStatus.WAITING, GameVisibility.PUBLIC, NOW.minusSeconds(60));
        insertGame(newerLowerId, GameStatus.WAITING, GameVisibility.PUBLIC, NOW);
        insertGame(newerHigherId, GameStatus.WAITING, GameVisibility.PUBLIC, NOW);
        insertGame(UUID.randomUUID(), GameStatus.ACTIVE, GameVisibility.PUBLIC, NOW.plusSeconds(60));
        insertGame(UUID.randomUUID(), GameStatus.WAITING, GameVisibility.PRIVATE, NOW.plusSeconds(60));

        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Page<LobbyGame> firstPage = store.findPublicWaitingGames(PageRequest.of(0, 2, sort));
        Page<LobbyGame> secondPage = store.findPublicWaitingGames(PageRequest.of(1, 2, sort));

        assertThat(firstPage.getContent())
                .extracting(LobbyGame::gameId)
                .containsExactly(newerHigherId, newerLowerId);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(secondPage.getContent())
                .extracting(LobbyGame::gameId)
                .containsExactly(oldest);
    }

    @Test
    void returnsTheSnapshotOnlyForAPersistedParticipant() {
        UUID gameId = UUID.randomUUID();
        UUID participantSessionId = UUID.randomUUID();
        insertGuestSession(participantSessionId);
        insertGame(gameId, GameStatus.ACTIVE, GameVisibility.PRIVATE, NOW);
        jdbcTemplate.update("""
                INSERT INTO chess.game_participant (game_id, side, session_id, kind)
                VALUES (?, 'WHITE', ?, 'HUMAN')
                """, gameId, participantSessionId);
        jdbcTemplate.update("""
                INSERT INTO chess.game_participant (game_id, side, session_id, kind)
                VALUES (?, 'BLACK', NULL, 'AI')
                """, gameId);

        assertThat(store.findSnapshot(gameId, participantSessionId))
                .get()
                .extracting(GameSnapshot::gameId)
                .isEqualTo(gameId);
        assertThat(store.findSnapshot(gameId, UUID.randomUUID())).isEmpty();
    }

    private void insertGame(UUID id, GameStatus status, GameVisibility visibility, Instant createdAt) {
        String entryCode = visibility == GameVisibility.PRIVATE ? "ABC234" : null;
        jdbcTemplate.update("""
                INSERT INTO chess.game (
                    id, status, visibility, entry_code, revision, position_fen, last_move_uci,
                    white_remaining_ms, black_remaining_ms, active_side, clock_updated_at,
                    initial_time_ms, increment_ms, draw_offer_side, result_outcome, result_reason,
                    created_at, updated_at, ended_at
                ) VALUES (?, ?, ?, ?, 0, ?, NULL, 600000, 600000, NULL, NULL,
                    600000, 0, NULL, NULL, NULL, ?, ?, NULL)
                """,
                id,
                status.name(),
                visibility.name(),
                entryCode,
                POSITION_FEN,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }

    private void insertGuestSession(UUID sessionId) {
        jdbcTemplate.update("""
                INSERT INTO chess.guest_session (
                    id, recovery_token_hash, expires_at, created_at, last_seen_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                sessionId,
                "a".repeat(64),
                Timestamp.from(NOW.plusSeconds(3600)),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
    }
}
