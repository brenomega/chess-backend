package com.projetoxadrez.backend.game.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.projetoxadrez.backend.game.application.GameActionService;
import com.projetoxadrez.backend.game.application.GameQueryService;
import com.projetoxadrez.backend.game.application.GameSnapshot;
import com.projetoxadrez.backend.game.domain.Match;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private final ObjectReader messageReader;

    public GameWebSocketHandler(
            GameQueryService queryService,
            GameActionService actionService,
            GameSubscriptionRegistry subscriptions,
            ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.actionService = actionService;
        this.subscriptions = subscriptions;
        this.messageReader = objectMapper.reader().with(
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws IOException {
        JsonNode message;
        try {
            message = messageReader.readTree(textMessage.getPayload());
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
            case "game.move" -> action(session, requestId, message, request -> {
                String uci = text(message, "uci");
                if (uci == null || uci.isBlank()) {
                    throw new InvalidFieldException("uci is required");
                }
                return actionService.move(request, uci);
            });
            case "game.abandon" -> action(session, requestId, message, actionService::abandon);
            case "draw.offer" -> action(session, requestId, message, actionService::offerDraw);
            case "draw.respond" -> action(session, requestId, message, request -> {
                JsonNode accept = message.get("accept");
                if (accept == null || !accept.isBoolean()) {
                    throw new InvalidFieldException("accept must be a boolean");
                }
                return actionService.respondDraw(request, accept.booleanValue());
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
            subscriptions.subscribe(session, gameId, requestId, () -> queryService.game(gameId, sessionId));
        } catch (GameQueryService.GameNotFoundException exception) {
            subscriptions.sendError(session, requestId, "GAME_NOT_FOUND", "Game not found");
        } catch (GameQueryService.NotAGameParticipantException exception) {
            subscriptions.sendError(session, requestId, "NOT_A_PARTICIPANT", "Not a game participant");
        }
    }

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
            if (requestId.codePointCount(0, requestId.length()) > 128 || requestId.indexOf(0) >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(requestId)) {
                throw new InvalidFieldException("requestId must be valid Unicode, at most 128 characters and without NUL");
            }
            GameSnapshot snapshot = gameAction.execute(new GameActionService.Request(
                    gameId, sessionId, requestId, session.getId(), revision(message)));
            subscriptions.sendState(session, requestId, snapshot);
        } catch (InvalidFieldException exception) {
            subscriptions.sendError(session, requestId, "VALIDATION_ERROR", exception.getMessage());
        } catch (GameActionService.MissingRevisionException exception) {
            subscriptions.sendError(session, requestId, "VALIDATION_ERROR", "revision is required for new actions");
        } catch (GameActionService.RequestIdConflictException exception) {
            subscriptions.sendError(session, requestId, "REQUEST_ID_CONFLICT", "requestId already used for another action");
        } catch (GameActionService.InvalidMoveFormatException exception) {
            subscriptions.sendError(session, requestId, "VALIDATION_ERROR", "uci must use UCI notation");
        } catch (GameActionService.GameNotFoundException exception) {
            subscriptions.sendError(session, requestId, "GAME_NOT_FOUND", "Game not found");
        } catch (Match.NotAParticipantException exception) {
            subscriptions.sendError(session, requestId, "NOT_A_PARTICIPANT", "Not a game participant");
        } catch (Match.InvalidGameStateException exception) {
            subscriptions.sendError(session, requestId, "INVALID_GAME_STATE", "Game is not active");
        } catch (Match.GameTimedOutException exception) {
            subscriptions.sendError(session, requestId, "INVALID_GAME_STATE", "Game ended on time");
        } catch (Match.NotYourTurnException exception) {
            subscriptions.sendError(session, requestId, "NOT_YOUR_TURN", "It is not your turn");
        } catch (Match.StaleRevisionException exception) {
            subscriptions.sendError(session, requestId, "STALE_REVISION", "Revision is out of date");
        } catch (Match.IllegalMoveException exception) {
            subscriptions.sendError(session, requestId, "ILLEGAL_MOVE", "Move is not legal in this position");
        } catch (Match.NoDrawOfferException exception) {
            subscriptions.sendError(session, requestId, "NO_DRAW_OFFER", "There is no draw offer to respond to");
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

    private static Long revision(JsonNode message) {
        JsonNode value = message.get("revision");
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new InvalidFieldException("revision must be a non-negative 64-bit integer");
        }
        return value.longValue();
    }

    @FunctionalInterface
    private interface GameAction {
        GameSnapshot execute(GameActionService.Request request);
    }

    private static final class InvalidFieldException extends RuntimeException {
        private InvalidFieldException(String message) {
            super(message);
        }
    }

    private static String text(JsonNode message, String field) {
        JsonNode value = message.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
