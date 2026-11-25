package com.projetoxadrez.backend.game.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.domain.GameStatus;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class GameSubscriptionRegistry {

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public GameSubscriptionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void subscribe(WebSocketSession session, UUID gameId, String requestId, Supplier<GameSnapshot> authorizedSnapshot)
            throws IOException {
        String sessionId = session.getId();
        Subscription pending = new Subscription(gameId);
        Connection initializing;
        synchronized (session) {
            initializing = new Connection(session, activeSubscription(session), pending);
            connections.put(sessionId, initializing);
        }

        try {
            GameSnapshot snapshot = authorizedSnapshot.get();
            synchronized (session) {
                if (connections.get(sessionId) != initializing) {
                    return;
                }
                Subscription active = initializing.active();
                if (follows(active, gameId) && isOlderThan(snapshot, active.latest)) {
                    snapshot = active.latest;
                }
                if (isOlderThan(snapshot, pending.latest)) {
                    snapshot = pending.latest;
                }
                sendStateMessage(session, requestId, snapshot);
                pending.latest = snapshot;
                connections.put(sessionId, new Connection(session, pending, null));
            }
        } finally {
            synchronized (session) {
                if (connections.get(sessionId) == initializing) {
                    if (initializing.active() == null) {
                        connections.remove(sessionId);
                    } else {
                        connections.put(sessionId, new Connection(session, initializing.active(), null));
                    }
                }
            }
        }
    }

    void remove(WebSocketSession session) {
        synchronized (session) {
            connections.remove(session.getId());
        }
    }

    void sendState(WebSocketSession session, String requestId, GameSnapshot snapshot) throws IOException {
        synchronized (session) {
            Subscription active = activeSubscription(session);
            if (follows(active, snapshot.gameId())) {
                sendSubscribedState(session, requestId, snapshot, active);
            } else {
                sendStateMessage(session, requestId, snapshot);
            }
        }
    }

    private Subscription activeSubscription(WebSocketSession session) {
        Connection connection = connections.get(session.getId());
        return connection == null ? null : connection.active();
    }

    Long observedRevision(WebSocketSession session, UUID gameId) {
        synchronized (session) {
            Subscription active = activeSubscription(session);
            return follows(active, gameId) ? active.latest.revision() : null;
        }
    }

    private void sendSubscribedState(
            WebSocketSession session, String requestId, GameSnapshot snapshot, Subscription active) throws IOException {
        if (isOlderThan(snapshot, active.latest)) {
            if (requestId == null) {
                return;
            }
            snapshot = active.latest;
        }
        sendStateMessage(session, requestId, snapshot);
        active.latest = snapshot;
    }

    private static boolean isOlderThan(GameSnapshot snapshot, GameSnapshot latest) {
        if (latest == null) {
            return false;
        }
        if (snapshot.revision() != latest.revision()) {
            return snapshot.revision() < latest.revision();
        }
        return (latest.status() == GameStatus.FINISHED && snapshot.status() != GameStatus.FINISHED)
                || snapshot.clock().whiteRemainingMs() > latest.clock().whiteRemainingMs()
                || snapshot.clock().blackRemainingMs() > latest.clock().blackRemainingMs();
    }

    private void sendStateMessage(WebSocketSession session, String requestId, GameSnapshot snapshot) throws IOException {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "game.state");
        if (requestId != null) {
            message.put("requestId", requestId);
        }
        message.set("snapshot", objectMapper.valueToTree(snapshot));
        send(session, message);
    }

    void sendError(WebSocketSession session, String requestId, String code, String description) throws IOException {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "game.error");
        if (requestId != null) {
            message.put("requestId", requestId);
        }
        message.put("code", code);
        message.put("message", description);
        send(session, message);
    }

    public void publish(GameSnapshot snapshot) {
        publish(snapshot, null);
    }

    void publish(GameSnapshot snapshot, String excludedConnectionId) {
        connections.values().stream()
                .filter(connection -> !connection.session().getId().equals(excludedConnectionId))
                .filter(connection -> follows(connection.active(), snapshot.gameId())
                        || follows(connection.pending(), snapshot.gameId()))
                .forEach(connection -> publish(connection.session(), snapshot));
    }

    private void publish(WebSocketSession session, GameSnapshot snapshot) {
        synchronized (session) {
            Connection connection = connections.get(session.getId());
            if (connection == null) {
                return;
            }
            Subscription pending = connection.pending();
            if (follows(pending, snapshot.gameId()) && !isOlderThan(snapshot, pending.latest)) {
                pending.latest = snapshot;
            }
            Subscription active = connection.active();
            if (follows(active, snapshot.gameId())) {
                try {
                    sendSubscribedState(session, null, snapshot, active);
                } catch (IOException | RuntimeException exception) {
                    connections.remove(session.getId());
                }
            }
        }
    }

    private static boolean follows(Subscription subscription, UUID gameId) {
        return subscription != null && subscription.gameId.equals(gameId);
    }

    private void send(WebSocketSession session, ObjectNode message) throws IOException {
        synchronized (session) {
            if (!session.isOpen()) {
                throw new IOException("WebSocket session is closed");
            }
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        }
    }

    private record Connection(WebSocketSession session, Subscription active, Subscription pending) {
    }

    private static final class Subscription {
        private final UUID gameId;
        private GameSnapshot latest;

        private Subscription(UUID gameId) {
            this.gameId = gameId;
        }
    }
}
