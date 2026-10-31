package com.discordmini.server.session;

import com.discordmini.server.model.User;
import com.discordmini.server.network.ClientHandler;

import java.util.UUID;

/**
 * Session implementation wrapping a Swing/TCP ClientHandler connection.
 */
public class TcpSession implements Session {
    private final String sessionId;
    private final ClientHandler handler;
    private final EventBus eventBus;

    public TcpSession(ClientHandler handler, EventBus eventBus) {
        this.sessionId = "tcp-" + UUID.randomUUID();
        this.handler = handler;
        this.eventBus = eventBus != null ? eventBus : EventBus.getInstance();
    }

    public ClientHandler getHandler() {
        return handler;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public TransportType getTransport() {
        return TransportType.TCP;
    }

    @Override
    public User getUser() {
        return handler.getUser();
    }

    @Override
    public void setUser(User user) {
        handler.setUser(user);
    }

    @Override
    public Integer getCurrentRoomId() {
        return handler.getCurrentRoomId();
    }

    @Override
    public void setCurrentRoomId(Integer roomId) {
        handler.setCurrentRoomId(roomId);
    }

    @Override
    public void sendTcpFrame(String frame) {
        if (frame == null || frame.isBlank()) return;
        handler.sendPacket(frame);
        User u = handler.getUser();
        if (eventBus != null) {
            eventBus.recordProtocolFrame("OUT", TransportType.TCP, frame, u != null ? u.getId() : null);
        }
    }

    @Override
    public void sendWsEvent(String type, String rid, Object payload) {
        // No-op for TCP session; publishToRoom/publishToUser/publishGlobal provides tcpFrame alongside wsPayload
    }

    @Override
    public boolean isOpen() {
        return handler.isConnectionOpen();
    }

    @Override
    public void close() {
        handler.closeConnection();
    }
}
