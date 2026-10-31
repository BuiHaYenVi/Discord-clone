package com.discordmini.client.network;

import com.discordmini.client.model.Room;
import com.discordmini.client.service.AuthCallback;
import com.discordmini.client.service.AuthResult;
import com.discordmini.client.service.AuthService;
import com.discordmini.client.service.RoomEventListener;
import com.discordmini.client.service.RoomService;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Trình quản lý tự động kết nối lại (Automatic Reconnection) phía Client.
 *
 * Tiêu chí AN-10:
 * 1. Chỉ tự reconnect khi mất kết nối ngoài ý muốn (NETWORK_LOST hoặc TIMEOUT) sau khi đã đăng nhập.
 * 2. Tối đa 5 lần thử, chờ lần lượt 1, 2, 4, 8, 16 giây; mỗi connect vẫn có timeout hữu hạn.
 * 3. Một sự cố chỉ có một chuỗi retry; không tạo nhiều Socket/reader song song.
 * 4. Chạy ngoài EDT; hiển thị trạng thái và số lần thử lên UI.
 * 5. Phục hồi theo thứ tự nghiêm ngặt: Nối TCP -> Xác thực thành công -> Vào phòng cũ thành công -> Cho phép chat.
 * 6. Không tuyên bố phục hồi thành công ngay khi TCP vừa nối.
 * 7. Kiểm tra cơ chế xác thực thật. Không tự bịa session token hoặc lưu mật khẩu xuống đĩa.
 *    Thông tin đăng nhập chỉ được lưu tạm trong bộ nhớ RAM, giới hạn vòng đời và xóa sạch khi đăng xuất.
 * 8. Hủy retry khi đóng ứng dụng/đăng xuất.
 * 9. Sau 5 lần thất bại, báo rõ và cho người dùng thao tác thử lại thủ công.
 * 10. Không tự gửi lại các tin chưa ACK (các tin này đã chuyển sang UNKNOWN_TIMEOUT ở AN-05).
 * 11. Nếu Server từ chối phiên cũ còn tồn tại (ALREADY_ONLINE), coi như lỗi protocol và retry ở nhịp sau.
 */
public class ReconnectManager {
    public static final long[] DEFAULT_BACKOFF_MS = {1_000, 2_000, 4_000, 8_000, 16_000};
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final SocketClient client;
    private final ConnectionManager connectionManager;
    private final AuthService authService;
    private final RoomService roomService;

    private final long[] backoffDelaysMs;
    private final int maxAttempts;

    // Bộ nhớ tạm thời lưu thông tin đăng nhập trong RAM (tuyệt đối không ghi đĩa)
    private volatile String cachedHost;
    private volatile int cachedPort;
    private volatile String cachedUsername;
    private volatile String cachedPassword;

    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger currentAttempt = new AtomicInteger(0);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ChatClient-ReconnectWorker");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> currentTask = null;
    private final List<ReconnectListener> listeners = new CopyOnWriteArrayList<>();

    public ReconnectManager(SocketClient client, AuthService authService, RoomService roomService) {
        this(client, authService, roomService, DEFAULT_BACKOFF_MS, DEFAULT_MAX_ATTEMPTS);
    }

    public ReconnectManager(SocketClient client, AuthService authService, RoomService roomService,
                            long[] backoffDelaysMs, int maxAttempts) {
        this.client = Objects.requireNonNull(client, "SocketClient không được null");
        this.connectionManager = Objects.requireNonNull(client.getConnectionManager(), "ConnectionManager không được null");
        this.authService = authService;
        this.roomService = roomService;
        this.backoffDelaysMs = (backoffDelaysMs != null && backoffDelaysMs.length > 0) ? backoffDelaysMs : DEFAULT_BACKOFF_MS;
        this.maxAttempts = maxAttempts > 0 ? maxAttempts : DEFAULT_MAX_ATTEMPTS;

        registerConnectionStateListener();
    }

    /**
     * Lưu trữ thông tin đăng nhập tạm thời trong bộ nhớ RAM để phục vụ tự động kết nối lại.
     * Vòng đời của thông tin này gắn liền với phiên làm việc và sẽ bị xóa sạch khi đăng xuất hoặc hủy.
     */
    public synchronized void setCredentials(String host, int port, String username, String password) {
        this.cachedHost = host;
        this.cachedPort = port;
        this.cachedUsername = (username != null) ? username.trim() : null;
        this.cachedPassword = password;
        this.cancelled.set(false);
    }

    /**
     * Xóa sạch thông tin đăng nhập khỏi bộ nhớ RAM khi người dùng đăng xuất hoặc hủy.
     */
    public synchronized void clearCredentials() {
        this.cachedUsername = null;
        this.cachedPassword = null;
    }

    public synchronized boolean hasCredentials() {
        return cachedUsername != null && cachedPassword != null;
    }

    public synchronized String getCachedUsername() {
        return cachedUsername;
    }

    private void registerConnectionStateListener() {
        connectionManager.addStateChangeListener((oldState, newState, reason) -> {
            if (newState == ConnectionState.DISCONNECTED) {
                // Tiêu chí 1: Chỉ tự reconnect khi mất kết nối ngoài ý muốn (NETWORK_LOST hoặc TIMEOUT)
                boolean isUnexpectedLoss = (reason == DisconnectReason.NETWORK_LOST || reason == DisconnectReason.TIMEOUT);
                boolean hasCredentials = (cachedUsername != null && cachedPassword != null);

                if (isUnexpectedLoss && hasCredentials && !cancelled.get()) {
                    triggerReconnect();
                }
            } else if (newState == ConnectionState.AUTHENTICATED && oldState != ConnectionState.AUTHENTICATED) {
                // Chỉ reset khi người dùng đăng nhập bình thường ngoài luồng reconnect.
                // Nếu đang reconnect, currentAttempt phải được giữ nguyên để theo dõi các bước tiếp theo (như khôi phục phòng).
                if (!isReconnecting.get()) {
                    currentAttempt.set(0);
                    if (connectionManager.getReconnectAttempts() != 0) {
                        connectionManager.setReconnectAttempts(0);
                    }
                }
            }
        });
    }

    /**
     * Bắt đầu tiến trình tự động kết nối lại nếu chưa có tiến trình nào đang chạy.
     */
    public boolean triggerReconnect() {
        if (cancelled.get() || client.isConnected()) {
            return false;
        }

        // Tiêu chí 3: Một sự cố chỉ có một chuỗi retry; không tạo nhiều luồng song song
        if (!isReconnecting.compareAndSet(false, true)) {
            return false;
        }

        currentAttempt.set(0);
        scheduleNextAttempt();
        return true;
    }

    /**
     * Lên lịch cho lần thử tiếp theo với thời gian backoff tương ứng.
     */
    private synchronized void scheduleNextAttempt() {
        if (cancelled.get() || client.isConnected()) {
            isReconnecting.set(false);
            return;
        }

        int attempt = currentAttempt.incrementAndGet();

        // Tiêu chí 2 & 9: Hết 5 lần thất bại thì dừng lại và báo rõ
        if (attempt > maxAttempts) {
            isReconnecting.set(false);
            connectionManager.setReconnectAttempts(maxAttempts);
            notifyListeners(l -> l.onReconnectFailed("Đã thử kết nối lại " + maxAttempts + " lần nhưng không thành công."));
            return;
        }

        long delayMs = backoffDelaysMs[Math.min(attempt - 1, backoffDelaysMs.length - 1)];

        // Chuyển trạng thái sang RECONNECTING và cập nhật số lần thử trên EDT
        connectionManager.setReconnecting();
        connectionManager.setReconnectAttempts(attempt);

        notifyListeners(l -> l.onReconnectAttempt(attempt, maxAttempts, delayMs));

        // Lên lịch thực thi trên luồng nền (ngoài EDT)
        currentTask = scheduler.schedule(() -> executeReconnectStep(attempt), delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Thực hiện quy trình phục hồi 4 bước theo đúng thứ tự nghiêm ngặt (chạy ngoài EDT).
     */
    private void executeReconnectStep(int attempt) {
        if (cancelled.get()) {
            isReconnecting.set(false);
            return;
        }

        String host = (cachedHost != null) ? cachedHost : client.getServerHost();
        int port = (cachedPort > 0) ? cachedPort : client.getServerPort();
        if (host == null || port <= 0) {
            host = "127.0.0.1";
            port = 5000;
        }

        try {
            // ==========================================
            // BƯỚC 1: NỐI TCP (CONNECT)
            // ==========================================
            try {
                client.connect(host, port);
            } catch (IOException ioEx) {
                // Thất bại TCP -> lên lịch thử lại lần tiếp theo
                scheduleNextAttempt();
                return;
            }

            if (cancelled.get() || !client.isConnected()) {
                scheduleNextAttempt();
                return;
            }

            // ==========================================
            // BƯỚC 2: XÁC THỰC THÀNH CÔNG (AUTHENTICATION)
            // Tiêu chí 6: Không tuyên bố thành công ngay khi TCP vừa nối
            // ==========================================
            if (authService == null || cachedUsername == null || cachedPassword == null) {
                scheduleNextAttempt();
                return;
            }

            CompletableFuture<Boolean> authFuture = new CompletableFuture<>();
            boolean requested = authService.login(cachedUsername, cachedPassword, new AuthCallback() {
                @Override
                public void onSuccess(AuthResult result) {
                    authFuture.complete(true);
                }

                @Override
                public void onError(String errorMessage) {
                    // Nếu Server báo ALREADY_ONLINE (phiên cũ chưa kịp timeout), coi như lỗi protocol và thử nhịp sau
                    authFuture.complete(false);
                }

                @Override
                public void onTimeout() {
                    authFuture.complete(false);
                }

                @Override
                public void onNetworkError() {
                    authFuture.complete(false);
                }
            });

            if (!requested) {
                client.close();
                scheduleNextAttempt();
                return;
            }

            boolean authSuccess = false;
            try {
                authSuccess = authFuture.get(4, TimeUnit.SECONDS);
            } catch (Exception ex) {
                authSuccess = false;
            }

            if (!authSuccess || cancelled.get()) {
                client.close();
                scheduleNextAttempt();
                return;
            }

            // ==========================================
            // BƯỚC 3: THAM GIA LẠI PHÒNG CŨ (REJOIN LAST ROOM)
            // ==========================================
            boolean needRoomRecovery = (roomService != null && roomService.getLastSuccessfulRoomId() != null);
            if (needRoomRecovery) {
                CompletableFuture<Boolean> roomFuture = new CompletableFuture<>();
                RoomEventListener tempListener = new RoomEventListener() {
                    @Override
                    public void onRoomJoined(Room room) {
                        roomFuture.complete(true);
                    }

                    @Override
                    public void onRoomError(String errorMessage) {
                        roomFuture.complete(false);
                    }
                };

                roomService.addListener(tempListener);
                boolean roomRecovered = false;
                try {
                    boolean joinRequested = roomService.rejoinLastRoom();
                    if (joinRequested) {
                        try {
                            roomRecovered = Boolean.TRUE.equals(roomFuture.get(4, TimeUnit.SECONDS));
                        } catch (Exception ex) {
                            roomRecovered = false;
                        }
                    }
                } finally {
                    roomService.removeListener(tempListener);
                }

                // Tiêu chí 2: Nếu cần khôi phục phòng mà thất bại/timeout/ngắt kết nối:
                // Tuyệt đối không báo đã khôi phục đầy đủ! Đóng kết nối và lên lịch thử lại lần tiếp theo.
                if (!roomRecovered || cancelled.get() || !client.isConnected()) {
                    client.close();
                    scheduleNextAttempt();
                    return;
                }
            }

            // ==========================================
            // BƯỚC 4: CHO PHÉP CHAT & HOÀN TẤT PHỤC HỒI
            // ==========================================
            if (cancelled.get() || !client.isConnected()) {
                scheduleNextAttempt();
                return;
            }

            isReconnecting.set(false);
            currentAttempt.set(0);
            connectionManager.setReconnectAttempts(0);

            notifyListeners(ReconnectListener::onReconnectSucceeded);

        } catch (Throwable t) {
            scheduleNextAttempt();
        }
    }

    /**
     * Hủy bỏ toàn bộ quá trình reconnect (khi người dùng đăng xuất hoặc đóng ứng dụng).
     */
    public synchronized void cancel() {
        cancelled.set(true);
        isReconnecting.set(false);
        if (currentTask != null) {
            currentTask.cancel(true);
            currentTask = null;
        }
        clearCredentials();
        connectionManager.setReconnectAttempts(0);
        notifyListeners(ReconnectListener::onReconnectCancelled);
    }

    /**
     * Cho phép người dùng thử lại thủ công (Manual Retry) sau khi đã hết 5 lần tự động.
     */
    public void retryManually() {
        cancelled.set(false);
        currentAttempt.set(0);
        isReconnecting.set(false);
        triggerReconnect();
    }

    public boolean isReconnecting() {
        return isReconnecting.get();
    }

    public int getCurrentAttempt() {
        return currentAttempt.get();
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void addListener(ReconnectListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(ReconnectListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(Consumer<ReconnectListener> action) {
        for (ReconnectListener l : listeners) {
            try {
                action.accept(l);
            } catch (Throwable ignored) {}
        }
    }

    public void shutdown() {
        cancel();
        scheduler.shutdownNow();
    }
}
