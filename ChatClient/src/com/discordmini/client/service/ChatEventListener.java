package com.discordmini.client.service;

import com.discordmini.client.model.ChatMessage;
import com.discordmini.client.model.MessageStatus;

import java.util.List;

/**
 * Giao diện lắng nghe các sự kiện Chat từ ChatService.
 */
public interface ChatEventListener {
    /**
     * Kích hoạt khi có tin nhắn mới trong phòng (realtime broadcast).
     */
    default void onNewMessage(ChatMessage message) {}

    /**
     * Kích hoạt khi trạng thái của một tin nhắn do Client gửi thay đổi
     * (PENDING -> SENT / FAILED / UNKNOWN_TIMEOUT).
     */
    default void onMessageStatusChanged(String requestId, MessageStatus status, String errorMessage) {}

    /**
     * Kích hoạt khi toàn bộ lịch sử tin nhắn của phòng được tải xong và sắp xếp đúng thứ tự.
     */
    default void onHistoryLoaded(List<ChatMessage> history) {}

    /**
     * Kích hoạt khi nhận được tin nhắn riêng 1-1 từ người dùng khác.
     */
    default void onPrivateMessage(int senderId, String senderName, String content, String timestamp) {}

    /**
     * Kích hoạt khi Server xác nhận tin nhắn riêng 1-1 đã gửi tới người nhận.
     */
    default void onPrivateMessageSent(int targetUserId) {}

    /**
     * Kích hoạt khi có lỗi nghiệp vụ chat từ Server.
     */
    default void onChatError(String errorMessage) {}
}
