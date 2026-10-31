package com.discordmini.client.model;

/**
 * Trạng thái gửi của một tin nhắn phía Client.
 *
 * Tiêu chí:
 * - PENDING: Tin nhắn đang gửi đi, đang chờ phản hồi ACK từ Server.
 * - SENT: Server đã nhận và lưu thành công (ACK khớp đúng requestId của tin).
 * - FAILED: Gửi tin thất bại (Server trả về ERROR hoặc lỗi kết nối).
 * - UNKNOWN_TIMEOUT: Hết thời gian chờ ACK từ Server (không xác định được Server đã lưu hay chưa; không tự động gửi lại).
 */
public enum MessageStatus {
    PENDING,
    SENT,
    FAILED,
    UNKNOWN_TIMEOUT
}
