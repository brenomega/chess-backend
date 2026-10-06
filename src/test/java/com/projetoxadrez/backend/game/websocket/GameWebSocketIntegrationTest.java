package com.projetoxadrez.backend.game.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class GameWebSocketIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>("postgres:17-alpine");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresql::getJdbcUrl);
        registry.add("spring.datasource.username", postgresql::getUsername);
        registry.add("spring.datasource.password", postgresql::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void publishesCommittedActiveSnapshotWhenSecondPlayerJoinsByRest() throws Exception {
        Session playerA = createSession();
        Session playerB = createSession();
        Session observer = createSession();
        UUID gameId = createPublicGame(playerA.token());
        SocketClient playerASocket = openSocket(playerA.token());
        SocketClient observerSocket = openSocket(observer.token());
        try {
            observerSocket.send(subscribe("observer-1", gameId));
            JsonNode observerError = observerSocket.nextMessage(objectMapper);
            assertThat(observerError.get("type").asText()).isEqualTo("game.error");
            assertThat(observerError.get("requestId").asText()).isEqualTo("observer-1");
            assertThat(observerError.get("code").asText()).isEqualTo("NOT_A_PARTICIPANT");

            playerASocket.send(subscribe("subscribe-1", gameId));
            JsonNode waitingState = playerASocket.nextMessage(objectMapper);
            assertThat(waitingState.get("type").asText()).isEqualTo("game.state");
            assertThat(waitingState.get("requestId").asText()).isEqualTo("subscribe-1");
            assertThat(waitingState.at("/snapshot/status").asText()).isEqualTo("WAITING");
            assertThat(waitingState.at("/snapshot/revision").asLong()).isZero();

            JsonNode joinResponse = joinPublicGame(playerB.token(), gameId);
            assertThat(joinResponse.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(joinResponse.get("revision").asLong()).isEqualTo(1);

            JsonNode activeState = playerASocket.nextMessage(objectMapper);
            assertThat(activeState.get("type").asText()).isEqualTo("game.state");
            assertThat(activeState.has("requestId")).isFalse();
            assertThat(activeState.at("/snapshot/gameId").asText()).isEqualTo(gameId.toString());
            assertThat(activeState.at("/snapshot/status").asText()).isEqualTo("ACTIVE");
            assertThat(activeState.at("/snapshot/revision").asLong()).isEqualTo(1);
            assertThat(activeState.at("/snapshot/players").size()).isEqualTo(2);
            assertThat(activeState.at("/snapshot/clock/activeSide").asText()).isEqualTo("WHITE");

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM chess.game WHERE id = ?", String.class, gameId))
                    .isEqualTo("ACTIVE");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT revision FROM chess.game WHERE id = ?", Long.class, gameId))
                    .isEqualTo(1L);
            assertThat(observerSocket.pollMessage()).isNull();
        } finally {
            playerASocket.close();
            observerSocket.close();
        }
    }

    @Test
    void rejectsInvalidSessionDuringHandshake() {
        Throwable failure = catchThrowable(() -> openSocket("invalid-token"));

        assertThat(failure).isInstanceOf(CompletionException.class);
        assertThat(failure.getCause()).isInstanceOf(WebSocketHandshakeException.class);
        WebSocketHandshakeException handshakeFailure = (WebSocketHandshakeException) failure.getCause();
        assertThat(handshakeFailure.getResponse().statusCode()).isEqualTo(401);
    }

    private Session createSession() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(httpUri("/v1/sessions"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        return new Session(body.get("recoveryToken").asText());
    }

    private UUID createPublicGame(String token) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(httpUri("/v1/games"))
                .header("X-Session-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"visibility":"PUBLIC","timeControl":{"initialTimeMs":600000,"incrementMs":0}}
                        """))
                .build());
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(response.body()).get("gameId").asText());
    }

    private JsonNode joinPublicGame(String token, UUID gameId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(httpUri("/v1/games/" + gameId + "/join"))
                .header("X-Session-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private SocketClient openSocket(String token) {
        MessageListener listener = new MessageListener();
        WebSocket socket = httpClient.newWebSocketBuilder()
                .header("X-Session-Token", token)
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener)
                .join();
        return new SocketClient(socket, listener);
    }

    private URI httpUri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String subscribe(String requestId, UUID gameId) {
        return """
                {"type":"game.subscribe","requestId":"%s","gameId":"%s"}
                """.formatted(requestId, gameId);
    }

    private record Session(String token) {
    }

    private record SocketClient(WebSocket socket, MessageListener listener) {

        void send(String message) {
            socket.sendText(message, true).join();
        }

        JsonNode nextMessage(ObjectMapper objectMapper) throws Exception {
            String message = listener.messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            return objectMapper.readTree(message);
        }

        String pollMessage() throws InterruptedException {
            return listener.messages.poll(200, TimeUnit.MILLISECONDS);
        }

        void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    private static final class MessageListener implements WebSocket.Listener {

        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder currentMessage = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            currentMessage.append(data);
            if (last) {
                messages.add(currentMessage.toString());
                currentMessage.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
