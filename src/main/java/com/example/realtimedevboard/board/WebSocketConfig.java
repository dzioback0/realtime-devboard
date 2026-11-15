package com.example.realtimedevboard.board;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final BoardSocketHandler handler;

    public WebSocketConfig(BoardSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // No setAllowedOrigins: Spring then accepts same-origin handshakes only (blocks cross-site socket hijacking).
        registry.addHandler(handler, "/ws/boards/*").addInterceptors(handler);
    }
}
