package com.discordmini.server.model;

public class DirectConversation {
    private long id;
    private int userLowId;
    private int userHighId;
    private int peerUserId;
    private String peerUsername;
    private String peerDisplayName;
    private String peerAvatarColor;
    private String peerPresenceStatus;
    private String lastMessagePreview;
    private String createdAt;
    private int unreadCount;

    public DirectConversation() {
    }

    public DirectConversation(long id, int userLowId, int userHighId, int peerUserId,
                              String peerUsername, String peerDisplayName,
                              String peerAvatarColor, String peerPresenceStatus,
                              String lastMessagePreview, String createdAt) {
        this.id = id;
        this.userLowId = userLowId;
        this.userHighId = userHighId;
        this.peerUserId = peerUserId;
        this.peerUsername = peerUsername;
        this.peerDisplayName = peerDisplayName;
        this.peerAvatarColor = peerAvatarColor != null ? peerAvatarColor : "#2563EB";
        this.peerPresenceStatus = peerPresenceStatus != null ? peerPresenceStatus : "OFFLINE";
        this.lastMessagePreview = lastMessagePreview != null ? lastMessagePreview : "";
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public int getUserLowId() { return userLowId; }
    public void setUserLowId(int userLowId) { this.userLowId = userLowId; }

    public int getUserHighId() { return userHighId; }
    public void setUserHighId(int userHighId) { this.userHighId = userHighId; }

    public int getPeerUserId() { return peerUserId; }
    public void setPeerUserId(int peerUserId) { this.peerUserId = peerUserId; }

    public String getPeerUsername() { return peerUsername; }
    public void setPeerUsername(String peerUsername) { this.peerUsername = peerUsername; }

    public String getPeerDisplayName() { return peerDisplayName; }
    public void setPeerDisplayName(String peerDisplayName) { this.peerDisplayName = peerDisplayName; }

    public String getPeerAvatarColor() { return peerAvatarColor; }
    public void setPeerAvatarColor(String peerAvatarColor) { this.peerAvatarColor = peerAvatarColor; }

    public String getPeerPresenceStatus() { return peerPresenceStatus; }
    public void setPeerPresenceStatus(String peerPresenceStatus) { this.peerPresenceStatus = peerPresenceStatus; }

    public String getLastMessagePreview() { return lastMessagePreview; }
    public void setLastMessagePreview(String lastMessagePreview) { this.lastMessagePreview = lastMessagePreview; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = Math.max(0, unreadCount); }

    @Override
    public String toString() {
        return "DirectConversation{id=" + id + ", peerUserId=" + peerUserId +
               ", peerDisplayName='" + peerDisplayName + "'}";
    }
}
