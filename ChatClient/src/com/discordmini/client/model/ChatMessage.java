package com.discordmini.client.model;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Biểu diễn thông tin một tin nhắn Chat phía Client.
 *
 * Phân biệt rõ:
 * - requestId: Chuỗi định danh yêu cầu do Client tự sinh để theo dõi vòng đời gửi tin và nhận ACK (@RID).
 * - serverMessageId: Số nguyên định danh do Server Database sinh ra (id trong bảng messages).
 */
public class ChatMessage {
    private final String requestId;           // Client-generated request ID (null nếu là tin từ người khác)
    private volatile Integer serverMessageId;   // Database message ID từ Server (null nếu đang PENDING)
    private final String sender;              // Tên hiển thị người gửi
    private final String content;             // Nội dung tin nhắn (hỗ trợ Unicode tiếng Việt, emoji)
    private final String timestamp;           // Thời gian định dạng HH:mm:ss
    private volatile MessageStatus status;    // Trạng thái: PENDING, SENT, FAILED, UNKNOWN_TIMEOUT
    private volatile String errorMessage;     // Thông điệp lỗi nếu FAILED
    private final boolean isMine;             // Đánh dấu tin nhắn do chính người dùng hiện tại gửi

    public ChatMessage(String requestId, Integer serverMessageId, String sender, String content, String timestamp, MessageStatus status, boolean isMine) {
        this.requestId = requestId;
        this.serverMessageId = serverMessageId;
        this.sender = sender;
        this.content = content;
        this.timestamp = timestamp;
        this.status = status;
        this.isMine = isMine;
    }

    public static ChatMessage createOutgoing(String requestId, String sender, String content) {
        String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return new ChatMessage(requestId, null, sender, content, now, MessageStatus.PENDING, true);
    }

    public static ChatMessage createIncoming(int serverMessageId, String sender, String content, String timestamp, boolean isMine) {
        return new ChatMessage(null, serverMessageId, sender, content, timestamp, MessageStatus.SENT, isMine);
    }

    public String getRequestId() {
        return requestId;
    }

    public Integer getServerMessageId() {
        return serverMessageId;
    }

    public void setServerMessageId(Integer serverMessageId) {
        this.serverMessageId = serverMessageId;
    }

    public String getSender() {
        return sender;
    }

    public String getContent() {
        return content;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isMine() {
        return isMine;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatMessage that = (ChatMessage) o;
        if (serverMessageId != null && that.serverMessageId != null) {
            return Objects.equals(serverMessageId, that.serverMessageId);
        }
        if (requestId != null && that.requestId != null) {
            return Objects.equals(requestId, that.requestId);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverMessageId != null ? serverMessageId : requestId);
    }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + sender + ": " + content + " (" + status + ")";
    }
}
