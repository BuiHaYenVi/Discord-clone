package com.discordmini.client.service;

/**
 * Giao diện callback nhận kết quả xác thực từ AuthService.
 * Giúp tách biệt hoàn toàn nghiệp vụ xác thực khỏi giao diện Swing.
 */
public interface AuthCallback {
    /**
     * Xác thực thành công (đăng nhập hoặc đăng ký thành công).
     */
    void onSuccess(AuthResult result);

    /**
     * Lỗi nghiệp vụ từ Server (sai mật khẩu, username đã tồn tại...).
     */
    void onError(String errorMessage);

    /**
     * Hết thời gian chờ phản hồi từ Server (Timeout).
     */
    void onTimeout();

    /**
     * Mất kết nối mạng hoặc server sập trong khi đang chờ xác thực.
     */
    void onNetworkError();
}
