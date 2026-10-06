package com.projetoxadrez.backend.game.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class GameSubscriptionRegistry {

    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public GameSubscriptionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void subscribe(WebSocketSession session, UUID gameId) {
        subscriptions.put(session.getId(), new Subscription(session, gameId));
    }

    void remove(WebSocketSession session) {
        subscriptions.remove(session.getId());
    }

    void sendState(WebSocketSession session, String requestId, GameSnapshot snapshot) throws IOException {
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
        subscriptions.values().stream()
                .filter(subscription -> subscription.gameId().equals(snapshot.gameId()))
                .forEach(subscription -> publish(subscription, snapshot));
    }

    private void publish(Subscription subscription, GameSnapshot snapshot) {
        try {
            sendState(subscription.session(), null, snapshot);
        } catch (IOException | RuntimeException exception) {
            subscriptions.remove(subscription.session().getId(), subscription);
        }
    }

    private void send(WebSocketSession session, ObjectNode message) throws IOException {
        synchronized (session) {
            if (!session.isOpen()) {
                throw new IOException("WebSocket session is closed");
            }
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        }
    }

    private record Subscription(WebSocketSession session, UUID gameId) {
    }
}
