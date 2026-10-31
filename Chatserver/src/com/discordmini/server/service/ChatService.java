package com.discordmini.server.service;

import com.discordmini.server.dao.MessageDAO;
import com.discordmini.server.dao.RoomDAO;
import com.discordmini.server.model.Message;
import com.discordmini.server.model.ReactionSummary;
import com.discordmini.server.model.Room;
import com.discordmini.server.model.User;
import com.discordmini.server.protocol.ProtocolHandler;
import com.discordmini.server.session.EventBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service xử lý tin nhắn kết hợp ChatPersistenceService và EventBus để đảm bảo
 * tin nhắn được lưu vào PostgreSQL trước khi được gửi tới cả TCP Client và WebSocket Client.
 */
public class ChatService {
    private final MessageDAO messageDAO;
    private final RoomDAO roomDAO;
    private final ChatPersistenceService persistenceService;
    private final EventBus eventBus;

    public ChatService() {
        this(new MessageDAO(), new RoomDAO(), EventBus.getInstance());
    }

    public ChatService(MessageDAO messageDAO) {
        this(messageDAO, new RoomDAO(), EventBus.getInstance());
    }

    public ChatService(MessageDAO messageDAO, RoomDAO roomDAO, EventBus eventBus) {
        this.messageDAO = messageDAO != null ? messageDAO : new MessageDAO();
        this.roomDAO = roomDAO != null ? roomDAO : new RoomDAO();
        this.persistenceService = new ChatPersistenceService(this.messageDAO);
        this.eventBus = eventBus != null ? eventBus : EventBus.getInstance();
    }

    /**
     * [HAI-06] Ghi tin nhắn vào PostgreSQL đồng bộ trước khi cho phép broadcast.
     */
    public Message save(int roomId, User user, String content) throws Exception {
        if (user == null) {
            throw new IllegalArgumentException("User không hợp lệ.");
        }
        return persistenceService.persistBeforeBroadcast(roomId, user.getId(), user.getDisplayName(), content);
    }

    /**
     * Lưu tin nhắn (hỗ trợ reply và attachments) và phát tán đồng thời tới cả TCP và WebSocket qua EventBus.
     */
    public Message sendMessage(int roomId, User user, String content, Integer replyToId, List<Long> attachmentIds) throws Exception {
        if (user == null) {
            throw new IllegalArgumentException("User không hợp lệ.");
        }
        return sendMessage(roomId, user.getId(), user.getDisplayName(), content, replyToId, attachmentIds);
    }

    public Message sendMessage(int roomId, int senderId, String senderDisplayName,
                               String content, Integer replyToId, List<Long> attachmentIds) throws Exception {
        Message saved = messageDAO.saveRichMessage(roomId, senderId, senderDisplayName, content, replyToId, attachmentIds);
        if (saved == null || saved.getId() <= 0) {
            throw new Exception("Gửi tin nhắn thất bại, lỗi lưu trữ CSDL.");
        }
        broadcastNewMessage(saved, true);
        return saved;
    }

    /**
     * Phát tán tin nhắn mới tới EventBus.
     * @param includeTcp true nếu phát từ Web/REST/WS cần đẩy cả frame TCP tới các TcpSession đang ở trong phòng.
     */
    public void broadcastNewMessage(Message message, boolean includeTcp) {
        if (message == null) return;
        String tcpFrame = null;
        if (includeTcp) {
            tcpFrame = "NEW_MESSAGE|"
                    + message.getId() + "|"
                    + message.getRoomId() + "|"
                    + ProtocolHandler.encode(message.getSender()) + "|"
                    + ProtocolHandler.encode(message.getContent()) + "|"
                    + ProtocolHandler.encode(message.getTimestamp());
        }
        Set<Integer> memberIds = resolveRoomMembers(message.getRoomId());
        eventBus.publishToRoom(message.getRoomId(), memberIds, tcpFrame, "MESSAGE_CREATED", message, null);
    }

    /**
     * [HAI-07] Truy vấn lịch sử tin nhắn của phòng.
     */
    public List<Message> history(int roomId) {
        try {
            return persistenceService.fetchHistory(roomId, 100);
        } catch (Exception e) {
            System.err.println("[ChatService ERROR] Lỗi lấy lịch sử chat: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<Message> getMessagesCursor(int roomId, Integer beforeId, int limit) {
        try {
            return messageDAO.getMessagesCursor(roomId, beforeId, limit);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public Message findById(int messageId) {
        return messageDAO.findById(messageId);
    }

    public Message editMessage(int messageId, int actorUserId, String newContent) {
        Message updated = messageDAO.editMessage(messageId, actorUserId, newContent);
        if (updated != null) {
            String editedAt = updated.getEditedAt() != null ? updated.getEditedAt() : updated.getTimestamp();
            String tcpFrame = "MSG_EDITED|"
                    + updated.getRoomId() + "|"
                    + updated.getId() + "|"
                    + ProtocolHandler.encode(updated.getContent()) + "|"
                    + editedAt;
            Set<Integer> memberIds = resolveRoomMembers(updated.getRoomId());
            eventBus.publishToRoom(updated.getRoomId(), memberIds, tcpFrame, "MESSAGE_UPDATED", updated, null);
        }
        return updated;
    }

    public boolean deleteMessage(int messageId, int actorUserId, boolean isAdminOrOwner) {
        Message existing = messageDAO.findById(messageId);
        if (existing == null) return false;
        boolean deleted = messageDAO.deleteMessage(messageId, actorUserId, isAdminOrOwner);
        if (deleted) {
            int roomId = existing.getRoomId();
            String tcpFrame = "MSG_DELETED|" + roomId + "|" + messageId;
            Map<String, Object> wsPayload = new LinkedHashMap<>();
            wsPayload.put("id", messageId);
            wsPayload.put("messageId", messageId);
            wsPayload.put("roomId", roomId);
            wsPayload.put("channelId", roomId);
            Set<Integer> memberIds = resolveRoomMembers(roomId);
            eventBus.publishToRoom(roomId, memberIds, tcpFrame, "MESSAGE_DELETED", wsPayload, null);
        }
        return deleted;
    }

    public List<ReactionSummary> toggleReaction(int messageId, int userId, String emoji) {
        Message existing = messageDAO.findById(messageId);
        if (existing == null) return Collections.emptyList();
        List<ReactionSummary> summaries = messageDAO.toggleReaction(messageId, userId, emoji);

        boolean added = false;
        String cleanEmoji = emoji != null ? emoji.trim() : "";
        for (ReactionSummary s : summaries) {
            if (cleanEmoji.equals(s.getEmoji()) && s.getUserIds().contains(userId)) {
                added = true;
                break;
            }
        }

        int roomId = existing.getRoomId();
        String tcpFrame = "MSG_REACTED|"
                + roomId + "|"
                + messageId + "|"
                + userId + "|"
                + ProtocolHandler.encode(cleanEmoji) + "|"
                + (added ? "1" : "0");

        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("roomId", roomId);
        wsPayload.put("messageId", messageId);
        wsPayload.put("userId", userId);
        wsPayload.put("emoji", cleanEmoji);
        wsPayload.put("added", added);
        wsPayload.put("reactions", summaries);

        Set<Integer> memberIds = resolveRoomMembers(roomId);
        eventBus.publishToRoom(roomId, memberIds, tcpFrame, "REACTION_UPDATED", wsPayload, null);
        return summaries;
    }

    public Message pinMessage(int messageId, boolean pinned) {
        Message updated = messageDAO.pinMessage(messageId, pinned);
        if (updated != null) {
            int roomId = updated.getRoomId();
            String tcpFrame = "MSG_PINNED|" + roomId + "|" + messageId + "|" + (pinned ? "1" : "0");
            Set<Integer> memberIds = resolveRoomMembers(roomId);
            eventBus.publishToRoom(roomId, memberIds, tcpFrame, "MESSAGE_PINNED", updated, null);
        }
        return updated;
    }

    public List<Message> getPinnedMessages(int roomId) {
        return messageDAO.getPinnedMessages(roomId);
    }

    public List<Message> searchMessages(int userId, Integer guildId, Integer roomId, String query, int limit) {
        return messageDAO.searchMessages(userId, guildId, roomId, query, limit);
    }

    private Set<Integer> resolveRoomMembers(int roomId) {
        try {
            Room room = roomDAO.findRoom(roomId);
            if (room != null && room.getMemberIds() != null) {
                return room.getMemberIds();
            }
        } catch (Exception ignored) {}
        return Collections.emptySet();
    }

    public ChatPersistenceService getPersistenceService() {
        return persistenceService;
    }

    public MessageDAO getMessageDAO() {
        return messageDAO;
    }

    public List<Message> historyBefore(int roomId, int beforeMessageId, int limit) {
        return getMessagesCursor(roomId, beforeMessageId, limit);
    }

    public Message getMessageById(int messageId) {
        return findById(messageId);
    }
}
