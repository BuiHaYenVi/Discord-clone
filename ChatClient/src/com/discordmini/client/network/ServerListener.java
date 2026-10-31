package com.discordmini.client.network;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Điều phối và phân phối các gói tin, sự kiện nhận được từ Server tới các thành phần trong Client.
 *
 * Đảm bảo:
 * 1. Hỗ trợ đa listener (ConnectionListener, MessageListener, ActiveUiListener).
 * 2. Phân định rõ trách nhiệm: SocketClient sở hữu Socket & duy nhất 1 luồng nhận dữ liệu,
 *    ServerListener quản lý đăng ký và phân phối sự kiện.
 * 3. Bảo vệ sự kiện quản lý kết nối: Đăng ký connectionListener độc lập, tránh việc
 *    màn hình đăng nhập/đăng ký thay listener làm mất sự kiện CLIENT_DISCONNECTED.
 * 4. Cách ly lỗi callback: Một listener bị lỗi RuntimeException/Error không làm chết
 *    các listener khác và không làm sập luồng nhận dữ liệu từ mạng.
 * 5. Hỗ trợ đăng ký listener chạy trên EDT của Swing cho UI.
 */
public class ServerListener {
    private final SocketClient client;
    private final List<Consumer<String>> connectionListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> messageListeners = new CopyOnWriteArrayList<>();
    private volatile Consumer<String> activeUiListener;

    public ServerListener(SocketClient client) {
        this.client = client;
    }

    public SocketClient getClient() {
        return client;
    }

    /**
     * Đăng ký listener nhận sự kiện kết nối hệ thống (CLIENT_DISCONNECTED, REQUEST_TIMEOUT, PROTOCOL_ERROR).
     * Listener này tồn tại xuyên suốt và không bị ghi đè khi đổi màn hình UI.
     */
    public void addConnectionListener(Consumer<String> listener) {
        if (listener != null) {
            connectionListeners.add(listener);
        }
    }

    public void removeConnectionListener(Consumer<String> listener) {
        if (listener != null) {
            connectionListeners.remove(listener);
        }
    }

    /**
     * Đăng ký listener nhận gói tin/thông điệp từ Server.
     */
    public void addMessageListener(Consumer<String> listener) {
        if (listener != null) {
            messageListeners.add(listener);
        }
    }

    public void removeMessageListener(Consumer<String> listener) {
        if (listener != null) {
            messageListeners.remove(listener);
        }
    }

    /**
     * Đăng ký listener cập nhật giao diện Swing tự động điều phối sang Event Dispatch Thread (EDT).
     */
    public void addEdtMessageListener(Consumer<String> listener) {
        if (listener != null) {
            messageListeners.add(packet -> SwingUtilities.invokeLater(() -> {
                try {
                    listener.accept(packet);
                } catch (Throwable t) {
                    System.err.println("[ServerListener] Lỗi trong EDT listener: " + t.getMessage());
                }
            }));
        }
    }

    /**
     * Thiết lập listener cho màn hình giao diện hiện tại (Active UI Listener).
     * Tương thích với các màn hình LoginFrame, RegisterFrame, MainFrame.
     */
    public void setActiveUiListener(Consumer<String> listener) {
        this.activeUiListener = listener;
    }

    public Consumer<String> getActiveUiListener() {
        return activeUiListener;
    }

    /**
     * Tương thích ngược với interface cũ của ServerListener.
     */
    public void setConsumer(Consumer<String> consumer) {
        setActiveUiListener(consumer);
    }

    public Consumer<String> getConsumer() {
        return getActiveUiListener();
    }

    /**
     * Phân phối packet tới các listener tương ứng một cách an toàn.
     * Mọi ngoại lệ từ callback đều được cô lập độc lập.
     */
    public void dispatch(String packet) {
        if (packet == null) return;

        boolean isConnEvent = isConnectionEvent(packet);

        if (isConnEvent) {
            for (Consumer<String> listener : connectionListeners) {
                safeNotify(listener, packet);
            }
        }

        Consumer<String> ui = activeUiListener;
        if (ui != null) {
            safeNotify(ui, packet);
        }

        for (Consumer<String> listener : messageListeners) {
            safeNotify(listener, packet);
        }
    }

    public static boolean isConnectionEvent(String packet) {
        if (packet == null) return false;
        return "CLIENT_DISCONNECTED".equals(packet)
                || packet.startsWith("REQUEST_TIMEOUT|")
                || packet.startsWith("PROTOCOL_ERROR|");
    }

    private void safeNotify(Consumer<String> listener, String packet) {
        try {
            listener.accept(packet);
        } catch (Throwable t) {
            System.err.println("[ServerListener] Lỗi trong callback listener: " + t.getMessage());
        }
    }
}

