package com.projetoxadrez.backend.game.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projetoxadrez.backend.game.application.GameEntryService;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.application.GameStatePublisher;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
    GameEntryService.class,
    JpaGameEntryStore.class,
    GameSnapshotFactory.class,
    JpaGameEntryStoreIntegrationTest.ClockConfiguration.class
})
class JpaGameEntryStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-03T15:00:00Z");
    private static final String POSITION_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Container
    static final PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresql::getJdbcUrl);
        registry.add("spring.datasource.username", postgresql::getUsername);
        registry.add("spring.datasource.password", postgresql::getPassword);
    }

    @Autowired
    private GameEntryService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @EnumSource(GameVisibility.class)
    void persistsPublicAndPrivateEntryAsOneCommittedTransition(GameVisibility visibility) {
        UUID gameId = UUID.randomUUID();
        UUID creatorSessionId = insertGuestSession();
        UUID joiningSessionId = insertGuestSession();
        String entryCode = visibility == GameVisibility.PRIVATE ? "ABC234" : null;
        insertGame(gameId, GameStatus.WAITING, visibility, entryCode);
        insertParticipant(gameId, Side.WHITE, creatorSessionId);

        GameSnapshot snapshot = service.join(
                gameId,
                joiningSessionId,
                entryCode);

        assertThat(snapshot.status()).isEqualTo(GameStatus.ACTIVE);
        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(snapshot.players()).extracting(GameSnapshot.Player::side)
                .containsExactly(Side.WHITE, Side.BLACK);
        assertThat(readGame(gameId)).isEqualTo(new GameRow(
                GameStatus.ACTIVE.name(), 1, Side.WHITE.name(), NOW, NOW));
        assertThat(readParticipants(gameId)).containsExactlyInAnyOrder(
                Map.of("side", Side.WHITE.name(), "session_id", creatorSessionId, "kind", "HUMAN"),
                Map.of("side", Side.BLACK.name(), "session_id", joiningSessionId, "kind", "HUMAN"));
    }

    @Test
    void rejectsMissingAndInvalidPrivateCodesWithoutChangingStoredRows() {
        UUID missingCodeGameId = waitingGame(GameVisibility.PRIVATE, "DEF567");
        UUID invalidCodeGameId = waitingGame(GameVisibility.PRIVATE, "GHJ678");

        assertThatThrownBy(() -> service.join(missingCodeGameId, insertGuestSession(), null))
                .isInstanceOf(GameEntryService.EntryCodeRequiredException.class);
        assertWaitingAndUnchanged(missingCodeGameId);

        assertThatThrownBy(() -> service.join(invalidCodeGameId, insertGuestSession(), "XYZ789"))
                .isInstanceOf(GameEntryService.InvalidEntryCodeException.class);
        assertWaitingAndUnchanged(invalidCodeGameId);
    }

    @Test
    void rejectsNonWaitingGameWithoutChangingStoredRows() {
        UUID gameId = UUID.randomUUID();
        UUID creatorSessionId = insertGuestSession();
        insertGame(gameId, GameStatus.ACTIVE, GameVisibility.PUBLIC, null);
        insertParticipant(gameId, Side.WHITE, creatorSessionId);

        assertThatThrownBy(() -> service.join(gameId, insertGuestSession(), null))
                .isInstanceOf(GameEntryService.GameNotJoinableException.class);

        assertThat(readGame(gameId)).isEqualTo(new GameRow(GameStatus.ACTIVE.name(), 0, null, null, NOW.minusSeconds(60)));
        assertThat(readParticipants(gameId)).containsExactly(
                Map.of("side", Side.WHITE.name(), "session_id", creatorSessionId, "kind", "HUMAN"));
    }

    @Test
    void rejectsFullGameWithoutChangingStoredRows() {
        UUID gameId = UUID.randomUUID();
        UUID whiteSessionId = insertGuestSession();
        UUID blackSessionId = insertGuestSession();
        insertGame(gameId, GameStatus.WAITING, GameVisibility.PUBLIC, null);
        insertParticipant(gameId, Side.WHITE, whiteSessionId);
        insertParticipant(gameId, Side.BLACK, blackSessionId);

        assertThatThrownBy(() -> service.join(gameId, insertGuestSession(), null))
                .isInstanceOf(GameEntryService.GameNotJoinableException.class);

        assertThat(readGame(gameId)).isEqualTo(new GameRow(GameStatus.WAITING.name(), 0, null, null, NOW.minusSeconds(60)));
        assertThat(readParticipants(gameId)).containsExactlyInAnyOrder(
                Map.of("side", Side.WHITE.name(), "session_id", whiteSessionId, "kind", "HUMAN"),
                Map.of("side", Side.BLACK.name(), "session_id", blackSessionId, "kind", "HUMAN"));
    }

    private UUID waitingGame(GameVisibility visibility, String entryCode) {
        UUID gameId = UUID.randomUUID();
        UUID creatorSessionId = insertGuestSession();
        insertGame(gameId, GameStatus.WAITING, visibility, entryCode);
        insertParticipant(gameId, Side.WHITE, creatorSessionId);
        return gameId;
    }

    private void assertWaitingAndUnchanged(UUID gameId) {
        assertThat(readGame(gameId)).isEqualTo(new GameRow(GameStatus.WAITING.name(), 0, null, null, NOW.minusSeconds(60)));
        assertThat(readParticipants(gameId)).hasSize(1);
        assertThat(readParticipants(gameId).getFirst().get("side")).isEqualTo(Side.WHITE.name());
    }

    private UUID insertGuestSession() {
        UUID sessionId = UUID.randomUUID();
        String tokenHash = sessionId.toString().replace("-", "").repeat(2);
        jdbcTemplate.update("""
                INSERT INTO chess.guest_session (
                    id, recovery_token_hash, expires_at, created_at, last_seen_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                sessionId,
                tokenHash,
                Timestamp.from(NOW.plusSeconds(3600)),
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW.minusSeconds(60)));
        return sessionId;
    }

    private void insertGame(UUID gameId, GameStatus status, GameVisibility visibility, String entryCode) {
        jdbcTemplate.update("""
                INSERT INTO chess.game (
                    id, status, visibility, entry_code, revision, position_fen, last_move_uci,
                    white_remaining_ms, black_remaining_ms, active_side, clock_updated_at,
                    initial_time_ms, increment_ms, draw_offer_side, result_outcome, result_reason,
                    created_at, updated_at, ended_at
                ) VALUES (?, ?, ?, ?, 0, ?, NULL, 600000, 600000, NULL, NULL,
                    600000, 0, NULL, NULL, NULL, ?, ?, NULL)
                """,
                gameId,
                status.name(),
                visibility.name(),
                entryCode,
                POSITION_FEN,
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW.minusSeconds(60)));
    }

    private void insertParticipant(UUID gameId, Side side, UUID sessionId) {
        jdbcTemplate.update("""
                INSERT INTO chess.game_participant (game_id, side, session_id, kind)
                VALUES (?, ?, ?, 'HUMAN')
                """, gameId, side.name(), sessionId);
    }

    private GameRow readGame(UUID gameId) {
        return jdbcTemplate.queryForObject("""
                SELECT status, revision, active_side, clock_updated_at, updated_at
                FROM chess.game
                WHERE id = ?
                """, (resultSet, rowNumber) -> new GameRow(
                resultSet.getString("status"),
                resultSet.getLong("revision"),
                resultSet.getString("active_side"),
                resultSet.getTimestamp("clock_updated_at") == null
                        ? null
                        : resultSet.getTimestamp("clock_updated_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()), gameId);
    }

    private List<Map<String, Object>> readParticipants(UUID gameId) {
        return jdbcTemplate.queryForList("""
                SELECT side, session_id, kind
                FROM chess.game_participant
                WHERE game_id = ?
                """, gameId);
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        GameStatePublisher gameStatePublisher() {
            return snapshot -> {
            };
        }
    }

    private record GameRow(String status, long revision, String activeSide, Instant clockUpdatedAt, Instant updatedAt) {
    }
}
