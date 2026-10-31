package com.discordmini.server.model;

import java.sql.Timestamp;

public class User {
    private int id;
    private String username;
    private String password;
    private String displayName;
    private String status; // "ONLINE" or "OFFLINE"
    private Timestamp createdAt;
    private String avatarColor = "#2563EB";
    private String presenceStatus = "ONLINE";
    private String customStatus = "";
    private String role = "MEMBER";

    public User() {
        this.status = "OFFLINE";
    }

    public User(int id, String username, String password, String displayName) {
        this(id, username, password, displayName, "OFFLINE", null);
    }

    public User(int id, String username, String password, String displayName, String status, Timestamp createdAt) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.status = status != null ? status : "OFFLINE";
        this.presenceStatus = this.status;
        this.createdAt = createdAt;
    }

    public User(int id, String username, String password, String displayName, String status, Timestamp createdAt,
                String avatarColor, String presenceStatus, String customStatus) {
        this(id, username, password, displayName, status, createdAt);
        this.avatarColor = (avatarColor != null && !avatarColor.isBlank()) ? avatarColor : "#2563EB";
        this.presenceStatus = (presenceStatus != null && !presenceStatus.isBlank()) ? presenceStatus : this.status;
        this.customStatus = customStatus != null ? customStatus : "";
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isOnline() { return !"OFFLINE".equalsIgnoreCase(this.status) && !"OFFLINE".equalsIgnoreCase(this.presenceStatus); }
    public void setOnline(boolean online) {
        this.status = online ? "ONLINE" : "OFFLINE";
        if (!online) {
            this.presenceStatus = "OFFLINE";
        } else if ("OFFLINE".equalsIgnoreCase(this.presenceStatus)) {
            this.presenceStatus = "ONLINE";
        }
    }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public String getAvatarColor() { return avatarColor; }
    public void setAvatarColor(String avatarColor) { this.avatarColor = avatarColor != null ? avatarColor : "#2563EB"; }

    public String getPresenceStatus() { return presenceStatus; }
    public void setPresenceStatus(String presenceStatus) { this.presenceStatus = presenceStatus != null ? presenceStatus : "ONLINE"; }

    public String getCustomStatus() { return customStatus; }
    public void setCustomStatus(String customStatus) { this.customStatus = customStatus != null ? customStatus : ""; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role != null ? role : "MEMBER"; }

    @Override
    public String toString() {
        return "User{id=" + id + ", username='" + username + "', displayName='" + displayName +
               "', status='" + status + "', presenceStatus='" + presenceStatus + "'}";
    }
}
