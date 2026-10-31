package com.discordmini.client;

import com.discordmini.client.bridge.UiBridgeServer;
import com.discordmini.client.network.SocketClient;
import com.discordmini.client.ui.LoginFrame;

import javax.swing.*;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * ClientApplication: Điểm khởi động ứng dụng Discord Mini Client.
 *
 * Mặc định:
 * - Khởi chạy chế độ Web UI: Khởi động cầu nối HTTP/SSE UiBridgeServer trên localhost trước khi đăng nhập.
 * - Mở trình duyệt web trỏ tới đúng cổng HTTP đang chạy (mặc định http://localhost:8082).
 * - Toàn bộ trải nghiệm đăng ký, đăng nhập và chat chạy trên giao diện Web.
 * - Không hiển thị cửa sổ JFrame / JOptionPane Swing trong chế độ web.
 * - Trang web vẫn mở được bình thường ngay cả khi TCP Server chưa chạy.
 *
 * Tương thích ngược:
 * - Sử dụng cờ lệnh `--swing` (hoặc `-swing`) để khởi chạy giao diện Swing cũ.
 */
public class ClientApplication {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 5000;
    public static final int DEFAULT_HTTP_PORT = 8082;

    public static void main(String[] args) {
        boolean swingMode = false;
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        int httpPort = DEFAULT_HTTP_PORT;

        if (args != null) {
            for (String arg : args) {
                if ("--swing".equalsIgnoreCase(arg) || "-swing".equalsIgnoreCase(arg)) {
                    swingMode = true;
                    break;
                }
            }
            if (args.length > 0 && !args[0].startsWith("-") && !args[0].isBlank()) {
                host = args[0];
            }
            if (args.length > 1 && !args[1].startsWith("-")) {
                try {
                    port = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {}
            }
        }

        if (swingMode) {
            System.out.println("[ClientApplication] Khởi động ở chế độ Desktop Java Swing (--swing)...");
            CountDownLatch latch = new CountDownLatch(1);
            connectAsync(new SocketClient(), host, port, true, true, latch);
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        // Chế độ Web UI mặc định
        try {
            UiBridgeServer bridge = new UiBridgeServer(httpPort, host, port);
            bridge.start();

            String webUrl = "http://localhost:" + bridge.getPort();
            System.out.println("============================================================");
            System.out.println("  DISCORD MINI - GIAO DIỆN WEB LOCALHOST ĐÃ SẴN SÀNG");
            System.out.println("  Địa chỉ truy cập trình duyệt: " + webUrl);
            System.out.println("  Cầu nối máy chủ ChatServer: " + host + ":" + port);
            System.out.println("  (Để chạy giao diện Swing cũ, truyền cờ lệnh: --swing)");
            System.out.println("============================================================");

            // Tự động mở trình duyệt nếu môi trường hỗ trợ
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(webUrl));
                } else {
                    System.out.println("[Thông báo] Vui lòng mở trình duyệt và truy cập: " + webUrl);
                }
            } catch (Exception e) {
                System.out.println("[Thông báo] Không thể tự động mở trình duyệt (" + e.getMessage() + "). Vui lòng truy cập thủ công: " + webUrl);
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("[ClientApplication] Đang dừng UiBridgeServer...");
                bridge.stop();
            }));

            // Giữ luồng chính hoạt động phục vụ web server
            Thread.currentThread().join();

        } catch (IOException e) {
            System.err.println("[ClientApplication LỖI] Không thể khởi động UiBridgeServer: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Khởi tạo kết nối tới server và mở giao diện đăng nhập nếu kết nối thành công.
     * Thao tác kết nối mạng được chuyển sang luồng nền (Worker Thread) bằng SwingWorker,
     * không làm đơ/chặn Event Dispatch Thread (EDT) của Swing.
     */
    public static SwingWorker<SocketClient, Void> connectAndStart(String host, int port) {
        return connectAsync(new SocketClient(), host, port, true, true, null);
    }

    public static SwingWorker<SocketClient, Void> connectAsync(
            SocketClient client,
            String host,
            int port,
            boolean showLoginFrame,
            boolean showErrorDialog) {
        return connectAsync(client, host, port, showLoginFrame, showErrorDialog, null);
    }

    /**
     * Phương thức kết nối bất đồng bộ cho phép tùy biến hiển thị UI phục vụ cả thực tế lẫn kiểm thử tự động.
     * doInBackground() chạy trên luồng nền (Worker Thread).
     * done() chạy trên Event Dispatch Thread (EDT).
     */
    public static SwingWorker<SocketClient, Void> connectAsync(
            SocketClient client,
            String host,
            int port,
            boolean showLoginFrame,
            boolean showErrorDialog,
            CountDownLatch latch) {
        Objects.requireNonNull(client, "client không được null");
        SwingWorker<SocketClient, Void> worker = new SwingWorker<>() {
            @Override
            protected SocketClient doInBackground() throws Exception {
                client.getConnectionManager().setConnecting();
                client.connect(host, port);
                return client;
            }

            @Override
            protected void done() {
                try {
                    SocketClient connectedClient = get();
                    if (showLoginFrame) {
                        new LoginFrame(connectedClient).setVisible(true);
                    }
                } catch (Exception e) {
                    client.close();
                    if (showErrorDialog) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                        JOptionPane.showMessageDialog(null,
                                "Không kết nối được ChatServer tại " + host + ":" + port +
                                "\nHãy chạy ChatServer trước.\n\nChi tiết lỗi: " + message,
                                "Connection Error", JOptionPane.ERROR_MESSAGE);
                    }
                } finally {
                    if (latch != null) {
                        latch.countDown();
                    }
                }
            }
        };
        worker.execute();
        return worker;
    }
}
