package com.discordmini.client.network;

/**
 * Giao diện lắng nghe tiến trình tự động kết nối lại (AN-10).
 */
public interface ReconnectListener {
    /**
     * Kích hoạt trước mỗi lần thử kết nối lại kèm thời gian chờ (ms).
     */
    void onReconnectAttempt(int attempt, int maxAttempts, long delayMs);

    /**
     * Kích hoạt khi toàn bộ quy trình phục hồi thành công (TCP -> Auth -> Rejoin room).
     */
    void onReconnectSucceeded();

    /**
     * Kích hoạt khi hết số lần retry tối đa mà vẫn không thành công.
     */
    void onReconnectFailed(String reason);

    /**
     * Kích hoạt khi tiến trình retry bị hủy (do đóng ứng dụng hoặc đăng xuất).
     */
    void onReconnectCancelled();
}
