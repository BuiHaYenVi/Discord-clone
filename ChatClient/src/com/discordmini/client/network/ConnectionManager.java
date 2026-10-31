package com.discordmini.client.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Nguồn trạng thái kết nối duy nhất (Single Source of Truth) phía Client.
 *
 * Nhiệm vụ:
 * 1. Quản lý vòng đời trạng thái: DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, RECONNECTING.
 * 2. Phân biệt CONNECTED (chỉ TCP nối thành công) với AUTHENTICATED (đăng nhập thành công từ Server).
 * 3. Phân biệt ngắt kết nối ngoài ý muốn (NETWORK_LOST/TIMEOUT) với đóng chủ động (MANUAL_CLOSE).
 * 4. Ngăn chặn sự kiện từ kết nối cũ (generation cũ) ghi đè trạng thái kết nối mới.
 * 5. Chuyển trạng thái an toàn đa luồng, gọi callback bên ngoài lock để không làm kẹt khóa quản lý kết nối.
 */
public class ConnectionManager {

    @FunctionalInterface
    public interface StateChangeListener {
        void onStateChanged(ConnectionState oldState, ConnectionState newState, DisconnectReason reason);
    }

    private final SocketClient client;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile DisconnectReason lastDisconnectReason = DisconnectReason.NONE;
    private volatile long activeGeneration = 0;

    private volatile Integer authenticatedUserId = null;
    private volatile String authenticatedUsername = null;
    private volatile String authenticatedDisplayName = null;

    private final List<StateChangeListener> stateListeners = new CopyOnWriteArrayList<>();

    public ConnectionManager(SocketClient client) {
        this.client = client;
        registerClientListener();
    }

    private void registerClientListener() {
        if (client == null) return;
        client.addConnectionListener(event -> {
            if ("CLIENT_DISCONNECTED".equals(event)) {
                // Kiểm tra xem ngắt kết nối này đến từ luồng nào
                long clientGen = client.getGeneration();
                synchronized (this) {
                    if (activeGeneration != 0 && clientGen != activeGeneration) {
                        // Bỏ qua sự kiện từ thế hệ kết nối cũ
                        return;
                    }
                }
                DisconnectReason reason = (this.lastDisconnectReason == DisconnectReason.MANUAL_CLOSE)
                        ? DisconnectReason.MANUAL_CLOSE
                        : DisconnectReason.NETWORK_LOST;
                setDisconnected(reason, clientGen);
            } else if (event.startsWith("REQUEST_TIMEOUT|")) {
                this.lastDisconnectReason = DisconnectReason.TIMEOUT;
            }
        });
    }

    public SocketClient getClient() {
        return client;
    }

    public ConnectionState getState() {
        return state;
    }

    public DisconnectReason getLastDisconnectReason() {
        return lastDisconnectReason;
    }

    public boolean isConnected() {
        ConnectionState s = state;
        return s == ConnectionState.CONNECTED || s == ConnectionState.AUTHENTICATED;
    }

    public boolean isAuthenticated() {
        return state == ConnectionState.AUTHENTICATED;
    }

    public Integer getAuthenticatedUserId() {
        return authenticatedUserId;
    }

    public String getAuthenticatedUsername() {
        return authenticatedUsername;
    }

    public String getAuthenticatedDisplayName() {
        return authenticatedDisplayName;
    }

    private volatile int reconnectAttempts = 0;

    public int getReconnectAttempts() {
        return reconnectAttempts;
    }

    public void setReconnectAttempts(int attempts) {
        List<StateChangeListener> listenersToNotify;
        synchronized (this) {
            if (this.reconnectAttempts == attempts) {
                return;
            }
            this.reconnectAttempts = attempts;
            listenersToNotify = new ArrayList<>(this.stateListeners);
        }

        // Phát thông báo trạng thái cập nhật để UI render số lần retry
        for (StateChangeListener listener : listenersToNotify) {
            try {
                listener.onStateChanged(this.state, this.state, this.lastDisconnectReason);
            } catch (Throwable ignored) {}
        }
    }

    public String getServerHost() {
        return client != null ? client.getServerHost() : null;
    }

    public int getServerPort() {
        return client != null ? client.getServerPort() : 0;
    }

    public void addStateChangeListener(StateChangeListener listener) {
        if (listener != null) {
            stateListeners.add(listener);
        }
    }

    public void removeStateChangeListener(StateChangeListener listener) {
        if (listener != null) {
            stateListeners.remove(listener);
        }
    }

    /**
     * Bắt đầu kết nối TCP tới server.
     */
    public synchronized void connect(String host, int port) throws IOException {
        if (isConnected()) return;

        transitionTo(ConnectionState.CONNECTING, DisconnectReason.NONE);
        try {
            client.connect(host, port);
            this.activeGeneration = client.getGeneration();
            transitionTo(ConnectionState.CONNECTED, DisconnectReason.NONE);
        } catch (IOException e) {
            transitionTo(ConnectionState.DISCONNECTED, DisconnectReason.CONNECTION_FAILED);
            throw e;
        }
    }

    /**
     * Đóng kết nối một cách chủ động (đăng xuất hoặc đóng ứng dụng).
     */
    public void disconnect() {
        synchronized (this) {
            this.lastDisconnectReason = DisconnectReason.MANUAL_CLOSE;
            clearAuthData();
        }
        client.close();
        transitionTo(ConnectionState.DISCONNECTED, DisconnectReason.MANUAL_CLOSE);
    }

    /**
     * Chuyển trạng thái sang CONNECTING (dùng khi SwingWorker hoặc tiến trình nền kết nối).
     */
    public void setConnecting() {
        transitionTo(ConnectionState.CONNECTING, DisconnectReason.NONE);
    }

    /**
     * Chuyển trạng thái sang CONNECTED khi TCP đã nối thành công.
     */
    public void setConnected(long generation) {
        synchronized (this) {
            this.activeGeneration = generation;
        }
        transitionTo(ConnectionState.CONNECTED, DisconnectReason.NONE);
    }

    /**
     * Chuyển trạng thái sang AUTHENTICATED chỉ sau khi nhận phản hồi đăng nhập thành công.
     */
    public void setAuthenticated(int userId, String username, String displayName) {
        synchronized (this) {
            this.authenticatedUserId = userId;
            this.authenticatedUsername = username;
            this.authenticatedDisplayName = displayName;
        }
        transitionTo(ConnectionState.AUTHENTICATED, DisconnectReason.NONE);
    }

    /**
     * Chuyển trạng thái sang RECONNECTING để chuẩn bị tái kết nối.
     */
    public void setReconnecting() {
        transitionTo(ConnectionState.RECONNECTING, DisconnectReason.NONE);
    }

    /**
     * Chuyển trạng thái sang DISCONNECTED kèm lý do ngắt kết nối.
     * Kiểm tra generation để ngăn chặn kết nối cũ ghi đè kết nối mới.
     */
    public void setDisconnected(DisconnectReason reason, long generation) {
        synchronized (this) {
            if (generation != 0 && this.activeGeneration != 0 && generation != this.activeGeneration) {
                // Sự kiện từ kết nối cũ -> bỏ qua
                return;
            }
            clearAuthData();
        }
        transitionTo(ConnectionState.DISCONNECTED, reason);
    }

    private void clearAuthData() {
        this.authenticatedUserId = null;
        this.authenticatedUsername = null;
        this.authenticatedDisplayName = null;
    }

    /**
     * Chuyển trạng thái an toàn:
     * Khóa synchronized chỉ giữ trong thời gian cập nhật trạng thái nội bộ,
     * sau đó nhả khóa rồi mới phát callback cho listener để tránh nguy cơ deadlock.
     */
    private void transitionTo(ConnectionState newState, DisconnectReason reason) {
        ConnectionState oldState;
        List<StateChangeListener> listenersToNotify;

        synchronized (this) {
            if (this.state == newState) {
                this.lastDisconnectReason = reason;
                return;
            }
            oldState = this.state;
            this.state = newState;
            this.lastDisconnectReason = reason;
            listenersToNotify = new ArrayList<>(this.stateListeners);
        }

        // Gọi callback bên ngoài khối synchronized
        for (StateChangeListener listener : listenersToNotify) {
            try {
                listener.onStateChanged(oldState, newState, reason);
            } catch (Throwable t) {
                System.err.println("[ConnectionManager] Callback listener gặp lỗi: " + t.getMessage());
            }
        }
    }
}
