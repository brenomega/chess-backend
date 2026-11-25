package com.projetoxadrez.backend.game.rest;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.websocket.GameSubscriptionRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AtomicEntryIntegrationTest {

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
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private GameSubscriptionRegistry subscriptions;

    @ParameterizedTest
    @CsvSource({
        "PUBLIC, BY_ID, BY_ID",
        "PRIVATE, BY_CODE, BY_CODE",
        "PRIVATE, BY_CODE, BY_ID"
    })
    void serializesConcurrentEntriesAndRejectsRetries(
            GameVisibility visibility, Route firstRoute, Route secondRoute) throws Exception {
        Session creator = createSession();
        CreatedGame game = createGame(creator, visibility);
        Attempt first = new Attempt(createSession(), firstRoute);
        Attempt second = new Attempt(createSession(), secondRoute);

        assertThat(lobbyGameIds(creator).contains(game.id().toString()))
                .isEqualTo(visibility == GameVisibility.PUBLIC);
        observeCommittedPublication(game.id());

        JoinResponse firstResponse;
        JoinResponse secondResponse;
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CyclicBarrier start = new CyclicBarrier(2);
            List<Future<JoinResponse>> responses = new TransactionTemplate(transactionManager).execute(transaction -> {
                lockGame(game.id());
                Future<JoinResponse> firstWorker = workers.submit(() -> {
                    start.await(10, SECONDS);
                    return join(game, first);
                });
                Future<JoinResponse> secondWorker = workers.submit(() -> {
                    start.await(10, SECONDS);
                    return join(game, second);
                });
                awaitBlockedEntries(2);
                verifyNoInteractions(subscriptions);
                return List.of(firstWorker, secondWorker);
            });
            firstResponse = responses.get(0).get(10, SECONDS);
            secondResponse = responses.get(1).get(10, SECONDS);
        }

        assertThat(List.of(firstResponse.status(), secondResponse.status())).containsOnlyOnce(200);
        boolean firstWon = firstResponse.status() == 200;
        JoinResponse success = firstWon ? firstResponse : secondResponse;
        Attempt winner = firstWon ? first : second;
        Attempt loser = firstWon ? second : first;

        assertThat(success.body().path("gameId").asText()).isEqualTo(game.id().toString());
        assertThat(success.body().path("status").asText()).isEqualTo("ACTIVE");
        assertThat(success.body().path("revision").asLong()).isEqualTo(1);
        assertThat(success.body().path("players").findValuesAsText("side")).containsExactly("WHITE", "BLACK");
        assertThat(success.body().path("entryCode").asText(null)).isEqualTo(game.entryCode());
        assertRejected(firstWon ? secondResponse : firstResponse, loser.route());
        assertRejected(join(game, winner), winner.route());
        assertRejected(join(game, loser), loser.route());

        assertThat(lobbyGameIds(creator)).doesNotContain(game.id().toString());
        assertThat(readGame(game.id()))
                .containsEntry("status", "ACTIVE")
                .containsEntry("revision", 1L)
                .containsEntry("visibility", visibility.name())
                .containsEntry("entry_code", game.entryCode())
                .containsEntry("active_side", "WHITE")
                .hasEntrySatisfying("clock_updated_at", anchor -> assertThat(anchor).isNotNull());
        assertThat(readParticipants(game.id())).containsExactlyInAnyOrder(
                participant("WHITE", creator), participant("BLACK", winner.session()));
        verify(subscriptions).publish(any());
    }

    @ParameterizedTest
    @EnumSource(GameVisibility.class)
    void rejectsCodeWhenCandidateChangesWhileEntryWaitsForTheLock(GameVisibility newVisibility) throws Exception {
        Session creator = createSession();
        CreatedGame game = createGame(creator, GameVisibility.PRIVATE);
        Attempt attempt = new Attempt(createSession(), Route.BY_CODE);
        String replacementCode = switch (newVisibility) {
            case PUBLIC -> null;
            case PRIVATE -> "ABC234".equals(game.entryCode()) ? "DEF567" : "ABC234";
        };

        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<JoinResponse> response = new TransactionTemplate(transactionManager).execute(transaction -> {
                lockGame(game.id());
                Future<JoinResponse> pending = worker.submit(() -> join(game, attempt));
                awaitBlockedEntries(1);
                jdbc.update("UPDATE chess.game SET visibility = ?, entry_code = ? WHERE id = ?",
                        newVisibility.name(), replacementCode, game.id());
                return pending;
            });
            assertRejected(response.get(10, SECONDS), Route.BY_CODE);
        }

        assertThat(readGame(game.id()))
                .containsEntry("status", "WAITING")
                .containsEntry("revision", 0L)
                .containsEntry("visibility", newVisibility.name())
                .containsEntry("entry_code", replacementCode);
        assertThat(readParticipants(game.id())).containsExactly(participant("WHITE", creator));
        verifyNoInteractions(subscriptions);
    }

    private void observeCommittedPublication(UUID gameId) {
        TransactionTemplate committedRead = new TransactionTemplate(transactionManager);
        committedRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            GameSnapshot snapshot = invocation.getArgument(0);
            assertThat(snapshot.gameId()).isEqualTo(gameId);
            assertThat(snapshot.status()).isEqualTo(GameStatus.ACTIVE);
            assertThat(snapshot.revision()).isEqualTo(1);
            committedRead.executeWithoutResult(transaction -> {
                assertThat(readGame(gameId)).containsEntry("status", "ACTIVE").containsEntry("revision", 1L);
                assertThat(readParticipants(gameId)).hasSize(2);
            });
            return invocation.callRealMethod();
        }).when(subscriptions).publish(any());
    }

    private void lockGame(UUID gameId) {
        jdbc.queryForObject("SELECT id FROM chess.game WHERE id = ? FOR UPDATE", UUID.class, gameId);
    }

    private void awaitBlockedEntries(int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                """, Integer.class)).as("entries waiting for PostgreSQL locks").isEqualTo(count));
    }

    private Session createSession() throws Exception {
        JsonNode body = json(mockMvc.perform(post("/v1/sessions")).andExpect(status().isCreated()).andReturn());
        return new Session(UUID.fromString(body.path("sessionId").asText()), body.path("recoveryToken").asText());
    }

    private CreatedGame createGame(Session creator, GameVisibility visibility) throws Exception {
        JsonNode body = json(mockMvc.perform(post("/v1/games")
                        .header("X-Session-Token", creator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"visibility":"%s","timeControl":{"initialTimeMs":600000,"incrementMs":0}}
                                """.formatted(visibility)))
                .andExpect(status().isCreated())
                .andReturn());
        return new CreatedGame(UUID.fromString(body.path("gameId").asText()), body.path("entryCode").asText(null));
    }

    private List<String> lobbyGameIds(Session session) throws Exception {
        return json(mockMvc.perform(get("/v1/games").header("X-Session-Token", session.token()))
                        .andExpect(status().isOk()).andReturn())
                .path("games").findValuesAsText("gameId");
    }

    private JoinResponse join(CreatedGame game, Attempt attempt) throws Exception {
        String path = attempt.route() == Route.BY_CODE ? "/v1/games/join" : "/v1/games/" + game.id() + "/join";
        var request = post(path).header("X-Session-Token", attempt.session().token());
        if (game.entryCode() != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"entryCode":"%s"}
                            """.formatted(game.entryCode()));
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return new JoinResponse(result.getResponse().getStatus(), json(result));
    }

    private void assertRejected(JoinResponse response, Route route) {
        assertThat(response.status()).isEqualTo(route == Route.BY_CODE ? 403 : 409);
        assertThat(response.body().path("code").asText())
                .isEqualTo(route == Route.BY_CODE ? "INVALID_ENTRY_CODE" : "GAME_NOT_JOINABLE");
        assertThat(response.body().path("details")).isEqualTo(objectMapper.createObjectNode());
        assertThat(response.body().has("gameId")).isFalse();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private Map<String, Object> readGame(UUID gameId) {
        return jdbc.queryForMap("""
                SELECT status, revision, visibility, entry_code, active_side, clock_updated_at
                FROM chess.game WHERE id = ?
                """, gameId);
    }

    private List<Map<String, Object>> readParticipants(UUID gameId) {
        return jdbc.queryForList("""
                SELECT side, session_id, kind FROM chess.game_participant WHERE game_id = ?
                """, gameId);
    }

    private static Map<String, Object> participant(String side, Session session) {
        return Map.of("side", side, "session_id", session.id(), "kind", "HUMAN");
    }

    private enum Route {
        BY_ID, BY_CODE
    }

    private record Session(UUID id, String token) {
    }

    private record CreatedGame(UUID id, String entryCode) {
    }

    private record Attempt(Session session, Route route) {
    }

    private record JoinResponse(int status, JsonNode body) {
    }
}
