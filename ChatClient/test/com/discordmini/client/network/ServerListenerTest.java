package com.discordmini.client.network;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bộ kiểm thử tự động cho AN-02: SERVER LISTENER.
 *
 * Kiểm tra các tiêu chí:
 * 1. Đơn luồng nhận: Mỗi kết nối chỉ có 1 thread reader ("ServerListener"), không tạo reader cạnh tranh.
 * 2. Đa frame bất đồng bộ: Server gửi liên tiếp nhiều frame, Client nhận đủ và đúng thứ tự.
 * 3. Phân phối sự kiện & bảo vệ kết nối: Màn hình đổi listener không làm mất listener quản lý kết nối.
 * 4. Cách ly lỗi callback: Callback ném RuntimeException/Error không làm chết luồng đọc mạng và các listener khác.
 * 5. Kết thúc sạch sẽ khi đóng Client trong lúc đang chờ readLine(): Luồng đọc thoát ngay, không bị treo.
 * 6. Xử lý EOF (Server đóng kết nối graceful): Nhận diện đúng và phát CLIENT_DISCONNECTED.
 * 7. Cơ chế generation: Luồng cũ không làm sai trạng thái kết nối mới.
 */
public final class ServerListenerTest {
    private ServerListenerTest() {}

    public static void main(String[] args) throws Exception {
        System.out.println("=== BẮT ĐẦU KIỂM THỬ AN-02: SERVER LISTENER ===");
        testSingleReaderThread();
        testMultipleFramesAsynchronousDelivery();
        testMultipleListenersAndConnectionProtection();
        testCallbackErrorIsolation();
        testCleanTerminationOnCloseWhileWaitingReadLine();
        testEofHandlingGracefulShutdown();
        testGenerationIsolation();
        System.out.println("=== TẤT CẢ TEST AN-02: SERVER LISTENER ĐÃ PASS! ===");
    }

    /**
     * Tiêu chí 1: Đảm bảo chỉ có duy nhất 1 luồng đọc mạng trên kết nối, mang tên "ServerListener".
     */
    private static void testSingleReaderThread() throws Exception {
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
            client.connect("127.0.0.1", port);

            Thread reader = client.getReaderThread();
            check(reader != null, "Luồng reader phải được khởi tạo");
            check(reader.isAlive(), "Luồng reader phải đang chạy");
            check("ServerListener".equals(reader.getName()), "Tên luồng phải là ServerListener");

            client.close();
            reader.join(2000);
            check(!reader.isAlive(), "Luồng reader phải kết thúc sau khi close()");
            System.out.println("  [PASS] testSingleReaderThread");
        }
    }

    /**
     * Tiêu chí 2: Server gửi liên tiếp nhiều frame bất đồng bộ; Client nhận đủ và đúng thứ tự.
     */
    private static void testMultipleFramesAsynchronousDelivery() throws Exception {
        int frameCount = 20;
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     PrintWriter writer = new PrintWriter(
                             new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    for (int i = 0; i < frameCount; i++) {
                        writer.println("MSG|FRAME_" + i);
                    }
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            BlockingQueue<String> receivedQueue = new ArrayBlockingQueue<>(frameCount + 5);
            client.addMessageListener(receivedQueue::offer);

            client.connect("127.0.0.1", port);

            List<String> collected = new ArrayList<>();
            for (int i = 0; i < frameCount; i++) {
                String packet = receivedQueue.poll(3, TimeUnit.SECONDS);
                check(packet != null, "Phải nhận được gói tin thứ " + i);
                check(("MSG|FRAME_" + i).equals(packet), "Gói tin phải đúng thứ tự: MSG|FRAME_" + i + " nhưng nhận " + packet);
                collected.add(packet);
            }
            check(collected.size() == frameCount, "Phải nhận đủ " + frameCount + " frames");

            client.close();
            System.out.println("  [PASS] testMultipleFramesAsynchronousDelivery");
        }
    }

    /**
     * Tiêu chí 3: Đăng ký connectionListener và UI listener; khi UI đổi listener (ví dụ RegisterFrame
     * thay LoginFrame), connectionListener KHÔNG bị mất và vẫn nhận CLIENT_DISCONNECTED.
     */
    private static void testMultipleListenersAndConnectionProtection() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            CountDownLatch serverCloseLatch = new CountDownLatch(1);

            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     PrintWriter writer = new PrintWriter(
                             new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    writer.println("OK|CONNECTED_TEST");
                    serverCloseLatch.await();
                    // Đóng server để kích hoạt EOF
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            BlockingQueue<String> connEvents = new ArrayBlockingQueue<>(10);
            BlockingQueue<String> loginUiEvents = new ArrayBlockingQueue<>(10);
            BlockingQueue<String> registerUiEvents = new ArrayBlockingQueue<>(10);

            // 1. Quản lý kết nối đăng ký connection listener
            client.addConnectionListener(connEvents::offer);

            // 2. Màn hình Login đăng ký làm active UI listener
            client.setListener(loginUiEvents::offer);

            client.connect("127.0.0.1", port);

            // Xác nhận gói tin ban đầu đến được login UI
            String initialMsg = loginUiEvents.poll(3, TimeUnit.SECONDS);
            check("OK|CONNECTED_TEST".equals(initialMsg), "Login UI phải nhận được gói tin khởi tạo");

            // 3. Người dùng mở RegisterFrame -> RegisterFrame ghi đè UI listener
            client.setListener(registerUiEvents::offer);

            // 4. Server ngắt kết nối
            serverCloseLatch.countDown();

            // 5. Xác nhận:
            // a) Quản lý kết nối VẪN nhận được CLIENT_DISCONNECTED (không bị ghi đè hay mất!)
            String connEvent = connEvents.poll(3, TimeUnit.SECONDS);
            check("CLIENT_DISCONNECTED".equals(connEvent), "ConnectionListener PHẢI nhận được CLIENT_DISCONNECTED");

            // b) Register UI hiện tại cũng nhận được CLIENT_DISCONNECTED
            String regEvent = registerUiEvents.poll(3, TimeUnit.SECONDS);
            check("CLIENT_DISCONNECTED".equals(regEvent), "Register UI hiện tại cũng nhận được CLIENT_DISCONNECTED");

            // c) Login UI (đã bị thay thế làm UI listener) không nhận thêm gói tin của register
            check(loginUiEvents.isEmpty(), "Login UI không bị nhận trùng gói tin sau khi đã đổi sang Register UI");

            client.close();
            System.out.println("  [PASS] testMultipleListenersAndConnectionProtection");
        }
    }

    /**
     * Tiêu chí 4: Cách ly lỗi callback.
     * Nếu một listener ném RuntimeException hoặc Error, các listener khác vẫn nhận đủ tin
     * và luồng nhận dữ liệu mạng ("ServerListener") KHÔNG bị chết.
     */
    private static void testCallbackErrorIsolation() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            BlockingQueue<String> serverSendQueue = new ArrayBlockingQueue<>(10);

            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     PrintWriter writer = new PrintWriter(
                             new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    while (true) {
                        String msg = serverSendQueue.take();
                        if ("QUIT".equals(msg)) break;
                        writer.println(msg);
                    }
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();

            // Listener 1: Cố tình quăng RuntimeException
            client.addMessageListener(packet -> {
                if (packet.contains("FAIL_RUNTIME")) {
                    throw new RuntimeException("Cố tình ném lỗi RuntimeException từ UI");
                }
            });

            // Listener 2: Listener quan trọng cần nhận dữ liệu
            BlockingQueue<String> healthyQueue = new ArrayBlockingQueue<>(10);
            client.addMessageListener(healthyQueue::offer);

            // Listener 3: Cố tình quăng Error
            client.addMessageListener(packet -> {
                if (packet.contains("FAIL_ERROR")) {
                    throw new Error("Cố tình ném Error từ UI");
                }
            });

            client.connect("127.0.0.1", port);

            // Gửi gói tin gây lỗi ở Listener 1
            serverSendQueue.offer("MSG|FAIL_RUNTIME_1");
            String received1 = healthyQueue.poll(3, TimeUnit.SECONDS);
            check("MSG|FAIL_RUNTIME_1".equals(received1), "Listener khỏe mạnh vẫn phải nhận được MSG|FAIL_RUNTIME_1");

            // Gửi gói tin gây lỗi ở Listener 3
            serverSendQueue.offer("MSG|FAIL_ERROR_2");
            String received2 = healthyQueue.poll(3, TimeUnit.SECONDS);
            check("MSG|FAIL_ERROR_2".equals(received2), "Listener khỏe mạnh vẫn phải nhận được MSG|FAIL_ERROR_2");

            // Xác nhận luồng reader vẫn sống bình thường và nhận tiếp gói tin sau đó
            Thread reader = client.getReaderThread();
            check(reader != null && reader.isAlive(), "Luồng ServerListener reader PHẢI còn sống sau các lỗi callback");

            serverSendQueue.offer("MSG|HEALTHY_3");
            String received3 = healthyQueue.poll(3, TimeUnit.SECONDS);
            check("MSG|HEALTHY_3".equals(received3), "Luồng reader vẫn tiếp tục nhận được MSG|HEALTHY_3");

            serverSendQueue.offer("QUIT");
            client.close();
            System.out.println("  [PASS] testCallbackErrorIsolation");
        }
    }

    /**
     * Tiêu chí 5: Đóng Client khi đang chờ readLine() (blocking).
     * Luồng nhận mạng phải kết thúc sạch sẽ (không bị treo/leak thread).
     */
    private static void testCleanTerminationOnCloseWhileWaitingReadLine() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept()) {
                    check(peer.isConnected(), "Peer kết nối thành công");
                    // Server giữ socket mở nhưng KHÔNG gửi gì để client block ở readLine()
                    Thread.sleep(3000);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);

            Thread reader = client.getReaderThread();
            check(reader != null && reader.isAlive(), "Reader thread phải đang chạy và chờ dữ liệu");

            // Đợi 200ms để chắc chắn reader đang block ở readLine()
            Thread.sleep(200);

            // Gọi close() từ phía client
            client.close();

            // Luồng reader phải kết thúc nhanh chóng (dưới 1.5 giây), không bị treo
            reader.join(1500);
            check(!reader.isAlive(), "Reader thread PHẢI kết thúc sạch sẽ khi client.close() được gọi lúc đang chờ readLine()");

            System.out.println("  [PASS] testCleanTerminationOnCloseWhileWaitingReadLine");
        }
    }

    /**
     * Tiêu chí 6: Xử lý EOF từ Server (Server đóng socket graceful).
     * Client phát hiện readLine() == null và gọi markDisconnected() an toàn.
     */
    private static void testEofHandlingGracefulShutdown() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     PrintWriter writer = new PrintWriter(
                             new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    writer.println("OK|WELCOME");
                    // Ngay sau đó server đóng kết nối (gây EOF)
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            BlockingQueue<String> events = new ArrayBlockingQueue<>(10);
            client.addConnectionListener(events::offer);

            client.connect("127.0.0.1", port);

            String disconnectEvent = events.poll(3, TimeUnit.SECONDS);
            check("CLIENT_DISCONNECTED".equals(disconnectEvent), "Client phải phát hiện EOF và kích hoạt CLIENT_DISCONNECTED");
            check(!client.isConnected(), "Trạng thái client phải là disconnected sau EOF");

            Thread reader = client.getReaderThread();
            if (reader != null) {
                reader.join(1500);
                check(!reader.isAlive(), "Reader thread phải kết thúc sau EOF");
            }

            client.close();
            System.out.println("  [PASS] testEofHandlingGracefulShutdown");
        }
    }

    /**
     * Tiêu chí 7: Cơ chế generation.
     * Khi client kết nối lại hoặc tăng generation, luồng cũ không can thiệp vào kết nối mới.
     */
    private static void testGenerationIsolation() throws Exception {
        try (ServerSocket fakeServer1 = new ServerSocket(0);
             ServerSocket fakeServer2 = new ServerSocket(0)) {

            int port1 = fakeServer1.getLocalPort();
            int port2 = fakeServer2.getLocalPort();

            Thread s1 = new Thread(() -> {
                try (Socket peer = fakeServer1.accept()) {
                    check(peer.isConnected(), "Peer 1 kết nối thành công");
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
            s1.setDaemon(true);
            s1.start();

            Thread s2 = new Thread(() -> {
                try (Socket peer = fakeServer2.accept();
                     PrintWriter writer = new PrintWriter(
                             new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    writer.println("MSG|FROM_SERVER_2");
                } catch (Exception ignored) {}
            });
            s2.setDaemon(true);
            s2.start();

            SocketClient client = new SocketClient();
            BlockingQueue<String> messages = new ArrayBlockingQueue<>(10);
            client.addMessageListener(messages::offer);

            // Kết nối server 1
            client.connect("127.0.0.1", port1);
            Thread oldReader = client.getReaderThread();

            // Ngắt và kết nối server 2
            client.close();
            oldReader.join(1500);

            client.connect("127.0.0.1", port2);
            Thread newReader = client.getReaderThread();

            check(oldReader != newReader, "Luồng reader mới phải khác luồng reader cũ");
            check(!oldReader.isAlive(), "Luồng reader cũ phải đã dừng");
            check(newReader.isAlive(), "Luồng reader mới phải đang chạy");

            String msg = messages.poll(3, TimeUnit.SECONDS);
            check("MSG|FROM_SERVER_2".equals(msg), "Kết nối mới phải nhận đúng tin từ Server 2");

            client.close();
            System.out.println("  [PASS] testGenerationIsolation");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
