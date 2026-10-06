package com.projetoxadrez.backend.game.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class GameWebSocketConfiguration implements WebSocketConfigurer {

    private final GameWebSocketHandler handler;
    private final GuestSessionHandshakeInterceptor sessionInterceptor;

    public GameWebSocketConfiguration(
            GameWebSocketHandler handler,
            GuestSessionHandshakeInterceptor sessionInterceptor) {
        this.handler = handler;
        this.sessionInterceptor = sessionInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").addInterceptors(sessionInterceptor);
    }
}
