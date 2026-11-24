package com.projetoxadrez.backend.game.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projetoxadrez.backend.game.application.GameActionService;
import com.projetoxadrez.backend.game.application.GameQueryService;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.domain.Match;
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
    private final GameActionService actionService;
    private final GameSubscriptionRegistry subscriptions;
    private final ObjectMapper objectMapper;

    public GameWebSocketHandler(
            GameQueryService queryService,
            GameActionService actionService,
            GameSubscriptionRegistry subscriptions,
            ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.actionService = actionService;
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
        switch (type) {
            case "game.subscribe" -> subscribe(session, requestId, message);
            case "game.move" -> action(session, requestId, message, (gameId, sessionId) -> {
                String uci = text(message, "uci");
                if (uci == null || uci.isBlank()) {
                    throw new MissingFieldException("uci is required");
                }
                return actionService.move(gameId, sessionId, revision(message), uci);
            });
            default -> subscriptions.sendError(
                    session, requestId, "UNSUPPORTED_MESSAGE", "Message type is not supported");
        }
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
        UUID gameId = gameId(session, requestId, message);
        if (gameId == null) {
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

    /**
     * Executa uma ação da partida e devolve o estado resultante, traduzindo as recusas do domínio
     * para os códigos do protocolo (EAP 2.15.1).
     */
    private void action(WebSocketSession session, String requestId, JsonNode message, GameAction gameAction)
            throws IOException {
        UUID gameId = gameId(session, requestId, message);
        if (gameId == null) {
            return;
        }
        Object sessionAttribute = session.getAttributes().get(GuestSessionHandshakeInterceptor.SESSION_ID_ATTRIBUTE);
        if (!(sessionAttribute instanceof UUID sessionId)) {
            subscriptions.sendError(session, requestId, "SESSION_INVALID", "Session is not valid");
            return;
        }

        try {
            GameSnapshot snapshot = gameAction.execute(gameId, sessionId);
            subscriptions.sendState(session, requestId, snapshot);
        } catch (MissingFieldException exception) {
            subscriptions.sendError(session, requestId, "VALIDATION_ERROR", exception.getMessage());
        } catch (GameActionService.InvalidMoveFormatException exception) {
            subscriptions.sendError(session, requestId, "VALIDATION_ERROR", "uci must use UCI notation");
        } catch (GameActionService.GameNotFoundException exception) {
            subscriptions.sendError(session, requestId, "GAME_NOT_FOUND", "Game not found");
        } catch (Match.NotAParticipantException exception) {
            subscriptions.sendError(session, requestId, "NOT_A_PARTICIPANT", "Not a game participant");
        } catch (Match.InvalidGameStateException exception) {
            subscriptions.sendError(session, requestId, "INVALID_GAME_STATE", "Game is not active");
        } catch (Match.NotYourTurnException exception) {
            subscriptions.sendError(session, requestId, "NOT_YOUR_TURN", "It is not your turn");
        } catch (Match.StaleRevisionException exception) {
            subscriptions.sendError(session, requestId, "STALE_REVISION", "Revision is out of date");
        } catch (Match.IllegalMoveException exception) {
            subscriptions.sendError(session, requestId, "ILLEGAL_MOVE", "Move is not legal in this position");
        }
    }

    private UUID gameId(WebSocketSession session, String requestId, JsonNode message) throws IOException {
        try {
            String value = text(message, "gameId");
            if (value == null) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            subscriptions.sendError(session, requestId, "VALIDATION_ERROR", "gameId must be a UUID");
            return null;
        }
    }

    /** Revisão esperada pelo cliente; ausente significa "não verificar" (EAP 2.11.1). */
    private static long revision(JsonNode message) {
        JsonNode value = message.get("revision");
        return value != null && value.canConvertToLong() ? value.longValue() : GameActionService.ANY_REVISION;
    }

    @FunctionalInterface
    private interface GameAction {
        GameSnapshot execute(UUID gameId, UUID sessionId);
    }

    private static final class MissingFieldException extends RuntimeException {
        private MissingFieldException(String message) {
            super(message);
        }
    }

    private static String text(JsonNode message, String field) {
        JsonNode value = message.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
