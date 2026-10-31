package com.discordmini.server.model;

import java.util.ArrayList;
import java.util.List;

public class DirectMessage {
    private long id;
    private long conversationId;
    private int senderId;
    private String senderDisplayName;
    private String senderAvatarColor;
    private String content;
    private String sentAt;
    private String editedAt;
    private String deletedAt;
    private Long replyToMessageId;
    private List<Attachment> attachments = new ArrayList<>();
    private List<ReactionSummary> reactions = new ArrayList<>();

    public DirectMessage() {
    }

    public DirectMessage(long id, long conversationId, int senderId,
                         String senderDisplayName, String senderAvatarColor,
                         String content, String sentAt, String editedAt, String deletedAt,
                         Long replyToMessageId) {
        this.id = id;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.senderDisplayName = senderDisplayName;
        this.senderAvatarColor = senderAvatarColor != null ? senderAvatarColor : "#2563EB";
        this.content = content;
        this.sentAt = sentAt;
        this.editedAt = editedAt;
        this.deletedAt = deletedAt;
        this.replyToMessageId = replyToMessageId;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getConversationId() { return conversationId; }
    public void setConversationId(long conversationId) { this.conversationId = conversationId; }

    public int getSenderId() { return senderId; }
    public void setSenderId(int senderId) { this.senderId = senderId; }

    public String getSenderDisplayName() { return senderDisplayName; }
    public void setSenderDisplayName(String senderDisplayName) { this.senderDisplayName = senderDisplayName; }

    public String getSenderAvatarColor() { return senderAvatarColor; }
    public void setSenderAvatarColor(String senderAvatarColor) { this.senderAvatarColor = senderAvatarColor; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSentAt() { return sentAt; }
    public void setSentAt(String sentAt) { this.sentAt = sentAt; }

    public String getEditedAt() { return editedAt; }
    public void setEditedAt(String editedAt) { this.editedAt = editedAt; }

    public String getDeletedAt() { return deletedAt; }
    public void setDeletedAt(String deletedAt) { this.deletedAt = deletedAt; }

    public Long getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(Long replyToMessageId) { this.replyToMessageId = replyToMessageId; }

    public List<Attachment> getAttachments() { return attachments; }
    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments != null ? attachments : new ArrayList<>();
    }

    public List<ReactionSummary> getReactions() { return reactions; }
    public void setReactions(List<ReactionSummary> reactions) {
        this.reactions = reactions != null ? reactions : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "DirectMessage{id=" + id + ", conversationId=" + conversationId +
               ", senderId=" + senderId + ", content='" + content + "'}";
    }
}
