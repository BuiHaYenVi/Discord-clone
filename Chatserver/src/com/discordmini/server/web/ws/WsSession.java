package com.discordmini.server.web.ws;

import com.discordmini.server.model.User;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.session.Session;
import com.discordmini.server.session.TransportType;
import com.discordmini.server.web.http.JsonUtil;
import org.java_websocket.WebSocket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Session implementation wrapping a Java-WebSocket client connection.
 */
public class WsSession implements Session {

    private final String sessionId;
    private final WebSocket conn;
    private final EventBus eventBus;
    private volatile User user;
    private volatile Integer currentRoomId;

    public WsSession(WebSocket conn, EventBus eventBus) {
        this.sessionId = "ws-" + UUID.randomUUID();
        this.conn = conn;
        this.eventBus = eventBus != null ? eventBus : EventBus.getInstance();
    }

    public WebSocket getWebSocket() {
        return conn;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public TransportType getTransport() {
        return TransportType.WS;
    }

    @Override
    public User getUser() {
        return user;
    }

    @Override
    public void setUser(User user) {
        this.user = user;
    }

    @Override
    public Integer getCurrentRoomId() {
        return currentRoomId;
    }

    @Override
    public void setCurrentRoomId(Integer roomId) {
        this.currentRoomId = roomId;
    }

    @Override
    public void sendTcpFrame(String frame) {
        // No-op for WebSocket sessions; EventBus dispatches sendWsEvent for WS transport.
    }

    @Override
    public void sendWsEvent(String type, String rid, Object payload) {
        if (conn == null || !conn.isOpen() || type == null || type.isBlank()) {
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        if (rid != null && !rid.isBlank()) {
            envelope.put("rid", rid);
        }
        envelope.put("payload", payload);

        String json = JsonUtil.toJson(envelope);
        try {
            conn.send(json);
            if (!"PROTOCOL_FRAME_LOG".equals(type) && eventBus != null) {
                User u = this.user;
                eventBus.recordProtocolFrame("OUT", TransportType.WS, json, u != null ? u.getId() : null);
            }
        } catch (Exception ignored) {
        }
    }

    public void sendAck(String rid, Object data) {
        if (rid == null || rid.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rid", rid);
        payload.put("status", "OK");
        if (data != null) {
            payload.put("data", data);
        }
        sendWsEvent("ACK", rid, payload);
    }

    public void sendError(String rid, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (rid != null && !rid.isBlank()) {
            payload.put("rid", rid);
        }
        payload.put("code", code != null ? code : "BAD_REQUEST");
        payload.put("message", message != null ? message : "WebSocket command failed");
        sendWsEvent("ERROR", rid, payload);
    }

    @Override
    public boolean isOpen() {
        return conn != null && conn.isOpen();
    }

    @Override
    public void close() {
        if (conn != null && conn.isOpen()) {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
    }
}
