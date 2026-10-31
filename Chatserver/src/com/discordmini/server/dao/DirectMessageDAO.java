package com.discordmini.server.dao;

import com.discordmini.server.model.Attachment;
import com.discordmini.server.model.DirectConversation;
import com.discordmini.server.model.DirectMessage;
import com.discordmini.server.model.ReactionSummary;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * DAO quản lý hội thoại trực tiếp 1-1 (Direct Conversations & Direct Messages).
 */
public class DirectMessageDAO {

    private DirectConversation mapConversation(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        String presence = rs.getString("peer_presence_status");
        if (presence == null || presence.isBlank()) {
            presence = rs.getString("peer_status");
        }
        DirectConversation conversation = new DirectConversation(
            rs.getLong("id"),
            rs.getInt("user_low_id"),
            rs.getInt("user_high_id"),
            rs.getInt("peer_user_id"),
            rs.getString("peer_username"),
            rs.getString("peer_display_name"),
            rs.getString("peer_avatar_color"),
            presence != null ? presence : "OFFLINE",
            rs.getString("last_message_preview"),
            ts != null ? ts.toString() : ""
        );
        try {
            conversation.setUnreadCount(rs.getInt("unread_count"));
        } catch (SQLException ignored) {
            // Conversation lookup paths that do not request unread counts remain compatible.
        }
        return conversation;
    }

    /**
     * Lấy danh sách tất cả cuộc trò chuyện 1-1 của người dùng kèm tin nhắn cuối cùng.
     */
    public List<DirectConversation> listConversations(int userId) {
        List<DirectConversation> list = new ArrayList<>();
        String sql = "SELECT dc.id, dc.user_low_id, dc.user_high_id, dc.created_at, " +
                     "u.id AS peer_user_id, u.username AS peer_username, u.display_name AS peer_display_name, " +
                     "COALESCE(u.avatar_color, '#2563EB') AS peer_avatar_color, " +
                     "u.presence_status AS peer_presence_status, u.status AS peer_status, " +
                     "(SELECT dm.content FROM direct_messages dm " +
                     " WHERE dm.conversation_id = dc.id AND dm.deleted_at IS NULL " +
                     " ORDER BY dm.id DESC LIMIT 1) AS last_message_preview " +
                     ", (SELECT COUNT(*) FROM direct_messages unread_dm " +
                     "   WHERE unread_dm.conversation_id = dc.id AND unread_dm.sender_id <> ? " +
                     "     AND unread_dm.deleted_at IS NULL AND unread_dm.id > COALESCE((" +
                     "       SELECT dcr.last_read_message_id FROM direct_conversation_reads dcr " +
                     "       WHERE dcr.conversation_id = dc.id AND dcr.user_id = ?), 0)) AS unread_count " +
                     "FROM direct_conversations dc " +
                     "JOIN users u ON u.id = (CASE WHEN dc.user_low_id = ? THEN dc.user_high_id ELSE dc.user_low_id END) " +
                     "WHERE dc.user_low_id = ? OR dc.user_high_id = ? " +
                     "ORDER BY COALESCE((SELECT MAX(dm2.id) FROM direct_messages dm2 WHERE dm2.conversation_id = dc.id), 0) DESC, dc.id DESC";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            ps.setInt(4, userId);
            ps.setInt(5, userId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapConversation(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("[DirectMessageDAO ERROR] Lỗi listConversations (userId=" + userId + "): " + e.getMessage());
        }
        return list;
    }

    /**
     * Lấy hoặc tạo mới cuộc hội thoại 1-1 giữa userA (người gọi) và userB (đối phương).
     */
    public DirectConversation getOrCreateConversation(int userA, int userB) {
        if (userA <= 0 || userB <= 0 || userA == userB) return null;

        int low = Math.min(userA, userB);
        int high = Math.max(userA, userB);

        String upsertSql = "INSERT INTO direct_conversations (user_low_id, user_high_id) VALUES (?, ?) " +
                           "ON CONFLICT (user_low_id, user_high_id) DO NOTHING";
        String selectSql = "SELECT dc.id, dc.user_low_id, dc.user_high_id, dc.created_at, " +
                           "u.id AS peer_user_id, u.username AS peer_username, u.display_name AS peer_display_name, " +
                           "COALESCE(u.avatar_color, '#2563EB') AS peer_avatar_color, " +
                           "u.presence_status AS peer_presence_status, u.status AS peer_status, " +
                           "(SELECT dm.content FROM direct_messages dm " +
                           " WHERE dm.conversation_id = dc.id AND dm.deleted_at IS NULL " +
                           " ORDER BY dm.id DESC LIMIT 1) AS last_message_preview " +
                           "FROM direct_conversations dc " +
                           "JOIN users u ON u.id = ? " +
                           "WHERE dc.user_low_id = ? AND dc.user_high_id = ?";

        try (Connection conn = DatabaseConnection.getConnection()) {
            try (PreparedStatement upPs = conn.prepareStatement(upsertSql)) {
                upPs.setInt(1, low);
                upPs.setInt(2, high);
                upPs.executeUpdate();
            }

            try (PreparedStatement selPs = conn.prepareStatement(selectSql)) {
                selPs.setInt(1, userB);
                selPs.setInt(2, low);
                selPs.setInt(3, high);
                try (ResultSet rs = selPs.executeQuery()) {
                    if (rs.next()) {
                        return mapConversation(rs);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[DirectMessageDAO ERROR] Lỗi getOrCreateConversation (" + userA + ", " + userB + "): " + e.getMessage());
        }
        return null;
    }

    /**
     * Lấy danh sách tin nhắn trong cuộc trò chuyện 1-1 theo con trỏ beforeId, trả về thứ tự TĂNG DẦN (ASC).
     */
    public List<DirectMessage> getMessages(long conversationId, Long beforeId, int limit) {
        List<DirectMessage> list = new ArrayList<>();
        int validLimit = (limit <= 0 || limit > 200) ? 50 : limit;
        boolean hasCursor = beforeId != null && beforeId > 0;

        String sql = "SELECT dm.id, dm.conversation_id, dm.sender_id, u.display_name, " +
                     "COALESCE(u.avatar_color, '#2563EB') AS avatar_color, " +
                     "dm.content, dm.sent_at, dm.edited_at, dm.deleted_at, dm.reply_to_message_id " +
                     "FROM direct_messages dm " +
                     "JOIN users u ON dm.sender_id = u.id " +
                     "WHERE dm.conversation_id = ? " +
                     (hasCursor ? "AND dm.id < ? " : "") +
                     "ORDER BY dm.id DESC LIMIT ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int idx = 1;
            ps.setLong(idx++, conversationId);
            if (hasCursor) {
                ps.setLong(idx++, beforeId);
            }
            ps.setInt(idx, validLimit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp sentTs = rs.getTimestamp("sent_at");
                    Timestamp editedTs = rs.getTimestamp("edited_at");
                    Timestamp deletedTs = rs.getTimestamp("deleted_at");
                    Long replyId = rs.getObject("reply_to_message_id") != null ? rs.getLong("reply_to_message_id") : null;
                    list.add(new DirectMessage(
                        rs.getLong("id"),
                        rs.getLong("conversation_id"),
                        rs.getInt("sender_id"),
                        rs.getString("display_name"),
                        rs.getString("avatar_color"),
                        rs.getString("content"),
                        sentTs != null ? sentTs.toString() : "",
                        editedTs != null ? editedTs.toString() : null,
                        deletedTs != null ? deletedTs.toString() : null,
                        replyId
                    ));
                }
            }
            Collections.reverse(list);
            hydrateDmAttachments(conn, conversationId, list);
            hydrateDmReactions(conn, list);
        } catch (SQLException e) {
            System.err.println("[DirectMessageDAO ERROR] Lỗi getMessages (conversationId=" + conversationId + "): " + e.getMessage());
        }
        return list;
    }

    public long findConversationIdForMessage(long messageId) {
        String sql = "SELECT conversation_id FROM direct_messages WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("conversation_id") : 0L;
            }
        } catch (SQLException e) {
            System.err.println("[DirectMessageDAO ERROR] Could not find conversation for message " + messageId + ": " + e.getMessage());
            return 0L;
        }
    }

    /** Advance a member's conversation read cursor; returns 0 if the message is not in that conversation. */
    public long markConversationRead(long conversationId, int userId, long messageId) {
        if (conversationId <= 0 || userId <= 0 || messageId <= 0) return 0;
        String targetSql = "SELECT 1 FROM direct_messages WHERE id = ? AND conversation_id = ?";
        String upsertSql = "INSERT INTO direct_conversation_reads " +
                "(conversation_id, user_id, last_read_message_id, last_read_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (conversation_id, user_id) DO UPDATE SET " +
                "last_read_message_id = GREATEST(COALESCE(direct_conversation_reads.last_read_message_id, 0), EXCLUDED.last_read_message_id), " +
                "last_read_at = CURRENT_TIMESTAMP RETURNING last_read_message_id";
        try (Connection conn = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(targetSql)) {
                ps.setLong(1, messageId);
                ps.setLong(2, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return 0;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                ps.setLong(1, conversationId);
                ps.setInt(2, userId);
                ps.setLong(3, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong("last_read_message_id") : 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DirectMessageDAO ERROR] Could not mark conversation read: " + e.getMessage());
            return 0;
        }
    }

    public int getUnreadCount(long conversationId, int userId) {
        String sql = "SELECT COUNT(*) FROM direct_messages dm " +
                "WHERE dm.conversation_id = ? AND dm.sender_id <> ? AND dm.deleted_at IS NULL " +
                "AND dm.id > COALESCE((SELECT last_read_message_id FROM direct_conversation_reads " +
                "WHERE conversation_id = ? AND user_id = ?), 0)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, conversationId);
            ps.setInt(2, userId);
            ps.setLong(3, conversationId);
            ps.setInt(4, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            System.err.println("[DirectMessageDAO ERROR] Could not count unread messages: " + e.getMessage());
            return 0;
        }
    }

    public List<ReactionSummary> toggleReaction(long messageId, int userId, String emoji) {
        if (messageId <= 0 || userId <= 0 || emoji == null || emoji.isBlank()
                || emoji.codePointCount(0, emoji.length()) > 32) return null;
        String cleanEmoji = emoji.trim();
        String deleteSql = "DELETE FROM direct_message_reactions WHERE direct_message_id = ? AND user_id = ? AND reaction = ?";
        String insertSql = "INSERT INTO direct_message_reactions (direct_message_id, user_id, reaction) "
                + "VALUES (?, ?, ?) ON CONFLICT (direct_message_id, user_id, reaction) DO NOTHING";
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lockPs = conn.prepareStatement(
                    "SELECT id FROM direct_messages WHERE id = ? FOR UPDATE")) {
                lockPs.setLong(1, messageId);
                try (ResultSet rs = lockPs.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return null;
                    }
                }
            }

                int removed;
                try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                    ps.setLong(1, messageId);
                    ps.setInt(2, userId);
                    ps.setString(3, cleanEmoji);
                    removed = ps.executeUpdate();
                }
                if (removed == 0) {
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setLong(1, messageId);
                        ps.setInt(2, userId);
                        ps.setString(3, cleanEmoji);
                        ps.executeUpdate();
                    }
                }

                Map<String, ReactionSummary> grouped = new LinkedHashMap<>();
                String selectSql = "SELECT reaction, user_id FROM direct_message_reactions "
                        + "WHERE direct_message_id = ? ORDER BY created_at ASC, user_id ASC";
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setLong(1, messageId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String reaction = rs.getString("reaction");
                            ReactionSummary summary = grouped.computeIfAbsent(reaction,
                                    key -> new ReactionSummary(key, 0, new ArrayList<>()));
                            summary.getUserIds().add(rs.getInt("user_id"));
                            summary.setCount(summary.getCount() + 1);
                        }
                    }
                }
                conn.commit();
                return new ArrayList<>(grouped.values());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("[DirectMessageDAO ERROR] Could not update reaction for message " + messageId + ": " + e.getMessage());
            return null;
        }
    }

    private void hydrateDmReactions(Connection conn, List<DirectMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        Map<Long, DirectMessage> byId = new LinkedHashMap<>();
        StringBuilder placeholders = new StringBuilder();
        for (DirectMessage message : messages) {
            byId.put(message.getId(), message);
            if (placeholders.length() > 0) placeholders.append(",");
            placeholders.append("?");
        }
        String sql = "SELECT direct_message_id, reaction, user_id FROM direct_message_reactions "
                + "WHERE direct_message_id IN (" + placeholders + ") ORDER BY created_at ASC, user_id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Long messageId : byId.keySet()) ps.setLong(idx++, messageId);
            Map<Long, Map<String, ReactionSummary>> grouped = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long messageId = rs.getLong("direct_message_id");
                    String emoji = rs.getString("reaction");
                    ReactionSummary summary = grouped.computeIfAbsent(messageId, key -> new LinkedHashMap<>())
                            .computeIfAbsent(emoji, key -> new ReactionSummary(key, 0, new ArrayList<>()));
                    summary.getUserIds().add(rs.getInt("user_id"));
                    summary.setCount(summary.getCount() + 1);
                }
            }
            grouped.forEach((messageId, reactions) -> {
                DirectMessage message = byId.get(messageId);
                if (message != null) message.setReactions(new ArrayList<>(reactions.values()));
            });
        } catch (SQLException e) {
            System.err.println("[DirectMessageDAO ERROR] Could not load message reactions: " + e.getMessage());
        }
    }

    private void hydrateDmAttachments(Connection conn, long conversationId, List<DirectMessage> messages) {
        if (messages == null || messages.isEmpty()) return;

        Map<Integer, DirectMessage> byId = new LinkedHashMap<>();
        StringBuilder placeholders = new StringBuilder();
        for (DirectMessage dm : messages) {
            byId.put((int) dm.getId(), dm);
            if (placeholders.length() > 0) placeholders.append(",");
            placeholders.append("?");
        }

        String sql = "SELECT id, uploader_id, room_id, direct_conversation_id, message_id, " +
                     "original_name, stored_name, content_type, size_bytes, sha256, created_at " +
                     "FROM attachments WHERE direct_conversation_id = ? AND message_id IN (" + placeholders + ") ORDER BY id ASC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setLong(idx++, conversationId);
            for (Integer msgId : byId.keySet()) {
                ps.setInt(idx++, msgId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int msgId = rs.getInt("message_id");
                    DirectMessage target = byId.get(msgId);
                    if (target != null) {
                        Timestamp ts = rs.getTimestamp("created_at");
                        target.getAttachments().add(new Attachment(
                            rs.getLong("id"),
                            rs.getInt("uploader_id"),
                            null,
                            conversationId,
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
     * Lưu một tin nhắn trực tiếp 1-1 mới và gắn kết các tệp đính kèm nếu có.
     */
    public DirectMessage saveDirectMessage(long conversationId, int senderId, String content,
                                           Long replyToId, List<Long> attachmentIds) {
        List<Long> safeAttachmentIds = attachmentIds == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(attachmentIds));
        if (safeAttachmentIds.stream().anyMatch(id -> id == null || id <= 0)) return null;
        boolean hasAttachments = !safeAttachmentIds.isEmpty();
        if ((content == null || content.isBlank()) && !hasAttachments) {
            return null;
        }
        String finalContent = (content != null && !content.isBlank()) ? content.trim() : "[Attachment]";
        if (finalContent.length() > 5000) return null;

        if (replyToId != null && replyToId > 0 && !isReplyTargetInConversation(replyToId, conversationId)) {
            return null;
        }

        String insertSql = "INSERT INTO direct_messages (conversation_id, sender_id, content, reply_to_message_id) " +
                           "VALUES (?, ?, ?, ?) RETURNING id, sent_at";

        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            long msgId = -1;
            String sentAtStr = "";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setLong(1, conversationId);
                ps.setInt(2, senderId);
                ps.setString(3, finalContent);
                if (replyToId != null && replyToId > 0) {
                    ps.setLong(4, replyToId);
                } else {
                    ps.setNull(4, Types.BIGINT);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        msgId = rs.getLong("id");
                        Timestamp ts = rs.getTimestamp("sent_at");
                        sentAtStr = ts != null ? ts.toString() : "";
                    }
                }
            }

            if (msgId <= 0) return null;

            if (hasAttachments) {
                String linkSql = "UPDATE attachments SET direct_conversation_id = ?, room_id = NULL, message_id = ? "
                        + "WHERE id = ? AND uploader_id = ? AND message_id IS NULL AND direct_conversation_id = ? AND room_id IS NULL";
                try (PreparedStatement attPs = conn.prepareStatement(linkSql)) {
                    for (Long attId : safeAttachmentIds) {
                        attPs.setLong(1, conversationId);
                        attPs.setInt(2, (int) msgId);
                        attPs.setLong(3, attId);
                        attPs.setInt(4, senderId);
                        attPs.setLong(5, conversationId);
                        attPs.addBatch();
                    }
                    int[] results = attPs.executeBatch();
                    for (int result : results) {
                        if (result != 1 && result != Statement.SUCCESS_NO_INFO) {
                            conn.rollback();
                            return null;
                        }
                    }
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            }

            String senderDisplayName = "User#" + senderId;
            String senderAvatarColor = "#2563EB";
            try (PreparedStatement uPs = conn.prepareStatement(
                    "SELECT display_name, COALESCE(avatar_color, '#2563EB') AS avatar_color FROM users WHERE id = ?")) {
                uPs.setInt(1, senderId);
                try (ResultSet uRs = uPs.executeQuery()) {
                    if (uRs.next()) {
                        senderDisplayName = uRs.getString("display_name");
                        senderAvatarColor = uRs.getString("avatar_color");
                    }
                }
            } catch (SQLException ignored) {
            }

            DirectMessage dm = new DirectMessage(
                msgId, conversationId, senderId,
                senderDisplayName, senderAvatarColor,
                finalContent, sentAtStr, null, null, replyToId
            );
            hydrateDmAttachments(conn, conversationId, Collections.singletonList(dm));
            conn.commit();
            return dm;
        } catch (SQLException e) {
            System.err.println("[DirectMessageDAO ERROR] Lỗi saveDirectMessage (conversationId=" + conversationId + "): " + e.getMessage());
            return null;
        }
    }

    private boolean isReplyTargetInConversation(long replyToId, long conversationId) {
        String sql = "SELECT 1 FROM direct_messages WHERE id = ? AND conversation_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, replyToId);
            ps.setLong(2, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("[DirectMessageDAO ERROR] Could not validate reply target: " + e.getMessage());
            return false;
        }
    }
}
