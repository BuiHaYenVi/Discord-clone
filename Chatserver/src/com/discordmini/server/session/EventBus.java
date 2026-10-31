package com.discordmini.server.session;

import com.discordmini.server.model.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified thread-safe EventBus and multi-transport Session Registry (TCP, WS, UDP, HTTP).
 * Supports multi-session per user (e.g. simultaneous Swing TCP client + Browser WebSocket tabs)
 * and live protocol frame telemetry ring buffer for the Network Monitor Drawer.
 */
public class EventBus {
    private static final int MAX_RECENT_FRAMES = 200;
    private static volatile EventBus INSTANCE = new EventBus();

    // Shared static backing stores so any EventBus instance or getInstance() sees the unified state
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<ServerEvent.FrameRecord> FRAME_BUFFER = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger FRAME_COUNT = new AtomicInteger(0);

    public EventBus() {
        INSTANCE = this;
    }

    public static EventBus getInstance() {
        return INSTANCE;
    }

    public void registerSession(Session session) {
        if (session == null || session.getSessionId() == null) return;
        SESSIONS.put(session.getSessionId(), session);
    }

    public void unregisterSession(Session session) {
        if (session == null || session.getSessionId() == null) return;
        SESSIONS.remove(session.getSessionId());
    }

    public void unregisterSession(String sessionId) {
        if (sessionId == null) return;
        SESSIONS.remove(sessionId);
    }

    public List<Session> getAllSessions() {
        List<Session> list = new ArrayList<>();
        for (Session s : SESSIONS.values()) {
            if (s != null && s.isOpen()) {
                list.add(s);
            }
        }
        return list;
    }

    public int getActiveSessionCount() {
        return getAllSessions().size();
    }

    public List<Session> getSessionsByUser(int userId) {
        List<Session> list = new ArrayList<>();
        for (Session s : SESSIONS.values()) {
            User u = s.getUser();
            if (u != null && u.getId() == userId && s.isOpen()) {
                list.add(s);
            }
        }
        return list;
    }

    /** Remove every live session for a user from a channel after access is revoked. */
    public void clearCurrentRoomForUser(int userId, int roomId) {
        for (Session session : SESSIONS.values()) {
            User user = session != null ? session.getUser() : null;
            if (user != null && user.getId() == userId
                    && Objects.equals(session.getCurrentRoomId(), roomId)) {
                session.setCurrentRoomId(null);
            }
        }
    }

    public List<User> getOnlineUsers() {
        Map<Integer, User> uniqueUsers = new LinkedHashMap<>();
        for (Session s : SESSIONS.values()) {
            User u = s.getUser();
            if (u != null && s.isOpen()) {
                uniqueUsers.putIfAbsent(u.getId(), u);
            }
        }
        return new ArrayList<>(uniqueUsers.values());
    }

    public boolean isUserOnline(int userId) {
        for (Session s : SESSIONS.values()) {
            User u = s.getUser();
            if (u != null && u.getId() == userId && s.isOpen()) {
                return true;
            }
        }
        return false;
    }

    public void publishToRoom(
            int roomId,
            String tcpFrame,
            String wsEventType,
            Object wsPayload,
            Integer excludeUserId) {
        publishToRoom(roomId, Collections.emptySet(), tcpFrame, wsEventType, wsPayload, excludeUserId);
    }

    public void publishToRoom(
            int roomId,
            Set<Integer> roomMemberIds,
            String tcpFrame,
            String wsEventType,
            Object wsPayload,
            Integer excludeUserId) {
        for (Session s : SESSIONS.values()) {
            if (s == null || !s.isOpen()) continue;
            User u = s.getUser();
            if (u == null) continue;
            if (excludeUserId != null && u.getId() == excludeUserId) continue;

            if (s.getTransport() == TransportType.TCP) {
                boolean inActiveRoom = Objects.equals(s.getCurrentRoomId(), roomId);
                boolean isMemberForGuildEvent = (roomId <= 0 && roomMemberIds != null && roomMemberIds.contains(u.getId()));
                if ((inActiveRoom || isMemberForGuildEvent) && tcpFrame != null && !tcpFrame.isBlank()) {
                    try {
                        s.sendTcpFrame(tcpFrame);
                        String legacy = legacyMessageFrame(tcpFrame);
                        if (legacy != null) s.sendTcpFrame(legacy);
                    } catch (Exception ignored) {}
                }
            } else if (s.getTransport() == TransportType.WS) {
                boolean shouldReceive = (roomMemberIds != null && roomMemberIds.contains(u.getId()))
                        || Objects.equals(s.getCurrentRoomId(), roomId);
                if (shouldReceive && wsEventType != null && !wsEventType.isBlank()) {
                    try {
                        s.sendWsEvent(wsEventType, null, wsPayload);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private static String legacyMessageFrame(String frame) {
        if (frame.startsWith("MSG_EDITED|")) {
            String[] fields = frame.split("\\|", 5);
            if (fields.length >= 4) return "MESSAGE_EDITED|" + fields[1] + "|" + fields[2] + "|" + fields[3];
        } else if (frame.startsWith("MSG_DELETED|")) {
            return "MESSAGE_DELETED|" + frame.substring("MSG_DELETED|".length());
        }
        return null;
    }

    public void publishToUser(
            int targetUserId,
            String tcpFrame,
            String wsEventType,
            Object wsPayload) {
        for (Session s : SESSIONS.values()) {
            if (s == null || !s.isOpen()) continue;
            User u = s.getUser();
            if (u == null || u.getId() != targetUserId) continue;

            if (s.getTransport() == TransportType.TCP) {
                if (tcpFrame != null && !tcpFrame.isBlank()) {
                    try {
                        s.sendTcpFrame(tcpFrame);
                    } catch (Exception ignored) {}
                }
            } else if (s.getTransport() == TransportType.WS) {
                if (wsEventType != null && !wsEventType.isBlank()) {
                    try {
                        s.sendWsEvent(wsEventType, null, wsPayload);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    public void publishGlobal(
            String tcpFrame,
            String wsEventType,
            Object wsPayload,
            Integer excludeUserId) {
        for (Session s : SESSIONS.values()) {
            if (s == null || !s.isOpen()) continue;
            User u = s.getUser();
            if (u != null && excludeUserId != null && u.getId() == excludeUserId) continue;

            if (s.getTransport() == TransportType.TCP) {
                if (tcpFrame != null && !tcpFrame.isBlank()) {
                    try {
                        s.sendTcpFrame(tcpFrame);
                    } catch (Exception ignored) {}
                }
            } else if (s.getTransport() == TransportType.WS) {
                if (wsEventType != null && !wsEventType.isBlank()) {
                    try {
                        s.sendWsEvent(wsEventType, null, wsPayload);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    public void recordProtocolFrame(
            String direction,
            TransportType transport,
            String rawFrame,
            Integer userId) {
        if (rawFrame == null || rawFrame.contains("PROTOCOL_FRAME_LOG")) {
            return;
        }
        String transportName = transport != null ? transport.name() : "TCP";
        recordProtocolFrame(direction, transportName, rawFrame, userId);
    }

    public void recordProtocolFrame(
            String direction,
            String transport,
            String rawFrame,
            Integer userId) {
        if (rawFrame == null || rawFrame.contains("PROTOCOL_FRAME_LOG")) {
            return;
        }
        // Protocol telemetry is visible to every connected monitor client. Keep only the
        // command/type marker; payloads can contain credentials, DMs, or private content.
        String cleanFrame = safeFrameLabel(rawFrame);
        ServerEvent.FrameRecord record = new ServerEvent.FrameRecord(
                direction != null ? direction : "IN",
                transport != null ? transport : "TCP",
                cleanFrame,
                userId,
                System.currentTimeMillis()
        );

        synchronized (FRAME_BUFFER) {
            FRAME_BUFFER.addLast(record);
            while (FRAME_BUFFER.size() > MAX_RECENT_FRAMES) {
                FRAME_BUFFER.pollFirst();
            }
            FRAME_COUNT.set(FRAME_BUFFER.size());
        }

        // Stream live frame telemetry to open WebSocket sessions for the Network Monitor Drawer
        for (Session s : SESSIONS.values()) {
            if (s != null && s.isOpen() && s.getTransport() == TransportType.WS && s.getUser() != null) {
                try {
                    s.sendWsEvent("PROTOCOL_FRAME_LOG", null, record);
                } catch (Exception ignored) {}
            }
        }
    }

    private static String safeFrameLabel(String rawFrame) {
        String candidate = rawFrame.trim();
        if (candidate.startsWith("{")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\\\"(?:type|command)\\\"\\s*:\\s*\\\"([^\\\"]{1,64})\\\"")
                    .matcher(candidate);
            if (matcher.find()) return matcher.group(1) + "|[REDACTED]";
            return "JSON_FRAME|[REDACTED]";
        }
        int separator = candidate.indexOf('|');
        String label = separator >= 0 ? candidate.substring(0, separator) : candidate;
        if (!label.matches("[A-Z][A-Z0-9_]{0,63}")) label = "FRAME";
        return label + "|[REDACTED]";
    }

    public List<ServerEvent.FrameRecord> getRecentFrames(int limit) {
        int max = limit <= 0 ? 50 : Math.min(limit, MAX_RECENT_FRAMES);
        List<ServerEvent.FrameRecord> all = new ArrayList<>(FRAME_BUFFER);
        if (all.size() <= max) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - max, all.size()));
    }

    public List<ServerEvent.FrameRecord> getRecentFrames() {
        return getRecentFrames(100);
    }
}
