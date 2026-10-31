package com.discordmini.client.service;

import com.discordmini.client.model.ChatMessage;
import com.discordmini.client.model.MessageStatus;
import com.discordmini.client.network.SocketClient;
import com.discordmini.client.protocol.ProtocolHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bộ kiểm thử tự động cho AN-05: CHAT SERVICE.
 *
 * Kiểm tra các tiêu chí:
 * 1. Gửi tin nhắn Unicode & xác nhận ACK khớp requestId chuyển trạng thái SENT.
 * 2. Theo dõi tin đang gửi: ACK đến khác thứ tự vẫn map đúng tin nhắn tương ứng.
 * 3. Không biến ACK của LOGIN hay JOIN_ROOM thành xác nhận gửi tin.
 * 4. Phản hồi ERROR đánh dấu tin nhắn FAILED.
 * 5. Timeout đánh dấu UNKNOWN_TIMEOUT, không tự ý gửi lại tin.
 * 6. Mất mạng ngoài ý muốn chuyển tin PENDING thành UNKNOWN_TIMEOUT.
 * 7. Lịch sử được sắp xếp đúng thứ tự và deduplicate với tin nhắn realtime.
 */
public final class ChatServiceTest {
    private ChatServiceTest() {}

    public static void main(String[] args) throws Exception {
        System.out.println("=== BẮT ĐẦU KIỂM THỬ AN-05: CHAT SERVICE ===");
        testSendMessageSuccessWithMatchingAck();
        testOutboundRequestTrackingAndNonMatchingAck();
        testNonChatAckIgnored();
        testErrorMessageMarksFailed();
        testTimeoutMarksUnknownTimeout();
        testNetworkLossMarksPendingMessages();
        testHistoryOrderAndDeduplicationWithRealtime();
        System.out.println("=== TẤT CẢ TEST AN-05: CHAT SERVICE ĐÃ PASS! ===");
    }

    /**
     * Tiêu chí 1: Gửi tin nhắn Unicode tiếng Việt, nhận đúng ACK -> SENT.
     */
    private static void testSendMessageSuccessWithMatchingAck() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line = readCommand(reader, writer, "SEND_MESSAGE");
                    String[] parts = ProtocolHandler.split(line);
                    check(parts.length >= 4 && "@RID".equals(parts[parts.length - 2]), "Gói tin phải có @RID trailer");
                    String reqId = parts[parts.length - 1];

                    // Server phản hồi ACK khớp requestId
                    writer.println("ACK|" + reqId + "|OK");
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ChatService chat = new ChatService(client, "Bảo An");

            BlockingQueue<MessageStatus> statusQueue = new ArrayBlockingQueue<>(5);
            chat.addListener(new ChatEventListener() {
                @Override
                public void onMessageStatusChanged(String requestId, MessageStatus status, String errorMessage) {
                    statusQueue.offer(status);
                }
            });

            String reqId = chat.sendMessage("Xin chào tiếng Việt có dấu 🚀");
            check(reqId != null, "sendMessage phải trả về requestId");

            ChatMessage pending = chat.getPendingMessage(reqId);
            check(pending != null, "Tin nhắn phải nằm trong pending map");
            check("Xin chào tiếng Việt có dấu 🚀".equals(pending.getContent()), "Nội dung Unicode phải được giữ nguyên");

            // Chờ nhận trạng thái SENT sau khi Server trả về ACK
            MessageStatus finalStatus = null;
            for (int i = 0; i < 2; i++) {
                MessageStatus s = statusQueue.poll(3, TimeUnit.SECONDS);
                if (s == MessageStatus.SENT) {
                    finalStatus = s;
                    break;
                }
            }
            check(finalStatus == MessageStatus.SENT, "Trạng thái tin nhắn phải chuyển sang SENT sau khi nhận ACK khớp");

            client.close();
            System.out.println("  [PASS] testSendMessageSuccessWithMatchingAck");
        }
    }

    /**
     * Tiêu chí 2: Gửi nhiều tin, ACK đến khác thứ tự vẫn đối chiếu chính xác.
     */
    private static void testOutboundRequestTrackingAndNonMatchingAck() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line1 = readCommand(reader, writer, "SEND_MESSAGE");
                    String line2 = readCommand(reader, writer, "SEND_MESSAGE");

                    String[] parts1 = ProtocolHandler.split(line1);
                    String[] parts2 = ProtocolHandler.split(line2);

                    String rid1 = parts1[parts1.length - 1];
                    String rid2 = parts2[parts2.length - 1];

                    // Server cố tình gửi ACK của tin 2 trước, tin 1 sau
                    writer.println("ACK|" + rid2 + "|OK");
                    Thread.sleep(100);
                    writer.println("ACK|" + rid1 + "|OK");
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ChatService chat = new ChatService(client, "Bảo An");

            BlockingQueue<String> ackOrder = new ArrayBlockingQueue<>(5);
            chat.addListener(new ChatEventListener() {
                @Override
                public void onMessageStatusChanged(String requestId, MessageStatus status, String errorMessage) {
                    if (status == MessageStatus.SENT) {
                        ackOrder.offer(requestId);
                    }
                }
            });

            String req1 = chat.sendMessage("Tin nhắn 1");
            String req2 = chat.sendMessage("Tin nhắn 2");

            String firstAcked = ackOrder.poll(3, TimeUnit.SECONDS);
            check(req2.equals(firstAcked), "Tin nhắn 2 phải được ACK trước: mong đợi " + req2 + ", nhận " + firstAcked);

            String secondAcked = ackOrder.poll(3, TimeUnit.SECONDS);
            check(req1.equals(secondAcked), "Tin nhắn 1 phải được ACK sau: mong đợi " + req1 + ", nhận " + secondAcked);

            client.close();
            System.out.println("  [PASS] testOutboundRequestTrackingAndNonMatchingAck");
        }
    }

    /**
     * Tiêu chí 3: ACK của lệnh khác (như LOGIN hay JOIN_ROOM) tuyệt đối không được làm tin nhắn thành SENT.
     */
    private static void testNonChatAckIgnored() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    readCommand(reader, writer, "SEND_MESSAGE");

                    // Server gửi ACK của một lệnh khác (không khớp requestId của tin nhắn)
                    writer.println("ACK|unrelated-join-room-id-999|OK");
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ChatService chat = new ChatService(client, "Bảo An");

            AtomicReference<MessageStatus> statusTriggered = new AtomicReference<>();
            chat.addListener(new ChatEventListener() {
                @Override
                public void onMessageStatusChanged(String requestId, MessageStatus status, String errorMessage) {
                    if (status == MessageStatus.SENT) {
                        statusTriggered.set(status);
                    }
                }
            });

            String reqId = chat.sendMessage("Tin nhắn đang chờ ACK");
            Thread.sleep(300);

            // Tin nhắn không được chuyển sang SENT
            check(statusTriggered.get() == null, "ACK không khớp requestId tuyệt đối không được chuyển tin nhắn sang SENT");
            ChatMessage pending = chat.getPendingMessage(reqId);
            check(pending != null && pending.getStatus() == MessageStatus.PENDING, "Tin nhắn vẫn phải ở trạng thái PENDING");

            client.close();
            System.out.println("  [PASS] testNonChatAckIgnored");
        }
    }

    /**
     * Tiêu chí 4: Server phản hồi ERROR gắn với requestId -> tin nhắn FAILED.
     */
    private static void testErrorMessageMarksFailed() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line = readCommand(reader, writer, "SEND_MESSAGE");
                    String[] parts = ProtocolHandler.split(line);
                    String reqId = parts[parts.length - 1];

                    // Server gửi ERROR kèm requestId
                    writer.println("ERROR|" + ProtocolHandler.encode("Bạn chưa vào phòng chat.") + "|" + reqId);
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ChatService chat = new ChatService(client, "Bảo An");

            BlockingQueue<String> failedQueue = new ArrayBlockingQueue<>(1);
            chat.addListener(new ChatEventListener() {
                @Override
                public void onMessageStatusChanged(String requestId, MessageStatus status, String errorMessage) {
                    if (status == MessageStatus.FAILED) {
                        failedQueue.offer(errorMessage);
                    }
                }
            });

            chat.sendMessage("Tin nhắn lỗi");
            String err = failedQueue.poll(3, TimeUnit.SECONDS);
            check("Bạn chưa vào phòng chat.".equals(err), "Lỗi phải được giải mã tiếng Việt đúng: " + err);

            client.close();
            System.out.println("  [PASS] testErrorMessageMarksFailed");
        }
    }

    /**
     * Tiêu chí 5: Timeout chuyển UNKNOWN_TIMEOUT, không tự ý gửi lại tin.
     */
    private static void testTimeoutMarksUnknownTimeout() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8))) {
                    // Server giữ socket mở nhưng không gửi gì (để giả lập timeout)
                    readCommand(reader, null, "SEND_MESSAGE");
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ChatService chat = new ChatService(client, "Bảo An");

            BlockingQueue<MessageStatus> timeoutQueue = new ArrayBlockingQueue<>(1);
            chat.addListener(new ChatEventListener() {
                @Override
                public void onMessageStatusChanged(String requestId, MessageStatus status, String errorMessage) {
                    if (status == MessageStatus.UNKNOWN_TIMEOUT) {
                        timeoutQueue.offer(status);
                    }
                }
            });

            String reqId = chat.sendMessage("Tin nhắn timeout");

            // Giả lập sự kiện REQUEST_TIMEOUT phát từ SocketClient
            client.getServerListener().dispatch("REQUEST_TIMEOUT|" + reqId + "|SEND_MESSAGE");

            MessageStatus s = timeoutQueue.poll(3, TimeUnit.SECONDS);
            check(s == MessageStatus.UNKNOWN_TIMEOUT, "Trạng thái phải là UNKNOWN_TIMEOUT khi timeout");

            // Đảm bảo tin đã bị xóa khỏi pending map
            check(chat.getPendingMessage(reqId) == null, "Tin nhắn timeout phải được gỡ khỏi pending map");

            client.close();
            System.out.println("  [PASS] testTimeoutMarksUnknownTimeout");
        }
    }

    /**
     * Tiêu chí 6: Mất mạng đột ngột chuyển tin nhắn PENDING thành UNKNOWN_TIMEOUT.
     */
    private static void testNetworkLossMarksPendingMessages() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8))) {
                    readCommand(reader, null, "SEND_MESSAGE");
                    // Đóng socket đột ngột
                    peer.close();
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ChatService chat = new ChatService(client, "Bảo An");

            BlockingQueue<MessageStatus> disconnectQueue = new ArrayBlockingQueue<>(1);
            chat.addListener(new ChatEventListener() {
                @Override
                public void onMessageStatusChanged(String requestId, MessageStatus status, String errorMessage) {
                    if (status == MessageStatus.UNKNOWN_TIMEOUT) {
                        disconnectQueue.offer(status);
                    }
                }
            });

            chat.sendMessage("Tin nhắn trước khi rớt mạng");

            MessageStatus s = disconnectQueue.poll(3, TimeUnit.SECONDS);
            check(s == MessageStatus.UNKNOWN_TIMEOUT, "Rớt mạng phải chuyển tin PENDING sang UNKNOWN_TIMEOUT");

            client.close();
            System.out.println("  [PASS] testNetworkLossMarksPendingMessages");
        }
    }

    /**
     * Tiêu chí 7: Xử lý lịch sử đúng thứ tự thời gian và deduplicate với tin nhắn realtime.
     */
    private static void testHistoryOrderAndDeduplicationWithRealtime() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    // 1. Gửi tin realtime trước (messageId = 100)
                    writer.println("NEW_MESSAGE|100|1|" + ProtocolHandler.encode("Bảo An")
                            + "|" + ProtocolHandler.encode("Tin nhắn realtime")
                            + "|" + ProtocolHandler.encode("10:30:00"));

                    Thread.sleep(100);

                    // 2. Gửi lịch sử: bao gồm tin 98, tin 99 và tin 100 (trùng với realtime!)
                    writer.println("HISTORY_BEGIN|1");
                    writer.println("HISTORY_ITEM|100|" + ProtocolHandler.encode("Bảo An")
                            + "|" + ProtocolHandler.encode("Tin nhắn realtime")
                            + "|" + ProtocolHandler.encode("10:30:00"));
                    writer.println("HISTORY_ITEM|98|" + ProtocolHandler.encode("Vi")
                            + "|" + ProtocolHandler.encode("Tin nhắn số 98")
                            + "|" + ProtocolHandler.encode("10:28:00"));
                    writer.println("HISTORY_ITEM|99|" + ProtocolHandler.encode("Vinh")
                            + "|" + ProtocolHandler.encode("Tin nhắn số 99")
                            + "|" + ProtocolHandler.encode("10:29:00"));
                    writer.println("HISTORY_END");
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            ChatService chat = new ChatService(client, "Bảo An");

            BlockingQueue<ChatMessage> realtimeMessages = new ArrayBlockingQueue<>(5);
            BlockingQueue<List<ChatMessage>> historyBatches = new ArrayBlockingQueue<>(5);

            chat.addListener(new ChatEventListener() {
                @Override
                public void onNewMessage(ChatMessage message) {
                    realtimeMessages.offer(message);
                }

                @Override
                public void onHistoryLoaded(List<ChatMessage> history) {
                    historyBatches.offer(history);
                }
            });

            // Nhận tin realtime 100
            ChatMessage realtime = realtimeMessages.poll(3, TimeUnit.SECONDS);
            check(realtime != null && realtime.getServerMessageId() == 100, "Phải nhận được tin realtime 100");

            // Nhận danh sách lịch sử
            List<ChatMessage> history = historyBatches.poll(3, TimeUnit.SECONDS);
            check(history != null, "Phải nhận được danh sách lịch sử");
            check(history.size() == 3, "Lịch sử phải có 3 tin (98, 99, 100), thực tế nhận: " + history.size());

            // Kiểm tra thứ tự: ID 98 -> 99 -> 100 (đúng thứ tự thời gian tăng dần!)
            check(history.get(0).getServerMessageId() == 98, "Tin đầu tiên phải là ID 98");
            check(history.get(1).getServerMessageId() == 99, "Tin thứ hai phải là ID 99");
            check(history.get(2).getServerMessageId() == 100, "Tin thứ ba phải là ID 100");

            // Kiểm tra lại: Nếu server gửi lại NEW_MESSAGE 100, ChatService phải deduplicate không phát lại
            client.getServerListener().dispatch("NEW_MESSAGE|100|1|" + ProtocolHandler.encode("Bảo An")
                    + "|" + ProtocolHandler.encode("Tin nhắn realtime")
                    + "|" + ProtocolHandler.encode("10:30:00"));

            ChatMessage duplicate = realtimeMessages.poll(300, TimeUnit.MILLISECONDS);
            check(duplicate == null, "Tin nhắn đã có trong lịch sử KHÔNG được phát lại qua realtime (deduplicate thành công)");

            client.close();
            System.out.println("  [PASS] testHistoryOrderAndDeduplicationWithRealtime");
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
                if (writer != null) writer.println("PONG");
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
