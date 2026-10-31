package com.discordmini.server.protocol;

import com.discordmini.server.session.EventBus;
import com.discordmini.server.session.TransportType;
import com.discordmini.server.session.Session;
import com.discordmini.server.model.User;
import java.util.ArrayList;
import java.util.List;

/** Ensures monitor telemetry never retains request or response payloads. */
public final class ProtocolFramePrivacyTest {
    public static void main(String[] args) {
        EventBus bus = new EventBus();
        String token = "secret-jwt-value";
        bus.recordProtocolFrame("IN", TransportType.WS,
                "{\"type\":\"AUTH\",\"payload\":{\"token\":\"" + token + "\"}}", null);
        bus.recordProtocolFrame("OUT", TransportType.WS,
                "{\"type\":\"MESSAGE_CREATED\",\"payload\":{\"content\":\"private text\"}}", 42);

        var frames = bus.getRecentFrames(10);
        check(frames.size() == 2, "both frames remain visible as telemetry");
        check(frames.get(0).rawFrame().equals("AUTH|[REDACTED]"), "JSON event type retained without payload");
        check(frames.get(1).rawFrame().equals("MESSAGE_CREATED|[REDACTED]"), "message body removed from telemetry");
        check(frames.stream().noneMatch(frame -> frame.rawFrame().contains(token) || frame.rawFrame().contains("private text")),
                "credential and message content absent from retained frames");

        Session roomOne = new TestSession("room-one", new User(71, "one", "", "One"), 1);
        Session roomTwo = new TestSession("room-two", new User(72, "two", "", "Two"), 2);
        TestSession tcpGuildMember = new TestSession("tcp-guild-member", new User(73, "three", "", "Three"), 3, TransportType.TCP);
        TestSession tcpNonMember = new TestSession("tcp-non-member", new User(74, "four", "", "Four"), 4, TransportType.TCP);
        bus.registerSession(roomOne);
        bus.registerSession(roomTwo);
        bus.registerSession(tcpGuildMember);
        bus.registerSession(tcpNonMember);
        bus.publishToRoom(1, "ROOM_PRIVATE|data", "ROOM_PRIVATE", "payload", null);
        check(((TestSession) roomOne).events.size() == 1, "active channel member receives channel event");
        check(((TestSession) roomTwo).events.isEmpty(), "other channel session does not receive channel event");
        bus.publishToRoom(0, java.util.Set.of(71, 73), "CHANNEL_CREATED|99", "CHANNEL_CREATED", "metadata", null);
        check(((TestSession) roomOne).events.size() == 2, "guild member receives new channel metadata");
        check(((TestSession) roomTwo).events.isEmpty(), "user outside the guild does not receive new channel metadata");
        check(tcpGuildMember.frames.size() == 1, "TCP guild member receives new channel metadata");
        check(tcpNonMember.frames.isEmpty(), "TCP user outside the guild does not receive new channel metadata");
        bus.clearCurrentRoomForUser(71, 1);
        check(((TestSession) roomOne).getCurrentRoomId() == null,
                "access revocation clears the channel from every matching user session");
        bus.publishToRoom(1, "ROOM_PRIVATE|after-kick", "ROOM_PRIVATE", "payload", null);
        check(((TestSession) roomOne).events.size() == 2,
                "revoked session no longer receives active-room events");
        bus.unregisterSession(roomOne);
        bus.unregisterSession(roomTwo);
        bus.unregisterSession(tcpGuildMember);
        bus.unregisterSession(tcpNonMember);
        System.out.println("ProtocolFramePrivacyTest: 12 assertions passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestSession implements Session {
        private final String id;
        private final TransportType transport;
        private User user;
        private Integer roomId;
        private final List<String> events = new ArrayList<>();
        private final List<String> frames = new ArrayList<>();

        private TestSession(String id, User user, int roomId) {
            this(id, user, roomId, TransportType.WS);
        }

        private TestSession(String id, User user, int roomId, TransportType transport) {
            this.id = id;
            this.user = user;
            this.roomId = roomId;
            this.transport = transport;
        }

        @Override public String getSessionId() { return id; }
        @Override public TransportType getTransport() { return transport; }
        @Override public User getUser() { return user; }
        @Override public void setUser(User user) { this.user = user; }
        @Override public Integer getCurrentRoomId() { return roomId; }
        @Override public void setCurrentRoomId(Integer roomId) { this.roomId = roomId; }
        @Override public void sendTcpFrame(String frame) { frames.add(frame); }
        @Override public void sendWsEvent(String type, String rid, Object payload) { events.add(type); }
        @Override public boolean isOpen() { return true; }
        @Override public void close() { }
    }
}
