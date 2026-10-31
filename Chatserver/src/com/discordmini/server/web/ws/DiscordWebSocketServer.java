package com.discordmini.server.web.ws;

import com.discordmini.server.auth.JwtUtil;
import com.discordmini.server.dao.DirectMessageDAO;
import com.discordmini.server.dao.GuildDAO;
import com.discordmini.server.dao.MessageDAO;
import com.discordmini.server.dao.RoomDAO;
import com.discordmini.server.dao.UserDAO;
import com.discordmini.server.model.DirectConversation;
import com.discordmini.server.model.DirectMessage;
import com.discordmini.server.model.Message;
import com.discordmini.server.model.ReactionSummary;
import com.discordmini.server.model.Room;
import com.discordmini.server.model.User;
import com.discordmini.server.network.ChatServer;
import com.discordmini.server.network.ClientHandler;
import com.discordmini.server.protocol.ProtocolHandler;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.session.Session;
import com.discordmini.server.session.TransportType;
import com.discordmini.server.udp.UdpTelemetryServer;
import com.discordmini.server.web.http.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-time WebSocket Gateway (:8081) bridging Browser Web Clients with
 * Swing TCP Clients (:5000) and UDP Ephemeral Signals (:5001) via EventBus.
 */
public class DiscordWebSocketServer extends WebSocketServer {

    public static final int DEFAULT_WS_PORT = 8081;

    private final ChatServer chatServer;
    private final EventBus eventBus;
    private final UdpTelemetryServer udpServer;
    private final VoiceRoomRegistry voiceRoomRegistry = new VoiceRoomRegistry();

    private final UserDAO userDAO;
    private final RoomDAO roomDAO;
    private final MessageDAO messageDAO;
    private final DirectMessageDAO directMessageDAO;
    private final GuildDAO guildDAO;

    private final Map<WebSocket, WsSession> sessionsBySocket = new ConcurrentHashMap<>();

    public DiscordWebSocketServer(
            int wsPort,
            ChatServer chatServer,
            EventBus eventBus,
            UdpTelemetryServer udpServer) {
        super(new InetSocketAddress("0.0.0.0", wsPort > 0 ? wsPort : resolveWsPort()));
        setReuseAddr(true);
        setConnectionLostTimeout(45);
        this.chatServer = chatServer;
        this.eventBus = eventBus != null ? eventBus : EventBus.getInstance();
        this.udpServer = udpServer;

        this.userDAO = chatServer != null && chatServer.getUserDAO() != null ? chatServer.getUserDAO() : new UserDAO();
        this.roomDAO = chatServer != null && chatServer.getRoomDAO() != null ? chatServer.getRoomDAO() : new RoomDAO();
        this.messageDAO = chatServer != null && chatServer.getMessageDAO() != null ? chatServer.getMessageDAO() : new MessageDAO();
        this.directMessageDAO = new DirectMessageDAO();
        this.guildDAO = new GuildDAO();
    }

    public static int resolveWsPort() {
        String wsEnv = System.getenv("WS_PORT");
        if (wsEnv != null && !wsEnv.isBlank()) {
            try {
                return Integer.parseInt(wsEnv.trim());
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_WS_PORT;
    }

    public VoiceRoomRegistry getVoiceRoomRegistry() {
        return voiceRoomRegistry;
    }

    @Override
    public void onStart() {
        System.out.println("[WS] DiscordWebSocketServer listening on ws://0.0.0.0:" + getPort());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        WsSession session = new WsSession(conn, eventBus);
        sessionsBySocket.put(conn, session);

        String resource = handshake != null ? handshake.getResourceDescriptor() : "";
        String token = extractTokenFromResource(resource);
        if (token != null && !token.isBlank()) {
            authenticateSession(session, token, null);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        WsSession session = sessionsBySocket.remove(conn);
        if (session == null) return;

        eventBus.unregisterSession(session);
        User user = session.getUser();
        if (user != null) {
            int userId = user.getId();

            Map<Integer, List<VoiceRoomRegistry.VoiceParticipant>> affectedVoiceRooms =
                    voiceRoomRegistry.leaveAllRooms(userId, session.getSessionId());
            for (Map.Entry<Integer, List<VoiceRoomRegistry.VoiceParticipant>> entry : affectedVoiceRooms.entrySet()) {
                broadcastVoiceParticipants(entry.getKey(), entry.getValue());
            }

            // If user has no other active sessions (WS or TCP), mark offline and notify peers
            boolean stillOnline = eventBus.isUserOnline(userId)
                    || (chatServer != null && chatServer.getClientManager() != null
                    && chatServer.getClientManager().isOnline(userId));
            if (!stillOnline) {
                user.setOnline(false);
                userDAO.updateUserStatus(userId, "OFFLINE");

                String tcpOfflineFrame = "USER_OFFLINE|" + userId + "|" + ProtocolHandler.encode(user.getDisplayName());
                Map<String, Object> presencePayload = new LinkedHashMap<>();
                presencePayload.put("userId", userId);
                presencePayload.put("username", user.getUsername());
                presencePayload.put("displayName", user.getDisplayName());
                presencePayload.put("status", "OFFLINE");
                presencePayload.put("presenceStatus", "OFFLINE");
                presencePayload.put("customStatus", user.getCustomStatus());
                presencePayload.put("transport", "WS");

                eventBus.publishGlobal(tcpOfflineFrame, "PRESENCE_UPDATED", presencePayload, null);
                sendLegacyTcpGlobalIfNecessary(tcpOfflineFrame, userId);
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        WsSession session = sessionsBySocket.get(conn);
        if (session == null) {
            session = new WsSession(conn, eventBus);
            sessionsBySocket.put(conn, session);
        }

        if (message == null || message.isBlank()) {
            return;
        }

        User currentUser = session.getUser();
        if (!message.contains("\"PING_RTT\"") && !message.contains("\"PROTOCOL_FRAME_LOG\"")) {
            eventBus.recordProtocolFrame("IN", TransportType.WS, message, currentUser != null ? currentUser.getId() : null);
        }

        String rid = null;
        try {
            JsonElement parsed = JsonParser.parseString(message);
            if (!parsed.isJsonObject()) {
                session.sendError(null, "BAD_REQUEST", "WebSocket frame must be a JSON object.");
                return;
            }
            JsonObject envelope = parsed.getAsJsonObject();
            String type = JsonUtil.getString(envelope, "type", "").trim().toUpperCase(Locale.ROOT);
            rid = JsonUtil.getString(envelope, "rid", null);

            JsonObject payload = new JsonObject();
            if (envelope.has("payload") && envelope.get("payload").isJsonObject()) {
                payload = envelope.getAsJsonObject("payload");
            } else {
                payload = envelope;
            }

            if ("AUTH".equals(type) || "AUTH_TOKEN".equals(type)) {
                String token = JsonUtil.getString(payload, "token", "");
                authenticateSession(session, token, rid);
                return;
            }

            if ("PING".equals(type) || "PING_RTT".equals(type)) {
                handlePingRtt(session, payload, rid);
                return;
            }

            if (session.getUser() == null) {
                session.sendError(rid, "AUTH_REQUIRED", "WebSocket session is not authenticated.");
                return;
            }

            switch (type) {
                case "JOIN_CHANNEL", "JOIN_ROOM" -> handleJoinChannel(session, payload, rid);
                case "SEND_MESSAGE" -> handleSendMessage(session, payload, rid);
                case "EDIT_MESSAGE", "EDIT_MSG" -> handleEditMessage(session, payload, rid);
                case "DELETE_MESSAGE", "DELETE_MSG" -> handleDeleteMessage(session, payload, rid);
                case "TOGGLE_REACTION", "REACT_MSG" -> handleToggleReaction(session, payload, rid);
                case "PIN_MESSAGE", "PIN_MSG" -> handlePinMessage(session, payload, rid);
                case "MARK_READ" -> handleMarkRead(session, payload, rid);
                case "SEND_DM" -> handleSendDm(session, payload, rid);
                case "TYPING_START" -> handleTyping(session, payload, true, rid);
                case "TYPING_STOP" -> handleTyping(session, payload, false, rid);
                case "TYPING" -> handleTyping(session, payload, JsonUtil.getBoolean(payload, "isTyping", true), rid);
                case "PRESENCE_UPDATE", "PRESENCE" -> handlePresenceUpdate(session, payload, rid);
                case "VOICE_JOIN" -> handleVoiceJoin(session, payload, rid);
                case "VOICE_LEAVE" -> handleVoiceLeave(session, payload, rid);
                case "VOICE_STATE" -> handleVoiceState(session, payload, rid);
                case "SIGNAL_OFFER" -> handleWebRtcSignal(session, "SIGNAL_OFFER", payload, rid);
                case "SIGNAL_ANSWER" -> handleWebRtcSignal(session, "SIGNAL_ANSWER", payload, rid);
                case "SIGNAL_ICE" -> handleWebRtcSignal(session, "SIGNAL_ICE", payload, rid);
                default -> session.sendError(rid, "BAD_REQUEST", "Unsupported WebSocket event type: " + type);
            }
        } catch (Exception e) {
            session.sendError(rid, "INTERNAL_ERROR",
                    e.getMessage() != null ? e.getMessage() : "Failed to process WebSocket message");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (ex != null && ex.getMessage() != null && !ex.getMessage().contains("Connection reset")) {
            System.err.println("[WS ERROR] " + ex.getMessage());
        }
    }

    private boolean authenticateSession(WsSession session, String token, String rid) {
        JwtUtil.JwtClaims claims = JwtUtil.verifyToken(token);
        if (claims == null) {
            session.sendError(rid, "INVALID_TOKEN", "Invalid or expired JWT token.");
            return false;
        }

        User user = userDAO.findById(claims.userId());
        if (user == null) {
            user = new User(claims.userId(), claims.username(), "", claims.displayName());
        }

        boolean wasAlreadyOnline = eventBus.isUserOnline(user.getId());
        user.setStatus("ONLINE");
        userDAO.updateUserStatus(user.getId(), "ONLINE");

        session.setUser(user);
        eventBus.registerSession(session);

        Map<String, Object> userDto = JsonUtil.toUserDto(user, eventBus);
        session.sendWsEvent("READY", rid, Map.of("user", userDto, "sessionId", session.getSessionId()));
        session.sendAck(rid, Map.of("user", userDto));

        boolean invisible = "OFFLINE".equalsIgnoreCase(user.getPresenceStatus());
        String tcpOnlineFrame = "USER_ONLINE|" + user.getId() + "|" + ProtocolHandler.encode(user.getDisplayName());
        Map<String, Object> presencePayload = new LinkedHashMap<>();
        presencePayload.put("userId", user.getId());
        presencePayload.put("username", user.getUsername());
        presencePayload.put("displayName", user.getDisplayName());
        presencePayload.put("status", user.getPresenceStatus() != null ? user.getPresenceStatus() : "ONLINE");
        presencePayload.put("presenceStatus", user.getPresenceStatus() != null ? user.getPresenceStatus() : "ONLINE");
        presencePayload.put("customStatus", user.getCustomStatus());
        presencePayload.put("transport", "WS");

        eventBus.publishGlobal(wasAlreadyOnline || invisible ? null : tcpOnlineFrame,
                "PRESENCE_UPDATED", presencePayload, null);
        if (!wasAlreadyOnline && !invisible) {
            sendLegacyTcpGlobalIfNecessary(tcpOnlineFrame, user.getId());
        }
        return true;
    }

    private void handleJoinChannel(WsSession session, JsonObject payload, String rid) {
        int channelId = resolveChannelId(payload, session.getCurrentRoomId());
        if (channelId <= 0) {
            session.sendError(rid, "CHANNEL_NOT_FOUND", "Valid channelId is required.");
            return;
        }
        User user = session.getUser();
        if (roomDAO.getMemberRole(channelId, user.getId()) == null) {
            session.sendError(rid, "FORBIDDEN", "Channel membership is required.");
            return;
        }
        session.setCurrentRoomId(channelId);
        session.sendAck(rid, Map.of("channelId", channelId, "roomId", channelId));
    }

    private void handleSendMessage(WsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        int channelId = resolveChannelId(payload, session.getCurrentRoomId());
        if (channelId <= 0) {
            session.sendError(rid, "CHANNEL_NOT_FOUND", "channelId is required.");
            return;
        }
        if (roomDAO.getMemberRole(channelId, user.getId()) == null) {
            session.sendError(rid, "FORBIDDEN", "Channel membership is required to send messages.");
            return;
        }

        String content = JsonUtil.getString(payload, "content", "").trim();
        Integer replyToId = JsonUtil.getInteger(payload, "replyToId");
        List<Long> attachmentIds = JsonUtil.getLongList(payload, "attachmentIds");

        if (content.isEmpty() && attachmentIds.isEmpty()) {
            session.sendError(rid, "BAD_REQUEST", "Message content or attachment is required.");
            return;
        }
        if (content.length() > 5000) {
            session.sendError(rid, "PAYLOAD_TOO_LARGE", "Message exceeds 5000 characters.");
            return;
        }

        session.setCurrentRoomId(channelId);

        Message saved = messageDAO.saveRichMessage(
                channelId,
                user.getId(),
                user.getDisplayName(),
                content,
                replyToId,
                attachmentIds
        );

        if (saved == null) {
            session.sendError(rid, "DATABASE_ERROR", "Failed to save message.");
            return;
        }

        String tcpFrame = "NEW_MESSAGE|"
                + saved.getId() + "|"
                + saved.getRoomId() + "|"
                + ProtocolHandler.encode(saved.getSender()) + "|"
                + ProtocolHandler.encode(saved.getContent()) + "|"
                + ProtocolHandler.encode(saved.getTimestamp());

        Map<String, Object> msgDto = JsonUtil.toMessageDto(saved, user.getId(), "WS");
        Map<String, Object> wsPayload = new LinkedHashMap<>(msgDto);
        wsPayload.put("message", msgDto);

        Room room = roomDAO.findRoom(channelId);
        Set<Integer> memberIds = room != null ? room.getMemberIds() : Set.of();

        eventBus.publishToRoom(channelId, memberIds, tcpFrame, "MESSAGE_CREATED", wsPayload, null);
        sendLegacyTcpRoomIfNecessary(channelId, tcpFrame);
        session.sendAck(rid, wsPayload);
    }

    private void handleEditMessage(WsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        int messageId = JsonUtil.getInt(payload, "messageId", JsonUtil.getInt(payload, "id", -1));
        String content = JsonUtil.getString(payload, "content", "").trim();

        if (messageId <= 0 || content.isEmpty()) {
            session.sendError(rid, "BAD_REQUEST", "messageId and non-empty content are required.");
            return;
        }
        if (content.length() > 5000) {
            session.sendError(rid, "PAYLOAD_TOO_LARGE", "Message exceeds 5000 characters.");
            return;
        }

        Message existing = messageDAO.findById(messageId);
        if (existing == null) {
            session.sendError(rid, "MESSAGE_NOT_FOUND", "Message not found.");
            return;
        }
        if (roomDAO.getMemberRole(existing.getRoomId(), user.getId()) == null) {
            session.sendError(rid, "FORBIDDEN", "Channel membership is required to edit messages.");
            return;
        }
        if (existing.getSenderId() != user.getId()) {
            session.sendError(rid, "FORBIDDEN", "You can only edit your own messages.");
            return;
        }

        Message updated = messageDAO.editMessage(messageId, user.getId(), content);
        if (updated == null) {
            session.sendError(rid, "DATABASE_ERROR", "Failed to edit message.");
            return;
        }

        int roomId = updated.getRoomId();
        String tcpFrame = "MSG_EDITED|" + roomId + "|" + messageId + "|"
                + ProtocolHandler.encode(updated.getContent()) + "|"
                + (updated.getEditedAt() != null ? updated.getEditedAt() : Instant.now().toString());

        Map<String, Object> msgDto = JsonUtil.toMessageDto(updated, user.getId(), "WS");
        Map<String, Object> wsPayload = new LinkedHashMap<>(msgDto);
        wsPayload.put("message", msgDto);

        eventBus.publishToRoom(roomId, tcpFrame, "MESSAGE_UPDATED", wsPayload, null);
        sendLegacyTcpRoomIfNecessary(roomId, tcpFrame);
        session.sendAck(rid, wsPayload);
    }

    private void handleDeleteMessage(WsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        int messageId = JsonUtil.getInt(payload, "messageId", JsonUtil.getInt(payload, "id", -1));
        if (messageId <= 0) {
            session.sendError(rid, "BAD_REQUEST", "Valid messageId is required.");
            return;
        }

        Message existing = messageDAO.findById(messageId);
        if (existing == null) {
            session.sendError(rid, "MESSAGE_NOT_FOUND", "Message not found.");
            return;
        }

        int roomId = existing.getRoomId();
        String role = roomDAO.getMemberRole(roomId, user.getId());
        if (role == null) {
            session.sendError(rid, "FORBIDDEN", "Channel membership is required to delete messages.");
            return;
        }
        boolean isAdminOrOwner = "OWNER".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);
        if (existing.getSenderId() != user.getId() && !isAdminOrOwner) {
            session.sendError(rid, "FORBIDDEN", "Insufficient permission to delete this message.");
            return;
        }

        boolean deleted = messageDAO.deleteMessage(messageId, user.getId(), isAdminOrOwner);
        if (!deleted) {
            session.sendError(rid, "DATABASE_ERROR", "Failed to delete message.");
            return;
        }

        String deletedAt = Instant.now().toString();
        String tcpFrame = "MSG_DELETED|" + roomId + "|" + messageId;
        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("id", messageId);
        wsPayload.put("messageId", messageId);
        wsPayload.put("channelId", roomId);
        wsPayload.put("roomId", roomId);
        wsPayload.put("deletedAt", deletedAt);

        eventBus.publishToRoom(roomId, tcpFrame, "MESSAGE_DELETED", wsPayload, null);
        sendLegacyTcpRoomIfNecessary(roomId, tcpFrame);
        session.sendAck(rid, wsPayload);
    }

    private void handleToggleReaction(WsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        int messageId = JsonUtil.getInt(payload, "messageId", JsonUtil.getInt(payload, "id", -1));
        String emoji = JsonUtil.getString(payload, "emoji", "").trim();

        if (messageId <= 0 || emoji.isEmpty() || emoji.codePointCount(0, emoji.length()) > 32) {
            session.sendError(rid, "BAD_REQUEST", "messageId and emoji are required.");
            return;
        }

        Long requestedConversationId = JsonUtil.getLongObj(payload, "conversationId");
        if (requestedConversationId != null && requestedConversationId > 0) {
            handleToggleDmReaction(session, user, messageId, requestedConversationId, emoji, rid);
            return;
        }

        Message existing = messageDAO.findById(messageId);
        if (existing == null) {
            session.sendError(rid, "MESSAGE_NOT_FOUND", "Message not found.");
            return;
        }

        int roomId = existing.getRoomId();
        if (roomDAO.getMemberRole(roomId, user.getId()) == null) {
            session.sendError(rid, "FORBIDDEN", "Channel membership is required to react to messages.");
            return;
        }
        List<ReactionSummary> reactions = messageDAO.toggleReaction(messageId, user.getId(), emoji);
        if (reactions == null) {
            session.sendError(rid, "DATABASE_ERROR", "Could not update reaction.");
            return;
        }
        boolean added = false;
        List<Map<String, Object>> reactionDtos = new ArrayList<>();
        for (ReactionSummary r : reactions) {
            if (emoji.equals(r.getEmoji()) && r.getUserIds().contains(user.getId())) {
                added = true;
            }
            reactionDtos.add(JsonUtil.toReactionDto(r, user.getId()));
        }

        String tcpFrame = "MSG_REACTED|" + roomId + "|" + messageId + "|" + user.getId()
                + "|" + ProtocolHandler.encode(emoji) + "|" + (added ? "1" : "0");

        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("messageId", messageId);
        wsPayload.put("channelId", roomId);
        wsPayload.put("roomId", roomId);
        wsPayload.put("emoji", emoji);
        wsPayload.put("userId", user.getId());
        wsPayload.put("added", added);
        wsPayload.put("reactions", reactionDtos);

        eventBus.publishToRoom(roomId, tcpFrame, "MESSAGE_REACTION_UPDATED", wsPayload, null);
        sendLegacyTcpRoomIfNecessary(roomId, tcpFrame);
        session.sendAck(rid, wsPayload);
    }

    private void handleToggleDmReaction(WsSession session, User user, long messageId,
                                        long conversationId, String emoji, String rid) {
        long actualConversationId = directMessageDAO.findConversationIdForMessage(messageId);
        if (actualConversationId <= 0 || actualConversationId != conversationId) {
            session.sendError(rid, "MESSAGE_NOT_FOUND", "Direct message not found.");
            return;
        }

        DirectConversation conversation = directMessageDAO.listConversations(user.getId()).stream()
                .filter(item -> item.getId() == conversationId)
                .findFirst()
                .orElse(null);
        if (conversation == null) {
            session.sendError(rid, "FORBIDDEN", "Conversation membership is required to react to messages.");
            return;
        }

        List<ReactionSummary> reactions = directMessageDAO.toggleReaction(messageId, user.getId(), emoji);
        if (reactions == null) {
            session.sendError(rid, "DATABASE_ERROR", "Could not update reaction.");
            return;
        }

        List<Map<String, Object>> viewerReactions = new ArrayList<>();
        for (ReactionSummary reaction : reactions) {
            viewerReactions.add(JsonUtil.toReactionDto(reaction, user.getId()));
        }
        Map<String, Object> viewerPayload = new LinkedHashMap<>();
        viewerPayload.put("conversationId", conversationId);
        viewerPayload.put("messageId", messageId);
        viewerPayload.put("userId", user.getId());
        viewerPayload.put("emoji", emoji);
        viewerPayload.put("reactions", viewerReactions);

        Map<String, Object> peerPayload = new LinkedHashMap<>(viewerPayload);
        List<Map<String, Object>> peerReactions = new ArrayList<>();
        for (ReactionSummary reaction : reactions) {
            peerReactions.add(JsonUtil.toReactionDto(reaction, conversation.getPeerUserId()));
        }
        peerPayload.put("reactions", peerReactions);

        eventBus.publishToUser(user.getId(), null, "DM_REACTION_UPDATED", viewerPayload);
        eventBus.publishToUser(conversation.getPeerUserId(), null, "DM_REACTION_UPDATED", peerPayload);
        session.sendAck(rid, viewerPayload);
    }

    private void handlePinMessage(WsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        int messageId = JsonUtil.getInt(payload, "messageId", JsonUtil.getInt(payload, "id", -1));
        boolean pinned = JsonUtil.getBoolean(payload, "pinned", true);

        if (messageId <= 0) {
            session.sendError(rid, "BAD_REQUEST", "Valid messageId is required.");
            return;
        }

        Message existing = messageDAO.findById(messageId);
        if (existing == null) {
            session.sendError(rid, "MESSAGE_NOT_FOUND", "Message not found.");
            return;
        }
        if (roomDAO.getMemberRole(existing.getRoomId(), user.getId()) == null) {
            session.sendError(rid, "FORBIDDEN", "Channel membership is required to pin messages.");
            return;
        }

        Message updated = messageDAO.pinMessage(messageId, pinned);
        if (updated == null) {
            session.sendError(rid, "MESSAGE_NOT_FOUND", "Message not found.");
            return;
        }

        int roomId = updated.getRoomId();
        String tcpFrame = "MSG_PINNED|" + roomId + "|" + messageId + "|" + (pinned ? "1" : "0");
        Map<String, Object> msgDto = JsonUtil.toMessageDto(updated, user.getId(), "WS");
        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("messageId", messageId);
        wsPayload.put("channelId", roomId);
        wsPayload.put("roomId", roomId);
        wsPayload.put("pinned", pinned);
        wsPayload.put("message", msgDto);

        eventBus.publishToRoom(roomId, tcpFrame, "MESSAGE_PIN_UPDATED", wsPayload, null);
        sendLegacyTcpRoomIfNecessary(roomId, tcpFrame);
        session.sendAck(rid, wsPayload);
    }

    private void handleMarkRead(WsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        int channelId = resolveChannelId(payload, session.getCurrentRoomId());
        int lastReadMessageId = JsonUtil.getInt(payload, "lastReadMessageId",
                JsonUtil.getInt(payload, "lastMessageId", 0));

        if (channelId <= 0) {
            session.sendError(rid, "CHANNEL_NOT_FOUND", "Valid channelId is required.");
            return;
        }
        if (roomDAO.getMemberRole(channelId, user.getId()) == null) {
            session.sendError(rid, "FORBIDDEN", "Channel membership is required.");
            return;
        }

        RoomDAO.ReadResult readResult = roomDAO.markRead(user.getId(), channelId, lastReadMessageId);
        if (readResult == RoomDAO.ReadResult.INVALID_MESSAGE) {
            session.sendError(rid, "BAD_REQUEST", "Message does not belong to this channel.");
            return;
        }
        if (readResult != RoomDAO.ReadResult.UPDATED) {
            session.sendError(rid, "DATABASE_ERROR", "Could not update the read position.");
            return;
        }
        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("channelId", channelId);
        wsPayload.put("roomId", channelId);
        wsPayload.put("userId", user.getId());
        wsPayload.put("lastReadMessageId", lastReadMessageId);
        wsPayload.put("lastMessageId", lastReadMessageId);
        Integer unreadCount = roomDAO.getUnreadMessageCount(user.getId(), channelId);
        if (unreadCount != null) wsPayload.put("unreadCount", unreadCount);
        eventBus.publishToUser(user.getId(),
                "READ_UPDATED|" + channelId + "|" + user.getId() + "|" + lastReadMessageId,
                "READ_UPDATED", wsPayload);
        session.sendAck(rid, wsPayload);
    }

    private void handleSendDm(WsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        Long convId = JsonUtil.getLongObj(payload, "conversationId");
        Integer peerUserId = JsonUtil.getInteger(payload, "peerUserId");
        if (peerUserId == null) {
            peerUserId = JsonUtil.getInteger(payload, "targetUserId");
        }
        if (peerUserId == null) {
            peerUserId = JsonUtil.getInteger(payload, "receiverId");
        }

        String content = JsonUtil.getString(payload, "content", "").trim();
        Long replyToId = JsonUtil.getLongObj(payload, "replyToId");
        List<Long> attachmentIds = JsonUtil.getLongList(payload, "attachmentIds");

        if (content.isEmpty() && attachmentIds.isEmpty()) {
            session.sendError(rid, "BAD_REQUEST", "DM content or attachment is required.");
            return;
        }
        if (content.length() > 5000) {
            session.sendError(rid, "PAYLOAD_TOO_LARGE", "Message exceeds 5000 characters.");
            return;
        }

        DirectConversation targetConv = null;
        if (peerUserId != null && peerUserId > 0) {
            targetConv = directMessageDAO.getOrCreateConversation(user.getId(), peerUserId);
        } else if (convId != null && convId > 0) {
            for (DirectConversation dc : directMessageDAO.listConversations(user.getId())) {
                if (dc.getId() == convId) {
                    targetConv = dc;
                    peerUserId = dc.getPeerUserId();
                    break;
                }
            }
        }

        if (targetConv == null) {
            session.sendError(rid, "BAD_REQUEST", "Valid conversationId or peerUserId is required.");
            return;
        }

        DirectMessage saved = directMessageDAO.saveDirectMessage(
                targetConv.getId(),
                user.getId(),
                content,
                replyToId,
                attachmentIds
        );

        if (saved == null) {
            session.sendError(rid, "DATABASE_ERROR", "Failed to save direct message.");
            return;
        }

        Map<String, Object> senderDto = JsonUtil.toDirectMessageDto(saved, user.getId());
        Map<String, Object> senderPayload = new LinkedHashMap<>(senderDto);
        senderPayload.put("conversationId", targetConv.getId());
        senderPayload.put("message", senderDto);
        Map<String, Object> peerDto = JsonUtil.toDirectMessageDto(saved, peerUserId);
        Map<String, Object> peerPayload = new LinkedHashMap<>(peerDto);
        peerPayload.put("conversationId", targetConv.getId());
        peerPayload.put("message", peerDto);

        String timeStr = LocalTime.now().toString();
        if (timeStr.length() > 8) {
            timeStr = timeStr.substring(0, 8);
        }
        String tcpPrivateFrame = "NEW_PRIVATE_MESSAGE|"
                + user.getId() + "|"
                + ProtocolHandler.encode(user.getDisplayName()) + "|"
                + ProtocolHandler.encode(saved.getContent()) + "|"
                + ProtocolHandler.encode(timeStr);

        // Deliver to sender's sessions and peer's sessions
        eventBus.publishToUser(user.getId(), null, "DM_CREATED", senderPayload);
        if (peerUserId != null && peerUserId > 0 && peerUserId != user.getId()) {
            eventBus.publishToUser(peerUserId, tcpPrivateFrame, "DM_CREATED", peerPayload);
            if (chatServer != null && chatServer.getClientManager() != null
                    && eventBus.getSessionsByUser(peerUserId).stream().noneMatch(s -> s.getTransport() == TransportType.TCP)) {
                chatServer.getClientManager().sendToUser(peerUserId, tcpPrivateFrame);
            }
        }
        session.sendAck(rid, senderPayload);
    }

    private void handleTyping(WsSession session, JsonObject payload, boolean isTyping, String rid) {
        User user = session.getUser();
        Integer channelId = JsonUtil.getInteger(payload, "channelId");
        if (channelId == null) {
            channelId = JsonUtil.getInteger(payload, "roomId");
        }
        Long conversationId = JsonUtil.getLongObj(payload, "conversationId");

        boolean sentViaUdp = false;
        if (channelId != null && channelId > 0 && udpServer != null && udpServer.isUdpActive()) {
            if (roomDAO.getMemberRole(channelId, user.getId()) == null) {
                session.sendError(rid, "FORBIDDEN", "Channel membership is required to send typing updates.");
                return;
            }
            sentViaUdp = sendLoopbackUdpTyping(user.getId(), channelId, isTyping, user.getDisplayName());
        }

        if (!sentViaUdp) {
            Map<String, Object> wsPayload = new LinkedHashMap<>();
            wsPayload.put("channelId", channelId);
            wsPayload.put("roomId", channelId);
            wsPayload.put("conversationId", conversationId);
            wsPayload.put("userId", user.getId());
            wsPayload.put("username", user.getUsername());
            wsPayload.put("displayName", user.getDisplayName());
            wsPayload.put("isTyping", isTyping);
            wsPayload.put("transport", "WS");

            if (channelId != null && channelId > 0) {
                Room room = roomDAO.findRoom(channelId);
                if (room == null || !room.getMemberIds().contains(user.getId())) {
                    session.sendError(rid, "FORBIDDEN", "Channel membership is required to send typing updates.");
                    return;
                }
                String tcpFrame = "TYPING_UPDATE|" + channelId + "|" + user.getId()
                        + "|" + (isTyping ? "1" : "0") + "|" + ProtocolHandler.encode(user.getDisplayName());
                eventBus.publishToRoom(channelId, room.getMemberIds(), tcpFrame, "TYPING_UPDATE", wsPayload, user.getId());
            } else if (conversationId != null && conversationId > 0) {
                for (DirectConversation dc : directMessageDAO.listConversations(user.getId())) {
                    if (dc.getId() == conversationId) {
                        eventBus.publishToUser(dc.getPeerUserId(), null, "TYPING_UPDATE", wsPayload);
                        break;
                    }
                }
            }
        }
        session.sendAck(rid, Map.of("isTyping", isTyping, "transport", sentViaUdp ? "UDP" : "WS"));
    }

    private boolean sendLoopbackUdpTyping(int userId, int roomId, boolean isTyping, String displayName) {
        if (udpServer == null || !udpServer.isUdpActive()) return false;
        try (DatagramSocket sock = new DatagramSocket()) {
            String frame = "UDP_TYPING|" + userId + "|" + roomId + "|" + (isTyping ? "1" : "0")
                    + "|" + ProtocolHandler.encode(displayName != null ? displayName : "");
            byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(
                    bytes, bytes.length, InetAddress.getByName("127.0.0.1"), udpServer.getUdpPort()
            );
            sock.send(pkt);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void handlePresenceUpdate(WsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        String status = JsonUtil.getString(payload, "status",
                JsonUtil.getString(payload, "presenceStatus", "ONLINE")).trim().toUpperCase(Locale.ROOT);
        if (!"ONLINE".equals(status) && !"IDLE".equals(status) && !"DND".equals(status) && !"OFFLINE".equals(status)) {
            status = "ONLINE";
        }
        String customStatus = JsonUtil.getString(payload, "customStatus", user.getCustomStatus());
        if (customStatus != null && customStatus.length() > 120) {
            customStatus = customStatus.substring(0, 120);
        }
        if (!userDAO.updatePresence(user.getId(), status, customStatus)) {
            session.sendError(rid, "DATABASE_ERROR", "Could not save presence settings.");
            return;
        }
        user.setPresenceStatus(status);
        user.setCustomStatus(customStatus);
        user.setStatus("OFFLINE".equals(status) ? "OFFLINE" : "ONLINE");

        String tcpFrame = "PRESENCE_UPDATED|" + user.getId() + "|" + status;
        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("userId", user.getId());
        wsPayload.put("username", user.getUsername());
        wsPayload.put("displayName", user.getDisplayName());
        wsPayload.put("status", status);
        wsPayload.put("presenceStatus", status);
        wsPayload.put("customStatus", customStatus);
        wsPayload.put("transport", "WS");

        eventBus.publishGlobal(tcpFrame, "PRESENCE_UPDATED", wsPayload, null);
        session.sendAck(rid, wsPayload);
    }

    private void handlePingRtt(WsSession session, JsonObject payload, String rid) {
        int seq = JsonUtil.getInt(payload, "seq", 1);
        long clientTs = JsonUtil.getLong(payload, "clientTimestampMs", System.currentTimeMillis());
        long serverTs = System.currentTimeMillis();
        User u = session.getUser();
        int userId = u != null ? u.getId() : 0;

        long udpMicros = -1L;
        if (udpServer != null && udpServer.isUdpActive()) {
            udpMicros = udpServer.probeLocalUdpRttMicros(seq, userId);
        }
        boolean udpActive = udpMicros >= 0;
        Double udpRttMs = udpActive ? Math.round(udpMicros / 10.0) / 100.0 : null;

        Map<String, Object> pongPayload = new LinkedHashMap<>();
        pongPayload.put("seq", seq);
        pongPayload.put("clientTimestampMs", clientTs);
        pongPayload.put("serverTimestampMs", serverTs);
        pongPayload.put("udpRttMs", udpRttMs);
        pongPayload.put("udpActive", udpActive);
        pongPayload.put("transport", udpActive ? "UDP" : "WS");

        session.sendWsEvent("PONG_RTT", rid, pongPayload);
    }

    private void handleVoiceJoin(WsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        int channelId = resolveChannelId(payload, session.getCurrentRoomId());
        if (channelId <= 0) {
            session.sendError(rid, "CHANNEL_NOT_FOUND", "Valid channelId is required to join voice.");
            return;
        }
        if (roomDAO.getMemberRole(channelId, user.getId()) == null) {
            session.sendError(rid, "FORBIDDEN", "Channel membership is required to join voice.");
            return;
        }
        boolean muted = JsonUtil.getBoolean(payload, "muted", false);
        boolean cameraOn = JsonUtil.getBoolean(payload, "cameraOn", false);

        try {
            List<VoiceRoomRegistry.VoiceParticipant> participants =
                    voiceRoomRegistry.joinRoom(channelId, user, session.getSessionId(), muted, cameraOn);
            broadcastVoiceParticipants(channelId, participants);
            session.sendAck(rid, Map.of("channelId", channelId, "participants", participants));
        } catch (IllegalStateException ex) {
            session.sendError(rid, "FORBIDDEN", ex.getMessage());
        }
    }

    private void handleVoiceLeave(WsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        int channelId = resolveChannelId(payload, -1);
        if (channelId > 0) {
            List<VoiceRoomRegistry.VoiceParticipant> participants =
                    voiceRoomRegistry.leaveRoom(channelId, user.getId(), session.getSessionId());
            broadcastVoiceParticipants(channelId, participants);
        } else {
            Map<Integer, List<VoiceRoomRegistry.VoiceParticipant>> updated =
                    voiceRoomRegistry.leaveAllRooms(user.getId(), session.getSessionId());
            for (Map.Entry<Integer, List<VoiceRoomRegistry.VoiceParticipant>> entry : updated.entrySet()) {
                broadcastVoiceParticipants(entry.getKey(), entry.getValue());
            }
        }
        session.sendAck(rid, Map.of("channelId", channelId));
    }

    private void handleVoiceState(WsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        int channelId = resolveChannelId(payload, -1);
        if (channelId <= 0) {
            session.sendError(rid, "CHANNEL_NOT_FOUND", "Valid channelId is required.");
            return;
        }
        if (roomDAO.getMemberRole(channelId, user.getId()) == null) {
            List<VoiceRoomRegistry.VoiceParticipant> participants = voiceRoomRegistry.leaveRoom(channelId, user.getId(), session.getSessionId());
            broadcastVoiceParticipants(channelId, participants);
            session.sendError(rid, "FORBIDDEN", "Channel membership is required to change voice state.");
            return;
        }
        if (voiceRoomRegistry.getParticipants(channelId).stream().noneMatch(p -> p.getUserId() == user.getId()
                && session.getSessionId().equals(p.getSessionId()))) {
            session.sendError(rid, "FORBIDDEN", "Join the voice channel before changing your voice state.");
            return;
        }
        boolean muted = JsonUtil.getBoolean(payload, "muted", false);
        boolean cameraOn = JsonUtil.getBoolean(payload, "cameraOn", false);

        List<VoiceRoomRegistry.VoiceParticipant> participants =
                voiceRoomRegistry.updateState(channelId, user.getId(), session.getSessionId(), muted, cameraOn);
        broadcastVoiceParticipants(channelId, participants);
        session.sendAck(rid, Map.of("channelId", channelId, "participants", participants));
    }

    private void broadcastVoiceParticipants(int channelId, List<VoiceRoomRegistry.VoiceParticipant> participants) {
        Room room = roomDAO.findRoom(channelId);
        if (room == null) return;
        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("channelId", channelId);
        wsPayload.put("roomId", channelId);
        wsPayload.put("participants", participants);
        eventBus.publishToRoom(channelId, room.getMemberIds(), null, "VOICE_PARTICIPANTS", wsPayload, null);
    }

    private void handleWebRtcSignal(WsSession session, String signalType, JsonObject payload, String rid) {
        User sender = session.getUser();
        int channelId = resolveChannelId(payload, session.getCurrentRoomId());
        Integer targetUserId = JsonUtil.getInteger(payload, "targetUserId");
        if (targetUserId == null) {
            targetUserId = JsonUtil.getInteger(payload, "toUserId");
        }
        if (targetUserId == null || targetUserId <= 0) {
            session.sendError(rid, "BAD_REQUEST", "targetUserId is required for WebRTC signaling.");
            return;
        }
        if (channelId <= 0) {
            session.sendError(rid, "BAD_REQUEST", "channelId is required for WebRTC signaling.");
            return;
        }
        int resolvedTargetUserId = targetUserId;
        if (roomDAO.getMemberRole(channelId, sender.getId()) == null
                || roomDAO.getMemberRole(channelId, resolvedTargetUserId) == null) {
            session.sendError(rid, "FORBIDDEN", "Both users must still be channel members to exchange WebRTC signaling.");
            return;
        }
        List<VoiceRoomRegistry.VoiceParticipant> participants = voiceRoomRegistry.getParticipants(channelId);
        boolean senderJoined = participants.stream().anyMatch(participant -> participant.getUserId() == sender.getId());
        boolean targetJoined = participants.stream().anyMatch(participant -> participant.getUserId() == resolvedTargetUserId);
        if (!senderJoined || !targetJoined) {
            session.sendError(rid, "FORBIDDEN", "Both users must be in the voice channel to exchange WebRTC signaling.");
            return;
        }

        Map<String, Object> relayPayload = new LinkedHashMap<>();
        relayPayload.put("channelId", channelId);
        relayPayload.put("roomId", channelId);
        relayPayload.put("fromUserId", sender.getId());
        relayPayload.put("fromUsername", sender.getUsername());
        relayPayload.put("fromDisplayName", sender.getDisplayName());
        relayPayload.put("targetUserId", targetUserId);

        if (payload.has("sdp") && !payload.get("sdp").isJsonNull()) {
            relayPayload.put("sdp", payload.get("sdp"));
        }
        if (payload.has("candidate") && !payload.get("candidate").isJsonNull()) {
            relayPayload.put("candidate", payload.get("candidate"));
        }

        eventBus.publishToUser(targetUserId, null, signalType, relayPayload);
        session.sendAck(rid, Map.of("targetUserId", targetUserId, "type", signalType));
    }

    private void sendLegacyTcpRoomIfNecessary(int roomId, String tcpFrame) {
        if (chatServer == null || chatServer.getRoomManager() == null || tcpFrame == null) return;
        boolean hasEventBusTcp = false;
        for (Session s : eventBus.getAllSessions()) {
            if (s.getTransport() == TransportType.TCP) {
                hasEventBusTcp = true;
                break;
            }
        }
        if (!hasEventBusTcp) {
            for (ClientHandler client : chatServer.getRoomManager().clientsIn(roomId)) {
                try {
                    client.sendPacket(tcpFrame);
                } catch (Exception ignored) {}
            }
        }
    }

    private void sendLegacyTcpGlobalIfNecessary(String tcpFrame, int excludeUserId) {
        if (chatServer == null || chatServer.getClientManager() == null || tcpFrame == null) return;
        boolean hasEventBusTcp = false;
        for (Session s : eventBus.getAllSessions()) {
            if (s.getTransport() == TransportType.TCP) {
                hasEventBusTcp = true;
                break;
            }
        }
        if (!hasEventBusTcp) {
            for (ClientHandler client : chatServer.getClientManager().all()) {
                try {
                    User u = client.getUser();
                    if (u != null && u.getId() == excludeUserId) continue;
                    client.sendPacket(tcpFrame);
                } catch (Exception ignored) {}
            }
        }
    }

    private static int resolveChannelId(JsonObject payload, Integer fallback) {
        Integer cid = JsonUtil.getInteger(payload, "channelId");
        if (cid == null || cid <= 0) {
            cid = JsonUtil.getInteger(payload, "roomId");
        }
        if ((cid == null || cid <= 0) && fallback != null && fallback > 0) {
            cid = fallback;
        }
        return cid != null ? cid : -1;
    }

    private static String extractTokenFromResource(String resourceDescriptor) {
        if (resourceDescriptor == null || !resourceDescriptor.contains("?")) {
            return null;
        }
        String query = resourceDescriptor.substring(resourceDescriptor.indexOf('?') + 1);
        Map<String, String> params = JsonUtil.parseQueryString(query);
        return params.get("token");
    }
}
