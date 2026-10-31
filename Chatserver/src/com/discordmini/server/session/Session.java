package com.discordmini.server.session;

import com.discordmini.server.model.User;

public interface Session {
    String getSessionId();
    TransportType getTransport();
    User getUser();
    void setUser(User user);
    Integer getCurrentRoomId();
    void setCurrentRoomId(Integer roomId);
    void sendTcpFrame(String frame);
    void sendWsEvent(String type, String rid, Object payload);
    boolean isOpen();
    void close();
}
