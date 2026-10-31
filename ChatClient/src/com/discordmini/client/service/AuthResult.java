package com.discordmini.client.service;

import java.util.Objects;

/**
 * Kết quả xác thực (Đăng nhập / Đăng ký) trả về từ AuthService.
 */
public final class AuthResult {
    private final boolean isSuccess;
    private final boolean isLogin;
    private final int userId;
    private final String username;
    private final String displayName;
    private final String message;

    public AuthResult(boolean isSuccess, boolean isLogin, int userId, String username, String displayName, String message) {
        this.isSuccess = isSuccess;
        this.isLogin = isLogin;
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.message = message;
    }

    public static AuthResult loginSuccess(int userId, String username, String displayName) {
        return new AuthResult(true, true, userId, username, displayName, "Đăng nhập thành công.");
    }

    public static AuthResult registerSuccess(String message) {
        return new AuthResult(true, false, 0, null, null, message);
    }

    // Record-style accessors
    public boolean isSuccess() { return isSuccess; }
    public boolean isLogin() { return isLogin; }
    public int userId() { return userId; }
    public String username() { return username; }
    public String displayName() { return displayName; }
    public String message() { return message; }

    // Standard JavaBean getters
    public boolean getIsSuccess() { return isSuccess; }
    public boolean getIsLogin() { return isLogin; }
    public int getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getMessage() { return message; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuthResult that = (AuthResult) o;
        return isSuccess == that.isSuccess &&
                isLogin == that.isLogin &&
                userId == that.userId &&
                Objects.equals(username, that.username) &&
                Objects.equals(displayName, that.displayName) &&
                Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isSuccess, isLogin, userId, username, displayName, message);
    }

    @Override
    public String toString() {
        return "AuthResult{" +
                "isSuccess=" + isSuccess +
                ", isLogin=" + isLogin +
                ", userId=" + userId +
                ", username='" + username + '\'' +
                ", displayName='" + displayName + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
