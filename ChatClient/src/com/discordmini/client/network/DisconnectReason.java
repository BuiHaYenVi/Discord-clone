package com.discordmini.client.network;

/**
 * Nguyên nhân dẫn đến việc ngắt kết nối.
 * Giúp phân biệt việc đóng chủ động/đăng xuất với việc mất mạng ngoài ý muốn.
 */
public enum DisconnectReason {
    NONE,
    MANUAL_CLOSE,       // Đóng ứng dụng hoặc gọi disconnect/close chủ động
    NETWORK_LOST,       // Mất kết nối mạng đột ngột hoặc server đóng socket (EOF/IOException)
    SERVER_SHUTDOWN,    // Server gửi tín hiệu dừng hoạt động
    TIMEOUT,            // Heartbeat hoặc Request timeout
    CONNECTION_FAILED   // Thất bại trong quá trình thiết lập TCP (Connection refused...)
}
