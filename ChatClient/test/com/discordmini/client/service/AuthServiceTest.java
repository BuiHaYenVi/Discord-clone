package com.discordmini.client.service;

import com.discordmini.client.network.ConnectionManager;
import com.discordmini.client.network.SocketClient;
import com.discordmini.client.protocol.ProtocolHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bộ kiểm thử tự động cho AN-04: AUTHENTICATION CLIENT.
 *
 * Kiểm tra các tiêu chí:
 * 1. Phân biệt phản hồi đăng nhập thành công với ACK: ACK bất kỳ không được coi là đã đăng nhập.
 * 2. Đăng nhập thành công cập nhật ConnectionManager sang AUTHENTICATED.
 * 3. Xử lý phản hồi lỗi (sai username/password, tài khoản trùng...).
 * 4. Xử lý đăng ký thành công (OK|REGISTERED|...).
 * 5. Chống spam click: Không gửi yêu cầu trùng khi người dùng bấm liên tục.
 * 6. Xử lý mất mạng ngoài ý muốn khi đang xác thực (onNetworkError).
 * 7. Hủy yêu cầu đang chờ (cancelPendingRequest) để tránh callback cũ tác động màn hình mới.
 */
public final class AuthServiceTest {
    private AuthServiceTest() {}

    public static void main(String[] args) throws Exception {
        System.out.println("=== BẮT ĐẦU KIỂM THỬ AN-04: AUTHENTICATION CLIENT ===");
        testLoginSuccessUpdatesConnectionManager();
        testAckDoesNotTriggerAuthentication();
        testAuthFailedErrors();
        testRegisterSuccess();
        testSpamClickGuard();
        testNetworkLossDuringAuth();
        testCancelPendingRequest();
        System.out.println("=== TẤT CẢ TEST AN-04: AUTHENTICATION CLIENT ĐÃ PASS! ===");
    }

    /**
     * Tiêu chí 1 & 2: Đăng nhập thành công cập nhật ConnectionManager.
     */
    private static void testLoginSuccessUpdatesConnectionManager() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line = readCommand(reader, writer, "LOGIN");
                    check(line != null, "Server phải nhận được request");
                    String[] parts = ProtocolHandler.split(line);
                    check("LOGIN".equals(parts[0]), "Lệnh phải là LOGIN");
                    check("an_tong".equals(ProtocolHandler.decode(parts[1])), "Username decode phải là an_tong");
                    check("secret_pass".equals(ProtocolHandler.decode(parts[2])), "Password decode phải là secret_pass");

                    // Server phản hồi đăng nhập thành công
                    writer.println("OK|LOGIN|100|" + ProtocolHandler.encode("Bao An"));
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ConnectionManager cm = client.getConnectionManager();
            AuthService auth = new AuthService(client, cm);

            check(!cm.isAuthenticated(), "Chưa đăng nhập thì isAuthenticated phải là false");

            BlockingQueue<AuthResult> results = new ArrayBlockingQueue<>(1);
            boolean sent = auth.login("an_tong", "secret_pass", new AuthCallback() {
                @Override
                public void onSuccess(AuthResult result) {
                    results.offer(result);
                }

                @Override
                public void onError(String errorMessage) {}

                @Override
                public void onTimeout() {}

                @Override
                public void onNetworkError() {}
            });

            check(sent, "Lệnh login phải được gửi đi thành công");

            AuthResult res = results.poll(3, TimeUnit.SECONDS);
            check(res != null, "Phải nhận được kết quả AuthResult");
            check(res.isSuccess() && res.isLogin(), "AuthResult phải là login thành công");
            check(res.userId() == 100, "UserId phải là 100");
            check("Bao An".equals(res.displayName()), "DisplayName phải là Bao An");

            // Kiểm tra ConnectionManager được cập nhật tự động
            check(cm.isAuthenticated(), "ConnectionManager PHẢI chuyển sang AUTHENTICATED");
            check(cm.getAuthenticatedUserId() == 100, "UserId trong ConnectionManager phải là 100");
            check("Bao An".equals(cm.getAuthenticatedDisplayName()), "DisplayName trong ConnectionManager phải là Bao An");

            client.close();
            System.out.println("  [PASS] testLoginSuccessUpdatesConnectionManager");
        }
    }

    /**
     * Tiêu chí 1: Phân biệt ACK với phản hồi đăng nhập. ACK bất kỳ không được coi là đã đăng nhập!
     */
    private static void testAckDoesNotTriggerAuthentication() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            BlockingQueue<String> serverCommands = new ArrayBlockingQueue<>(5);

            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line = readCommand(reader, writer, "LOGIN");
                    check(line != null && line.startsWith("LOGIN|"), "Server nhận lệnh LOGIN");

                    // 1. Server gửi ACK trước
                    writer.println("ACK|test-rid-123|OK");

                    // Chờ tín hiệu từ client trước khi gửi OK|LOGIN
                    String trigger = serverCommands.poll(3, TimeUnit.SECONDS);
                    if ("SEND_OK".equals(trigger)) {
                        writer.println("OK|LOGIN|200|" + ProtocolHandler.encode("Xac Thuc Hop Le"));
                        Thread.sleep(1000);
                    }
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ConnectionManager cm = client.getConnectionManager();
            AuthService auth = new AuthService(client, cm);

            AtomicBoolean successTriggered = new AtomicBoolean(false);
            BlockingQueue<AuthResult> results = new ArrayBlockingQueue<>(1);

            auth.login("user_ack", "pass_ack", new AuthCallback() {
                @Override
                public void onSuccess(AuthResult result) {
                    successTriggered.set(true);
                    results.offer(result);
                }

                @Override
                public void onError(String errorMessage) {}

                @Override
                public void onTimeout() {}

                @Override
                public void onNetworkError() {}
            });

            // Chờ 300ms sau khi ACK được gửi tới
            Thread.sleep(300);

            // Kiểm tra: Dù ACK đã đến, Client TUYỆT ĐỐI KHÔNG được coi là đã đăng nhập thành công
            check(!successTriggered.get(), "ACK không được kích hoạt onSuccess của AuthService!");
            check(!cm.isAuthenticated(), "ACK không được chuyển trạng thái sang AUTHENTICATED!");

            // Bây giờ cho phép server gửi gói tin OK|LOGIN
            serverCommands.offer("SEND_OK");

            AuthResult res = results.poll(3, TimeUnit.SECONDS);
            check(res != null && res.userId() == 200, "Chỉ khi có gói tin OK|LOGIN thì mới đăng nhập thành công");
            check(cm.isAuthenticated(), "Lúc này ConnectionManager mới là AUTHENTICATED");

            client.close();
            System.out.println("  [PASS] testAckDoesNotTriggerAuthentication");
        }
    }

    /**
     * Tiêu chí 3: Phản hồi lỗi từ Server (Sai mật khẩu).
     */
    private static void testAuthFailedErrors() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    readCommand(reader, writer, "LOGIN");
                    writer.println("ERROR|" + ProtocolHandler.encode("Sai username hoặc password."));
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ConnectionManager cm = client.getConnectionManager();
            AuthService auth = new AuthService(client, cm);

            BlockingQueue<String> errors = new ArrayBlockingQueue<>(1);
            auth.login("wrong_user", "wrong_pass", new AuthCallback() {
                @Override
                public void onSuccess(AuthResult result) {}

                @Override
                public void onError(String errorMessage) {
                    errors.offer(errorMessage);
                }

                @Override
                public void onTimeout() {}

                @Override
                public void onNetworkError() {}
            });

            String err = errors.poll(3, TimeUnit.SECONDS);
            check("Sai username hoặc password.".equals(err), "Thông điệp lỗi phải được giải mã tiếng Việt đúng: " + err);
            check(!cm.isAuthenticated(), "Đăng nhập lỗi thì isAuthenticated phải là false");
            check(!auth.isInFlight(), "Cờ inFlight phải được reset sau lỗi");

            client.close();
            System.out.println("  [PASS] testAuthFailedErrors");
        }
    }

    /**
     * Tiêu chí 4: Xử lý đăng ký tài khoản thành công.
     */
    private static void testRegisterSuccess() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String req = readCommand(reader, writer, "REGISTER");
                    check(req != null && req.startsWith("REGISTER|"), "Server nhận lệnh REGISTER");
                    String[] parts = ProtocolHandler.split(req);
                    check("newbie".equals(ProtocolHandler.decode(parts[1])), "Username decode đúng");
                    check("New Member".equals(ProtocolHandler.decode(parts[3])), "DisplayName decode đúng");

                    writer.println("OK|REGISTERED|" + ProtocolHandler.encode("Đăng ký thành công."));
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            AuthService auth = new AuthService(client);

            BlockingQueue<AuthResult> results = new ArrayBlockingQueue<>(1);
            auth.register("newbie", "pass", "New Member", new AuthCallback() {
                @Override
                public void onSuccess(AuthResult result) {
                    results.offer(result);
                }

                @Override
                public void onError(String errorMessage) {}

                @Override
                public void onTimeout() {}

                @Override
                public void onNetworkError() {}
            });

            AuthResult res = results.poll(3, TimeUnit.SECONDS);
            check(res != null && !res.isLogin(), "Kết quả đăng ký phải có isLogin = false");
            check("Đăng ký thành công.".equals(res.message()), "Thông điệp đăng ký phải đúng: " + res.message());

            client.close();
            System.out.println("  [PASS] testRegisterSuccess");
        }
    }

    /**
     * Tiêu chí 5: Chống spam click: Không gửi yêu cầu trùng khi bấm liên tục.
     */
    private static void testSpamClickGuard() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    readCommand(reader, writer, "LOGIN");
                    // Giữ kết nối nhưng không trả lời ngay để client ở trạng thái inFlight
                    Thread.sleep(1500);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            AuthService auth = new AuthService(client);

            // Bấm lần 1: Đang gửi
            boolean firstCall = auth.login("spam_user", "spam_pass", new AuthCallback() {
                @Override
                public void onSuccess(AuthResult result) {}

                @Override
                public void onError(String errorMessage) {}

                @Override
                public void onTimeout() {}

                @Override
                public void onNetworkError() {}
            });
            check(firstCall, "Lần gọi thứ nhất phải thành công");
            check(auth.isInFlight(), "Trạng thái inFlight phải là true");

            // Bấm lần 2 ngay lập tức: Phải bị chặn
            AtomicBoolean secondCallBlocked = new AtomicBoolean(false);
            boolean secondCall = auth.login("spam_user", "spam_pass", new AuthCallback() {
                @Override
                public void onSuccess(AuthResult result) {}

                @Override
                public void onError(String errorMessage) {
                    if (errorMessage.contains("đang được xử lý")) {
                        secondCallBlocked.set(true);
                    }
                }

                @Override
                public void onTimeout() {}

                @Override
                public void onNetworkError() {}
            });

            check(!secondCall, "Lần gọi thứ 2 phải bị từ chối gửi mạng");
            check(secondCallBlocked.get(), "Lần gọi thứ 2 phải kích hoạt onError thông báo đang xử lý");

            client.close();
            System.out.println("  [PASS] testSpamClickGuard");
        }
    }

    /**
     * Tiêu chí 6: Mất mạng ngoài ý muốn trong lúc đang xác thực -> onNetworkError.
     */
    private static void testNetworkLossDuringAuth() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    readCommand(reader, writer, "LOGIN");
                    // Đột ngột ngắt socket mà không gửi kết quả
                    peer.close();
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            AuthService auth = new AuthService(client);

            BlockingQueue<String> networkEvents = new ArrayBlockingQueue<>(1);
            auth.login("net_user", "net_pass", new AuthCallback() {
                @Override
                public void onSuccess(AuthResult result) {}

                @Override
                public void onError(String errorMessage) {}

                @Override
                public void onTimeout() {}

                @Override
                public void onNetworkError() {
                    networkEvents.offer("NETWORK_ERROR");
                }
            });

            String evt = networkEvents.poll(3, TimeUnit.SECONDS);
            check("NETWORK_ERROR".equals(evt), "Phải kích hoạt onNetworkError khi server rớt socket");
            check(!auth.isInFlight(), "inFlight phải được reset sau khi mất mạng");

            client.close();
            System.out.println("  [PASS] testNetworkLossDuringAuth");
        }
    }

    /**
     * Tiêu chí 7: Hủy yêu cầu đang chờ (cancelPendingRequest).
     */
    private static void testCancelPendingRequest() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            BlockingQueue<String> serverTrigger = new ArrayBlockingQueue<>(1);

            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    readCommand(reader, writer, "LOGIN");
                    // Chờ tín hiệu client hủy yêu cầu xong rồi mới gửi kết quả muộn
                    serverTrigger.take();
                    writer.println("OK|LOGIN|500|" + ProtocolHandler.encode("Muộn Màng"));
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            AuthService auth = new AuthService(client);

            AtomicBoolean lateCallbackFired = new AtomicBoolean(false);
            auth.login("cancel_user", "cancel_pass", new AuthCallback() {
                @Override
                public void onSuccess(AuthResult result) {
                    lateCallbackFired.set(true);
                }

                @Override
                public void onError(String errorMessage) {
                    lateCallbackFired.set(true);
                }

                @Override
                public void onTimeout() {
                    lateCallbackFired.set(true);
                }

                @Override
                public void onNetworkError() {
                    lateCallbackFired.set(true);
                }
            });

            // Người dùng đóng form -> hủy yêu cầu
            auth.cancelPendingRequest();
            check(!auth.isInFlight(), "inFlight phải là false sau khi cancelPendingRequest()");

            // Server phản hồi muộn
            serverTrigger.offer("SEND_LATE_RESPONSE");
            Thread.sleep(300);

            // Callback cũ tuyệt đối không được kích hoạt
            check(!lateCallbackFired.get(), "Callback cũ KHÔNG được kích hoạt sau khi đã cancelPendingRequest()");

            client.close();
            System.out.println("  [PASS] testCancelPendingRequest");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String readCommand(BufferedReader reader, PrintWriter writer, String command) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            if ("PING".equals(line)) {
                writer.println("PONG");
                continue;
            }
            if (command.equals(ProtocolHandler.split(line)[0])) {
                return line;
            }
            throw new AssertionError("Expected " + command + ", received " + line);
        }
        return null;
    }
}
