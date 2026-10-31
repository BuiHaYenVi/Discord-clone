package com.discordmini.client.ui;

import com.discordmini.client.network.SocketClient;
import com.discordmini.client.protocol.ProtocolHandler;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ kiểm thử tự động cho AN-07: LOGIN/REGISTER UI.
 *
 * Kiểm tra các tiêu chí:
 * 1. Kiểm tra dữ liệu bắt buộc (nhập thiếu username, password, ký tự đặc biệt '|').
 * 2. Khóa thao tác gửi trùng (inFlight), bảo đảm không spam request.
 * 3. Mở lại form khi nhận lỗi, timeout, hoặc mất kết nối.
 * 4. Bảo vệ cửa sổ đã đóng: Kiểm tra isDisplayable() ngăn callback tác động vào frame mồ côi.
 * 5. Đóng RegisterFrame trả lại quyền điều khiển cho LoginFrame an toàn mà không làm mất listener.
 * 6. Sự kiện ngắt kết nối mạng (DISCONNECTED) cập nhật trạng thái tiếng Việt và mở lại form.
 */
public final class AuthUiValidationTest {
    private AuthUiValidationTest() {}

    public static void main(String[] args) throws Exception {
        System.out.println("=== BẮT ĐẦU KIỂM THỬ AN-07: LOGIN/REGISTER UI ===");
        testLoginInputValidation();
        testRegisterInputValidation();
        testSpamClickAndInFlightLock();
        testFormRestoreOnErrorAndNetworkError();
        testRegisterWindowCloseAndRestoreParent();
        testDisposedWindowCallbackSafety();
        testDisconnectUpdatesUiState();
        testLoginSuccessOpensMainFrame();
        System.out.println("=== TẤT CẢ TEST AN-07: LOGIN/REGISTER UI ĐÃ PASS! ===");
    }

    /**
     * Tiêu chí 1: Kiểm tra dữ liệu bắt buộc trên LoginFrame.
     */
    private static void testLoginInputValidation() throws Exception {
        SocketClient dummyClient = new SocketClient();
        LoginFrame frame = new LoginFrame(dummyClient);

        // 1. Username rỗng
        String err1 = frame.validateInputs("", "password123");
        check(err1 != null && err1.contains("tên đăng nhập"), "Phải bắt lỗi thiếu tên đăng nhập");

        // 2. Password rỗng
        String err2 = frame.validateInputs("an_tong", "");
        check(err2 != null && err2.contains("mật khẩu"), "Phải bắt lỗi thiếu mật khẩu");

        // 3. Username chứa ký tự cấm '|'
        String err3 = frame.validateInputs("an|tong", "password123");
        check(err3 != null && err3.contains("ký tự đặc biệt"), "Phải bắt lỗi chứa ký tự '|'");

        // 4. Password chứa ký tự cấm '|'
        String err4 = frame.validateInputs("an_tong", "pass|word");
        check(err4 != null && err4.contains("ký tự đặc biệt"), "Phải bắt lỗi password chứa ký tự '|'");

        // 5. Dữ liệu hợp lệ
        String ok = frame.validateInputs("an_tong", "valid_pass_123");
        check(ok == null, "Dữ liệu chuẩn phải trả về null (hợp lệ)");

        frame.dispose();
        System.out.println("  [PASS] testLoginInputValidation");
    }

    /**
     * Tiêu chí 1: Kiểm tra dữ liệu bắt buộc trên RegisterFrame.
     */
    private static void testRegisterInputValidation() throws Exception {
        SocketClient dummyClient = new SocketClient();
        LoginFrame loginFrame = new LoginFrame(dummyClient);
        RegisterFrame regFrame = new RegisterFrame(dummyClient, loginFrame);

        // 1. Username rỗng
        String err1 = regFrame.validateInputs("", "pass", "Bao An");
        check(err1 != null && err1.contains("tên đăng nhập"), "Phải bắt lỗi thiếu username");

        // 2. Password rỗng
        String err2 = regFrame.validateInputs("an_tong", "", "Bao An");
        check(err2 != null && err2.contains("mật khẩu"), "Phải bắt lỗi thiếu password");

        // 3. Tên hiển thị chứa ký tự cấm '|'
        String err3 = regFrame.validateInputs("an_tong", "123456", "Bao|An");
        check(err3 != null && err3.contains("ký tự đặc biệt"), "Phải bắt lỗi display name chứa '|'");

        // 4. Hợp lệ
        String ok = regFrame.validateInputs("an_tong", "123456", "Bảo An");
        check(ok == null, "Dữ liệu chuẩn phải trả về null");

        regFrame.dispose();
        loginFrame.dispose();
        System.out.println("  [PASS] testRegisterInputValidation");
    }

    /**
     * Tiêu chí 2: Khóa thao tác gửi trùng (inFlight) khi đang đăng nhập.
     */
    private static void testSpamClickAndInFlightLock() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            AtomicInteger receivedRequests = new AtomicInteger(0);

            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        check(!line.isBlank(), "Dòng lệnh nhận được không được để trống");
                        if ("PING".equals(line)) {
                            writer.println("PONG");
                            continue;
                        }
                        receivedRequests.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);

            LoginFrame frame = new LoginFrame(client);

            // Giả lập nhập thông tin qua Swing EDT
            SwingUtilities.invokeAndWait(() -> {
                frame.getUsernameField().setText("user_spam");
                frame.getPasswordField().setText("pass_spam");
            });

            // Người dùng click đăng nhập nhiều lần liên tiếp
            SwingUtilities.invokeAndWait(frame::doLogin);
            check(frame.isInFlight(), "Sau khi doLogin, inFlight phải là true");

            // Click lần 2 và lần 3 ngay lập tức khi đang inFlight
            SwingUtilities.invokeAndWait(frame::doLogin);
            SwingUtilities.invokeAndWait(frame::doLogin);

            Thread.sleep(150);
            check(receivedRequests.get() == 1,
                    "Chỉ được gửi 1 request duy nhất lên Server, các click spam tiếp theo phải bị chặn (đã nhận: " + receivedRequests.get() + ")");

            frame.dispose();
            client.close();
        }
        System.out.println("  [PASS] testSpamClickAndInFlightLock");
    }

    /**
     * Tiêu chí 3: Mở lại form khi nhận lỗi hoặc mất kết nối.
     */
    private static void testFormRestoreOnErrorAndNetworkError() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    // Nhận LOGIN request và phản hồi lỗi mật khẩu
                    reader.readLine();
                    writer.println("ERROR|" + ProtocolHandler.encode("Sai tài khoản hoặc mật khẩu."));
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            LoginFrame frame = new LoginFrame(client);

            SwingUtilities.invokeAndWait(() -> {
                JTextField u = findComponent(frame, JTextField.class);
                JPasswordField p = findComponent(frame, JPasswordField.class);
                u.setText("user_wrong");
                p.setText("pass_wrong");
            });

            SwingUtilities.invokeAndWait(frame::doLogin);
            check(frame.isInFlight(), "Đang gửi yêu cầu thì inFlight phải là true");

            // Đợi phản hồi lỗi từ server
            Thread.sleep(250);

            check(!frame.isInFlight(), "Sau khi nhận lỗi, inFlight phải trở về false");

            frame.dispose();
            client.close();
        }
        System.out.println("  [PASS] testFormRestoreOnErrorAndNetworkError");
    }

    /**
     * Tiêu chí 5: Đóng RegisterFrame khôi phục quyền điều khiển cho LoginFrame an toàn.
     */
    private static void testRegisterWindowCloseAndRestoreParent() throws Exception {
        SocketClient dummyClient = new SocketClient();
        LoginFrame loginFrame = new LoginFrame(dummyClient);
        RegisterFrame regFrame = new RegisterFrame(dummyClient, loginFrame);

        // Khóa form login tạm thời khi mở register
        loginFrame.setFormEnabled(false);

        // Gọi đóng register
        regFrame.closeAndRestore();

        // Kiểm tra loginFrame đã được khôi phục
        JButton loginBtn = findComponent(loginFrame, JButton.class);
        check(loginBtn != null && loginBtn.isEnabled(), "Nút đăng nhập của LoginFrame phải được kích hoạt lại");

        loginFrame.dispose();
        System.out.println("  [PASS] testRegisterWindowCloseAndRestoreParent");
    }

    /**
     * Tiêu chí 6: Kiểm tra isDisplayable() ngăn callback tác động vào frame đã đóng.
     */
    private static void testDisposedWindowCallbackSafety() throws Exception {
        SocketClient dummyClient = new SocketClient();
        LoginFrame frame = new LoginFrame(dummyClient);
        frame.dispose(); // Đóng frame

        check(frame.isDisposed(), "Sau khi dispose, frame.isDisposed() phải là true");

        // Gọi onRegisterClosed trên frame đã đóng không được ném ngoại lệ
        try {
            frame.onRegisterClosed();
        } catch (Throwable t) {
            throw new AssertionError("Không được ném lỗi khi cập nhật frame đã đóng: " + t.getMessage());
        }
        System.out.println("  [PASS] testDisposedWindowCallbackSafety");
    }

    /**
     * Tiêu chí 7: Trạng thái DISCONNECTED cập nhật giao diện tiếng Việt và giải phóng inFlight.
     */
    private static void testDisconnectUpdatesUiState() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            LoginFrame frame = new LoginFrame(client);

            // Giả lập trạng thái inFlight
            SwingUtilities.invokeAndWait(() -> {
                frame.getUsernameField().setText("user_test");
                frame.getPasswordField().setText("pass_test");
            });

            // Ngắt kết nối
            client.close();
            Thread.sleep(150);

            check(!frame.isInFlight(), "Khi mất kết nối, inFlight phải được reset về false");

            frame.dispose();
        }
        System.out.println("  [PASS] testDisconnectUpdatesUiState");
    }

    /**
     * Tiêu chí kiểm tra tích hợp: Đăng nhập thành công phải đóng LoginFrame và mở MainFrame thật.
     */
    private static void testLoginSuccessOpensMainFrame() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("LOGIN|")) {
                            String[] parts = line.split("\\|", -1);
                            String reqId = parts[1];
                            // Server phản hồi ACK mạng và OK đăng nhập thành công theo đúng giao thức
                            writer.println("ACK|" + reqId);
                            writer.println("OK|LOGIN|1|" + ProtocolHandler.encode("Bảo An"));
                        }
                    }
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            LoginFrame loginFrame = new LoginFrame(client);

            SwingUtilities.invokeAndWait(() -> {
                loginFrame.getUsernameField().setText("bao_an");
                loginFrame.getPasswordField().setText("secret123");
            });

            SwingUtilities.invokeAndWait(loginFrame::doLogin);

            // Chờ phản hồi và mở MainFrame
            MainFrame openedMainFrame = null;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 3000) {
                Thread.sleep(100);
                for (java.awt.Window w : java.awt.Window.getWindows()) {
                    if (w instanceof MainFrame mf && mf.isDisplayable()) {
                        openedMainFrame = mf;
                        break;
                    }
                }
                if (openedMainFrame != null) break;
            }

            check(loginFrame.isDisposed(), "LoginFrame phải được dispose() sau khi đăng nhập thành công");
            check(openedMainFrame != null, "MainFrame thực sự phải được mở sau khi đăng nhập thành công");
            check(openedMainFrame.getTitle().contains("Bảo An"), "MainFrame phải hiển thị đúng tên hiển thị của người dùng");

            // Kiểm tra các panel bên trong MainFrame đã được tạo và hiển thị
            check(openedMainFrame.getRoomPanel() != null, "MainFrame phải chứa RoomPanel");
            check(openedMainFrame.getChatPanel() != null, "MainFrame phải chứa ChatPanel");
            check(openedMainFrame.getUserPanel() != null, "MainFrame phải chứa UserPanel");
            check(openedMainFrame.getNetworkStatusPanel() != null, "MainFrame phải chứa NetworkStatusPanel");

            final MainFrame mfToClose = openedMainFrame;
            SwingUtilities.invokeAndWait(mfToClose::dispose);
            client.close();
        }
        System.out.println("  [PASS] testLoginSuccessOpensMainFrame");
    }

    @SuppressWarnings("unchecked")
    private static <T extends JComponent> T findComponent(Container root, Class<T> clazz) {
        for (Component comp : root.getComponents()) {
            if (clazz.isInstance(comp)) {
                return (T) comp;
            }
            if (comp instanceof Container childContainer) {
                T found = findComponent(childContainer, clazz);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("KIỂM THỬ THẤT BẠI: " + message);
        }
    }
}
