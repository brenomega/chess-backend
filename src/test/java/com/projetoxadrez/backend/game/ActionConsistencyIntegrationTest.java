package com.projetoxadrez.backend.game;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projetoxadrez.backend.ChessBackendApplication;
import com.projetoxadrez.backend.game.application.GameActionService;
import com.projetoxadrez.backend.game.application.GameQueryService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ActionConsistencyIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String MOVE_TRANSACTION = GameActionService.class.getName() + ".move";

    @Container
    static final PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void serializesConcurrentRetriesAndPublishesOnlyAfterCommitToOtherConnections() throws Exception {
        try (Backend backend = startBackend()) {
            String white = backend.createSession();
            String black = backend.createSession();
            UUID gameId = backend.startGame(white, black);
            try (Socket sender = backend.connect(white); Socket mirror = backend.connect(white);
                    Socket opponent = backend.connect(black); Socket stranger = backend.connect(backend.createSession())) {
                sender.subscribe(gameId);
                mirror.subscribe(gameId);
                opponent.subscribe(gameId);

                var reachedCommit = new CompletableFuture<Void>();
                var releaseCommit = new CompletableFuture<Void>();
                backend.gateNextMoveCommit(reachedCommit, releaseCommit);
                ObjectNode move = command("game.move", "mv-1", gameId).put("uci", "e2e4").put("revision", 1);
                ObjectNode staleOffer = command("draw.offer", "do-1", gameId).put("revision", 1);
                try {
                    sender.send(move);
                    reachedCommit.get(10, SECONDS);
                    mirror.send(move);
                    opponent.send(staleOffer);
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                            assertThat(backend.jdbc().queryForObject("""
                                    SELECT count(*) FROM pg_stat_activity
                                    WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                                    """, Integer.class)).isEqualTo(2));
                    assertThat(backend.game(gameId)).containsEntry("revision", 1L).containsEntry("move_history", "");
                    assertThat(backend.receipts(gameId)).isZero();
                    sender.assertNoMessage();
                    mirror.assertNoMessage();
                    opponent.assertNoMessage();
                } finally {
                    releaseCommit.complete(null);
                }

                JsonNode reply = sender.receive();
                assertThat(reply.path("requestId").asText()).isEqualTo("mv-1");
                assertThat(reply.at("/snapshot/revision").asLong()).isEqualTo(2);
                assertThat(reply.at("/snapshot/position/lastMoveUci").asText()).isEqualTo("e2e4");
                ObjectNode publication = (ObjectNode) reply.deepCopy();
                publication.remove("requestId");
                assertThat(List.of(mirror.receive(), mirror.receive())).containsExactlyInAnyOrder(reply, publication);
                List<JsonNode> opponentMessages = List.of(opponent.receive(), opponent.receive());
                assertThat(opponentMessages).contains(publication);
                assertThat(opponentMessages).anySatisfy(message -> {
                    assertThat(message.path("requestId").asText()).isEqualTo("do-1");
                    assertError(message, "STALE_REVISION");
                });
                sender.subscribe(gameId);
                mirror.subscribe(gameId);
                opponent.subscribe(gameId);
                assertError(opponent.exchange(staleOffer), "STALE_REVISION");
                assertThat(backend.game(gameId)).containsEntry("revision", 2L)
                        .containsEntry("move_history", "e2e4").containsEntry("draw_offer_side", null);
                assertThat(backend.receipts(gameId)).isOne();

                assertError(sender.exchange(move.deepCopy().put("uci", "e2e3")), "REQUEST_ID_CONFLICT");
                assertError(stranger.exchange(move), "NOT_A_PARTICIPANT");
                ObjectNode blackMove = command("game.move", "mv-1", gameId).put("uci", "e7e5").put("revision", 2);
                assertThat(opponent.exchange(blackMove).at("/snapshot/revision").asLong()).isEqualTo(3);
                assertThat(sender.receive().has("requestId")).isFalse();
                assertThat(sender.exchange(move).at("/snapshot/revision").asLong()).isEqualTo(3);
                assertThat(backend.game(gameId)).containsEntry("revision", 3L)
                        .containsEntry("move_history", "e2e4 e7e5");
                assertThat(backend.receipts(gameId)).isEqualTo(2);
            }
        }
    }

    @Test
    void publishesBothTransitionsInOrderWhenTheFirstPostCommitCallbackIsDelayed() throws Exception {
        try (Backend backend = startBackend()) {
            String white = backend.createSession();
            String black = backend.createSession();
            UUID gameId = backend.startGame(white, black);
            try (Socket sender = backend.connect(white); Socket opponent = backend.connect(black);
                    Socket mirror = backend.connect(white)) {
                sender.subscribe(gameId);
                opponent.subscribe(gameId);
                mirror.subscribe(gameId);
                var firstCommitted = new CompletableFuture<Void>();
                var releaseFirstPublication = new CompletableFuture<Void>();
                var secondCallbacksCompleted = new CompletableFuture<Void>();
                var resubscriptionRead = new CompletableFuture<Void>();
                backend.gateFirstMovePublication(
                        firstCommitted, releaseFirstPublication, secondCallbacksCompleted, resubscriptionRead);
                try {
                    sender.send(command("game.move", "first", gameId).put("uci", "e2e4").put("revision", 1));
                    firstCommitted.get(10, SECONDS);
                    long revision = backend.rest.get().uri("/v1/games/{gameId}", gameId)
                            .header("X-Session-Token", black).retrieve().body(JsonNode.class).path("revision").asLong();
                    assertThat(revision).isEqualTo(2);
                    opponent.send(command("game.move", "second", gameId).put("uci", "e7e5").put("revision", revision));
                    secondCallbacksCompleted.get(10, SECONDS);
                    assertThat(backend.game(gameId)).containsEntry("revision", 3L)
                            .containsEntry("move_history", "e2e4 e7e5");
                    assertThat(backend.receipts(gameId)).isEqualTo(2);
                    mirror.send(command("game.subscribe", "resubscribe", gameId));
                    resubscriptionRead.get(10, SECONDS);
                    mirror.assertNoMessage();
                } finally {
                    releaseFirstPublication.complete(null);
                }

                JsonNode first = assertPublication(mirror.receive(), 2, "e2e4");
                JsonNode second = assertPublication(mirror.receive(), 3, "e7e5");
                JsonNode resubscribed = mirror.receive();
                assertThat(resubscribed.path("requestId").asText()).isEqualTo("resubscribe");
                assertThat(resubscribed.path("snapshot")).isEqualTo(second.path("snapshot"));
                assertThat(opponent.receive()).isEqualTo(first);
                JsonNode reply = opponent.receive();
                assertThat(reply.path("requestId").asText()).isEqualTo("second");
                assertThat(reply.path("snapshot")).isEqualTo(second.path("snapshot"));
                mirror.subscribe(gameId);
                opponent.subscribe(gameId);
            }
        }
    }

    @Test
    void bindsDrawActionsToTheirOfferAndPreservesReceiptsAcrossBackendRestart() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-24T15:00:00Z"), ZoneOffset.UTC);
        long initialTimeMs = 600_000;
        long elapsedMs = Duration.ofMinutes(1).toMillis();
        String white;
        String black;
        UUID gameId;
        ObjectNode decline;
        ObjectNode secondOffer;
        try (Backend backend = startBackend(clock)) {
            white = backend.createSession();
            black = backend.createSession();
            gameId = backend.startGame(white, black, initialTimeMs);
            try (Socket proposer = backend.connect(white); Socket responder = backend.connect(black)) {
                proposer.subscribe(gameId);
                ObjectNode firstOffer = command("draw.offer", "do-2", gameId).put("revision", 1);
                assertThat(proposer.exchange(firstOffer).at("/snapshot/drawOffer/offeredBy").asText())
                        .isEqualTo("WHITE");
                decline = command("draw.respond", "dr-3", gameId).put("accept", false);
                assertError(responder.exchange(decline), "VALIDATION_ERROR");

                responder.subscribe(gameId);
                assertThat(backend.jdbc().update("""
                        UPDATE chess.game SET clock_updated_at = clock_updated_at - INTERVAL '1 minute' WHERE id = ?
                        """, gameId)).isOne();
                Map<String, Object> offered = backend.game(gameId);
                JsonNode repeatedOffer = proposer.exchange(command("draw.offer", "do-repeat", gameId).put("revision", 2));
                assertThat(repeatedOffer.at("/snapshot/revision").asLong()).isEqualTo(2);
                assertThat(repeatedOffer.at("/snapshot/clock/whiteRemainingMs").asLong())
                        .isEqualTo(initialTimeMs - elapsedMs);
                assertThat(repeatedOffer.at("/snapshot/clock/blackRemainingMs").asLong()).isEqualTo(initialTimeMs);
                assertThat(backend.game(gameId)).isEqualTo(offered);
                responder.subscribe(gameId);

                assertError(responder.exchange(decline), "VALIDATION_ERROR");
                assertThat(responder.exchange(decline.deepCopy().put("revision", 2))
                        .at("/snapshot/drawOffer").isNull()).isTrue();
                assertThat(proposer.receive().at("/snapshot/revision").asLong()).isEqualTo(3);
                assertThat(proposer.exchange(firstOffer).at("/snapshot/drawOffer").isNull()).isTrue();
                responder.subscribe(gameId);

                ObjectNode delayedAccept = command("draw.respond", "late-accept", gameId).put("accept", true);
                assertError(responder.exchange(delayedAccept), "VALIDATION_ERROR");
                secondOffer = command("draw.offer", "do-4", gameId).put("revision", 3);
                assertThat(proposer.exchange(secondOffer).at("/snapshot/revision").asLong()).isEqualTo(4);
                assertPublication(responder.receive(), 4, null);
                assertError(responder.exchange(delayedAccept), "VALIDATION_ERROR");
                assertError(responder.exchange(delayedAccept.deepCopy().put("revision", 2)), "STALE_REVISION");
                assertError(proposer.exchange(command("game.abandon", "do-4", gameId).put("revision", 4)),
                        "REQUEST_ID_CONFLICT");
                assertThat(backend.game(gameId)).containsEntry("status", "ACTIVE")
                        .containsEntry("revision", 4L).containsEntry("draw_offer_side", "WHITE");
                assertThat(backend.receipts(gameId)).isEqualTo(4);
                assertThat(backend.jdbc().update("""
                        UPDATE chess.game_action_receipt SET action_identity = 'draw.respond::false'
                        WHERE game_id = ? AND request_id = ?
                        """, gameId, decline.path("requestId").asText())).isOne();
            }
        }

        try (Backend restarted = startBackend(clock); Socket proposer = restarted.connect(white);
                Socket responder = restarted.connect(black)) {
            Map<String, Object> beforeReplay = restarted.game(gameId);
            JsonNode replay = responder.exchange(decline);
            assertThat(replay.at("/snapshot/revision").asLong()).isEqualTo(4);
            assertThat(replay.at("/snapshot/drawOffer/offeredBy").asText()).isEqualTo("WHITE");
            responder.subscribe(gameId);
            assertThat(proposer.exchange(secondOffer).path("snapshot")).isEqualTo(replay.path("snapshot"));
            assertThat(restarted.receipts(gameId)).isEqualTo(4);
            assertThat(restarted.game(gameId)).isEqualTo(beforeReplay);
            responder.subscribe(gameId);
            assertError(responder.exchange(decline.deepCopy().put("accept", true)), "REQUEST_ID_CONFLICT");
            assertError(responder.exchange(command("draw.respond", "stale-draw", gameId)
                    .put("revision", 3).put("accept", true)), "STALE_REVISION");
            assertError(responder.exchange(command("game.abandon", "stale-abandon", gameId).put("revision", 3)),
                    "STALE_REVISION");
            assertThat(restarted.game(gameId)).containsEntry("status", "ACTIVE")
                    .containsEntry("revision", 4L).containsEntry("draw_offer_side", "WHITE");

            ObjectNode abandon = command("game.abandon", "rs-6", gameId).put("revision", 4);
            JsonNode finished = responder.exchange(abandon);
            assertThat(finished.at("/snapshot/result/reason").asText()).isEqualTo("RESIGNATION");
            assertThat(responder.exchange(abandon)).isEqualTo(finished);
            assertThat(restarted.game(gameId)).containsEntry("revision", 5L).containsEntry("status", "FINISHED")
                    .containsEntry("result_outcome", "WHITE_WIN").containsEntry("result_reason", "RESIGNATION");
            assertThat(restarted.receipts(gameId)).isEqualTo(5);

            UUID anotherGame = restarted.startGame(black, white);
            responder.subscribe(anotherGame);
            responder.exchange(command("game.abandon", "rs-6", anotherGame).put("revision", 1));
            assertThat(restarted.game(anotherGame)).containsEntry("revision", 2L)
                    .containsEntry("result_outcome", "BLACK_WIN").containsEntry("result_reason", "RESIGNATION");
            assertThat(restarted.receipts(anotherGame)).isOne();
        }
    }

    private static JsonNode assertPublication(JsonNode response, long revision, String lastMove) {
        assertThat(response.path("type").asText()).isEqualTo("game.state");
        assertThat(response.has("requestId")).isFalse();
        assertThat(response.at("/snapshot/revision").asLong()).isEqualTo(revision);
        assertThat(response.at("/snapshot/position/lastMoveUci").asText(null)).isEqualTo(lastMove);
        return response;
    }

    private static void assertError(JsonNode response, String code) {
        assertThat(response.path("type").asText()).isEqualTo("game.error");
        assertThat(response.path("code").asText()).isEqualTo(code);
    }

    private static ObjectNode command(String type, String requestId, UUID gameId) {
        return JSON.createObjectNode().put("type", type).put("requestId", requestId).put("gameId", gameId.toString());
    }

    private static Backend startBackend() {
        return startBackend(Clock.systemUTC());
    }

    private static Backend startBackend(Clock clock) {
        var application = new SpringApplication(ChessBackendApplication.class);
        application.addInitializers(context -> ((GenericApplicationContext) context).registerBean(
                "integrationClock", Clock.class, () -> clock, definition -> definition.setPrimary(true)));
        return new Backend(application.run(
                "--server.port=0",
                "--spring.datasource.url=" + postgresql.getJdbcUrl(),
                "--spring.datasource.username=" + postgresql.getUsername(),
                "--spring.datasource.password=" + postgresql.getPassword()));
    }

    private record Backend(ConfigurableApplicationContext context, int port, RestClient rest, JdbcTemplate jdbc)
            implements AutoCloseable {

        Backend(ConfigurableApplicationContext context) {
            this(context, context.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
        }

        Backend(ConfigurableApplicationContext context, int port) {
            this(context, port, RestClient.create("http://localhost:" + port), context.getBean(JdbcTemplate.class));
        }

        String createSession() {
            return rest.post().uri("/v1/sessions").retrieve().body(JsonNode.class).path("recoveryToken").asText();
        }

        UUID startGame(String white, String black) {
            return startGame(white, black, 0);
        }

        UUID startGame(String white, String black, long initialTimeMs) {
            JsonNode game = rest.post().uri("/v1/games").header("X-Session-Token", white)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""
                            {"visibility":"PUBLIC","timeControl":{"initialTimeMs":%d,"incrementMs":0}}
                            """.formatted(initialTimeMs)).retrieve().body(JsonNode.class);
            UUID gameId = UUID.fromString(game.path("gameId").asText());
            rest.post().uri("/v1/games/{gameId}/join", gameId).header("X-Session-Token", black)
                    .retrieve().toBodilessEntity();
            return gameId;
        }

        Map<String, Object> game(UUID gameId) {
            return jdbc.queryForMap("""
                    SELECT revision, status, move_history, draw_offer_side, result_outcome, result_reason,
                        white_remaining_ms, black_remaining_ms, clock_updated_at, updated_at
                    FROM chess.game WHERE id = ?
                    """, gameId);
        }

        int receipts(UUID gameId) {
            return jdbc.queryForObject(
                    "SELECT count(*) FROM chess.game_action_receipt WHERE game_id = ?", Integer.class, gameId);
        }

        void gateNextMoveCommit(CompletableFuture<Void> reached, CompletableFuture<Void> release) {
            AtomicBoolean armed = new AtomicBoolean(true);
            context.getBean(ConfigurableTransactionManager.class).addListener(new TransactionExecutionListener() {
                @Override
                public void beforeCommit(TransactionExecution transaction) {
                    if (MOVE_TRANSACTION.equals(transaction.getTransactionName())
                            && armed.compareAndSet(true, false)) {
                        reached.complete(null);
                        release.orTimeout(15, SECONDS).join();
                    }
                }
            });
        }

        void gateFirstMovePublication(CompletableFuture<Void> firstCommitted, CompletableFuture<Void> release,
                CompletableFuture<Void> secondCallbacksCompleted, CompletableFuture<Void> resubscriptionRead) {
            AtomicBoolean armed = new AtomicBoolean(true);
            context.getBean(ConfigurableTransactionManager.class).addListener(new TransactionExecutionListener() {
                @Override
                public void beforeCommit(TransactionExecution transaction) {
                    if (MOVE_TRANSACTION.equals(transaction.getTransactionName()) && armed.compareAndSet(true, false)) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public int getOrder() {
                                return Ordered.HIGHEST_PRECEDENCE;
                            }

                            @Override
                            public void afterCommit() {
                                firstCommitted.complete(null);
                                release.orTimeout(30, SECONDS).join();
                            }
                        });
                    }
                }

                @Override
                public void afterCommit(TransactionExecution transaction, Throwable failure) {
                    if (failure != null) {
                        return;
                    }
                    String transactionName = transaction.getTransactionName();
                    if (MOVE_TRANSACTION.equals(transactionName)) {
                        secondCallbacksCompleted.complete(null);
                    } else if (secondCallbacksCompleted.isDone()
                            && (GameQueryService.class.getName() + ".game").equals(transactionName)) {
                        resubscriptionRead.complete(null);
                    }
                }
            });
        }

        Socket connect(String token) {
            BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
            WebSocket socket = HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).header("X-Session-Token", token)
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                        private final StringBuilder text = new StringBuilder();

                        @Override
                        public CompletionStage<?> onText(WebSocket connection, CharSequence data, boolean last) {
                            text.append(data);
                            if (last) {
                                inbox.add(text.toString());
                                text.setLength(0);
                            }
                            connection.request(1);
                            return CompletableFuture.completedFuture(null);
                        }
                    }).orTimeout(5, SECONDS).join();
            return new Socket(socket, inbox);
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private record Socket(WebSocket connection, BlockingQueue<String> inbox) implements AutoCloseable {

        void send(JsonNode message) {
            connection.sendText(message.toString(), true).orTimeout(5, SECONDS).join();
        }

        JsonNode receive() throws Exception {
            String text = inbox.poll(5, SECONDS);
            assertThat(text).as("WebSocket message").isNotNull();
            return JSON.readTree(text);
        }

        void assertNoMessage() throws InterruptedException {
            assertThat(inbox.poll(150, MILLISECONDS)).as("Unexpected WebSocket message").isNull();
        }

        JsonNode exchange(JsonNode request) throws Exception {
            send(request);
            JsonNode response = receive();
            assertThat(response.path("requestId")).isEqualTo(request.path("requestId"));
            return response;
        }

        void subscribe(UUID gameId) throws Exception {
            JsonNode reply = exchange(command("game.subscribe", "sub-1", gameId));
            assertThat(reply.at("/snapshot/gameId").asText()).isEqualTo(gameId.toString());
        }

        @Override
        public void close() {
            connection.abort();
        }
    }
}
