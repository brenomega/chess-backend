package com.projetoxadrez.backend.game.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GameCreationFlowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresql::getJdbcUrl);
        registry.add("spring.datasource.username", postgresql::getUsername);
        registry.add("spring.datasource.password", postgresql::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsQueriesAndJoinsPublicAndPrivateGamesFromAnEmptyDatabase() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chess.game", Long.class)).isZero();

        Session sessionA = createSession();
        CreatedGame publicGame = createGame(sessionA.token(), "PUBLIC", 600_000);

        mockMvc.perform(get("/v1/games/{gameId}", publicGame.id())
                        .header("X-Session-Token", sessionA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.entryCode").doesNotExist())
                .andExpect(jsonPath("$.players.length()").value(1))
                .andExpect(jsonPath("$.players[0].side").value("WHITE"))
                .andExpect(jsonPath("$.players[0].kind").value("HUMAN"));

        assertLobbyContainsOnly(sessionA.token(), publicGame.id());

        CreatedGame privateGame = createGame(sessionA.token(), "PRIVATE", 180_000);
        assertThat(privateGame.entryCode()).matches("[A-HJ-NP-Z2-9]{6}");

        mockMvc.perform(get("/v1/games/{gameId}", privateGame.id())
                        .header("X-Session-Token", sessionA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.entryCode").value(privateGame.entryCode()));

        assertLobbyContainsOnly(sessionA.token(), publicGame.id());

        Session sessionB = createSession();
        joinPublicGame(sessionB.token(), publicGame.id());
        rejectUnknownPrivateCode(sessionB.token());
        joinPrivateGame(sessionB.token(), privateGame);

        Session sessionC = createSession();
        rejectCodeForPrivateGameThatIsNoLongerWaiting(sessionC.token(), privateGame.entryCode());

        mockMvc.perform(get("/v1/games").header("X-Session-Token", sessionB.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));

        assertPersistedGame(publicGame.id(), "PUBLIC", null, 600_000);
        assertPersistedGame(privateGame.id(), "PRIVATE", privateGame.entryCode(), 180_000);
        assertHumanParticipants(publicGame.id(), sessionA.id(), sessionB.id());
        assertHumanParticipants(privateGame.id(), sessionA.id(), sessionB.id());
    }

    private Session createSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/sessions"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return new Session(UUID.fromString(body.get("sessionId").asText()), body.get("recoveryToken").asText());
    }

    private CreatedGame createGame(String token, String visibility, long initialTimeMs) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/games")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "visibility":"%s",
                                  "timeControl":{"initialTimeMs":%d,"incrementMs":0}
                                }
                                """.formatted(visibility, initialTimeMs)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.visibility").value(visibility))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.players.length()").value(1))
                .andExpect(jsonPath("$.players[0].side").value("WHITE"))
                .andExpect(jsonPath("$.players[0].kind").value("HUMAN"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        JsonNode entryCode = body.get("entryCode");
        return new CreatedGame(
                UUID.fromString(body.get("gameId").asText()),
                entryCode == null || entryCode.isNull() ? null : entryCode.asText());
    }

    private void assertLobbyContainsOnly(String token, UUID gameId) throws Exception {
        mockMvc.perform(get("/v1/games").header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games.length()").value(1))
                .andExpect(jsonPath("$.games[0].gameId").value(gameId.toString()))
                .andExpect(jsonPath("$.games[0].status").value("WAITING"))
                .andExpect(jsonPath("$.games[0].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    private void joinPublicGame(String token, UUID gameId) throws Exception {
        mockMvc.perform(post("/v1/games/{gameId}/join", gameId)
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.entryCode").doesNotExist())
                .andExpect(jsonPath("$.players.length()").value(2))
                .andExpect(jsonPath("$.players[1].side").value("BLACK"))
                .andExpect(jsonPath("$.players[1].kind").value("HUMAN"));
    }

    private void joinPrivateGame(String token, CreatedGame game) throws Exception {
        mockMvc.perform(post("/v1/games/join")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryCode\":\"%s\"}".formatted(game.entryCode())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(game.id().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.entryCode").value(game.entryCode()))
                .andExpect(jsonPath("$.players.length()").value(2))
                .andExpect(jsonPath("$.players[1].side").value("BLACK"))
                .andExpect(jsonPath("$.players[1].kind").value("HUMAN"));
    }

    private void rejectUnknownPrivateCode(String token) throws Exception {
        mockMvc.perform(post("/v1/games/join")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryCode\":\"XYZ789\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_ENTRY_CODE"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.gameId").doesNotExist());
    }

    private void rejectCodeForPrivateGameThatIsNoLongerWaiting(String token, String entryCode) throws Exception {
        mockMvc.perform(post("/v1/games/join")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryCode\":\"%s\"}".formatted(entryCode)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_ENTRY_CODE"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    private void assertPersistedGame(
            UUID gameId, String visibility, String entryCode, long initialTimeMs) {
        Map<String, Object> game = jdbcTemplate.queryForMap("""
                SELECT status, visibility, entry_code, revision, position_fen,
                       initial_time_ms, increment_ms, active_side, clock_updated_at
                FROM chess.game
                WHERE id = ?
                """, gameId);
        assertThat(game)
                .containsEntry("status", "ACTIVE")
                .containsEntry("visibility", visibility)
                .containsEntry("revision", 1L)
                .containsEntry("position_fen", "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
                .containsEntry("initial_time_ms", initialTimeMs)
                .containsEntry("increment_ms", 0L)
                .containsEntry("active_side", "WHITE");
        assertThat(game.get("entry_code")).isEqualTo(entryCode);
        assertThat(game.get("clock_updated_at")).isNotNull();
    }

    private void assertHumanParticipants(UUID gameId, UUID whiteSessionId, UUID blackSessionId) {
        List<Map<String, Object>> participants = jdbcTemplate.queryForList("""
                SELECT side, session_id, kind
                FROM chess.game_participant
                WHERE game_id = ?
                ORDER BY CASE side WHEN 'WHITE' THEN 0 ELSE 1 END
                """, gameId);
        assertThat(participants).containsExactly(
                Map.of("side", "WHITE", "session_id", whiteSessionId, "kind", "HUMAN"),
                Map.of("side", "BLACK", "session_id", blackSessionId, "kind", "HUMAN"));
    }

    private record Session(UUID id, String token) {
    }

    private record CreatedGame(UUID id, String entryCode) {
    }
}
