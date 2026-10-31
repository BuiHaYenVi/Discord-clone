package com.discordmini.server.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class Message {
    private int id;
    private int roomId;
    private int userId; // sender_id
    private String sender; // sender display_name
    private String content;
    private String timestamp; // formatted sent_at
    private boolean isEdited;

    private Integer replyToId;
    private String replyToSender;
    private String replyToContent;
    private String editedAt;
    private String deletedAt;
    private boolean pinned;
    private List<ReactionSummary> reactions = new ArrayList<>();
    private List<Attachment> attachments = new ArrayList<>();

    public Message() {
    }

    public Message(int id, int roomId, int userId, String sender, String content, String timestamp) {
        this.id = id;
        this.roomId = roomId;
        this.userId = userId;
        this.sender = sender;
        this.content = content;
        this.timestamp = timestamp;
    }

    public Message(int id, int roomId, int userId, String sender, String content) {
        this(id, roomId, userId, sender, content,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    public Message(int id, int roomId, int userId, String sender, String content, String timestamp, boolean isEdited) {
        this(id, roomId, userId, sender, content, timestamp);
        this.isEdited = isEdited;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getRoomId() { return roomId; }
    public void setRoomId(int roomId) { this.roomId = roomId; }

    public int getUserId() { return userId; }
    public int getSenderId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }
    public void setSenderId(int senderId) { this.userId = senderId; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTimestamp() { return timestamp; }
    public String getSentAt() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public void setSentAt(String sentAt) { this.timestamp = sentAt; }

    public Integer getReplyToId() { return replyToId; }
    public void setReplyToId(Integer replyToId) { this.replyToId = replyToId; }

    public String getReplyToSender() { return replyToSender; }
    public void setReplyToSender(String replyToSender) { this.replyToSender = replyToSender; }

    public String getReplyToContent() { return replyToContent; }
    public void setReplyToContent(String replyToContent) { this.replyToContent = replyToContent; }

    public String getEditedAt() { return editedAt; }
    public void setEditedAt(String editedAt) {
        this.editedAt = editedAt;
        this.isEdited = editedAt != null && !editedAt.isBlank();
    }
    public boolean isEdited() { return isEdited || (editedAt != null && !editedAt.isBlank()); }
    public void setEdited(boolean edited) { this.isEdited = edited; }

    public String getDeletedAt() { return deletedAt; }
    public void setDeletedAt(String deletedAt) { this.deletedAt = deletedAt; }

    public boolean isPinned() { return pinned; }
    public boolean getPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public List<ReactionSummary> getReactions() { return reactions; }
    public void setReactions(List<ReactionSummary> reactions) {
        this.reactions = reactions != null ? reactions : new ArrayList<>();
    }

    public List<Attachment> getAttachments() { return attachments; }
    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments != null ? attachments : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "Message{id=" + id + ", roomId=" + roomId + ", userId=" + userId +
               ", sender='" + sender + "', content='" + content + "', timestamp='" + timestamp +
               "', replyToId=" + replyToId + ", pinned=" + pinned + "}";
    }
}
