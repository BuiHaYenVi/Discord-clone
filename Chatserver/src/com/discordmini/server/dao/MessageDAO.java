package com.discordmini.server.dao;

import com.discordmini.server.model.Attachment;
import com.discordmini.server.model.Message;
import com.discordmini.server.model.ReactionSummary;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * [HAI-05 & HAI-07] Message DAO: Ghi nhận tin nhắn, phản hồi (reply), chỉnh sửa,
 * xóa mềm, ghim (pin), cảm xúc (reactions), tệp đính kèm và tìm kiếm toàn văn (FTS).
 */
public class MessageDAO {

    private final AttachmentDAO attachmentDAO = new AttachmentDAO();

    private Message mapRichRow(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        int roomId = rs.getInt("room_id");
        int senderId = rs.getInt("sender_id");
        String displayName = rs.getString("display_name");
        String content = rs.getString("content");
        Timestamp sentTs = rs.getTimestamp("sent_at");
        String sentStr = sentTs != null ? sentTs.toString() : "";

        Message msg = new Message(id, roomId, senderId,
                displayName != null ? displayName : ("User#" + senderId),
                content != null ? content : "",
                sentStr);

        try {
            Object replyObj = rs.getObject("reply_to_id");
            if (replyObj != null) {
                msg.setReplyToId(rs.getInt("reply_to_id"));
                msg.setReplyToSender(rs.getString("reply_sender_name"));
                msg.setReplyToContent(rs.getString("reply_content"));
            }
        } catch (SQLException ignored) {
        }

        try {
            Timestamp editedTs = rs.getTimestamp("edited_at");
            if (editedTs != null) {
                msg.setEditedAt(editedTs.toString());
            }
        } catch (SQLException ignored) {
        }

        try {
            Timestamp deletedTs = rs.getTimestamp("deleted_at");
            if (deletedTs != null) {
                msg.setDeletedAt(deletedTs.toString());
            }
        } catch (SQLException ignored) {
        }

        try {
            msg.setPinned(rs.getBoolean("pinned"));
        } catch (SQLException ignored) {
        }

        return msg;
    }

    private void hydrateReactionsAndAttachments(Connection conn, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;

        Map<Integer, Message> byId = new LinkedHashMap<>();
        StringBuilder placeholders = new StringBuilder();
        for (Message m : messages) {
            byId.put(m.getId(), m);
            if (placeholders.length() > 0) placeholders.append(",");
            placeholders.append("?");
        }

        // 1. Nạp reactions theo batch
        String reactionSql = "SELECT message_id, reaction, user_id FROM message_reactions " +
                             "WHERE message_id IN (" + placeholders + ") ORDER BY created_at ASC, user_id ASC";
        try (PreparedStatement ps = conn.prepareStatement(reactionSql)) {
            int idx = 1;
            for (Integer id : byId.keySet()) {
                ps.setInt(idx++, id);
            }
            Map<Integer, Map<String, ReactionSummary>> grouped = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int msgId = rs.getInt("message_id");
                    String emoji = rs.getString("reaction");
                    int uid = rs.getInt("user_id");
                    Map<String, ReactionSummary> emojiMap = grouped.computeIfAbsent(msgId, k -> new LinkedHashMap<>());
                    ReactionSummary summary = emojiMap.computeIfAbsent(emoji, e -> new ReactionSummary(e, 0, new ArrayList<>()));
                    summary.getUserIds().add(uid);
                    summary.setCount(summary.getUserIds().size());
                }
            }
            for (Map.Entry<Integer, Map<String, ReactionSummary>> entry : grouped.entrySet()) {
                Message target = byId.get(entry.getKey());
                if (target != null) {
                    target.setReactions(new ArrayList<>(entry.getValue().values()));
                }
            }
        } catch (SQLException ignored) {
        }

        // 2. Nạp attachments metadata theo batch
        String attSql = "SELECT id, uploader_id, room_id, direct_conversation_id, message_id, " +
                        "original_name, stored_name, content_type, size_bytes, sha256, created_at " +
                        "FROM attachments WHERE message_id IN (" + placeholders + ") ORDER BY id ASC";
        try (PreparedStatement ps = conn.prepareStatement(attSql)) {
            int idx = 1;
            for (Integer id : byId.keySet()) {
                ps.setInt(idx++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int msgId = rs.getInt("message_id");
                    Message target = byId.get(msgId);
                    if (target != null) {
                        Integer rId = rs.getObject("room_id") != null ? rs.getInt("room_id") : null;
                        Long dmId = rs.getObject("direct_conversation_id") != null ? rs.getLong("direct_conversation_id") : null;
                        Timestamp ts = rs.getTimestamp("created_at");
                        target.getAttachments().add(new Attachment(
                            rs.getLong("id"),
                            rs.getInt("uploader_id"),
                            rId,
                            dmId,
                            msgId,
                            rs.getString("original_name"),
                            rs.getString("stored_name"),
                            rs.getString("content_type"),
                            rs.getLong("size_bytes"),
                            rs.getString("sha256"),
                            ts != null ? ts.toString() : ""
                        ));
                    }
                }
            }
        } catch (SQLException ignored) {
        }
    }

    /**
     * [HAI-05] Lưu tin nhắn vào PostgreSQL.
     */
    public Message saveMessage(int roomId, int senderId, String content) {
        return saveRichMessage(roomId, senderId, null, content, null, null);
    }

    /**
     * Overload method lưu tin nhắn với tên hiển thị trực tiếp.
     */
    public Message save(int roomId, int senderId, String senderDisplayName, String content) {
        return saveRichMessage(roomId, senderId, senderDisplayName, content, null, null);
    }

    /**
     * Lưu tin nhắn đầy đủ (hỗ trợ reply_to_id và danh sách attachmentIds).
     */
    public Message saveRichMessage(int roomId, int senderId, String senderDisplayName,
                                   String content, Integer replyToId, List<Long> attachmentIds) {
        List<Long> safeAttachmentIds = attachmentIds == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(attachmentIds));
        if (safeAttachmentIds.stream().anyMatch(id -> id == null || id <= 0)) return null;
        boolean hasAttachments = !safeAttachmentIds.isEmpty();
        if ((content == null || content.isBlank()) && !hasAttachments) {
            return null;
        }
        String finalContent = (content != null) ? content.trim() : "";
        if (roomId <= 0 || senderId <= 0 || finalContent.length() > 5000) return null;

        if (replyToId != null && replyToId > 0 && !isReplyTargetInRoom(replyToId, roomId)) {
            return null;
        }

        String insertSql = "INSERT INTO messages (room_id, user_id, sender_id, content, reply_to_id) " +
                           "VALUES (?, ?, ?, ?, ?) RETURNING id, sent_at";
        int newMsgId = -1;
        String sentAtStr = "";
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Savepoint insertSavepoint = conn.setSavepoint();
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setInt(1, roomId);
                    ps.setInt(2, senderId);
                    ps.setInt(3, senderId);
                    ps.setString(4, finalContent);
                    if (replyToId != null && replyToId > 0) {
                        ps.setInt(5, replyToId);
                    } else {
                        ps.setNull(5, Types.INTEGER);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            newMsgId = rs.getInt("id");
                            Timestamp ts = rs.getTimestamp("sent_at");
                            sentAtStr = ts != null ? ts.toString() : "";
                        }
                    }
                } catch (SQLException insertError) {
                    conn.rollback(insertSavepoint);
                    if (replyToId != null || !"42703".equals(insertError.getSQLState())) {
                        throw insertError;
                    }
                    String fallbackSql = "INSERT INTO messages (room_id, sender_id, content) VALUES (?, ?, ?) RETURNING id, sent_at";
                    try (PreparedStatement ps = conn.prepareStatement(fallbackSql)) {
                        ps.setInt(1, roomId);
                        ps.setInt(2, senderId);
                        ps.setString(3, finalContent);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                newMsgId = rs.getInt("id");
                                Timestamp ts = rs.getTimestamp("sent_at");
                                sentAtStr = ts != null ? ts.toString() : "";
                            }
                        }
                    }
                }

                if (newMsgId <= 0 || hasAttachments
                        && !attachmentDAO.linkAttachmentsToMessage(conn, newMsgId, roomId, senderId, safeAttachmentIds)) {
                    conn.rollback();
                    return null;
                }
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("[MessageDAO ERROR] Lỗi saveRichMessage (roomID=" + roomId + ", senderID=" + senderId + "): " + e.getMessage());
            return null;
        }

        Message loaded = findById(newMsgId);
        if (loaded != null) {
            return loaded;
        }
        String name = (senderDisplayName != null && !senderDisplayName.isBlank())
                ? senderDisplayName
                : ("User#" + senderId);
        return new Message(newMsgId, roomId, senderId, name, finalContent, sentAtStr);
    }

    /**
     * Lấy danh sách tin nhắn phân trang theo con trỏ (cursor pagination: id < beforeId)
     * kèm thông tin reply, reactions và attachments, trả về theo thứ tự thời gian TĂNG DẦN (ASC).
     */
    public List<Message> getMessagesCursor(int roomId, Integer beforeId, int limit) {
        List<Message> list = new ArrayList<>();
        int validLimit = (limit <= 0 || limit > 200) ? 50 : limit;
        boolean hasCursor = beforeId != null && beforeId > 0;

        String sql = "SELECT m.id, m.room_id, COALESCE(m.sender_id, m.user_id) AS sender_id, " +
                     "u.display_name, m.content, COALESCE(m.sent_at, m.created_at) AS sent_at, " +
                     "m.reply_to_id, ru.display_name AS reply_sender_name, rm.content AS reply_content, " +
                     "m.edited_at, m.deleted_at, m.pinned " +
                     "FROM messages m " +
                     "LEFT JOIN users u ON COALESCE(m.sender_id, m.user_id) = u.id " +
                     "LEFT JOIN messages rm ON m.reply_to_id = rm.id AND rm.room_id = m.room_id " +
                     "LEFT JOIN users ru ON COALESCE(rm.sender_id, rm.user_id) = ru.id " +
                     "WHERE m.room_id = ? " +
                     (hasCursor ? "AND m.id < ? " : "") +
                     "ORDER BY m.id DESC LIMIT ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int idx = 1;
            ps.setInt(idx++, roomId);
            if (hasCursor) {
                ps.setInt(idx++, beforeId);
            }
            ps.setInt(idx, validLimit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRichRow(rs));
                }
            }
            Collections.reverse(list);
            hydrateReactionsAndAttachments(conn, list);
        } catch (SQLException e) {
            System.err.println("[MessageDAO ERROR] Lỗi getMessagesCursor (roomID=" + roomId + "): " + e.getMessage());
        }
        return list;
    }

    private boolean isReplyTargetInRoom(int replyToId, int roomId) {
        String sql = "SELECT 1 FROM messages WHERE id = ? AND room_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, replyToId);
            ps.setInt(2, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("[MessageDAO ERROR] Could not validate reply target: " + e.getMessage());
            return false;
        }
    }

    /**
     * Tìm thông tin chi tiết một tin nhắn theo ID.
     */
    public Message findById(int messageId) {
        String sql = "SELECT m.id, m.room_id, COALESCE(m.sender_id, m.user_id) AS sender_id, " +
                     "u.display_name, m.content, COALESCE(m.sent_at, m.created_at) AS sent_at, " +
                     "m.reply_to_id, ru.display_name AS reply_sender_name, rm.content AS reply_content, " +
                     "m.edited_at, m.deleted_at, m.pinned " +
                     "FROM messages m " +
                     "LEFT JOIN users u ON COALESCE(m.sender_id, m.user_id) = u.id " +
                     "LEFT JOIN messages rm ON m.reply_to_id = rm.id AND rm.room_id = m.room_id " +
                     "LEFT JOIN users ru ON COALESCE(rm.sender_id, rm.user_id) = ru.id " +
                     "WHERE m.id = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Message msg = mapRichRow(rs);
                    hydrateReactionsAndAttachments(conn, Collections.singletonList(msg));
                    return msg;
                }
            }
        } catch (SQLException e) {
            System.err.println("[MessageDAO ERROR] Lỗi findById (messageId=" + messageId + "): " + e.getMessage());
        }
        return null;
    }

    /**
     * Chỉnh sửa nội dung tin nhắn (chỉ người gửi mới có quyền chỉnh sửa khi tin nhắn chưa bị xóa).
     */
    public Message editMessage(int messageId, int actorUserId, String newContent) {
        if (newContent == null || newContent.isBlank()) return null;

        String sql = "UPDATE messages SET content = ?, edited_at = CURRENT_TIMESTAMP " +
                     "WHERE id = ? AND COALESCE(sender_id, user_id) = ? AND deleted_at IS NULL";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, newContent.trim());
            ps.setInt(2, messageId);
            ps.setInt(3, actorUserId);

            if (ps.executeUpdate() > 0) {
                return findById(messageId);
            }
        } catch (SQLException e) {
            System.err.println("[MessageDAO ERROR] Lỗi editMessage (messageId=" + messageId + "): " + e.getMessage());
        }
        return null;
    }

    /**
     * Xóa mềm (soft-delete) một tin nhắn. Cho phép người gửi hoặc ADMIN/OWNER thực hiện.
     */
    public boolean deleteMessage(int messageId, int actorUserId, boolean isAdminOrOwner) {
        String sql = "UPDATE messages SET deleted_at = CURRENT_TIMESTAMP, " +
                     "content = '[tin nhắn đã bị xóa]', pinned = false " +
                     "WHERE id = ? AND (COALESCE(sender_id, user_id) = ? OR ? = true) AND deleted_at IS NULL";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, messageId);
            ps.setInt(2, actorUserId);
            ps.setBoolean(3, isAdminOrOwner);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[MessageDAO ERROR] Lỗi deleteMessage (messageId=" + messageId + "): " + e.getMessage());
            return false;
        }
    }

    /**
     * Ghim hoặc bỏ ghim một tin nhắn trong kênh.
     */
    public Message pinMessage(int messageId, boolean pinned) {
        String sql = "UPDATE messages SET pinned = ? WHERE id = ? AND deleted_at IS NULL";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setBoolean(1, pinned);
            ps.setInt(2, messageId);
            if (ps.executeUpdate() > 0) {
                return findById(messageId);
            }
        } catch (SQLException e) {
            System.err.println("[MessageDAO ERROR] Lỗi pinMessage (messageId=" + messageId + "): " + e.getMessage());
        }
        return null;
    }

    /**
     * Lấy danh sách các tin nhắn đang được ghim trong phòng chat.
     */
    public List<Message> getPinnedMessages(int roomId) {
        List<Message> list = new ArrayList<>();
        String sql = "SELECT m.id, m.room_id, COALESCE(m.sender_id, m.user_id) AS sender_id, " +
                     "u.display_name, m.content, COALESCE(m.sent_at, m.created_at) AS sent_at, " +
                     "m.reply_to_id, ru.display_name AS reply_sender_name, rm.content AS reply_content, " +
                     "m.edited_at, m.deleted_at, m.pinned " +
                     "FROM messages m " +
                     "LEFT JOIN users u ON COALESCE(m.sender_id, m.user_id) = u.id " +
                     "LEFT JOIN messages rm ON m.reply_to_id = rm.id AND rm.room_id = m.room_id " +
                     "LEFT JOIN users ru ON COALESCE(rm.sender_id, rm.user_id) = ru.id " +
                     "WHERE m.room_id = ? AND m.pinned = true AND m.deleted_at IS NULL " +
                     "ORDER BY m.id DESC";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRichRow(rs));
                }
            }
            hydrateReactionsAndAttachments(conn, list);
        } catch (SQLException e) {
            System.err.println("[MessageDAO ERROR] Lỗi getPinnedMessages (roomID=" + roomId + "): " + e.getMessage());
        }
        return list;
    }

    /**
     * Tìm kiếm toàn văn (Full-Text Search kết hợp ILIKE) trên tin nhắn theo guildId và/hoặc roomId.
     */
    public List<Message> searchMessages(int userId, Integer guildId, Integer roomId, String query, int limit) {
        List<Message> list = new ArrayList<>();
        if (userId <= 0 || (guildId == null && roomId == null) || query == null || query.isBlank()) return list;

        int validLimit = (limit <= 0 || limit > 100) ? 25 : limit;
        String cleanQuery = query.trim();

        StringBuilder sql = new StringBuilder(
            "SELECT m.id, m.room_id, COALESCE(m.sender_id, m.user_id) AS sender_id, " +
            "u.display_name, m.content, COALESCE(m.sent_at, m.created_at) AS sent_at, " +
            "m.reply_to_id, ru.display_name AS reply_sender_name, rm.content AS reply_content, " +
            "m.edited_at, m.deleted_at, m.pinned " +
            "FROM messages m " +
            "JOIN rooms r ON m.room_id = r.id " +
            "LEFT JOIN users u ON COALESCE(m.sender_id, m.user_id) = u.id " +
            "LEFT JOIN messages rm ON m.reply_to_id = rm.id AND rm.room_id = m.room_id " +
            "LEFT JOIN users ru ON COALESCE(rm.sender_id, rm.user_id) = ru.id " +
            "WHERE m.deleted_at IS NULL " +
            "  AND EXISTS (SELECT 1 FROM room_members access_member " +
            "              WHERE access_member.room_id = m.room_id AND access_member.user_id = ?) " +
            "  AND (m.search_vector @@ plainto_tsquery('simple', ?) OR m.content ILIKE ?) "
        );

        if (guildId != null && guildId > 0) {
            sql.append("AND COALESCE(r.guild_id, 1) = ? ");
        }
        if (roomId != null && roomId > 0) {
            sql.append("AND m.room_id = ? ");
        }
        sql.append("ORDER BY m.id DESC LIMIT ?");

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            int idx = 1;
            ps.setInt(idx++, userId);
            ps.setString(idx++, cleanQuery);
            ps.setString(idx++, "%" + cleanQuery + "%");
            if (guildId != null && guildId > 0) {
                ps.setInt(idx++, guildId);
            }
            if (roomId != null && roomId > 0) {
                ps.setInt(idx++, roomId);
            }
            ps.setInt(idx, validLimit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRichRow(rs));
                }
            }
            hydrateReactionsAndAttachments(conn, list);
        } catch (SQLException e) {
            System.err.println("[MessageDAO ERROR] Lỗi searchMessages ('" + cleanQuery + "'): " + e.getMessage());
        }
        return list;
    }

    /**
     * Bật/tắt (toggle) biểu tượng cảm xúc (emoji reaction) của userId cho messageId,
     * trả về danh sách ReactionSummary mới nhất của tin nhắn đó.
     */
    public List<ReactionSummary> toggleReaction(int messageId, int userId, String emoji) {
        if (messageId <= 0 || userId <= 0 || emoji == null || emoji.isBlank()) {
            return Collections.emptyList();
        }
        String cleanEmoji = emoji.trim();
        if (cleanEmoji.codePointCount(0, cleanEmoji.length()) > 32) {
            return null;
        }

        String deleteSql = "DELETE FROM message_reactions WHERE message_id = ? AND user_id = ? AND reaction = ?";
        String insertSql = "INSERT INTO message_reactions (message_id, user_id, reaction) VALUES (?, ?, ?) " +
                           "ON CONFLICT (message_id, user_id, reaction) DO NOTHING";
        String selectSql = "SELECT reaction, user_id FROM message_reactions WHERE message_id = ? ORDER BY created_at ASC, user_id ASC";

        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lockPs = conn.prepareStatement(
                    "SELECT id FROM messages WHERE id = ? FOR UPDATE")) {
                lockPs.setInt(1, messageId);
                try (ResultSet rs = lockPs.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return null;
                    }
                }
            }

                int deleted;
                try (PreparedStatement delPs = conn.prepareStatement(deleteSql)) {
                    delPs.setInt(1, messageId);
                    delPs.setInt(2, userId);
                    delPs.setString(3, cleanEmoji);
                    deleted = delPs.executeUpdate();
                }

                if (deleted == 0) {
                    try (PreparedStatement insPs = conn.prepareStatement(insertSql)) {
                        insPs.setInt(1, messageId);
                        insPs.setInt(2, userId);
                        insPs.setString(3, cleanEmoji);
                        insPs.executeUpdate();
                    }
                }

                Map<String, ReactionSummary> emojiMap = new LinkedHashMap<>();
                try (PreparedStatement selPs = conn.prepareStatement(selectSql)) {
                    selPs.setInt(1, messageId);
                    try (ResultSet rs = selPs.executeQuery()) {
                        while (rs.next()) {
                            String em = rs.getString("reaction");
                            int uid = rs.getInt("user_id");
                            ReactionSummary summary = emojiMap.computeIfAbsent(em, k -> new ReactionSummary(k, 0, new ArrayList<>()));
                            summary.getUserIds().add(uid);
                            summary.setCount(summary.getUserIds().size());
                        }
                    }
                }
                conn.commit();
                return new ArrayList<>(emojiMap.values());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("[MessageDAO ERROR] Lỗi toggleReaction (messageId=" + messageId + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * [HAI-07] Lấy ra danh sách N tin nhắn gần nhất theo thứ tự thời gian TĂNG DẦN (ASC).
     */
    public List<Message> getMessageHistory(int roomId, int limit) {
        return getMessagesCursor(roomId, null, limit);
    }

    /**
     * Lấy toàn bộ lịch sử tin nhắn của phòng (Hỗ trợ tương thích ngược).
     */
    public List<Message> history(int roomId) {
        return getMessageHistory(roomId, 100);
    }

    public List<Message> getMessageHistoryBefore(int roomId, int beforeMessageId, int limit) {
        return getMessagesCursor(roomId, beforeMessageId, limit);
    }

    public Message getMessageById(int messageId) {
        return findById(messageId);
    }
}
