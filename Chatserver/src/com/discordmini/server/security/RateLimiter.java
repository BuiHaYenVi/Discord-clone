package com.discordmini.server.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bộ giới hạn tần suất (Rate Limiter) an toàn đa luồng sử dụng cơ chế Sliding Window.
 * Bảo vệ máy chủ khỏi tấn công vét cạn mật khẩu (Brute-force Auth) và spam tin nhắn (Message Flood).
 */
public final class RateLimiter {

    public static final int DEFAULT_MAX_AUTH_ATTEMPTS = 10;
    public static final long DEFAULT_AUTH_WINDOW_MS = 60_000L;

    public static final int DEFAULT_MAX_MESSAGES = 30;
    public static final long DEFAULT_MESSAGE_WINDOW_MS = 10_000L;

    private static final String AUTH_PREFIX = "auth:";
    private static final String MSG_PREFIX = "msg:";

    private static final RateLimiter INSTANCE = new RateLimiter();

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxAuthAttempts;
    private final long authWindowMs;
    private final int maxMessages;
    private final long messageWindowMs;

    public RateLimiter() {
        this(DEFAULT_MAX_AUTH_ATTEMPTS, DEFAULT_AUTH_WINDOW_MS, DEFAULT_MAX_MESSAGES, DEFAULT_MESSAGE_WINDOW_MS);
    }

    public RateLimiter(int maxAuthAttempts, long authWindowMs, int maxMessages, long messageWindowMs) {
        this.maxAuthAttempts = maxAuthAttempts;
        this.authWindowMs = authWindowMs;
        this.maxMessages = maxMessages;
        this.messageWindowMs = messageWindowMs;
    }

    public static RateLimiter getInstance() {
        return INSTANCE;
    }

    /**
     * Kiểm tra và ghi nhận một lượt thử xác thực (LOGIN / REGISTER).
     * Mặc định: tối đa 10 lần / 60 giây trên mỗi clientKey (IP hoặc username).
     */
    public boolean allowAuthAttempt(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            return false;
        }
        String normalizedKey = AUTH_PREFIX + clientKey.trim().toLowerCase();
        return allow(normalizedKey, maxAuthAttempts, authWindowMs);
    }

    /**
     * Kiểm tra và ghi nhận một lượt gửi tin nhắn của người dùng.
     * Mặc định: tối đa 30 tin nhắn / 10 giây trên mỗi userId.
     */
    public boolean allowMessage(int userId) {
        if (userId <= 0) {
            return false;
        }
        String key = MSG_PREFIX + userId;
        return allow(key, maxMessages, messageWindowMs);
    }

    /**
     * Đặt lại bộ đếm giới hạn tần suất cho một khóa cụ thể.
     */
    public void reset(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        String trimmed = key.trim();
        windows.remove(trimmed);
        windows.remove(AUTH_PREFIX + trimmed.toLowerCase());
        windows.remove(MSG_PREFIX + trimmed);
    }

    /**
     * Đặt lại bộ đếm giới hạn tin nhắn cho userId.
     */
    public void reset(int userId) {
        windows.remove(MSG_PREFIX + userId);
    }

    private boolean allow(String key, int maxRequests, long windowMs) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> new Window());
        return window.tryAcquire(now, maxRequests, windowMs);
    }

    static final class Window {
        private final Deque<Long> timestamps = new ArrayDeque<>();

        synchronized boolean tryAcquire(long now, int maxRequests, long windowMs) {
            long cutoff = now - windowMs;
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequests) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }
}
