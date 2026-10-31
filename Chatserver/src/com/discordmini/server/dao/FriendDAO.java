package com.discordmini.server.dao;

import com.discordmini.server.model.User;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * FriendDAO: Quản lý mối quan hệ bạn bè và các lời mời kết bạn (Friend Requests).
 * Tương tác với bảng 'friendships' và 'users' trong PostgreSQL.
 *
 * Tương thích linh hoạt: Tự động tương thích với cả 2 phiên bản schema database:
 * - Bản 1: requester_id và addressee_id (theo schema.sql)
 * - Bản 2: user_id_1 và user_id_2 (theo database thực tế đang chạy)
 */
public class FriendDAO {

    private static volatile String colReq = null;
    private static volatile String colAddr = null;

    private static synchronized void initColumns(Connection conn) {
        if (colReq != null) return;
        try {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, "friendships", "requester_id")) {
                if (rs.next()) {
                    colReq = "requester_id";
                    colAddr = "addressee_id";
                    return;
                }
            }
        } catch (Exception ignored) {}
        colReq = "user_id_1";
        colAddr = "user_id_2";
    }

    private static String getColReq(Connection conn) {
        if (colReq == null) initColumns(conn);
        return colReq;
    }

    private static String getColAddr(Connection conn) {
        if (colAddr == null) initColumns(conn);
        return colAddr;
    }

    /**
     * DTO đại diện cho một lời mời kết bạn đang chờ xử lý.
     */
    public record FriendRequestItem(int requestId, int senderId, String senderUsername, String senderDisplayName, Timestamp createdAt) {}

    /**
     * Tìm kiếm người dùng theo username hoặc display_name (không phân biệt hoa thường).
     * Loại trừ chính người dùng đang tìm kiếm (currentUserId).
     */
    public List<User> searchUsers(int currentUserId, String query) {
        List<User> list = new ArrayList<>();
        if (query == null || query.isBlank()) return list;

        String pattern = "%" + query.trim().toLowerCase() + "%";
        String sql = "SELECT id, username, display_name, status, created_at FROM users " +
                     "WHERE id <> ? AND (LOWER(username) LIKE ? OR LOWER(display_name) LIKE ?) " +
                     "ORDER BY username ASC LIMIT 20";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, currentUserId);
            ps.setString(2, pattern);
            ps.setString(3, pattern);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new User(
                        rs.getInt("id"),
                        rs.getString("username"),
                        "",
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("[FriendDAO ERROR] searchUsers: " + e.getMessage());
        }
        return list;
    }

    /**
     * Gửi lời mời kết bạn từ senderId tới receiverId.
     */
    public String sendFriendRequest(int senderId, int receiverId) {
        if (senderId == receiverId) {
            return "SELF_REQUEST";
        }

        try (Connection conn = DatabaseConnection.getConnection()) {
            String rCol = getColReq(conn);
            String aCol = getColAddr(conn);

            String checkExistingSql = "SELECT id, " + rCol + ", " + aCol + ", status FROM friendships " +
                                      "WHERE (" + rCol + " = ? AND " + aCol + " = ?) OR (" + rCol + " = ? AND " + aCol + " = ?)";
            String insertSql = "INSERT INTO friendships (" + rCol + ", " + aCol + ", status) VALUES (?, ?, 'PENDING') RETURNING id";

            try (PreparedStatement checkPs = conn.prepareStatement(checkExistingSql)) {
                checkPs.setInt(1, senderId);
                checkPs.setInt(2, receiverId);
                checkPs.setInt(3, receiverId);
                checkPs.setInt(4, senderId);

                try (ResultSet rs = checkPs.executeQuery()) {
                    if (rs.next()) {
                        String st = rs.getString("status");
                        int req = rs.getInt(rCol);
                        if ("ACCEPTED".equalsIgnoreCase(st)) {
                            return "ALREADY_FRIENDS";
                        }
                        if ("PENDING".equalsIgnoreCase(st)) {
                            if (req == senderId) {
                                return "ALREADY_REQUESTED";
                            } else {
                                return "INCOMING_EXISTS";
                            }
                        }
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setInt(1, senderId);
                ps.setInt(2, receiverId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return "OK";
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[FriendDAO ERROR] sendFriendRequest: " + e.getMessage());
        }
        return "ERROR";
    }

    /**
     * Lấy danh sách các lời mời kết bạn đang chờ (PENDING) gửi đến userId.
     */
    public List<FriendRequestItem> getPendingRequests(int userId) {
        List<FriendRequestItem> list = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection()) {
            String rCol = getColReq(conn);
            String aCol = getColAddr(conn);

            String sql = "SELECT f.id, f." + rCol + " AS req_id, u.username, u.display_name, f.created_at " +
                         "FROM friendships f JOIN users u ON f." + rCol + " = u.id " +
                         "WHERE f." + aCol + " = ? AND f.status = 'PENDING' " +
                         "ORDER BY f.created_at DESC";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new FriendRequestItem(
                            rs.getInt("id"),
                            rs.getInt("req_id"),
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getTimestamp("created_at")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[FriendDAO ERROR] getPendingRequests: " + e.getMessage());
        }
        return list;
    }

    /**
     * Chấp nhận lời mời kết bạn theo requestId.
     */
    public boolean acceptRequest(int requestId, int currentUserId) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            String aCol = getColAddr(conn);
            String sql = "UPDATE friendships SET status = 'ACCEPTED' WHERE id = ? AND " + aCol + " = ? AND status = 'PENDING'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, requestId);
                ps.setInt(2, currentUserId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            System.err.println("[FriendDAO ERROR] acceptRequest by id: " + e.getMessage());
            return false;
        }
    }

    /**
     * Chấp nhận lời mời kết bạn theo senderId.
     */
    public boolean acceptRequestFromUser(int senderId, int currentUserId) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            String rCol = getColReq(conn);
            String aCol = getColAddr(conn);
            String sql = "UPDATE friendships SET status = 'ACCEPTED' WHERE " + rCol + " = ? AND " + aCol + " = ? AND status = 'PENDING'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, senderId);
                ps.setInt(2, currentUserId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            System.err.println("[FriendDAO ERROR] acceptRequestFromUser: " + e.getMessage());
            return false;
        }
    }

    /**
     * Từ chối lời mời kết bạn (Xóa bản ghi PENDING khỏi CSDL).
     */
    public boolean rejectRequest(int requestId, int currentUserId) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            String aCol = getColAddr(conn);
            String sql = "DELETE FROM friendships WHERE id = ? AND " + aCol + " = ? AND status = 'PENDING'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, requestId);
                ps.setInt(2, currentUserId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            System.err.println("[FriendDAO ERROR] rejectRequest: " + e.getMessage());
            return false;
        }
    }

    /**
     * Lấy danh sách bạn bè (status = 'ACCEPTED') của một người dùng.
     */
    public List<User> getFriends(int userId) {
        List<User> list = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getConnection()) {
            String rCol = getColReq(conn);
            String aCol = getColAddr(conn);

            String sql = "SELECT u.id, u.username, u.display_name, u.status, u.created_at " +
                         "FROM friendships f " +
                         "JOIN users u ON (CASE WHEN f." + rCol + " = ? THEN f." + aCol + " ELSE f." + rCol + " END) = u.id " +
                         "WHERE (f." + rCol + " = ? OR f." + aCol + " = ?) AND f.status = 'ACCEPTED' " +
                         "ORDER BY u.display_name ASC";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId);
                ps.setInt(2, userId);
                ps.setInt(3, userId);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new User(
                            rs.getInt("id"),
                            rs.getString("username"),
                            "",
                            rs.getString("display_name"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[FriendDAO ERROR] getFriends: " + e.getMessage());
        }
        return list;
    }

    /**
     * Kiểm tra xem 2 người đã là bạn bè (status = 'ACCEPTED') chưa.
     */
    public boolean areFriends(int userId1, int userId2) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            String rCol = getColReq(conn);
            String aCol = getColAddr(conn);

            String sql = "SELECT 1 FROM friendships WHERE ((" + rCol + " = ? AND " + aCol + " = ?) OR (" + rCol + " = ? AND " + aCol + " = ?)) AND status = 'ACCEPTED'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, userId1);
                ps.setInt(2, userId2);
                ps.setInt(3, userId2);
                ps.setInt(4, userId1);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            System.err.println("[FriendDAO ERROR] areFriends: " + e.getMessage());
            return false;
        }
    }
}
