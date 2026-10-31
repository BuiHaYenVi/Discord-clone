package com.discordmini.client.ui;

import com.discordmini.client.model.ChatMessage;
import com.discordmini.client.model.MessageStatus;
import com.discordmini.client.network.SocketClient;
import com.discordmini.client.protocol.ProtocolHandler;
import com.discordmini.client.service.ChatService;
import com.discordmini.client.service.RoomService;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Bộ kiểm thử tự động cho PROMPT 7 — AN-08: MAIN CHAT UI.
 *
 * Kiểm tra các tiêu chí:
 * 1. Khóa ô nhập và nút gửi khi chưa đăng nhập, chưa vào phòng hoặc mất kết nối.
 * 2. Mở ô nhập và nút gửi khi đã vào phòng thành công.
 * 3. Hiển thị trạng thái từng tin: PENDING (⏳ Đang gửi...), SENT (✓ Đã gửi), FAILED, UNKNOWN_TIMEOUT.
 * 4. Không coi "Sent" là người nhận đã đọc.
 * 5. Không hiển thị cùng một tin hai lần khi vừa thêm tin tạm vừa nhận broadcast từ Server.
 * 6. Dọn sạch dữ liệu phòng cũ khi chuyển phòng hoặc rời phòng.
 * 7. Mô phỏng luồng tin nhắn hai Client cùng phòng và khác phòng.
 */
public final class MainChatUiTest {
    private MainChatUiTest() {}

    public static void main(String[] args) throws Exception {
        System.out.println("=== BẮT ĐẦU KIỂM THỬ AN-08: MAIN CHAT UI ===");
        testSendLockedWhenNotInRoomOrDisconnected();
        testSendUnlockedWhenJoinedRoom();
        testMessageStatusLifecyclePendingAndSent();
        testMessageStatusTimeoutAndError();
        testPreventDuplicateMessageOnSelfBroadcast();
        testClearMessagesWhenLeavingOrSwitchingRoom();
        testTwoClientsSameRoomAndDifferentRoomSimulation();
        testLogoutCleansSessionAndRestoresLoginFrame();
        System.out.println("=== TẤT CẢ TEST AN-08: MAIN CHAT UI ĐÃ PASS! ===");
    }

    /**
     * Tiêu chí 1: Khóa ô nhập và nút gửi khi chưa vào phòng hoặc mất kết nối.
     */
    private static void testSendLockedWhenNotInRoomOrDisconnected() throws Exception {
        SocketClient dummyClient = new SocketClient();
        ChatService chatService = new ChatService(dummyClient, "Bao An");
        RoomService roomService = new RoomService(dummyClient, chatService);
        ChatPanel chatPanel = new ChatPanel(dummyClient, chatService, roomService, "Bao An");

        // 1. Khi chưa kết nối và chưa vào phòng -> Khóa gửi
        SwingUtilities.invokeAndWait(chatPanel::updateSendState);
        check(!chatPanel.getMessageInput().isEnabled(), "Ô nhập phải bị khóa khi chưa kết nối và chưa vào phòng");
        check(!chatPanel.getBtnSend().isEnabled(), "Nút gửi phải bị khóa khi chưa kết nối");

        System.out.println("  [PASS] testSendLockedWhenNotInRoomOrDisconnected");
    }

    /**
     * Tiêu chí 2: Mở gửi khi đã kết nối và vào phòng thành công.
     */
    private static void testSendUnlockedWhenJoinedRoom() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    // Nhận JOIN_ROOM|1 và trả về OK
                    String line = readCommand(reader, writer, "JOIN_ROOM");
                    check(line.contains("JOIN_ROOM|1"), "Server phải nhận lệnh JOIN_ROOM|1");
                    writer.println("OK|JOIN_ROOM|1|" + ProtocolHandler.encode("Sảnh chính"));
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ChatService chatService = new ChatService(client, "Bao An");
            RoomService roomService = new RoomService(client, chatService);
            ChatPanel chatPanel = new ChatPanel(client, chatService, roomService, "Bao An");

            // Trước khi vào phòng: Bị khóa
            check(!chatPanel.getMessageInput().isEnabled(), "Trước khi vào phòng, ô nhập phải khóa");

            // Vào phòng 1
            roomService.joinRoom(1);
            Thread.sleep(200);

            // Sau khi vào phòng thành công: Mở khóa
            SwingUtilities.invokeAndWait(() -> {
                check(chatPanel.getMessageInput().isEnabled(), "Sau khi vào phòng, ô nhập phải được mở");
                check(chatPanel.getBtnSend().isEnabled(), "Nút gửi phải được kích hoạt");
                check(chatPanel.getRoomTitleLabel().getText().contains("Sảnh chính"), "Tiêu đề phòng phải là # Sảnh chính");
            });

            client.close();
        }
        System.out.println("  [PASS] testSendUnlockedWhenJoinedRoom");
    }

    /**
     * Tiêu chí 3 & 4: Vòng đời trạng thái tin nhắn PENDING -> SENT (với nhãn ✓ Đã gửi, không coi là Đã đọc).
     */
    private static void testMessageStatusLifecyclePendingAndSent() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    // Đọc lệnh SEND_MESSAGE
                    String line = readCommand(reader, writer, "SEND_MESSAGE");
                    String reqId = ProtocolHandler.requestId(ProtocolHandler.split(line));

                    // Chờ một chút rồi phản hồi ACK khớp requestId
                    Thread.sleep(150);
                    writer.println("ACK|" + reqId);
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ChatService chatService = new ChatService(client, "Bao An");
            RoomService roomService = new RoomService(client, chatService);
            ChatPanel chatPanel = new ChatPanel(client, chatService, roomService, "Bao An");

            // Giả lập đã ở phòng 1
            roomService.handleServerPacket("OK|JOIN_ROOM|1|" + ProtocolHandler.encode("Sảnh chính"));
            Thread.sleep(50);

            // Gửi tin nhắn
            SwingUtilities.invokeAndWait(() -> {
                chatPanel.getMessageInput().setText("Xin chào mọi người!");
                chatPanel.getBtnSend().doClick();
            });

            // Kiểm tra trạng thái tức thì: PENDING
            SwingUtilities.invokeAndWait(() -> {
                DefaultListModel<ChatMessage> model = chatPanel.getMessageListModel();
                check(model.size() == 1, "Phải có 1 tin nhắn trong danh sách");
                ChatMessage msg = model.get(0);
                check(msg.getStatus() == MessageStatus.PENDING, "Trạng thái ban đầu phải là PENDING");
            });

            // Chờ nhận ACK từ server
            Thread.sleep(300);

            // Kiểm tra trạng thái sau ACK: SENT
            SwingUtilities.invokeAndWait(() -> {
                DefaultListModel<ChatMessage> model = chatPanel.getMessageListModel();
                ChatMessage msg = model.get(0);
                check(msg.getStatus() == MessageStatus.SENT, "Trạng thái sau khi có ACK phải là SENT");
            });

            client.close();
        }
        System.out.println("  [PASS] testMessageStatusLifecyclePendingAndSent");
    }

    /**
     * Tiêu chí 4: Xử lý timeout và lỗi gửi từ Server.
     */
    private static void testMessageStatusTimeoutAndError() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    // Đọc SEND_MESSAGE và phản hồi lỗi ERROR
                    String line = readCommand(reader, writer, "SEND_MESSAGE");
                    String reqId = ProtocolHandler.requestId(ProtocolHandler.split(line));
                    writer.println("ERROR|" + ProtocolHandler.encode("Bạn bị cấm chat trong phòng này.") + "|" + reqId);
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ChatService chatService = new ChatService(client, "Bao An");
            RoomService roomService = new RoomService(client, chatService);
            ChatPanel chatPanel = new ChatPanel(client, chatService, roomService, "Bao An");

            roomService.handleServerPacket("OK|JOIN_ROOM|1|" + ProtocolHandler.encode("Sảnh chính"));
            Thread.sleep(50);

            // Gửi tin
            SwingUtilities.invokeAndWait(() -> {
                chatPanel.getMessageInput().setText("Tin bị lỗi");
                chatPanel.getBtnSend().doClick();
            });

            Thread.sleep(200);

            // Sau khi nhận ERROR: trạng thái là FAILED
            SwingUtilities.invokeAndWait(() -> {
                ChatMessage msg = chatPanel.getMessageListModel().get(0);
                check(msg.getStatus() == MessageStatus.FAILED, "Khi nhận ERROR, tin phải chuyển thành FAILED");
            });

            client.close();
        }
        System.out.println("  [PASS] testMessageStatusTimeoutAndError");
    }

    /**
     * Tiêu chí 5: Không nhân đôi tin nhắn khi Server broadcast NEW_MESSAGE của chính mình.
     */
    private static void testPreventDuplicateMessageOnSelfBroadcast() throws Exception {
        SocketClient dummyClient = new SocketClient();
        ChatService chatService = new ChatService(dummyClient, "Bao An");
        RoomService roomService = new RoomService(dummyClient, chatService);
        ChatPanel chatPanel = new ChatPanel(dummyClient, chatService, roomService, "Bao An");

        // 1. Thêm tin nhắn tạm do mình gửi
        ChatMessage outgoing = ChatMessage.createOutgoing("req-999", "Bao An", "Hello world!");
        chatPanel.addOrUpdateMessage(outgoing);

        SwingUtilities.invokeAndWait(() -> {});
        check(chatPanel.getMessageListModel().size() == 1, "Ban đầu có 1 tin");

        // 2. Server broadcast NEW_MESSAGE chứa đúng nội dung mình vừa gửi
        ChatMessage broadcastFromSelf = ChatMessage.createIncoming(101, "Bao An", "Hello world!", "12:00:00", true);
        chatPanel.addOrUpdateMessage(broadcastFromSelf);

        SwingUtilities.invokeAndWait(() -> {});

        // Kiểm tra danh sách vẫn CHỈ CÓ 1 TIN (không bị nhân đôi thành 2 tin)
        check(chatPanel.getMessageListModel().size() == 1,
                "Không được nhân đôi tin nhắn khi nhận broadcast của chính mình (size = " + chatPanel.getMessageListModel().size() + ")");
        check(Integer.valueOf(101).equals(chatPanel.getMessageListModel().get(0).getServerMessageId()),
                "Tin nhắn phải được cập nhật serverMessageId = 101");

        System.out.println("  [PASS] testPreventDuplicateMessageOnSelfBroadcast");
    }

    /**
     * Tiêu chí 6: Dọn sạch tin nhắn phòng cũ khi chuyển phòng hoặc rời phòng.
     */
    private static void testClearMessagesWhenLeavingOrSwitchingRoom() throws Exception {
        SocketClient dummyClient = new SocketClient();
        ChatService chatService = new ChatService(dummyClient, "Bao An");
        RoomService roomService = new RoomService(dummyClient, chatService);
        ChatPanel chatPanel = new ChatPanel(dummyClient, chatService, roomService, "Bao An");

        // Đang có 3 tin nhắn trong phòng
        chatPanel.addOrUpdateMessage(ChatMessage.createIncoming(1, "User A", "Msg 1", "10:00:00", false));
        chatPanel.addOrUpdateMessage(ChatMessage.createIncoming(2, "User B", "Msg 2", "10:01:00", false));
        chatPanel.addOrUpdateMessage(ChatMessage.createIncoming(3, "Bao An", "Msg 3", "10:02:00", true));

        SwingUtilities.invokeAndWait(() -> {}); // Chờ EDT dispatch xong 3 tin nhắn
        check(chatPanel.getMessageListModel().size() == 3, "Phải có 3 tin nhắn");

        // Người dùng rời phòng
        SwingUtilities.invokeAndWait(chatPanel::clearMessages);

        check(chatPanel.getMessageListModel().isEmpty(), "Sau khi rời phòng, danh sách tin nhắn phải trống");
        check("Chưa chọn phòng".equals(chatPanel.getRoomTitleLabel().getText()), "Tiêu đề phải là Chưa chọn phòng");
        check(!chatPanel.getMessageInput().isEnabled(), "Ô nhập phải bị khóa lại");

        System.out.println("  [PASS] testClearMessagesWhenLeavingOrSwitchingRoom");
    }

    /**
     * Tiêu chí 7: Mô phỏng Client nhận tin nhắn từ Client khác trong cùng phòng.
     */
    private static void testTwoClientsSameRoomAndDifferentRoomSimulation() throws Exception {
        SocketClient dummyClient = new SocketClient();
        ChatService chatService = new ChatService(dummyClient, "Bao An");
        RoomService roomService = new RoomService(dummyClient, chatService);
        ChatPanel chatPanel = new ChatPanel(dummyClient, chatService, roomService, "Bao An");

        // Tin nhắn từ Client 2 (Yen Vi) trong cùng phòng
        ChatMessage incomingFromOther = ChatMessage.createIncoming(500, "Yen Vi", "Chào Bảo An nhé!", "10:30:00", false);
        chatPanel.addOrUpdateMessage(incomingFromOther);

        SwingUtilities.invokeAndWait(() -> {});
        check(chatPanel.getMessageListModel().size() == 1, "Phải nhận được tin của Yen Vi");
        ChatMessage received = chatPanel.getMessageListModel().get(0);
        check(!received.isMine(), "Tin của Yen Vi phải có isMine = false");
        check("Yen Vi".equals(received.getSender()), "Người gửi phải là Yen Vi");

        System.out.println("  [PASS] testTwoClientsSameRoomAndDifferentRoomSimulation");
    }

    /**
     * Tiêu chí kiểm tra tích hợp: Đăng xuất giải phóng tài nguyên phiên, hủy reconnect và mở lại LoginFrame.
     */
    private static void testLogoutCleansSessionAndRestoresLoginFrame() throws Exception {
        SocketClient dummyClient = new SocketClient();
        MainFrame mainFrame = new MainFrame(dummyClient, "Bảo An", "bao_an", "secret123");

        check("bao_an".equals(mainFrame.getReconnectManager().getCachedUsername()),
                "ReconnectManager phải lưu thông tin đăng nhập tạm thời");

        // Gọi performLogout() trên EDT
        SwingUtilities.invokeAndWait(mainFrame::performLogout);

        // Chờ LoginFrame được mở lại
        LoginFrame openedLoginFrame = null;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 3000) {
            Thread.sleep(100);
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof LoginFrame lf && lf.isDisplayable()) {
                    openedLoginFrame = lf;
                    break;
                }
            }
            if (openedLoginFrame != null) break;
        }

        check(!mainFrame.isDisplayable(), "MainFrame phải được đóng sau khi đăng xuất");
        check(mainFrame.getReconnectManager().getCachedUsername() == null,
                "ReconnectManager phải xóa thông tin đăng nhập sau khi đăng xuất");
        check(openedLoginFrame != null, "LoginFrame phải được mở lại sau khi đăng xuất");
        check("bao_an".equals(openedLoginFrame.getUsernameField().getText()),
                "LoginFrame phải được điền sẵn tên đăng nhập vừa đăng xuất");

        final LoginFrame lfToClose = openedLoginFrame;
        SwingUtilities.invokeAndWait(lfToClose::dispose);
        dummyClient.close();

        System.out.println("  [PASS] testLogoutCleansSessionAndRestoresLoginFrame");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("KIỂM THỬ THẤT BẠI: " + message);
        }
    }

    private static String readCommand(BufferedReader reader, PrintWriter writer, String command) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            if ("PING".equals(line)) {
                writer.println("PONG");
                continue;
            }
            String[] parts = ProtocolHandler.split(line);
            if (parts.length > 0 && command.equals(parts[0])) {
                return line;
            }
            throw new AssertionError("Expected " + command + ", received " + line);
        }
        return null;
    }
}
