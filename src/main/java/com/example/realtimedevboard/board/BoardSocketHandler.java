package com.example.realtimedevboard.board;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /ws/boards/{boardId}: server-to-client change notifications for one board.
 * The handshake is allowed only for members of the board's workspace (the subscription is fixed per
 * connection). The channel is read-only: all changes go through the authorized REST API,
 * so any client message closes the connection.
 */
@Component
public class BoardSocketHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BoardSocketHandler.class);
    private static final String BOARD_ID = "boardId";

    private final BoardRepository boards;
    private final JsonMapper json;
    private final Map<Long, Map<String, WebSocketSession>> sessionsByBoard = new ConcurrentHashMap<>();

    public BoardSocketHandler(BoardRepository boards, JsonMapper json) {
        this.boards = boards;
        this.json = json;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        Principal user = request.getPrincipal();
        String path = request.getURI().getPath();
        Long boardId;
        try {
            boardId = Long.valueOf(path.substring(path.lastIndexOf('/') + 1));
        } catch (NumberFormatException e) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        if (user == null || boards.findByIdAndWorkspace_Members_Username(boardId, user.getName()).isEmpty()) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(BOARD_ID, boardId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception e) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionsByBoard.computeIfAbsent(boardId(session), id -> new ConcurrentHashMap<>())
                .put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 5_000, 1024 * 1024));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        session.close(CloseStatus.POLICY_VIOLATION.withReason("Read-only channel, use the REST API"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Map<String, WebSocketSession> sessions = sessionsByBoard.get(boardId(session));
        if (sessions != null) {
            sessions.remove(session.getId());
        }
    }

    public void publish(Long boardId, Object event) {
        Map<String, WebSocketSession> sessions = sessionsByBoard.get(boardId);
        if (sessions == null) {
            return;
        }
        TextMessage message = new TextMessage(json.writeValueAsString(event));
        for (WebSocketSession session : sessions.values()) {
            try {
                session.sendMessage(message);
            } catch (IOException | RuntimeException e) {
                log.debug("Dropping message to closed socket {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    private static Long boardId(WebSocketSession session) {
        return (Long) session.getAttributes().get(BOARD_ID);
    }
}
