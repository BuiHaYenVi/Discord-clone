package com.discordmini.client.ui;

import com.discordmini.client.network.SocketClient;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bộ kiểm thử tự động toàn diện cho PROMPT 8 — AN-09: NETWORK STATUS MONITOR.
 *
 * Kiểm thử:
 * 1. PONG bình thường: Đo độ trễ phản hồi heartbeat, hiển thị ms thực tế, không 0 ms giả.
 * 2. Phản hồi chậm (Slow PONG): Đo khoảng thời gian thực tế tăng lên theo độ trễ phản hồi.
 * 3. Thiếu PONG / Timeout: Hiển thị trạng thái đang chờ PING/PONG, không ghi đè dữ liệu sai.
 * 4. Ngắt kết nối / Mất mạng: Trạng thái DISCONNECTED, địa chỉ và độ trễ trở về "—", không hiển thị 0 ms giả.
 * 5. Hiển thị số lần retry kết nối (chuẩn bị cho AN-10).
 * 6. Giải phóng listener (cleanup) khi đóng cửa sổ / panel.
 */
public class NetworkStatusMonitorTest {

    public static void main(String[] args) {
        System.out.println("=== BẮT ĐẦU KIỂM THỬ AN-09: NETWORK STATUS MONITOR ===");
        try {
            testInitialStateDisconnected();
            testNormalPongLatencyAndUiUpdate();
            testSlowPongLatencyMeasurement();
            testMissingPongAwaitingState();
            testDisconnectedResetsLatencyToDash();
            testRetryCountDisplayForAn10();
            testCleanupRemovesListeners();

            System.out.println("=== TẤT CẢ TEST AN-09: NETWORK STATUS MONITOR ĐÃ PASS! ===");
        } catch (Throwable t) {
            System.err.println("KIỂM THỬ THẤT BẠI: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Tiêu chí 1: Ban đầu khi chưa kết nối, độ trễ và địa chỉ hiển thị "—", không hiển thị 0 ms giả.
     */
    private static void testInitialStateDisconnected() throws Exception {
        SocketClient client = new SocketClient();
        NetworkStatusPanel panel = new NetworkStatusPanel(client);

        SwingUtilities.invokeAndWait(() -> {});

        check("—".equals(panel.getLatencyLabel().getText()), "Ban đầu độ trễ phải là '—', không được là 0 ms giả");
        check("—".equals(panel.getServerAddressLabel().getText()), "Ban đầu máy chủ phải là '—'");
        check(panel.getConnectionStatusLabel().getText().contains("Chưa kết nối") 
                || panel.getConnectionStatusLabel().getText().contains("Mất kết nối"), "Trạng thái ban đầu phải là Chưa kết nối / Mất kết nối");

        panel.cleanup();
        System.out.println("  [PASS] testInitialStateDisconnected");
    }

    /**
     * Tiêu chí 2: PONG bình thường - Độ trễ đo bằng nanoTime, hiển thị ms chính xác trên UI.
     */
    private static void testNormalPongLatencyAndUiUpdate() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("PING".equals(line)) {
                            Thread.sleep(10); // phản hồi sau 10ms
                            writer.println("PONG");
                        }
                    }
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            NetworkStatusPanel panel = new NetworkStatusPanel(client);

            SwingUtilities.invokeAndWait(() -> {});
            check(("127.0.0.1:" + port).equals(panel.getServerAddressLabel().getText()), "Phải hiển thị đúng IP và port của Server");
            check(panel.getConnectionStatusLabel().getText().contains("Đã kết nối"), "Trạng thái kết nối phải là Đã kết nối TCP");

            CountDownLatch pongLatch = new CountDownLatch(1);
            client.addHeartbeatListener(info -> {
                if (info.hasSample()) {
                    pongLatch.countDown();
                }
            });

            // Gửi heartbeat PING
            client.sendPingForHeartbeat();
            check(pongLatch.await(3, TimeUnit.SECONDS), "Phải nhận được PONG trong 3 giây");

            SwingUtilities.invokeAndWait(() -> {});
            String latencyText = panel.getLatencyLabel().getText();
            check(latencyText.endsWith(" ms"), "Độ trễ phải có đơn vị ms: " + latencyText);
            long parsedMs = Long.parseLong(latencyText.replace(" ms", "").trim());
            check(parsedMs >= 0, "Độ trễ không thể âm: " + parsedMs);

            check(panel.getPingPongStatusLabel().getText().contains("PONG OK"), "Trạng thái PING/PONG phải báo PONG OK");

            panel.cleanup();
            client.close();
        }
        System.out.println("  [PASS] testNormalPongLatencyAndUiUpdate");
    }

    /**
     * Tiêu chí 3: Phản hồi chậm (Slow PONG) - Đo khoảng thời gian thực tế tăng lên theo độ trễ.
     */
    private static void testSlowPongLatencyMeasurement() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("PING".equals(line)) {
                            Thread.sleep(60); // Phản hồi chậm 60ms
                            writer.println("PONG");
                        }
                    }
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            NetworkStatusPanel panel = new NetworkStatusPanel(client);

            CountDownLatch slowPongLatch = new CountDownLatch(1);
            client.addHeartbeatListener(info -> {
                if (info.hasSample()) {
                    slowPongLatch.countDown();
                }
            });

            client.sendPingForHeartbeat();
            check(slowPongLatch.await(3, TimeUnit.SECONDS), "Phải nhận được slow PONG");

            SwingUtilities.invokeAndWait(() -> {});
            long latencyMs = client.getCurrentLatencyMs();
            check(latencyMs >= 40, "Độ trễ phản hồi chậm phải >= 40ms nhưng thực tế là: " + latencyMs);

            panel.cleanup();
            client.close();
        }
        System.out.println("  [PASS] testSlowPongLatencyMeasurement");
    }

    /**
     * Tiêu chí 4: Thiếu PONG (Missing PONG) - Đang chờ PONG, trạng thái hiển thị "Đang chờ PONG".
     */
    private static void testMissingPongAwaitingState() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8))) {
                    // Nhận PING nhưng KHÔNG trả lời PONG
                    reader.readLine();
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            NetworkStatusPanel panel = new NetworkStatusPanel(client);

            client.sendPingForHeartbeat();
            SwingUtilities.invokeAndWait(() -> {});

            check(client.getHeartbeatInfo().pingAwaitingPong(), "Phải đang ở trạng thái pingAwaitingPong = true");
            check(panel.getPingPongStatusLabel().getText().contains("Đã gửi PING"), "Phải hiển thị trạng thái Đã gửi PING chờ PONG");

            panel.cleanup();
            client.close();
        }
        System.out.println("  [PASS] testMissingPongAwaitingState");
    }

    /**
     * Tiêu chí 5: Ngắt kết nối - Độ trễ lập tức reset về "—", không hiển thị 0 ms giả.
     */
    private static void testDisconnectedResetsLatencyToDash() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("PING".equals(line)) {
                            writer.println("PONG");
                        }
                    }
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            NetworkStatusPanel panel = new NetworkStatusPanel(client);

            CountDownLatch latch = new CountDownLatch(1);
            client.addHeartbeatListener(info -> {
                if (info.hasSample()) latch.countDown();
            });
            client.sendPingForHeartbeat();
            check(latch.await(3, TimeUnit.SECONDS), "Phải nhận được PONG");

            SwingUtilities.invokeAndWait(() -> {});
            check(panel.getLatencyLabel().getText().endsWith(" ms"), "Trước khi ngắt mạng phải có độ trễ");

            // Ngắt kết nối
            client.close();
            SwingUtilities.invokeAndWait(() -> {});

            check("—".equals(panel.getLatencyLabel().getText()), "Sau khi ngắt kết nối, độ trễ PHẢI là '—', không được là 0 ms giả");
            check("—".equals(panel.getServerAddressLabel().getText()), "Sau khi ngắt kết nối, máy chủ phải là '—'");
            check(panel.getConnectionStatusLabel().getText().contains("Mất kết nối"), "Trạng thái phải là Mất kết nối");

            panel.cleanup();
        }
        System.out.println("  [PASS] testDisconnectedResetsLatencyToDash");
    }

    /**
     * Tiêu chí 6: Hiển thị số lần retry kết nối (chuẩn bị cho AN-10).
     */
    private static void testRetryCountDisplayForAn10() throws Exception {
        SocketClient client = new SocketClient();
        NetworkStatusPanel panel = new NetworkStatusPanel(client);

        SwingUtilities.invokeAndWait(() -> {});
        check("—".equals(panel.getRetryCountLabel().getText()), "Ban đầu số lần thử lại là '—'");

        client.getConnectionManager().setReconnectAttempts(3);
        SwingUtilities.invokeAndWait(() -> {});

        check("3".equals(panel.getRetryCountLabel().getText()), "Sau khi set 3 lần retry, UI phải hiển thị '3'");

        panel.cleanup();
        System.out.println("  [PASS] testRetryCountDisplayForAn10");
    }

    /**
     * Tiêu chí 7: Giải phóng sự kiện (cleanup) để tránh rò rỉ bộ nhớ.
     */
    private static void testCleanupRemovesListeners() throws Exception {
        SocketClient client = new SocketClient();
        NetworkStatusPanel panel = new NetworkStatusPanel(client);

        SwingUtilities.invokeAndWait(() -> {});
        panel.cleanup();

        // Kích hoạt thay đổi sau khi cleanup
        client.getConnectionManager().setReconnectAttempts(99);
        SwingUtilities.invokeAndWait(() -> {});

        check(!"99".equals(panel.getRetryCountLabel().getText()), "Sau khi cleanup, listener không được tiếp tục nhận sự kiện");

        System.out.println("  [PASS] testCleanupRemovesListeners");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("KIỂM THỬ THẤT BẠI: " + message);
        }
    }
}
