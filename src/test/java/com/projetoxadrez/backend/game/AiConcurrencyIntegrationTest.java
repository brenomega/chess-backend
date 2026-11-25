package com.projetoxadrez.backend.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import com.projetoxadrez.backend.game.application.AiJob;
import com.projetoxadrez.backend.game.application.AiJobService;
import com.projetoxadrez.backend.game.application.GameActionService;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.domain.Match;
import com.projetoxadrez.backend.game.websocket.GameSubscriptionRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "chess.ai.lease-ms=15000")
@Testcontainers
@Timeout(30)
class AiConcurrencyIntegrationTest {

    private static final Instant START = Instant.parse("2026-10-02T15:00:00Z");
    private static final String BLANK_UCI = "   ";

    @Container
    static final PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresql::getJdbcUrl);
        registry.add("spring.datasource.username", postgresql::getUsername);
        registry.add("spring.datasource.password", postgresql::getPassword);
    }

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private GameActionService actions;

    @Autowired
    private AiJobService aiJobs;

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private GameSubscriptionRegistry subscriptions;

    @BeforeEach
    void prepare() {
        when(clock.instant()).thenAnswer(invocation -> now.get());
        jdbc.update("DELETE FROM chess.game_participant");
        jdbc.update("DELETE FROM chess.game");
        jdbc.update("DELETE FROM chess.guest_session");
    }

    @Test
    void exposesModesLobbyAndObservationThroughRealRest() {
        Session creator = createSession();
        Session guest = createSession();
        CreatedGame humans = createGame(creator, null, null, "PUBLIC");
        CreatedGame privateHumans = createGame(creator, "HUMAN_VS_HUMAN", null, "PRIVATE");
        CreatedGame humanWhite = createGame(creator, "HUMAN_VS_AI", "WHITE", "PRIVATE");
        CreatedGame humanBlack = createGame(creator, "HUMAN_VS_AI", "BLACK", "PRIVATE");
        CreatedGame aiGame = createGame(creator, "AI_VS_AI", null, "PRIVATE");

        assertThat(humans.body().path("status").asText()).isEqualTo("WAITING");
        assertPlayers(humans.body(), "HUMAN");
        assertThat(privateHumans.body().path("entryCode").asText()).hasSize(6);
        assertPlayers(humanWhite.body(), "HUMAN", "AI");
        assertPlayers(humanBlack.body(), "AI", "HUMAN");
        assertPlayers(aiGame.body(), "AI", "AI");
        for (CreatedGame game : List.of(humanWhite, humanBlack, aiGame)) {
            assertThat(game.body().path("status").asText()).isEqualTo("ACTIVE");
            assertThat(game.body().path("visibility").asText()).isEqualTo("PUBLIC");
            assertThat(game.body().path("entryCode").isNull()).isTrue();
            assertError(post("/v1/games/" + game.id() + "/join", null, guest.token()), 409, "GAME_NOT_JOINABLE");
        }

        ApiResponse lobby = get("/v1/games", creator);
        assertThat(lobby.status()).isEqualTo(200);
        assertThat(lobby.body().path("games")).hasSize(1);
        assertThat(lobby.body().at("/games/0/gameId").asText()).isEqualTo(humans.id().toString());

        ApiResponse joined = post("/v1/games/" + humans.id() + "/join", null, guest.token());
        assertThat(joined.status()).isEqualTo(200);
        assertThat(joined.body().path("status").asText()).isEqualTo("ACTIVE");
        assertPlayers(joined.body(), "HUMAN", "HUMAN");
        assertThat(get("/v1/games/" + aiGame.id(), guest).status()).isEqualTo(200);
        assertError(get("/v1/games/" + humanWhite.id(), guest), 403, "NOT_A_PARTICIPANT");
        assertSpectatorCommands(guest, aiGame.id(), humanWhite.id());

        assertThat(openRevisions(humans.id())).isEmpty();
        assertThat(openRevisions(humanWhite.id())).isEmpty();
        assertThat(openRevisions(humanBlack.id())).containsExactly(0L);
        assertThat(openRevisions(aiGame.id())).containsExactly(0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WHITE", "BLACK"})
    void handsOffTheTurnBetweenHumanAndAi(String humanSide) {
        Session human = createSession();
        CreatedGame game = createGame(human, "HUMAN_VS_AI", humanSide, "PUBLIC");
        if (humanSide.equals("WHITE")) {
            assertNoWork(next("worker"));
            actions.move(game.id(), human.id(), 0, "e2e4");
            Job job = claim("worker");
            assertThat(job.body().at("/position/sideToMove").asText()).isEqualTo("BLACK");
            assertThat(result("worker", job, "e7e5").status()).isEqualTo(200);
        } else {
            assertThatThrownBy(() -> actions.move(game.id(), human.id(), 0, "e2e4"))
                    .isInstanceOf(Match.NotYourTurnException.class);
            Job job = claim("worker");
            assertThat(job.body().at("/position/sideToMove").asText()).isEqualTo("WHITE");
            assertThat(result("worker", job, "e2e4").status()).isEqualTo(200);
            actions.move(game.id(), human.id(), 1, "e7e5");
        }
        assertThat(gameState(game.id(), "move_history")).isEqualTo("e2e4 e7e5");
        assertThat(openRevisions(game.id())).containsExactlyElementsOf(
                humanSide.equals("BLACK") ? List.of(2L) : List.of());
    }

    @Test
    void rejectsInvalidClaimsAndLeavesTheJobAvailable() {
        CreatedGame game = createGame(createSession(), "AI_VS_AI", null, "PUBLIC");
        UUID pendingJob = jdbc.queryForObject("SELECT id FROM chess.ai_job WHERE game_id = ?", UUID.class, game.id());
        String maxLengthWorkerId = "a".repeat(63) + "\uD83D\uDE80";

        assertValidationError(post("/v1/ai/jobs/next", null, null));
        assertValidationError(post("/v1/ai/jobs/next", objectMapper.createObjectNode().put("workerId", 123), null));
        ObjectNode unpairedHighSurrogate = objectMapper.createObjectNode()
                .putRawValue("workerId", new RawValue("\"worker-\\uD800\""));
        assertValidationError(post("/v1/ai/jobs/next", unpairedHighSurrogate, null));
        assertValidationError(next(maxLengthWorkerId + "a"));
        assertValidationError(next("worker\0id"));
        assertValidationError(postRawJson("/v1/ai/jobs/next", "{\"workerId\":\"owner\"} lixo", null));
        assertThat(jobState(pendingJob, "status")).isEqualTo("PENDING");
        assertThat(jobState(pendingJob, "worker_id")).isNull();
        assertThat(jobState(pendingJob, "expires_at")).isNull();
        assertThat(gameState(game.id(), "revision")).isEqualTo("0");
        assertThat(gameState(game.id(), "move_history")).isNullOrEmpty();
        verify(subscriptions, never()).publish(any());

        Job claimed = claim(maxLengthWorkerId);
        assertThat(claimed.id()).isEqualTo(pendingJob);
        assertThat(claimed.gameId()).isEqualTo(game.id());
        assertThat(jobState(claimed.id(), "worker_id")).isEqualTo(maxLengthWorkerId);
    }

    @Test
    void acceptsOnlyValidResultsAndSerializesAnIdenticalReplay() {
        CreatedGame game = createGame(createSession(), "AI_VS_AI", null, "PUBLIC");
        int numericWorkerId = 123;
        String owner = Integer.toString(numericWorkerId);
        Job job = claim(owner);
        assertThat(job.gameId()).isEqualTo(game.id());
        assertThat(job.revision()).isZero();
        assertThat(job.body().at("/position/sideToMove").asText()).isEqualTo("WHITE");
        assertThat(job.body().at("/position/fen").asText()).isEqualTo(game.body().at("/position/fen").asText());
        assertThat(job.body().path("timeBudgetMs").asLong()).isPositive();
        assertThat(Instant.parse(job.body().path("expiresAt").asText())).isEqualTo(START.plusSeconds(15));

        ObjectNode payload = resultPayload(owner, job, "e2e4");
        assertValidationError(postResult(payload.deepCopy().put("workerId", numericWorkerId)));
        ObjectNode unpairedLowSurrogate = payload.deepCopy()
                .putRawValue("workerId", new RawValue("\"worker-\\uDC00\""));
        assertValidationError(postResult(unpairedLowSurrogate));
        assertValidationError(postResult(payload.deepCopy().put("jobId", "not-a-uuid")));
        ObjectNode missingRevision = payload.deepCopy();
        missingRevision.remove("revision");
        assertValidationError(postResult(missingRevision));
        assertValidationError(postResult(payload.deepCopy().put("revision", 0.5)));
        assertError(result("stranger", job, "e2e4"), 409, "WORKER_OWNERSHIP_CONFLICT");
        assertError(postResult(payload.deepCopy().put("gameId", UUID.randomUUID().toString())), 400, "VALIDATION_ERROR");
        assertError(postResult(payload.deepCopy().put("revision", 1)), 409, "STALE_REVISION");
        assertError(result(owner, job, "e2e5"), 422, "ILLEGAL_MOVE");
        assertError(result(owner, job, BLANK_UCI), 400, "INVALID_CANDIDATE");
        assertValidationError(postRawJson("/v1/ai/jobs/result", payload + " {}", null));
        assertThat(jobState(job.id(), "status")).isEqualTo("ASSIGNED");
        assertThat(jobState(job.id(), "worker_id")).isEqualTo(owner);
        assertThat(openRevisions(game.id())).containsExactly(0L);
        assertThat(gameState(game.id(), "revision")).isEqualTo("0");
        assertThat(gameState(game.id(), "move_history")).isNullOrEmpty();
        verify(subscriptions, never()).publish(any());

        CompletableFuture<ApiResponse> replay = transaction.execute(status -> {
            assertThat(aiJobs.result(owner, job.id(), game.id(), 0, "e2e4").revision()).isEqualTo(1);
            int pid = backendPid();
            CompletableFuture<ApiResponse> pending = sendAsync("/v1/ai/jobs/result", payload);
            awaitBlockedBy(pid);
            verify(subscriptions, never()).publish(any());
            return pending;
        });
        ApiResponse applied = response(replay);
        assertThat(applied.status()).isEqualTo(200);
        assertThat(applied.body().path("status").asText()).isEqualTo("APPLIED");
        assertThat(applied.body().path("gameId").asText()).isEqualTo(game.id().toString());
        assertThat(applied.body().path("revision").asLong()).isEqualTo(1);
        assertThat(result(owner, job, "e2e4")).isEqualTo(applied);
        assertError(result("stranger", job, "e2e4"), 409, "WORKER_OWNERSHIP_CONFLICT");
        assertError(result(owner, job, "d2d4"), 409, "JOB_ALREADY_COMPLETED");

        assertThat(gameState(game.id(), "move_history")).isEqualTo("e2e4");
        assertThat(gameState(game.id(), "revision")).isEqualTo("1");
        assertThat(jobState(job.id(), "status")).isEqualTo("COMPLETED");
        assertThat(jobState(job.id(), "worker_id")).isEqualTo(owner);
        assertThat(openRevisions(game.id())).containsExactly(1L);
        verify(subscriptions).publish(argThat(snapshot ->
                snapshot.gameId().equals(game.id()) && snapshot.revision() == 1));
        verify(subscriptions).publish(any());

        Job reply = claim("other-worker");
        assertThat(reply.body().at("/position/sideToMove").asText()).isEqualTo("BLACK");
        assertThat(result("other-worker", reply, "e7e5").status()).isEqualTo(200);
        assertThat(gameState(game.id(), "move_history")).isEqualTo("e2e4 e7e5");
        assertThat(openRevisions(game.id())).containsExactly(2L);
    }

    @Test
    void serializesClaimAndReclaimWithSubmissionFromThePreviousOwner() {
        CreatedGame game = createGame(createSession(), "AI_VS_AI", null, "PUBLIC");
        AiJob first = transaction.execute(status -> {
            AiJob claimed = aiJobs.next("first").orElseThrow();
            assertNoWork(next("second"));
            return claimed;
        });
        Job job = new Job(first.jobId(), first.gameId(), first.revision(), null);
        now.set(first.expiresAt());
        assertError(result("first", job, "e2e4"), 409, "JOB_EXPIRED");

        CompletableFuture<ApiResponse> formerOwner = transaction.execute(status -> {
            AiJob reclaimed = aiJobs.next("second").orElseThrow();
            assertThat(reclaimed.jobId()).isEqualTo(job.id());
            assertNoWork(next("third"));
            int pid = backendPid();
            CompletableFuture<ApiResponse> pending =
                    sendAsync("/v1/ai/jobs/result", resultPayload("first", job, "e2e4"));
            awaitBlockedBy(pid);
            return pending;
        });
        assertError(response(formerOwner), 409, "WORKER_OWNERSHIP_CONFLICT");
        assertThat(jobState(job.id(), "worker_id")).isEqualTo("second");
        assertThat(openRevisions(game.id())).containsExactly(0L);
        assertThat(gameState(game.id(), "revision")).isEqualTo("0");
        verify(subscriptions, never()).publish(any());
        assertThat(result("second", job, "e2e4").status()).isEqualTo(200);
        assertThat(gameState(game.id(), "move_history")).isEqualTo("e2e4");
    }

    @Test
    void claimsAnotherGameWhileTheOldestGameIsLocked() {
        Session creator = createSession();
        CreatedGame oldest = createGame(creator, "AI_VS_AI", null, "PUBLIC");
        now.set(START.plusSeconds(1));
        CreatedGame newest = createGame(creator, "AI_VS_AI", null, "PUBLIC");

        transaction.executeWithoutResult(status -> {
            assertThat(aiJobs.next("first").orElseThrow().gameId()).isEqualTo(oldest.id());
            assertThat(claim("second").gameId()).isEqualTo(newest.id());
        });
        assertThat(openRevisions(oldest.id())).containsExactly(0L);
        assertThat(openRevisions(newest.id())).containsExactly(0L);
        assertNoWork(next("third"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void serializesResultBehindAbandonmentOrDrawOffer(boolean abandon) {
        Session human = createSession();
        CreatedGame game = createGame(human, "HUMAN_VS_AI", "BLACK", "PUBLIC");
        Job job = claim("owner");

        GameSnapshot committed = whileResultWaits(job, () -> abandon
                ? actions.abandon(game.id(), human.id())
                : actions.offerDraw(game.id(), human.id(), 0), "JOB_EXPIRED");

        assertThat(gameState(game.id(), "revision")).isEqualTo("1");
        assertThat(gameState(game.id(), "move_history")).isNullOrEmpty();
        assertThat(jobState(job.id(), "status")).isEqualTo("CANCELLED");
        assertThat(gameState(game.id(), "status")).isEqualTo(abandon ? "FINISHED" : "ACTIVE");
        if (abandon) {
            assertThat(gameState(game.id(), "result_reason")).isEqualTo("RESIGNATION");
            assertThat(openRevisions(game.id())).isEmpty();
        } else {
            assertThat(gameState(game.id(), "draw_offer_side")).isEqualTo("BLACK");
            assertThat(openRevisions(game.id())).containsExactly(1L);
        }
        verify(subscriptions).publish(committed);
        verify(subscriptions).publish(any());
    }

    @Test
    void checksLeaseAgainAfterWaitingForTheGameLock() {
        CreatedGame game = createGame(createSession(), "AI_VS_AI", null, "PUBLIC");
        Job job = claim("owner");

        whileResultWaits(job, () -> {
            now.set(START.plusSeconds(15));
            return null;
        }, "JOB_EXPIRED");

        assertThat(jobState(job.id(), "status")).isEqualTo("ASSIGNED");
        assertThat(gameState(game.id(), "revision")).isEqualTo("0");
        assertThat(openRevisions(game.id())).containsExactly(0L);
        verify(subscriptions, never()).publish(any());
    }

    @Test
    void publishesAndQueuesThePersistedTurnWhenTheNextClockReachesZeroDuringTheResult() {
        Session spectator = createSession();
        CreatedGame game = createGame(spectator, "AI_VS_AI", null, "PUBLIC");
        jdbc.update("UPDATE chess.game SET black_remaining_ms = 1 WHERE id = ?", game.id());
        Job job = claim("owner");
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocket socket = connect(spectator, messages);
        try {
            socketExchange(socket, messages, "game.subscribe", game.id());
            when(clock.instant()).thenReturn(START, START, START.plusMillis(1));

            assertThat(result("owner", job, "e2e4").status()).isEqualTo(200);

            JsonNode event = readSocketMessage(messages);
            JsonNode snapshot = event.path("snapshot");
            assertThat(event.path("type").asText()).isEqualTo("game.state");
            assertThat(snapshot.path("gameId").asText()).isEqualTo(game.id().toString());
            assertThat(snapshot.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(snapshot.path("result").isNull()).isTrue();
            assertThat(snapshot.path("revision").asLong()).isEqualTo(1);
            assertThat(snapshot.at("/clock/activeSide").asText()).isEqualTo("BLACK");
            assertThat(snapshot.at("/clock/blackRemainingMs").asLong()).isEqualTo(1);
            assertThat(gameState(game.id(), "status")).isEqualTo("ACTIVE");
            assertThat(gameState(game.id(), "revision")).isEqualTo("1");
            assertThat(gameState(game.id(), "move_history")).isEqualTo("e2e4");
            assertThat(gameState(game.id(), "black_remaining_ms")).isEqualTo("1");
            assertThat(jobState(job.id(), "status")).isEqualTo("COMPLETED");

            Job reply = claim("next-worker");
            assertThat(reply.gameId()).isEqualTo(game.id());
            assertThat(reply.revision()).isEqualTo(1);
            assertThat(reply.body().at("/position/sideToMove").asText()).isEqualTo("BLACK");
        } finally {
            socket.abort();
        }
    }

    @Test
    void commitsClockTimeoutAndCancelsTheJobBeforeRejectingABlankCandidate() {
        CreatedGame game = createGame(createSession(), "AI_VS_AI", null, "PUBLIC");
        now.set(START.plusSeconds(599));
        Job job = claim("owner");
        now.set(START.plusSeconds(600));

        ObjectNode missingUci = resultPayload("owner", job, "e2e4");
        missingUci.remove("uci");
        assertValidationError(postResult(missingUci));
        assertError(result("owner", job, BLANK_UCI), 422, "INVALID_GAME_STATE");

        assertThat(gameState(game.id(), "status")).isEqualTo("FINISHED");
        assertThat(gameState(game.id(), "result_outcome")).isEqualTo("BLACK_WIN");
        assertThat(gameState(game.id(), "result_reason")).isEqualTo("TIMEOUT");
        assertThat(gameState(game.id(), "move_history")).isNullOrEmpty();
        assertThat(gameState(game.id(), "revision")).isEqualTo("1");
        assertThat(jobState(job.id(), "status")).isEqualTo("CANCELLED");
        assertThat(openRevisions(game.id())).isEmpty();
        verify(subscriptions).publish(argThat(snapshot ->
                snapshot.gameId().equals(game.id()) && snapshot.revision() == 1
                        && snapshot.result() != null && snapshot.result().reason().equals("TIMEOUT")));
        verify(subscriptions).publish(any());
    }

    private <T> T whileResultWaits(Job job, Supplier<T> action, String expectedError) {
        AtomicReference<CompletableFuture<ApiResponse>> result = new AtomicReference<>();
        T committed = transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM chess.game WHERE id = ? FOR UPDATE", UUID.class, job.gameId());
            int pid = backendPid();
            result.set(sendAsync("/v1/ai/jobs/result", resultPayload("owner", job, "e2e4")));
            awaitBlockedBy(pid);
            T outcome = action.get();
            verify(subscriptions, never()).publish(any());
            return outcome;
        });
        assertError(response(result.get()), 409, expectedError);
        return committed;
    }

    private int backendPid() {
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private void awaitBlockedBy(int pid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_locks WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid))
                    )
                    """, Boolean.class, pid))) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("The result transaction did not wait for the game lock");
    }

    private void assertSpectatorCommands(Session guest, UUID aiGame, UUID humanGame) {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocket socket = connect(guest, messages);
        try {
            assertThat(socketExchange(socket, messages, "game.subscribe", humanGame).path("code").asText())
                    .isEqualTo("NOT_A_PARTICIPANT");
            JsonNode observed = socketExchange(socket, messages, "game.subscribe", aiGame);
            assertThat(observed.path("type").asText()).isEqualTo("game.state");
            assertThat(observed.at("/snapshot/gameId").asText()).isEqualTo(aiGame.toString());
            for (String type : List.of("game.move", "game.abandon", "draw.offer", "draw.respond")) {
                assertThat(socketExchange(socket, messages, type, aiGame).path("code").asText())
                        .isEqualTo("NOT_A_PARTICIPANT");
            }
            assertThat(gameState(aiGame, "revision")).isEqualTo("0");
        } finally {
            socket.abort();
        }
    }

    private WebSocket connect(Session guest, BlockingQueue<String> messages) {
        return httpClient.newWebSocketBuilder().header("X-Session-Token", guest.token())
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    private final StringBuilder text = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket connection, CharSequence data, boolean last) {
                        text.append(data);
                        if (last) {
                            messages.add(text.toString());
                            text.setLength(0);
                        }
                        connection.request(1);
                        return CompletableFuture.completedFuture(null);
                    }
                }).orTimeout(5, TimeUnit.SECONDS).join();
    }

    private JsonNode socketExchange(WebSocket socket, BlockingQueue<String> messages, String type, UUID gameId) {
        ObjectNode message = objectMapper.createObjectNode().put("type", type).put("requestId", type)
                .put("gameId", gameId.toString()).put("uci", "e2e4").put("accept", true);
        socket.sendText(message.toString(), true).orTimeout(5, TimeUnit.SECONDS).join();
        JsonNode response = readSocketMessage(messages);
        assertThat(response.path("requestId").asText()).isEqualTo(type);
        return response;
    }

    private JsonNode readSocketMessage(BlockingQueue<String> messages) {
        try {
            String reply = messages.poll(5, TimeUnit.SECONDS);
            assertThat(reply).isNotNull();
            return objectMapper.readTree(reply);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private Session createSession() {
        ApiResponse response = post("/v1/sessions", null, null);
        assertThat(response.status()).isEqualTo(201);
        return new Session(UUID.fromString(response.body().path("sessionId").asText()),
                response.body().path("recoveryToken").asText());
    }

    private CreatedGame createGame(Session creator, String mode, String side, String visibility) {
        ObjectNode request = objectMapper.createObjectNode().put("visibility", visibility);
        request.putObject("timeControl").put("initialTimeMs", 600000).put("incrementMs", 0);
        if (mode != null) {
            request.put("mode", mode);
        }
        if (side != null) {
            request.put("side", side);
        }
        ApiResponse response = post("/v1/games", request, creator.token());
        assertThat(response.status()).isEqualTo(201);
        return new CreatedGame(UUID.fromString(response.body().path("gameId").asText()), response.body());
    }

    private Job claim(String workerId) {
        ApiResponse response = next(workerId);
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.body();
        return new Job(UUID.fromString(body.path("jobId").asText()), UUID.fromString(body.path("gameId").asText()),
                body.path("revision").asLong(), body);
    }

    private ApiResponse next(String workerId) {
        return post("/v1/ai/jobs/next", objectMapper.createObjectNode().put("workerId", workerId), null);
    }

    private ApiResponse result(String workerId, Job job, String uci) {
        return postResult(resultPayload(workerId, job, uci));
    }

    private ApiResponse postResult(ObjectNode payload) {
        return post("/v1/ai/jobs/result", payload, null);
    }

    private ObjectNode resultPayload(String workerId, Job job, String uci) {
        return objectMapper.createObjectNode().put("workerId", workerId).put("jobId", job.id().toString())
                .put("gameId", job.gameId().toString()).put("revision", job.revision()).put("uci", uci);
    }

    private ApiResponse get(String path, Session session) {
        return response(send(request(path, session.token()).GET().build()));
    }

    private ApiResponse post(String path, JsonNode body, String token) {
        return postRawJson(path, body == null ? null : body.toString(), token);
    }

    private ApiResponse postRawJson(String path, String body, String token) {
        return response(send(postRequest(path, body, token)));
    }

    private CompletableFuture<ApiResponse> sendAsync(String path, JsonNode body) {
        return send(postRequest(path, body.toString(), null));
    }

    private HttpRequest postRequest(String path, String body, String token) {
        HttpRequest.Builder builder = request(path, token);
        return body == null
                ? builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                : builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(8));
        if (token != null) {
            request.header("X-Session-Token", token);
        }
        return request;
    }

    private CompletableFuture<ApiResponse> send(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            try {
                return new ApiResponse(response.statusCode(),
                        response.body().isBlank() ? null : objectMapper.readTree(response.body()));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    private static ApiResponse response(CompletableFuture<ApiResponse> pending) {
        return pending.orTimeout(10, TimeUnit.SECONDS).join();
    }

    private List<Long> openRevisions(UUID gameId) {
        return jdbc.queryForList(
                "SELECT revision FROM chess.ai_job WHERE game_id = ? AND status IN ('PENDING', 'ASSIGNED')",
                Long.class, gameId);
    }

    private String gameState(UUID id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM chess.game WHERE id = ?", String.class, id);
    }

    private String jobState(UUID id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM chess.ai_job WHERE id = ?", String.class, id);
    }

    private static void assertError(ApiResponse response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body().path("code").asText()).isEqualTo(code);
    }

    private static void assertValidationError(ApiResponse response) {
        assertError(response, 400, "VALIDATION_ERROR");
        assertThat(response.body().path("message").asText()).isEqualTo("Invalid AI job request");
        JsonNode details = response.body().path("details");
        assertThat(details.isObject() && details.isEmpty()).isTrue();
    }

    private static void assertNoWork(ApiResponse response) {
        assertThat(response).isEqualTo(new ApiResponse(204, null));
    }

    private static void assertPlayers(JsonNode game, String... kinds) {
        assertThat(game.path("players")).hasSize(kinds.length);
        for (int i = 0; i < kinds.length; i++) {
            String side = i == 0 ? "WHITE" : "BLACK";
            String kind = kinds[i];
            assertThat(game.path("players")).anySatisfy(player -> {
                assertThat(player.path("side").asText()).isEqualTo(side);
                assertThat(player.path("kind").asText()).isEqualTo(kind);
            });
        }
    }

    private record Session(UUID id, String token) {}
    private record CreatedGame(UUID id, JsonNode body) {}
    private record Job(UUID id, UUID gameId, long revision, JsonNode body) {}
    private record ApiResponse(int status, JsonNode body) {}
}
