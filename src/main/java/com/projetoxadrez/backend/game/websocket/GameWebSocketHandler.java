package com.projetoxadrez.backend.game.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projetoxadrez.backend.game.application.GameQueryService;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final GameQueryService queryService;
    private final GameSubscriptionRegistry subscriptions;
    private final ObjectMapper objectMapper;

    public GameWebSocketHandler(
            GameQueryService queryService,
            GameSubscriptionRegistry subscriptions,
            ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.subscriptions = subscriptions;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws IOException {
        JsonNode message;
        try {
            message = objectMapper.readTree(textMessage.getPayload());
        } catch (JsonProcessingException exception) {
            subscriptions.sendError(session, null, "VALIDATION_ERROR", "Message must be a valid JSON object");
            return;
        }
        if (message == null || !message.isObject()) {
            subscriptions.sendError(session, null, "VALIDATION_ERROR", "Message must be a valid JSON object");
            return;
        }

        String requestId = text(message, "requestId");
        String type = text(message, "type");
        if (requestId == null || requestId.isBlank() || type == null || type.isBlank()) {
            subscriptions.sendError(
                    session,
                    requestId == null || requestId.isBlank() ? null : requestId,
                    "VALIDATION_ERROR",
                    "type and requestId are required");
            return;
        }
        if (!"game.subscribe".equals(type)) {
            subscriptions.sendError(session, requestId, "UNSUPPORTED_MESSAGE", "Message type is not supported");
            return;
        }
        subscribe(session, requestId, message);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscriptions.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        subscriptions.remove(session);
    }

    private void subscribe(WebSocketSession session, String requestId, JsonNode message) throws IOException {
        UUID gameId;
        try {
            String value = text(message, "gameId");
            if (value == null) {
                throw new IllegalArgumentException();
            }
            gameId = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            subscriptions.sendError(session, requestId, "VALIDATION_ERROR", "gameId must be a UUID");
            return;
        }

        Object sessionAttribute = session.getAttributes().get(GuestSessionHandshakeInterceptor.SESSION_ID_ATTRIBUTE);
        if (!(sessionAttribute instanceof UUID sessionId)) {
            subscriptions.sendError(session, requestId, "SESSION_INVALID", "Session is not valid");
            return;
        }
        try {
            GameSnapshot snapshot = queryService.game(gameId, sessionId);
            subscriptions.subscribe(session, gameId);
            subscriptions.sendState(session, requestId, snapshot);
        } catch (GameQueryService.GameNotFoundException exception) {
            subscriptions.sendError(session, requestId, "GAME_NOT_FOUND", "Game not found");
        } catch (GameQueryService.NotAGameParticipantException exception) {
            subscriptions.sendError(session, requestId, "NOT_A_PARTICIPANT", "Not a game participant");
        }
    }

    private static String text(JsonNode message, String field) {
        JsonNode value = message.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
