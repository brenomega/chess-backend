package com.projetoxadrez.backend.game.websocket;

import com.projetoxadrez.backend.session.application.ExpiredGuestSessionException;
import com.projetoxadrez.backend.session.application.GuestSessionResult;
import com.projetoxadrez.backend.session.application.GuestSessionService;
import com.projetoxadrez.backend.session.application.InvalidGuestSessionException;
import com.projetoxadrez.backend.session.application.InvalidSessionRequestException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class GuestSessionHandshakeInterceptor implements HandshakeInterceptor {

    static final String SESSION_ID_ATTRIBUTE = "guestSessionId";

    private final GuestSessionService sessionService;

    public GuestSessionHandshakeInterceptor(GuestSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        try {
            GuestSessionResult session = sessionService.recover(
                    request.getHeaders().getFirst("X-Session-Token"));
            attributes.put(SESSION_ID_ATTRIBUTE, session.sessionId());
            return true;
        } catch (InvalidSessionRequestException
                | InvalidGuestSessionException
                | ExpiredGuestSessionException exception) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
    }
}
