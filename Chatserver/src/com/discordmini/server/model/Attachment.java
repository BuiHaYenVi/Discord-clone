package com.discordmini.server.model;

public class Attachment {
    private long id;
    private int uploaderId;
    private Integer roomId;
    private Long directConversationId;
    private Integer messageId;
    private String originalName;
    private String storedName;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private byte[] data;
    private String createdAt;

    public Attachment() {
    }

    public Attachment(long id, int uploaderId, Integer roomId, Long directConversationId, Integer messageId,
                      String originalName, String storedName, String contentType,
                      long sizeBytes, String sha256, String createdAt) {
        this.id = id;
        this.uploaderId = uploaderId;
        this.roomId = roomId;
        this.directConversationId = directConversationId;
        this.messageId = messageId;
        this.originalName = originalName;
        this.storedName = storedName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.createdAt = createdAt;
    }

    public Attachment(long id, int uploaderId, Integer roomId, Long directConversationId, Integer messageId,
                      String originalName, String storedName, String contentType,
                      long sizeBytes, String sha256, byte[] data, String createdAt) {
        this(id, uploaderId, roomId, directConversationId, messageId, originalName, storedName,
                contentType, sizeBytes, sha256, createdAt);
        this.data = data;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public int getUploaderId() { return uploaderId; }
    public void setUploaderId(int uploaderId) { this.uploaderId = uploaderId; }

    public Integer getRoomId() { return roomId; }
    public void setRoomId(Integer roomId) { this.roomId = roomId; }

    public Long getDirectConversationId() { return directConversationId; }
    public void setDirectConversationId(Long directConversationId) { this.directConversationId = directConversationId; }

    public Integer getMessageId() { return messageId; }
    public void setMessageId(Integer messageId) { this.messageId = messageId; }

    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }

    public String getStoredName() { return storedName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUrl() {
        return "/api/files/" + id;
    }

    @Override
    public String toString() {
        return "Attachment{id=" + id + ", originalName='" + originalName + "', contentType='" + contentType +
               "', sizeBytes=" + sizeBytes + "}";
    }
}
