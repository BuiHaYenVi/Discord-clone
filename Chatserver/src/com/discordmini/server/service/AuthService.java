package com.discordmini.server.service;

import com.discordmini.server.auth.JwtUtil;
import com.discordmini.server.dao.UserDAO;
import com.discordmini.server.model.User;
import com.discordmini.server.session.EventBus;

import java.util.LinkedHashMap;
import java.util.Map;

public class AuthService {
    private final UserDAO userDAO;
    private final EventBus eventBus;

    public AuthService() {
        this(new UserDAO(), EventBus.getInstance());
    }

    public AuthService(UserDAO userDAO) {
        this(userDAO, EventBus.getInstance());
    }

    public AuthService(UserDAO userDAO, EventBus eventBus) {
        this.userDAO = userDAO != null ? userDAO : new UserDAO();
        this.eventBus = eventBus != null ? eventBus : EventBus.getInstance();
    }

    public User login(String username, String password) throws Exception {
        return userDAO.login(username, password);
    }

    public User register(String username, String password, String displayName) throws Exception {
        if (username == null || username.isBlank() || password == null || password.isBlank()) return null;
        String name = (displayName == null || displayName.isBlank()) ? username.trim() : displayName.trim();
        return userDAO.register(username.trim(), password, name);
    }

    public String issueToken(User user) {
        return JwtUtil.generateToken(user);
    }

    public User verifyJwt(String token) {
        JwtUtil.JwtClaims claims = JwtUtil.verifyToken(token);
        if (claims == null) {
            return null;
        }
        User user = userDAO.findById(claims.userId());
        if (user == null) {
            user = new User(claims.userId(), claims.username(), "", claims.displayName());
            user.setOnline(true);
        }
        return user;
    }

    public User findById(int userId) {
        return userDAO.findById(userId);
    }

    public User findByUsername(String username) {
        return userDAO.findByUsername(username);
    }

    public boolean updatePresence(int userId, String presenceStatus, String customStatus) {
        String normalized = (presenceStatus != null && !presenceStatus.isBlank())
                ? presenceStatus.trim().toUpperCase()
                : "ONLINE";
        if (!"ONLINE".equals(normalized) && !"IDLE".equals(normalized)
                && !"DND".equals(normalized) && !"OFFLINE".equals(normalized)) {
            return false;
        }
        boolean ok = userDAO.updatePresence(userId, normalized, customStatus);
        String tcpFrame = "PRESENCE_UPDATED|" + userId + "|" + normalized;
        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("userId", userId);
        wsPayload.put("status", normalized);
        wsPayload.put("presenceStatus", normalized);
        if (customStatus != null) {
            wsPayload.put("customStatus", customStatus);
        }
        eventBus.publishGlobal(tcpFrame, "PRESENCE_UPDATED", wsPayload, null);
        return ok;
    }

    public UserDAO getUserDAO() {
        return userDAO;
    }
}
