package com.discordmini.client.network;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bộ kiểm thử tự động cho AN-03: CONNECTION MANAGER.
 *
 * Kiểm tra các tiêu chí:
 * 1. Chuyển trạng thái hợp lệ: DISCONNECTED -> CONNECTING -> CONNECTED -> AUTHENTICATED -> DISCONNECTED.
 * 2. Phân biệt CONNECTED (chỉ TCP nối) và AUTHENTICATED (xác thực thành công).
 * 3. Phân biệt ngắt mạng ngoài ý muốn (NETWORK_LOST) với đóng chủ động (MANUAL_CLOSE).
 * 4. Xử lý lỗi kết nối: CONNECTING -> DISCONNECTED (CONNECTION_FAILED).
 * 5. Cô lập generation: Sự kiện từ kết nối cũ không ghi đè trạng thái kết nối mới.
 * 6. An toàn đa luồng & không kẹt khóa: Callback gọi getState() không bị deadlock.
 */
public final class ConnectionManagerTest {
    private ConnectionManagerTest() {}

    public static void main(String[] args) throws Exception {
        System.out.println("=== BẮT ĐẦU KIỂM THỬ AN-03: CONNECTION MANAGER ===");
        testValidStateTransitions();
        testConnectionFailedStateTransition();
        testUnexpectedNetworkLoss();
        testManualCloseVsNetworkLoss();
        testOldGenerationDoesNotOverrideNewConnection();
        testCallbackDoesNotBlockConnectionLock();
        System.out.println("=== TẤT CẢ TEST AN-03: CONNECTION MANAGER ĐÃ PASS! ===");
    }

    /**
     * Tiêu chí 1 & 2: Chuyển trạng thái hợp lệ và phân biệt CONNECTED vs AUTHENTICATED.
     */
    private static void testValidStateTransitions() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept()) {
                    check(peer.isConnected(), "Peer kết nối thành công");
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            ConnectionManager cm = client.getConnectionManager();

            check(cm.getState() == ConnectionState.DISCONNECTED, "Trạng thái ban đầu phải là DISCONNECTED");
            check(!cm.isConnected(), "isConnected phải là false ban đầu");
            check(!cm.isAuthenticated(), "isAuthenticated phải là false ban đầu");

            List<ConnectionState> history = new ArrayList<>();
            cm.addStateChangeListener((oldState, newState, reason) -> history.add(newState));

            // Kết nối TCP
            cm.connect("127.0.0.1", port);
            check(cm.getState() == ConnectionState.CONNECTED, "Sau connect TCP, trạng thái phải là CONNECTED");
            check(cm.isConnected(), "isConnected phải là true");
            check(!cm.isAuthenticated(), "Chưa đăng nhập thì isAuthenticated phải là false");

            // Xác thực thành công
            cm.setAuthenticated(1, "an_tong", "Bảo An");
            check(cm.getState() == ConnectionState.AUTHENTICATED, "Sau khi xác thực, trạng thái phải là AUTHENTICATED");
            check(cm.isAuthenticated(), "isAuthenticated phải là true");
            check("Bảo An".equals(cm.getAuthenticatedDisplayName()), "Display name phải là Bảo An");

            // Đóng chủ động
            cm.disconnect();
            check(cm.getState() == ConnectionState.DISCONNECTED, "Sau khi disconnect, trạng thái phải là DISCONNECTED");
            check(cm.getLastDisconnectReason() == DisconnectReason.MANUAL_CLOSE, "Lý do phải là MANUAL_CLOSE");
            check(!cm.isAuthenticated(), "Sau disconnect, isAuthenticated phải là false");

            check(history.contains(ConnectionState.CONNECTING), "Phải có chuyển tiếp qua CONNECTING");
            check(history.contains(ConnectionState.CONNECTED), "Phải có chuyển tiếp qua CONNECTED");
            check(history.contains(ConnectionState.AUTHENTICATED), "Phải có chuyển tiếp qua AUTHENTICATED");
            check(history.contains(ConnectionState.DISCONNECTED), "Phải có chuyển tiếp về DISCONNECTED");

            System.out.println("  [PASS] testValidStateTransitions");
        }
    }

    /**
     * Tiêu chí 4: Thất bại khi kết nối mạng -> DISCONNECTED (CONNECTION_FAILED).
     */
    private static void testConnectionFailedStateTransition() {
        int closedPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            closedPort = temp.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        SocketClient client = new SocketClient();
        ConnectionManager cm = client.getConnectionManager();

        try {
            cm.connect("127.0.0.1", closedPort);
            throw new AssertionError("Kết nối cổng đóng phải ném IOException");
        } catch (IOException expected) {
            check(cm.getState() == ConnectionState.DISCONNECTED, "Trạng thái sau khi connect lỗi phải là DISCONNECTED");
            check(cm.getLastDisconnectReason() == DisconnectReason.CONNECTION_FAILED, "Lý do phải là CONNECTION_FAILED");
            System.out.println("  [PASS] testConnectionFailedStateTransition");
        }
    }

    /**
     * Tiêu chí 3: Server đóng socket đột ngột -> NETWORK_LOST.
     */
    private static void testUnexpectedNetworkLoss() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept()) {
                    // Đóng ngay lập tức để mô phỏng rớt mạng
                    peer.close();
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            ConnectionManager cm = client.getConnectionManager();

            BlockingQueue<DisconnectReason> reasons = new ArrayBlockingQueue<>(5);
            cm.addStateChangeListener((oldState, newState, reason) -> {
                if (newState == ConnectionState.DISCONNECTED) {
                    reasons.offer(reason);
                }
            });

            cm.connect("127.0.0.1", port);

            DisconnectReason reason = reasons.poll(3, TimeUnit.SECONDS);
            check(reason == DisconnectReason.NETWORK_LOST, "Server ngắt đột ngột phải báo NETWORK_LOST, nhưng nhận: " + reason);
            check(cm.getState() == ConnectionState.DISCONNECTED, "Trạng thái phải là DISCONNECTED");

            client.close();
            System.out.println("  [PASS] testUnexpectedNetworkLoss");
        }
    }

    /**
     * Tiêu chí 3b: Phân biệt rõ MANUAL_CLOSE với NETWORK_LOST.
     */
    private static void testManualCloseVsNetworkLoss() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept()) {
                    check(peer.isConnected(), "Peer kết nối thành công");
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            ConnectionManager cm = client.getConnectionManager();

            cm.connect("127.0.0.1", port);
            check(cm.isConnected(), "Client phải đã kết nối");

            // Chủ động ngắt kết nối
            cm.disconnect();
            check(cm.getLastDisconnectReason() == DisconnectReason.MANUAL_CLOSE, "Chủ động ngắt phải là MANUAL_CLOSE");
            check(cm.getState() == ConnectionState.DISCONNECTED, "Trạng thái phải là DISCONNECTED");

            System.out.println("  [PASS] testManualCloseVsNetworkLoss");
        }
    }

    /**
     * Tiêu chí 5: Sự kiện từ kết nối cũ (generation cũ) không ghi đè trạng thái kết nối mới.
     */
    private static void testOldGenerationDoesNotOverrideNewConnection() throws Exception {
        try (ServerSocket fakeServer1 = new ServerSocket(0);
             ServerSocket fakeServer2 = new ServerSocket(0)) {

            int port1 = fakeServer1.getLocalPort();
            int port2 = fakeServer2.getLocalPort();

            Thread s1 = new Thread(() -> {
                try (Socket peer = fakeServer1.accept()) {
                    check(peer.isConnected(), "Peer 1 kết nối thành công");
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            });
            s1.setDaemon(true);
            s1.start();

            Thread s2 = new Thread(() -> {
                try (Socket peer = fakeServer2.accept()) {
                    check(peer.isConnected(), "Peer 2 kết nối thành công");
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
            s2.setDaemon(true);
            s2.start();

            SocketClient client = new SocketClient();
            ConnectionManager cm = client.getConnectionManager();

            // Kết nối server 1
            cm.connect("127.0.0.1", port1);
            long oldGen = client.getGeneration();
            cm.disconnect();

            // Kết nối server 2 (generation mới)
            cm.connect("127.0.0.1", port2);
            long newGen = client.getGeneration();
            check(newGen > oldGen, "Thế hệ kết nối mới phải lớn hơn thế hệ cũ");
            check(cm.isConnected(), "Kết nối mới phải đang CONNECTED");

            // Giả lập sự kiện ngắt kết nối muộn gửi đến từ thế hệ cũ
            cm.setDisconnected(DisconnectReason.NETWORK_LOST, oldGen);

            // Xác nhận trạng thái kết nối mới KHÔNG bị kéo về DISCONNECTED!
            check(cm.isConnected(), "Sự kiện generation cũ KHÔNG được làm sai trạng thái kết nối mới");
            check(cm.getState() == ConnectionState.CONNECTED, "Trạng thái phải giữ nguyên là CONNECTED");

            cm.disconnect();
            System.out.println("  [PASS] testOldGenerationDoesNotOverrideNewConnection");
        }
    }

    /**
     * Tiêu chí 6: Callback gọi lại getState() không bị deadlock (không kẹt khóa synchronized).
     */
    private static void testCallbackDoesNotBlockConnectionLock() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept()) {
                    check(peer.isConnected(), "Peer kết nối thành công");
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            ConnectionManager cm = client.getConnectionManager();

            AtomicBoolean reentrantCallSuccess = new AtomicBoolean(false);
            cm.addStateChangeListener((oldState, newState, reason) -> {
                // Thử gọi lại phương thức của ConnectionManager ngay trong callback
                ConnectionState currentState = cm.getState();
                if (currentState == newState) {
                    reentrantCallSuccess.set(true);
                }
            });

            cm.connect("127.0.0.1", port);
            check(reentrantCallSuccess.get(), "Callback phải truy cập được ConnectionManager mà không bị deadlock");

            cm.disconnect();
            System.out.println("  [PASS] testCallbackDoesNotBlockConnectionLock");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
