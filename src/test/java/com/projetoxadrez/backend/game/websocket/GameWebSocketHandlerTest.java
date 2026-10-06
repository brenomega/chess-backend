package com.projetoxadrez.backend.game.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projetoxadrez.backend.game.application.GameQueryService;
import com.projetoxadrez.backend.game.application.GameQueryStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.application.LobbyGame;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

class GameWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID gameId = UUID.randomUUID();

    private InMemoryQueryStore store;
    private GameSubscriptionRegistry subscriptions;
    private GameWebSocketHandler handler;
    private RecordingWebSocketSession session;

    @BeforeEach
    void setUp() {
        store = new InMemoryQueryStore();
        subscriptions = new GameSubscriptionRegistry(objectMapper);
        handler = new GameWebSocketHandler(new GameQueryService(store), subscriptions, objectMapper);
        session = new RecordingWebSocketSession(sessionId);
    }

    @Test
    void subscribesParticipantAndSendsCorrelatedSnapshot() throws Exception {
        GameSnapshot waiting = snapshot(GameStatus.WAITING, 0, 1);
        GameSnapshot active = snapshot(GameStatus.ACTIVE, 1, 2);
        store.snapshot = waiting;

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.subscribe","requestId":"subscribe-1","gameId":"%s"}
                """.formatted(gameId)));
        subscriptions.publish(active);

        assertThat(session.messages).hasSize(2);
        JsonNode subscribed = json(session.messages.get(0));
        JsonNode published = json(session.messages.get(1));
        assertThat(subscribed.get("type").asText()).isEqualTo("game.state");
        assertThat(subscribed.get("requestId").asText()).isEqualTo("subscribe-1");
        assertThat(subscribed.at("/snapshot/status").asText()).isEqualTo("WAITING");
        assertThat(published.get("type").asText()).isEqualTo("game.state");
        assertThat(published.has("requestId")).isFalse();
        assertThat(published.at("/snapshot/status").asText()).isEqualTo("ACTIVE");
        assertThat(published.at("/snapshot/revision").asLong()).isEqualTo(1);
    }

    @Test
    void rejectsUnauthorizedSubscriptionWithoutAssociatingConnection() throws Exception {
        store.exists = true;

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.subscribe","requestId":"subscribe-2","gameId":"%s"}
                """.formatted(gameId)));
        subscriptions.publish(snapshot(GameStatus.ACTIVE, 1, 2));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode error = json(message);
            assertThat(error.get("type").asText()).isEqualTo("game.error");
            assertThat(error.get("requestId").asText()).isEqualTo("subscribe-2");
            assertThat(error.get("code").asText()).isEqualTo("NOT_A_PARTICIPANT");
            assertThat(error.has("snapshot")).isFalse();
        });
    }

    @Test
    void rejectsInvalidJsonWithoutQueryingOrAssociatingConnection() throws Exception {
        handler.handleTextMessage(session, new TextMessage("not-json"));
        subscriptions.publish(snapshot(GameStatus.ACTIVE, 1, 2));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode error = json(message);
            assertThat(error.get("type").asText()).isEqualTo("game.error");
            assertThat(error.get("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.has("requestId")).isFalse();
        });
        assertThat(store.snapshotQueries).isZero();
    }

    @Test
    void rejectsMissingRequestIdWithoutReturningAnInvalidCorrelation() throws Exception {
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.subscribe","gameId":"%s"}
                """.formatted(gameId)));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode error = json(message);
            assertThat(error.get("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.has("requestId")).isFalse();
        });
        assertThat(store.snapshotQueries).isZero();
    }

    @Test
    void rejectsUnknownGameWithCorrelatedError() throws Exception {
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.subscribe","requestId":"subscribe-unknown","gameId":"%s"}
                """.formatted(gameId)));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode error = json(message);
            assertThat(error.get("requestId").asText()).isEqualTo("subscribe-unknown");
            assertThat(error.get("code").asText()).isEqualTo("GAME_NOT_FOUND");
        });
    }

    @Test
    void rejectsCommandsOutsideSubscriptionScope() throws Exception {
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.move","requestId":"move-1","gameId":"%s","revision":0,"uci":"e2e4"}
                """.formatted(gameId)));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode error = json(message);
            assertThat(error.get("requestId").asText()).isEqualTo("move-1");
            assertThat(error.get("code").asText()).isEqualTo("UNSUPPORTED_MESSAGE");
        });
        assertThat(store.snapshotQueries).isZero();
    }

    @Test
    void removesSubscriptionWhenConnectionCloses() throws Exception {
        store.snapshot = snapshot(GameStatus.WAITING, 0, 1);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.subscribe","requestId":"subscribe-3","gameId":"%s"}
                """.formatted(gameId)));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        subscriptions.publish(snapshot(GameStatus.ACTIVE, 1, 2));

        assertThat(session.messages).hasSize(1);
    }

    private JsonNode json(TextMessage message) {
        try {
            return objectMapper.readTree(message.getPayload());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private GameSnapshot snapshot(GameStatus status, long revision, int playerCount) {
        List<GameSnapshot.Player> players = playerCount == 1
                ? List.of(new GameSnapshot.Player(Side.WHITE, "HUMAN"))
                : List.of(
                        new GameSnapshot.Player(Side.WHITE, "HUMAN"),
                        new GameSnapshot.Player(Side.BLACK, "HUMAN"));
        return new GameSnapshot(
                gameId,
                status,
                GameVisibility.PUBLIC,
                null,
                revision,
                players,
                new GameSnapshot.Position(
                        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                        Side.WHITE,
                        null),
                new GameSnapshot.Clock(600_000, 600_000, status == GameStatus.ACTIVE ? Side.WHITE : null),
                null,
                null);
    }

    private static final class InMemoryQueryStore implements GameQueryStore {

        private GameSnapshot snapshot;
        private boolean exists;
        private int snapshotQueries;

        @Override
        public Page<LobbyGame> findPublicWaitingGames(Pageable pageable) {
            return Page.empty(pageable);
        }

        @Override
        public Optional<GameSnapshot> findSnapshot(UUID queriedGameId, UUID queriedSessionId) {
            snapshotQueries++;
            return Optional.ofNullable(snapshot);
        }

        @Override
        public boolean existsById(UUID queriedGameId) {
            return exists;
        }
    }

    private static final class RecordingWebSocketSession implements WebSocketSession {

        private final Map<String, Object> attributes;
        private final List<TextMessage> messages = new ArrayList<>();
        private boolean open = true;

        private RecordingWebSocketSession(UUID sessionId) {
            attributes = Map.of(GuestSessionHandshakeInterceptor.SESSION_ID_ATTRIBUTE, sessionId);
        }

        @Override
        public String getId() {
            return "connection-1";
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/ws");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return HttpHeaders.EMPTY;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            messages.add((TextMessage) message);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void close(CloseStatus status) {
            open = false;
        }
    }
}
