package com.discordmini.server.dao;

import com.discordmini.server.model.Attachment;

import java.security.MessageDigest;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * DAO quản lý tệp đính kèm lưu trữ trực tiếp trong PostgreSQL / Neon (cột bytea, tối đa 5MB).
 */
public class AttachmentDAO {

    private static final long MAX_SIZE_BYTES = 5L * 1024L * 1024L; // 5 MB

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data != null ? data : new byte[0]);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "file.bin";
        String cleaned = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (cleaned.length() > 180) {
            cleaned = cleaned.substring(cleaned.length() - 180);
        }
        return cleaned.isEmpty() ? "file.bin" : cleaned;
    }

    /**
     * Lưu tệp đính kèm mới vào bảng attachments (cột bytea).
     */
    public Attachment saveAttachment(int uploaderId, Integer roomId, Long dmId,
                                     String originalName, String contentType, byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_SIZE_BYTES) {
            System.err.println("[AttachmentDAO] Kích thước tệp không hợp lệ: " + (data != null ? data.length : 0));
            return null;
        }

        String safeOriginal = sanitizeFileName(originalName);
        String storedName = UUID.randomUUID().toString().replace("-", "") + "_" + safeOriginal;
        String mime = (contentType != null && !contentType.isBlank())
                ? contentType.trim()
                : "application/octet-stream";
        String sha256 = sha256Hex(data);

        // Đảm bảo constraint ((room_id IS NOT NULL)::int + (direct_conversation_id IS NOT NULL)::int) <= 1
        Integer finalRoomId = roomId;
        Long finalDmId = (finalRoomId != null) ? null : dmId;

        String sql = "INSERT INTO attachments (uploader_id, room_id, direct_conversation_id, original_name, " +
                     "stored_name, content_type, size_bytes, sha256, data) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id, created_at";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, uploaderId);
            if (finalRoomId != null && finalRoomId > 0) {
                ps.setInt(2, finalRoomId);
            } else {
                ps.setNull(2, Types.INTEGER);
            }
            if (finalDmId != null && finalDmId > 0) {
                ps.setLong(3, finalDmId);
            } else {
                ps.setNull(3, Types.BIGINT);
            }
            ps.setString(4, originalName != null && !originalName.isBlank() ? originalName.trim() : safeOriginal);
            ps.setString(5, storedName);
            ps.setString(6, mime);
            ps.setLong(7, data.length);
            ps.setString(8, sha256);
            ps.setBytes(9, data);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    Timestamp ts = rs.getTimestamp("created_at");
                    return new Attachment(
                        id,
                        uploaderId,
                        finalRoomId,
                        finalDmId,
                        null,
                        originalName != null && !originalName.isBlank() ? originalName.trim() : safeOriginal,
                        storedName,
                        mime,
                        data.length,
                        sha256,
                        ts != null ? ts.toString() : ""
                    );
                }
            }
        } catch (SQLException e) {
            System.err.println("[AttachmentDAO ERROR] Lỗi saveAttachment: " + e.getMessage());
        }
        return null;
    }

    /**
     * Lấy thông tin tệp kèm dữ liệu nhị phân byte[] để phục vụ tải file (GET /api/files/{id}).
     */
    public Attachment getAttachmentWithData(long attachmentId) {
        String sql = "SELECT id, uploader_id, room_id, direct_conversation_id, message_id, " +
                     "original_name, stored_name, content_type, size_bytes, sha256, data, created_at " +
                     "FROM attachments WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, attachmentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Integer roomId = rs.getObject("room_id") != null ? rs.getInt("room_id") : null;
                    Long dmId = rs.getObject("direct_conversation_id") != null ? rs.getLong("direct_conversation_id") : null;
                    Integer msgId = rs.getObject("message_id") != null ? rs.getInt("message_id") : null;
                    Timestamp ts = rs.getTimestamp("created_at");
                    return new Attachment(
                        rs.getLong("id"),
                        rs.getInt("uploader_id"),
                        roomId,
                        dmId,
                        msgId,
                        rs.getString("original_name"),
                        rs.getString("stored_name"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("sha256"),
                        rs.getBytes("data"),
                        ts != null ? ts.toString() : ""
                    );
                }
            }
        } catch (SQLException e) {
            System.err.println("[AttachmentDAO ERROR] Lỗi getAttachmentWithData (id=" + attachmentId + "): " + e.getMessage());
        }
        return null;
    }

    /**
     * Lấy danh sách metadata các tệp đính kèm của một tin nhắn (không tải cột bytea data).
     */
    public List<Attachment> getAttachmentsForMessage(int messageId) {
        List<Attachment> list = new ArrayList<>();
        String sql = "SELECT id, uploader_id, room_id, direct_conversation_id, message_id, " +
                     "original_name, stored_name, content_type, size_bytes, sha256, created_at " +
                     "FROM attachments WHERE message_id = ? ORDER BY id ASC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer roomId = rs.getObject("room_id") != null ? rs.getInt("room_id") : null;
                    Long dmId = rs.getObject("direct_conversation_id") != null ? rs.getLong("direct_conversation_id") : null;
                    Integer msgId = rs.getObject("message_id") != null ? rs.getInt("message_id") : null;
                    Timestamp ts = rs.getTimestamp("created_at");
                    list.add(new Attachment(
                        rs.getLong("id"),
                        rs.getInt("uploader_id"),
                        roomId,
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
        } catch (SQLException e) {
            System.err.println("[AttachmentDAO ERROR] Lỗi getAttachmentsForMessage (messageId=" + messageId + "): " + e.getMessage());
        }
        return list;
    }

    /**
     * Liên kết danh sách attachmentId đã tải lên trước đó vào một tin nhắn trong phòng chat.
     */
    public boolean linkAttachmentsToMessage(Connection conn, int messageId, int roomId,
                                            int senderId, List<Long> attachmentIds) throws SQLException {
        if (conn == null || messageId <= 0 || roomId <= 0 || senderId <= 0
                || attachmentIds == null || attachmentIds.isEmpty()) return false;

        List<Long> uniqueIds = new ArrayList<>(new LinkedHashSet<>(attachmentIds));
        if (uniqueIds.stream().anyMatch(id -> id == null || id <= 0)) return false;

        String sql = "UPDATE attachments SET message_id = ?, room_id = ?, direct_conversation_id = NULL "
                + "WHERE id = ? AND uploader_id = ? AND message_id IS NULL AND room_id = ? AND direct_conversation_id IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Long attId : uniqueIds) {
                ps.setInt(1, messageId);
                ps.setInt(2, roomId);
                ps.setLong(3, attId);
                ps.setInt(4, senderId);
                ps.setInt(5, roomId);
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            if (results.length != uniqueIds.size()) return false;
            for (int result : results) {
                if (result != 1 && result != Statement.SUCCESS_NO_INFO) return false;
            }
            return true;
        }
    }
}
