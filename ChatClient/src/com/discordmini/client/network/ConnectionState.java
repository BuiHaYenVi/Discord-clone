package com.discordmini.client.network;

/**
 * Các trạng thái kết nối mạng của Client.
 *
 * Tiêu chí:
 * - DISCONNECTED: Chưa kết nối, bị ngắt mạng hoặc đã đóng chủ động.
 * - CONNECTING: Đang trong tiến trình bắt tay thiết lập socket TCP.
 * - CONNECTED: Socket TCP đã nối thành công (chưa xác thực người dùng).
 * - AUTHENTICATED: Đã xác thực đăng nhập thành công từ phản hồi của Server.
 * - RECONNECTING: Sẵn sàng trong tiến trình chuẩn bị kết nối lại.
 */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    RECONNECTING
}
