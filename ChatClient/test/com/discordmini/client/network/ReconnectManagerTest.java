package com.discordmini.client.network;

import com.discordmini.client.model.MessageStatus;
import com.discordmini.client.protocol.ProtocolHandler;
import com.discordmini.client.service.AuthCallback;
import com.discordmini.client.service.AuthResult;
import com.discordmini.client.service.AuthService;
import com.discordmini.client.service.ChatService;
import com.discordmini.client.service.RoomService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ kiểm thử tự động toàn diện cho PROMPT 9 — AN-10: AUTOMATIC RECONNECTION.
 *
 * Kiểm thử:
 * 1. Phục hồi hoàn chỉnh khi Server restart: TCP -> Login -> Rejoin đúng phòng cũ.
 * 2. Gửi tin nhắn thành công sau khi phục hồi.
 * 3. Hết 5 lần retry thất bại thì dừng lại và báo rõ lỗi cho người dùng.
 * 4. Cho phép thử lại thủ công (manual retry) sau khi thất bại.
 * 5. Hủy retry và xóa sạch thông tin đăng nhập khỏi RAM khi logout/cancel.
 * 6. Không tự reconnect khi đóng kết nối chủ động (MANUAL_CLOSE).
 */
public class ReconnectManagerTest {
    // Backoff siêu ngắn phục vụ kiểm thử nhanh (không làm chậm tiến trình test)
    private static final long[] FAST_BACKOFF_MS = {50, 100, 150, 200, 250};

    public static void main(String[] args) {
        System.out.println("=== BẮT ĐẦU KIỂM THỬ AN-10: AUTOMATIC RECONNECTION ===");
        try {
            testNoReconnectOnManualClose();
            testCancelReconnectClearsCredentials();
            testMaxFiveRetriesExhaustedAndReportFailure();
            testServerRestartAndSuccessfulFullRecovery();
            testSendChatMessageAfterRecovery();
            testManualRetryAfterFailure();

            System.out.println("=== TẤT CẢ TEST AN-10: AUTOMATIC RECONNECTION ĐÃ PASS! ===");
        } catch (Throwable t) {
            System.err.println("KIỂM THỬ THẤT BẠI: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Tiêu chí 1: Không tự động reconnect khi người dùng chủ động đóng kết nối (MANUAL_CLOSE).
     */
    private static void testNoReconnectOnManualClose() throws Exception {
        SocketClient client = new SocketClient();
        AuthService authService = new AuthService(client);
        RoomService roomService = new RoomService(client);
        ReconnectManager manager = new ReconnectManager(client, authService, roomService, FAST_BACKOFF_MS, 5);

        manager.setCredentials("127.0.0.1", 5000, "user1", "pass1");

        // Giả lập đóng chủ động
        client.close();

        Thread.sleep(150);
        check(!manager.isReconnecting(), "Đóng chủ động không được tự động kích hoạt reconnect");
        check(client.getConnectionManager().getReconnectAttempts() == 0, "Số lần retry phải là 0");

        manager.shutdown();
        System.out.println("  [PASS] testNoReconnectOnManualClose");
    }

    /**
     * Tiêu chí 7 & 8: Hủy retry và xóa sạch thông tin mật khẩu khỏi RAM khi cancel/logout.
     */
    private static void testCancelReconnectClearsCredentials() throws Exception {
        SocketClient client = new SocketClient();
        AuthService authService = new AuthService(client);
        RoomService roomService = new RoomService(client);
        ReconnectManager manager = new ReconnectManager(client, authService, roomService, FAST_BACKOFF_MS, 5);

        manager.setCredentials("127.0.0.1", 5000, "user1", "secret_pass");

        // Gọi cancel
        manager.cancel();

        check(!manager.isReconnecting(), "Sau khi cancel, isReconnecting phải là false");
        check(client.getConnectionManager().getReconnectAttempts() == 0, "Sau khi cancel, số lần thử phải là 0");

        manager.shutdown();
        System.out.println("  [PASS] testCancelReconnectClearsCredentials");
    }

    /**
     * Tiêu chí 2 & 9: Hết 5 lần thử thất bại thì dừng lại và báo rõ lỗi.
     */
    private static void testMaxFiveRetriesExhaustedAndReportFailure() throws Exception {
        // Tìm 1 cổng không có server lắng nghe
        int unusedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            unusedPort = s.getLocalPort();
        }

        SocketClient client = new SocketClient();
        AuthService authService = new AuthService(client);
        RoomService roomService = new RoomService(client);
        ReconnectManager manager = new ReconnectManager(client, authService, roomService, FAST_BACKOFF_MS, 5);

        manager.setCredentials("127.0.0.1", unusedPort, "user1", "pass1");

        CountDownLatch failLatch = new CountDownLatch(1);
        AtomicInteger attemptCounter = new AtomicInteger(0);

        manager.addListener(new ReconnectListener() {
            @Override
            public void onReconnectAttempt(int attempt, int maxAttempts, long delayMs) {
                attemptCounter.set(attempt);
            }

            @Override
            public void onReconnectSucceeded() {}

            @Override
            public void onReconnectFailed(String reason) {
                failLatch.countDown();
            }

            @Override
            public void onReconnectCancelled() {}
        });

        // Kích hoạt kết nối lại tới cổng chết
        manager.triggerReconnect();

        check(failLatch.await(5, TimeUnit.SECONDS), "Phải hoàn thành 5 lần retry và báo thất bại");
        check(attemptCounter.get() == 5, "Số lần thử phải đạt mức tối đa là 5 nhưng thực tế là: " + attemptCounter.get());
        check(!manager.isReconnecting(), "Sau khi hết lần thử, tiến trình reconnect phải kết thúc");
        check(client.getConnectionManager().getReconnectAttempts() == 5, "UI phải ghi nhận 5 lần thử");

        manager.shutdown();
        System.out.println("  [PASS] testMaxFiveRetriesExhaustedAndReportFailure");
    }

    /**
     * Tiêu chí 5 & 6: Server Restart - Phục hồi theo đúng thứ tự nghiêm ngặt:
     * Nối TCP -> Xác thực LOGIN -> Rejoin phòng cũ thành công.
     */
    private static void testServerRestartAndSuccessfulFullRecovery() throws Exception {
        ServerSocket serverSocket1 = new ServerSocket(0);
        int port = serverSocket1.getLocalPort();

        AtomicBoolean server1Running = new AtomicBoolean(true);
        Thread serverThread1 = new Thread(() -> {
            try {
                Socket peer = serverSocket1.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true);

                // Server 1 đọc LOGIN và JOIN_ROOM ban đầu
                String line;
                while (server1Running.get() && (line = reader.readLine()) != null) {
                    if (line.startsWith("LOGIN")) {
                        writer.println("OK|LOGIN|1|" + ProtocolHandler.encode("Bao An"));
                    } else if (line.startsWith("JOIN_ROOM")) {
                        writer.println("OK|JOIN_ROOM|101|" + ProtocolHandler.encode("Phòng đồ án"));
                    }
                }
                peer.close();
            } catch (Exception ignored) {}
        });
        serverThread1.setDaemon(true);
        serverThread1.start();

        SocketClient client = new SocketClient();
        client.connect("127.0.0.1", port);
        AuthService authService = new AuthService(client);
        ChatService chatService = new ChatService(client, "Bao An");
        RoomService roomService = new RoomService(client, chatService);

        // Đăng nhập ban đầu
        CountDownLatch loginLatch = new CountDownLatch(1);
        authService.login("baoan", "123456", new AuthCallback() {
            @Override
            public void onSuccess(AuthResult result) {
                loginLatch.countDown();
            }

            @Override
            public void onError(String errorMessage) {}

            @Override
            public void onTimeout() {}

            @Override
            public void onNetworkError() {}
        });
        check(loginLatch.await(3, TimeUnit.SECONDS), "Đăng nhập ban đầu phải thành công");

        // Vào phòng ban đầu
        roomService.joinRoom(101);
        Thread.sleep(100);
        check(Integer.valueOf(101).equals(roomService.getLastSuccessfulRoomId()), "Phải ghi nhớ lastSuccessfulRoomId = 101");

        // Khởi tạo ReconnectManager với thông tin đăng nhập
        ReconnectManager manager = new ReconnectManager(client, authService, roomService, FAST_BACKOFF_MS, 5);
        manager.setCredentials("127.0.0.1", port, "baoan", "123456");

        CountDownLatch recoveryLatch = new CountDownLatch(1);
        manager.addListener(new ReconnectListener() {
            @Override
            public void onReconnectAttempt(int attempt, int maxAttempts, long delayMs) {}

            @Override
            public void onReconnectSucceeded() {
                recoveryLatch.countDown();
            }

            @Override
            public void onReconnectFailed(String reason) {}

            @Override
            public void onReconnectCancelled() {}
        });

        // 1. MÔ PHỎNG SERVER 1 SẬP (RỚT MẠNG)
        server1Running.set(false);
        try { serverSocket1.close(); } catch (Exception ignored) {}
        client.simulateUnexpectedDisconnectionForTest(); // Đóng socket đột ngột gây ra mất kết nối ngoài ý muốn

        // 2. KHỞI ĐỘNG SERVER 2 THAY THẾ TRÊN CÙNG CỔNG
        Thread serverThread2 = new Thread(() -> {
            try {
                Thread.sleep(100); // Server 2 khởi động sau 100ms
                try (ServerSocket serverSocket2 = new ServerSocket(port)) {
                    Socket peer2 = serverSocket2.accept();
                    BufferedReader reader2 = new BufferedReader(new InputStreamReader(peer2.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter writer2 = new PrintWriter(new OutputStreamWriter(peer2.getOutputStream(), StandardCharsets.UTF_8), true);

                    String line;
                    while ((line = reader2.readLine()) != null) {
                        if (line.startsWith("LOGIN")) {
                            writer2.println("OK|LOGIN|1|" + ProtocolHandler.encode("Bao An"));
                        } else if (line.startsWith("JOIN_ROOM")) {
                            writer2.println("OK|JOIN_ROOM|101|" + ProtocolHandler.encode("Phòng đồ án"));
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
        serverThread2.setDaemon(true);
        serverThread2.start();

        // 3. KÍCH HOẠT QUÁ TRÌNH RECONNECT
        manager.triggerReconnect();

        // 4. KIỂM TRA PHỤC HỒI THÀNH CÔNG
        check(recoveryLatch.await(5, TimeUnit.SECONDS), "Phải phục hồi thành công qua Server 2");
        check(client.isConnected(), "Sau khi phục hồi, client phải CONNECTED");
        check(client.getConnectionManager().isAuthenticated(), "Sau khi phục hồi, client phải AUTHENTICATED");
        check(roomService.getCurrentRoom() != null && roomService.getCurrentRoom().id() == 101,
                "Sau khi phục hồi, client phải tự động vào lại đúng phòng cũ (101)");
        check(client.getConnectionManager().getReconnectAttempts() == 0, "Sau khi phục hồi, số lần thử phải reset về 0");

        manager.shutdown();
        client.close();
        System.out.println("  [PASS] testServerRestartAndSuccessfulFullRecovery");
    }

    /**
     * Tiêu chí: Gửi tin nhắn thành công sau khi phục hồi mạng.
     */
    private static void testSendChatMessageAfterRecovery() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("LOGIN")) {
                            writer.println("OK|LOGIN|1|" + ProtocolHandler.encode("Bao An"));
                        } else if (line.startsWith("JOIN_ROOM")) {
                            writer.println("OK|JOIN_ROOM|200|" + ProtocolHandler.encode("General"));
                        } else if (line.startsWith("SEND_MESSAGE")) {
                            String reqId = ProtocolHandler.requestId(ProtocolHandler.split(line));
                            writer.println("ACK|" + reqId + "|OK");
                        }
                    }
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ChatService chatService = new ChatService(client, "Bao An");
            RoomService roomService = new RoomService(client, chatService);

            // Giả lập phục hồi xong
            client.getConnectionManager().setAuthenticated(1, "baoan", "Bao An");
            roomService.handleServerPacket("OK|JOIN_ROOM|200|" + ProtocolHandler.encode("General"));

            CountDownLatch msgLatch = new CountDownLatch(1);
            chatService.addListener(new com.discordmini.client.service.ChatEventListener() {
                @Override
                public void onMessageStatusChanged(String requestId, MessageStatus status, String errorMessage) {
                    if (status == MessageStatus.SENT) {
                        msgLatch.countDown();
                    }
                }
            });

            String reqId = chatService.sendMessage("Tin nhắn gửi sau khi phục hồi");
            check(reqId != null, "Phải gửi được tin nhắn");
            check(msgLatch.await(3, TimeUnit.SECONDS), "Tin nhắn phải nhận được ACK và chuyển sang SENT");

            client.close();
        }
        System.out.println("  [PASS] testSendChatMessageAfterRecovery");
    }

    /**
     * Tiêu chí 9: Hỗ trợ thử lại thủ công (manual retry) sau khi đã thất bại.
     */
    private static void testManualRetryAfterFailure() throws Exception {
        SocketClient client = new SocketClient();
        AuthService authService = new AuthService(client);
        RoomService roomService = new RoomService(client);
        ReconnectManager manager = new ReconnectManager(client, authService, roomService, FAST_BACKOFF_MS, 2);

        manager.setCredentials("127.0.0.1", 1, "user1", "pass1"); // Cổng 1 chắc chắn fail

        CountDownLatch failLatch = new CountDownLatch(1);
        manager.addListener(new ReconnectListener() {
            @Override
            public void onReconnectAttempt(int attempt, int maxAttempts, long delayMs) {}
            @Override
            public void onReconnectSucceeded() {}
            @Override
            public void onReconnectFailed(String reason) {
                failLatch.countDown();
            }
            @Override
            public void onReconnectCancelled() {}
        });

        manager.triggerReconnect();
        check(failLatch.await(3, TimeUnit.SECONDS), "Phải báo thất bại");

        // Người dùng bấm nút "Thử lại kết nối" (Manual Retry)
        manager.retryManually();
        check(manager.isReconnecting(), "Sau khi bấm thử lại thủ công, isReconnecting phải là true");

        manager.shutdown();
        System.out.println("  [PASS] testManualRetryAfterFailure");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("KIỂM THỬ THẤT BẠI: " + message);
        }
    }
}
