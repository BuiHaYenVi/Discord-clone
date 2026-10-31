package com.discordmini.server.dao;

import com.discordmini.server.model.User;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * [HAI-03] User DAO: Thực hiện các thao tác CRUD với bảng users.
 * Sử dụng PreparedStatement và try-with-resources để quản lý bộ nhớ an toàn.
 */
public class UserDAO {

    private static final String[] AVATAR_PALETTE = {
        "#2563EB", "#059669", "#D97706", "#DC2626", "#7C3AED", "#DB2777", "#0891B2", "#4F46E5"
    };

    private static String pickAvatarColor(String username) {
        if (username == null || username.isEmpty()) return "#2563EB";
        int idx = Math.abs(username.toLowerCase().hashCode()) % AVATAR_PALETTE.length;
        return AVATAR_PALETTE[idx];
    }

    private User mapUser(ResultSet rs) throws SQLException {
        User user = new User(
            rs.getInt("id"),
            rs.getString("username"),
            rs.getString("password"),
            rs.getString("display_name"),
            rs.getString("status"),
            rs.getTimestamp("created_at")
        );
        try {
            String avatarColor = rs.getString("avatar_color");
            if (avatarColor != null && !avatarColor.isBlank()) {
                user.setAvatarColor(avatarColor);
            } else {
                user.setAvatarColor(pickAvatarColor(user.getUsername()));
            }
        } catch (SQLException ignored) {
            user.setAvatarColor(pickAvatarColor(user.getUsername()));
        }
        try {
            String presence = rs.getString("presence_status");
            if (presence != null && !presence.isBlank()) {
                user.setPresenceStatus(presence);
            }
        } catch (SQLException ignored) {
        }
        try {
            String custom = rs.getString("custom_status");
            user.setCustomStatus(custom != null ? custom : "");
        } catch (SQLException ignored) {
        }
        return user;
    }

    /**
     * Đăng ký tài khoản mới với username và password.
     * Trả về boolean đại diện cho kết quả thành công.
     */
    public boolean register(String username, String password) throws SQLException {
        User created = register(username, password, username);
        return created != null;
    }

    /**
     * Đăng ký tài khoản mới đầy đủ thông tin (username, password, display_name).
     * Trả về đối tượng User vừa tạo hoặc null nếu trùng lặp/lỗi.
     */
    public User register(String username, String password, String displayName) throws SQLException {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return null;
        }

        String checkSql = "SELECT id FROM users WHERE LOWER(username) = LOWER(?)";
        String insertSql = "INSERT INTO users (username, password, display_name, status, avatar_color, presence_status, custom_status) " +
                           "VALUES (?, ?, ?, 'OFFLINE', ?, 'ONLINE', '') RETURNING id, created_at";

        try (Connection conn = DatabaseConnection.getConnection()) {
            try (PreparedStatement checkPs = conn.prepareStatement(checkSql)) {
                checkPs.setString(1, username.trim());
                try (ResultSet checkRs = checkPs.executeQuery()) {
                    if (checkRs.next()) {
                        System.err.println("[UserDAO] Username đã tồn tại: " + username);
                        return null;
                    }
                }
            }

            String hashedPassword = com.discordmini.server.security.PasswordUtil.hashPassword(password);
            String cleanName = (displayName == null || displayName.isBlank()) ? username.trim() : displayName.trim();
            String avatarColor = pickAvatarColor(username.trim());

            try (PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                insertPs.setString(1, username.trim());
                insertPs.setString(2, hashedPassword);
                insertPs.setString(3, cleanName);
                insertPs.setString(4, avatarColor);

                try (ResultSet rs = insertPs.executeQuery()) {
                    if (rs.next()) {
                        int id = rs.getInt("id");
                        Timestamp createdAt = rs.getTimestamp("created_at");
                        User created = new User(id, username.trim(), hashedPassword, cleanName, "OFFLINE", createdAt,
                                avatarColor, "ONLINE", "");
                        enrollInDefaultGuildSilently(conn, id);
                        return created;
                    }
                }
            } catch (SQLException fallbackEx) {
                // Fallback nếu cột V003 chưa được migrate
                String fallbackInsert = "INSERT INTO users (username, password, display_name, status) VALUES (?, ?, ?, 'OFFLINE') RETURNING id, created_at";
                try (PreparedStatement ps2 = conn.prepareStatement(fallbackInsert)) {
                    ps2.setString(1, username.trim());
                    ps2.setString(2, hashedPassword);
                    ps2.setString(3, cleanName);
                    try (ResultSet rs = ps2.executeQuery()) {
                        if (rs.next()) {
                            int id = rs.getInt("id");
                            Timestamp createdAt = rs.getTimestamp("created_at");
                            return new User(id, username.trim(), hashedPassword, cleanName, "OFFLINE", createdAt);
                        }
                    }
                }
            }
        }
        return null;
    }

    private void enrollInDefaultGuildSilently(Connection conn, int userId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO guild_members (guild_id, user_id, role) " +
                "SELECT id, ?, 'MEMBER' FROM guilds ORDER BY id ASC LIMIT 1 " +
                "ON CONFLICT (guild_id, user_id) DO NOTHING")) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO room_members (room_id, user_id, role) " +
                "SELECT r.id, ?, 'MEMBER' FROM rooms r " +
                "WHERE COALESCE(r.is_private, false) = false " +
                "ON CONFLICT (room_id, user_id) DO NOTHING")) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    /**
     * Xác thực đăng nhập với username và password.
     * Sử dụng PasswordUtil hỗ trợ cả PBKDF2 và tự động nâng cấp plaintext cũ.
     */
    public User login(String username, String password) throws SQLException {
        if (username == null || password == null) return null;

        String sql = "SELECT * FROM users WHERE LOWER(username) = LOWER(?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username.trim());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedPassword = rs.getString("password");
                    if (com.discordmini.server.security.PasswordUtil.verifyPassword(password, storedPassword)) {
                        int userId = rs.getInt("id");
                        User user = mapUser(rs);
                        if (com.discordmini.server.security.PasswordUtil.needsRehash(storedPassword)) {
                            updateUserPassword(userId, com.discordmini.server.security.PasswordUtil.hashPassword(password));
                        }
                        updateUserStatus(user.getId(), "ONLINE");
                        user.setStatus("ONLINE");
                        if ("OFFLINE".equalsIgnoreCase(user.getPresenceStatus())) {
                            user.setPresenceStatus("ONLINE");
                        }
                        enrollInDefaultGuildSilently(conn, userId);
                        return user;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Cập nhật mật khẩu mới (đã băm) cho tài khoản.
     */
    public boolean updateUserPassword(int userId, String newHashedPassword) {
        String sql = "UPDATE users SET password = ? WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newHashedPassword);
            ps.setInt(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[UserDAO ERROR] Lỗi cập nhật mật khẩu user (ID=" + userId + "): " + e.getMessage());
            return false;
        }
    }

    /**
     * Cập nhật trạng thái ONLINE / OFFLINE cho tài khoản.
     */
    public boolean updateUserStatus(int userId, String status) {
        String normalized = status != null ? status.toUpperCase() : "OFFLINE";
        String sql = "UPDATE users SET status = ?, presence_status = CASE WHEN ? = 'OFFLINE' THEN 'OFFLINE' ELSE COALESCE(NULLIF(presence_status, 'OFFLINE'), 'ONLINE') END WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalized);
            ps.setString(2, normalized);
            ps.setInt(3, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            String fallbackSql = "UPDATE users SET status = ? WHERE id = ?";
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement ps = conn.prepareStatement(fallbackSql)) {
                ps.setString(1, normalized);
                ps.setInt(2, userId);
                return ps.executeUpdate() > 0;
            } catch (SQLException ex) {
                System.err.println("[UserDAO ERROR] Lỗi cập nhật status user (ID=" + userId + "): " + ex.getMessage());
                return false;
            }
        }
    }

    /**
     * Cập nhật trạng thái hiện diện (ONLINE, IDLE, DND, OFFLINE) và custom_status của người dùng.
     */
    public boolean updatePresence(int userId, String presenceStatus, String customStatus) {
        String normalizedPresence = "ONLINE";
        if (presenceStatus != null && !presenceStatus.isBlank()) {
            String upper = presenceStatus.trim().toUpperCase();
            if ("ONLINE".equals(upper) || "IDLE".equals(upper) || "DND".equals(upper) || "OFFLINE".equals(upper)) {
                normalizedPresence = upper;
            }
        }
        String basicStatus = "OFFLINE".equals(normalizedPresence) ? "OFFLINE" : "ONLINE";
        String sql = "UPDATE users SET presence_status = ?, custom_status = COALESCE(?, custom_status), status = ? WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizedPresence);
            if (customStatus != null) {
                ps.setString(2, customStatus.length() > 120 ? customStatus.substring(0, 120) : customStatus);
            } else {
                ps.setNull(2, Types.VARCHAR);
            }
            ps.setString(3, basicStatus);
            ps.setInt(4, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[UserDAO ERROR] Lỗi updatePresence (userID=" + userId + "): " + e.getMessage());
            return updateUserStatus(userId, basicStatus);
        }
    }

    /**
     * Tìm kiếm thông tin người dùng theo ID.
     */
    public User findById(int userId) {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapUser(rs);
                }
            }
        } catch (SQLException e) {
            System.err.println("[UserDAO ERROR] Lỗi findById (ID=" + userId + "): " + e.getMessage());
        }
        return null;
    }

    /**
     * Tìm kiếm người dùng theo Username (Không phân biệt hoa thường).
     */
    public User findByUsername(String username) {
        return findUser(username);
    }

    /**
     * Tìm kiếm người dùng theo Username (Alias method cho tương thích mã nguồn cũ).
     */
    public User findUser(String username) {
        if (username == null) return null;
        String sql = "SELECT * FROM users WHERE LOWER(username) = LOWER(?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapUser(rs);
                }
            }
        } catch (SQLException e) {
            System.err.println("[UserDAO ERROR] Lỗi findUser ('" + username + "'): " + e.getMessage());
        }
        return null;
    }

    /**
     * Lấy danh sách toàn bộ người dùng trong CSDL.
     */
    public Collection<User> allUsers() {
        List<User> list = new ArrayList<>();
        String sql = "SELECT * FROM users ORDER BY id ASC";
        try (Connection conn = DatabaseConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                list.add(mapUser(rs));
            }
        } catch (SQLException e) {
            System.err.println("[UserDAO ERROR] Lỗi lấy danh sách allUsers: " + e.getMessage());
        }
        return list;
    }
}
