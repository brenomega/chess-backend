package com.projetoxadrez.backend.game.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projetoxadrez.backend.game.application.GameActionService;
import com.projetoxadrez.backend.game.application.GameActionStore;
import com.projetoxadrez.backend.game.application.GameQueryService;
import com.projetoxadrez.backend.game.application.GameQueryStore;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.application.LobbyGame;
import com.projetoxadrez.backend.game.chess.ChessPosition;
import com.projetoxadrez.backend.game.chess.MoveHistory;
import com.projetoxadrez.backend.game.chess.Side;
import com.projetoxadrez.backend.game.domain.Game;
import com.projetoxadrez.backend.game.domain.GameId;
import com.projetoxadrez.backend.game.domain.GameStatus;
import com.projetoxadrez.backend.game.domain.GameVisibility;
import com.projetoxadrez.backend.game.domain.Match;
import com.projetoxadrez.backend.game.domain.TimeControl;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private final UUID opponentSessionId = UUID.randomUUID();
    private final UUID gameId = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-11-24T15:00:00Z");
    private static final TimeControl TEN_MINUTES = new TimeControl(600_000, 0);

    private InMemoryQueryStore store;
    private InMemoryActionStore actionStore;
    private GameSubscriptionRegistry subscriptions;
    private GameWebSocketHandler handler;
    private RecordingWebSocketSession session;

    @BeforeEach
    void setUp() {
        store = new InMemoryQueryStore();
        actionStore = new InMemoryActionStore();
        subscriptions = new GameSubscriptionRegistry(objectMapper);
        GameActionService actionService =
                new GameActionService(
                        actionStore, subscriptions::publish, snapshot -> {}, Clock.fixed(NOW, ZoneOffset.UTC));
        handler = new GameWebSocketHandler(
                new GameQueryService(store), actionService, subscriptions, objectMapper);
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
    void rejectsUnknownMessageTypeOutsideTheContract() throws Exception {
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.rewind","requestId":"rewind-1","gameId":"%s"}
                """.formatted(gameId)));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode error = json(message);
            assertThat(error.get("requestId").asText()).isEqualTo("rewind-1");
            assertThat(error.get("code").asText()).isEqualTo("UNSUPPORTED_MESSAGE");
        });
        assertThat(store.snapshotQueries).isZero();
    }

    @Test
    void appliesMoveAndAnswersWithTheResultingState() throws Exception {
        actionStore.match = activeMatch();

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.move","requestId":"move-1","gameId":"%s","revision":1,"uci":"e2e4"}
                """.formatted(gameId)));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode state = json(message);
            assertThat(state.get("type").asText()).isEqualTo("game.state");
            assertThat(state.get("requestId").asText()).isEqualTo("move-1");
            assertThat(state.at("/snapshot/revision").asLong()).isEqualTo(2);
            assertThat(state.at("/snapshot/position/lastMoveUci").asText()).isEqualTo("e2e4");
            assertThat(state.at("/snapshot/position/sideToMove").asText()).isEqualTo("BLACK");
        });
    }

    @Test
    void rejectsIllegalMoveWithCorrelatedError() throws Exception {
        actionStore.match = activeMatch();

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.move","requestId":"move-2","gameId":"%s","revision":1,"uci":"e2e5"}
                """.formatted(gameId)));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode error = json(message);
            assertThat(error.get("requestId").asText()).isEqualTo("move-2");
            assertThat(error.get("code").asText()).isEqualTo("ILLEGAL_MOVE");
        });
        assertThat(actionStore.saves).isZero();
    }

    @Test
    void rejectsMoveCarryingAnOutdatedRevision() throws Exception {
        actionStore.match = activeMatch();

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.move","requestId":"move-3","gameId":"%s","revision":0,"uci":"e2e4"}
                """.formatted(gameId)));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode error = json(message);
            assertThat(error.get("code").asText()).isEqualTo("STALE_REVISION");
        });
        assertThat(actionStore.saves).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.25", "\"1\"", "null", "9223372036854775808", "-1,\"revision\":1"})
    void rejectsInvalidRevisionEvenWithAnObservedSnapshot(String revision) throws Exception {
        actionStore.match = activeMatch();
        store.snapshot = snapshotOf(actionStore.match);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.subscribe","requestId":"subscribe-1","gameId":"%s"}
                """.formatted(gameId)));
        session.messages.clear();

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.move","requestId":"move-1","gameId":"%s","revision":%s,"uci":"e2e4"}
                """.formatted(gameId, revision)));

        assertThat(session.messages).singleElement().satisfies(message ->
                assertThat(json(message).path("code").asText()).isEqualTo("VALIDATION_ERROR"));
        assertThat(actionStore.saves).isZero();
    }

    @Test
    void rejectsMoveOutsideUciNotation() throws Exception {
        actionStore.match = activeMatch();

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.move","requestId":"move-4","gameId":"%s","uci":"torre pra frente"}
                """.formatted(gameId)));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode error = json(message);
            assertThat(error.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        });
    }

    @Test
    void abandonFinishesTheGameInFavourOfTheOpponent() throws Exception {
        actionStore.match = activeMatch();

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.abandon","requestId":"abandon-1","gameId":"%s","revision":1}
                """.formatted(gameId)));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode state = json(message);
            assertThat(state.at("/snapshot/status").asText()).isEqualTo("FINISHED");
            assertThat(state.at("/snapshot/result/outcome").asText()).isEqualTo("BLACK_WIN");
            assertThat(state.at("/snapshot/result/reason").asText()).isEqualTo("RESIGNATION");
        });
    }

    @Test
    void drawOfferIsRegisteredAndAnswerEndsTheGame() throws Exception {
        actionStore.match = activeMatch();

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"draw.offer","requestId":"draw-1","gameId":"%s","revision":1}
                """.formatted(gameId)));

        assertThat(json(session.messages.get(0)).at("/snapshot/drawOffer/offeredBy").asText())
                .isEqualTo("WHITE");

        RecordingWebSocketSession blackSession = new RecordingWebSocketSession(opponentSessionId);
        handler.handleTextMessage(blackSession, new TextMessage("""
                {"type":"draw.respond","requestId":"draw-2","gameId":"%s","accept":true,"revision":2}
                """.formatted(gameId)));

        assertThat(json(blackSession.messages.get(0)).at("/snapshot/result/reason").asText())
                .isEqualTo("DRAW_AGREED");
    }

    @Test
    void rejectsActionFromSessionThatDoesNotPlayTheGame() throws Exception {
        actionStore.match = activeMatch();
        RecordingWebSocketSession stranger = new RecordingWebSocketSession(UUID.randomUUID());

        handler.handleTextMessage(stranger, new TextMessage("""
                {"type":"game.move","requestId":"move-5","gameId":"%s","revision":1,"uci":"e2e4"}
                """.formatted(gameId)));

        assertThat(stranger.messages).singleElement().satisfies(message -> {
            JsonNode error = json(message);
            assertThat(error.get("code").asText()).isEqualTo("NOT_A_PARTICIPANT");
        });
        assertThat(actionStore.saves).isZero();
    }

    @Test
    void rejectsActionOnAnUnknownGame() throws Exception {
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"game.abandon","requestId":"abandon-2","gameId":"%s"}
                """.formatted(gameId)));

        assertThat(session.messages).singleElement().satisfies(message -> {
            JsonNode error = json(message);
            assertThat(error.get("code").asText()).isEqualTo("GAME_NOT_FOUND");
        });
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

    private Match activeMatch() {
        ChessPosition initial = ChessPosition.initial();
        return Match.rehydrate(
                new GameId(gameId),
                GameStatus.ACTIVE,
                TEN_MINUTES,
                1,
                List.of(
                        new Game.Participant(Side.WHITE, sessionId, "HUMAN"),
                        new Game.Participant(Side.BLACK, opponentSessionId, "HUMAN")),
                initial,
                MoveHistory.empty(),
                null,
                600_000,
                600_000,
                initial.sideToMove(),
                NOW,
                null,
                null,
                null);
    }

    private GameSnapshot snapshotOf(Match match) {
        return new GameSnapshot(
                gameId,
                match.status(),
                GameVisibility.PUBLIC,
                null,
                match.revision(),
                List.of(
                        new GameSnapshot.Player(Side.WHITE, "HUMAN"),
                        new GameSnapshot.Player(Side.BLACK, "HUMAN")),
                new GameSnapshot.Position(
                        match.position().toFen(), match.position().sideToMove(), match.lastMoveUci()),
                new GameSnapshot.Clock(
                        match.whiteRemainingMs(), match.blackRemainingMs(), match.activeSide()),
                match.drawOfferSide() == null ? null : new GameSnapshot.DrawOffer(match.drawOfferSide()),
                match.resultOutcome() == null
                        ? null
                        : new GameSnapshot.Result(match.resultOutcome(), match.resultReason()));
    }

    private final class InMemoryActionStore implements GameActionStore {

        private final Map<List<Object>, String> identities = new HashMap<>();
        private Match match;
        private int saves;

        @Override
        public Optional<Match> findForUpdate(UUID requestedGameId) {
            return Optional.ofNullable(match).filter(held -> held.id().value().equals(requestedGameId));
        }

        @Override
        public GameSnapshot save(Match savedMatch) {
            saves++;
            match = savedMatch;
            return snapshotOf(savedMatch);
        }

        @Override
        public GameSnapshot snapshot(UUID requestedGameId) {
            return snapshotOf(findForUpdate(requestedGameId).orElseThrow());
        }

        @Override
        public Optional<String> findActionIdentity(UUID gameId, UUID sessionId, String requestId) {
            return Optional.ofNullable(identities.get(List.of(gameId, sessionId, requestId)));
        }

        @Override
        public void recordAction(UUID gameId, UUID sessionId, String requestId, String identity) {
            identities.put(List.of(gameId, sessionId, requestId), identity);
        }
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
