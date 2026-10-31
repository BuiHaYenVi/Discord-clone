package com.discordmini.client.service;

import com.discordmini.client.model.ChatMessage;
import com.discordmini.client.model.MessageStatus;
import com.discordmini.client.network.SocketClient;
import com.discordmini.client.protocol.ProtocolHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Dịch vụ Chat phía Client: Quản lý gửi tin nhắn, nhận tin realtime và lịch sử.
 *
 * Tiêu chí AN-05:
 * 1. Dùng SEND_MESSAGE, GET_HISTORY và các frame theo đúng giao thức chuẩn.
 * 2. Theo dõi tin gửi bằng request ID (@RID trailer). Bổ sung phương thức trả về ID tương thích caller cũ.
 * 3. Quản lý trạng thái tin: PENDING, SENT, FAILED và UNKNOWN_TIMEOUT.
 * 4. Chỉ đánh dấu SENT khi ACK khớp đúng request ID của tin nhắn.
 * 5. Tuyệt đối không biến ACK của LOGIN hay JOIN_ROOM thành xác nhận gửi tin.
 * 6. Timeout không chứng minh Server chưa lưu tin -> chuyển UNKNOWN_TIMEOUT, KHÔNG tự động gửi lại.
 * 7. Phân biệt rõ request ID (UUID phía Client) với serverMessageId (ID trong DB do Server cấp).
 * 8. Sắp xếp lịch sử đúng thứ tự thời gian và deduplicate (tránh hiển thị trùng lặp với tin realtime).
 * 9. Cung cấp dữ liệu chuẩn bị cho AN-08 (giao diện dấu tích).
 */
public class ChatService {
    private final SocketClient client;
    private final String currentUserName;

    // Quản lý các tin nhắn đang gửi chờ ACK: key = requestId
    private final ConcurrentMap<String, ChatMessage> pendingMessages = new ConcurrentHashMap<>();

    // Tập hợp ID tin nhắn Server đã nhận nhằm deduplicate giữa lịch sử và tin realtime
    private final Set<Integer> receivedServerMessageIds = Collections.synchronizedSet(new LinkedHashSet<>());

    // Bộ nhớ tạm thời khi đang nhận chuỗi frame HISTORY_ITEM
    private final List<ChatMessage> historyBuffer = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean isReceivingHistory = false;

    // Danh sách các thành phần UI/logic đăng ký nhận sự kiện
    private final List<ChatEventListener> listeners = new CopyOnWriteArrayList<>();

    public ChatService(SocketClient client) {
        this(client, null);
    }

    public ChatService(SocketClient client, String currentUserName) {
        this.client = Objects.requireNonNull(client, "SocketClient không được null");
        this.currentUserName = (currentUserName != null && !currentUserName.isBlank())
                ? currentUserName.trim()
                : (client.getConnectionManager() != null ? client.getConnectionManager().getAuthenticatedDisplayName() : "Me");
        registerPacketListeners();
    }

    private void registerPacketListeners() {
        client.addMessageListener(this::handleServerPacket);
        client.addConnectionListener(this::handleConnectionEvent);
    }

    public void addListener(ChatEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(ChatEventListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Làm mới trạng thái tin nhắn khi người dùng chuyển phòng chat khác.
     */
    public void clearRoomState() {
        receivedServerMessageIds.clear();
        synchronized (historyBuffer) {
            historyBuffer.clear();
            isReceivingHistory = false;
        }
    }

    public String getCurrentUserName() {
        return currentUserName;
    }

    public ChatMessage getPendingMessage(String requestId) {
        if (requestId == null) return null;
        return pendingMessages.get(requestId);
    }

    public String sendMessage(String content) {
        if (content == null || content.isBlank() || !client.isConnected()) {
            return null;
        }

        String requestId = java.util.UUID.randomUUID().toString();
        ChatMessage outgoing = ChatMessage.createOutgoing(requestId, currentUserName, content);
        pendingMessages.put(requestId, outgoing);

        notifyListeners(l -> l.onMessageStatusChanged(requestId, MessageStatus.PENDING, null));

        boolean sent = client.sendCommandWithExplicitId("SEND_MESSAGE", requestId, content);
        if (!sent) {
            pendingMessages.remove(requestId);
            outgoing.setStatus(MessageStatus.FAILED);
            outgoing.setErrorMessage("Không thể ghi dữ liệu tới kết nối mạng.");
            notifyListeners(l -> l.onMessageStatusChanged(requestId, MessageStatus.FAILED, outgoing.getErrorMessage()));
            return null;
        }
        return requestId;
    }

    /**
     * Gửi tin nhắn riêng 1-1 tới targetUserId.
     * ID người nhận được gửi dạng số nguyên trực tiếp (không Base64).
     */
    public String sendPrivateMessage(int targetUserId, String content) {
        if (content == null || content.isBlank() || !client.isConnected()) {
            return null;
        }
        String cmd = "SEND_PRIVATE_MESSAGE|" + targetUserId + "|" + ProtocolHandler.encode(content);
        return client.sendWithId(cmd);
    }

    /**
     * Gửi yêu cầu lấy lịch sử tin nhắn của phòng hiện tại.
     */
    public void getHistory() {
        if (client.isConnected()) {
            client.sendCommand("GET_HISTORY");
        }
    }

    private void handleServerPacket(String packet) {
        try {
            String[] parts = ProtocolHandler.split(packet);
            if (parts.length == 0) return;

            String type = parts[0];

            switch (type) {
                case "ACK" -> handleAck(parts);
                case "ERROR" -> handleError(parts);
                case "NEW_MESSAGE" -> handleNewMessage(parts);
                case "HISTORY_BEGIN" -> handleHistoryBegin(parts);
                case "HISTORY_ITEM" -> handleHistoryItem(parts);
                case "HISTORY_END" -> handleHistoryEnd();
                case "NEW_PRIVATE_MESSAGE" -> handleNewPrivateMessage(parts);
                case "PRIVATE_MESSAGE_SENT" -> handlePrivateMessageSent(parts);
            }
        } catch (Exception ex) {
            System.err.println("[ChatService] Lỗi xử lý gói tin: " + ex.getMessage());
        }
    }

    /**
     * Xử lý gói tin ACK|requestId|OK:
     * CHỈ đánh dấu SENT nếu requestId khớp với tin nhắn trong pendingMessages.
     * Tuyệt đối không biến ACK của LOGIN hay JOIN_ROOM thành xác nhận gửi tin!
     */
    private void handleAck(String[] parts) {
        if (parts.length < 2) return;
        String requestId = parts[1];

        ChatMessage pending = pendingMessages.remove(requestId);
        if (pending != null) {
            pending.setStatus(MessageStatus.SENT);
            notifyListeners(l -> l.onMessageStatusChanged(requestId, MessageStatus.SENT, null));
        }
    }

    /**
     * Xử lý gói tin ERROR|encodedMessage hoặc ERROR|encodedMessage|requestId.
     */
    private void handleError(String[] parts) {
        if (parts.length < 2) return;
        String decodedError = ProtocolHandler.decode(parts[1]);

        if (parts.length >= 3) {
            String requestId = parts[2];
            ChatMessage pending = pendingMessages.remove(requestId);
            if (pending != null) {
                pending.setStatus(MessageStatus.FAILED);
                pending.setErrorMessage(decodedError);
                notifyListeners(l -> l.onMessageStatusChanged(requestId, MessageStatus.FAILED, decodedError));
                return;
            }
        }

        // Lỗi chung của nghiệp vụ chat
        notifyListeners(l -> l.onChatError(decodedError));
    }

    /**
     * Xử lý tin nhắn realtime broadcast: NEW_MESSAGE|id|roomId|sender|content|timestamp.
     * Deduplicate nếu tin nhắn này đã tồn tại trong lịch sử.
     */
    private void handleNewMessage(String[] parts) {
        if (parts.length < 6) return;
        int messageId = Integer.parseInt(parts[1]);
        String sender = ProtocolHandler.decode(parts[3]);
        String content = ProtocolHandler.decode(parts[4]);
        String timestamp = ProtocolHandler.decode(parts[5]);

        // Kiểm tra deduplication: nếu ID đã có thì không phát lại
        if (!receivedServerMessageIds.add(messageId)) {
            return;
        }

        boolean isMine = (currentUserName != null && currentUserName.equalsIgnoreCase(sender));
        ChatMessage incoming = ChatMessage.createIncoming(messageId, sender, content, timestamp, isMine);

        notifyListeners(l -> l.onNewMessage(incoming));
    }

    private void handleHistoryBegin(String[] parts) {
        synchronized (historyBuffer) {
            isReceivingHistory = true;
            historyBuffer.clear();
        }
    }

    private void handleHistoryItem(String[] parts) {
        if (parts.length < 5) return;
        int messageId = Integer.parseInt(parts[1]);
        String sender = ProtocolHandler.decode(parts[2]);
        String content = ProtocolHandler.decode(parts[3]);
        String timestamp = ProtocolHandler.decode(parts[4]);

        boolean isMine = (currentUserName != null && currentUserName.equalsIgnoreCase(sender));
        ChatMessage item = ChatMessage.createIncoming(messageId, sender, content, timestamp, isMine);

        synchronized (historyBuffer) {
            if (isReceivingHistory) {
                historyBuffer.add(item);
            }
        }
    }

    /**
     * Kết thúc nhận lịch sử: Sắp xếp đúng thứ tự thời gian và loại bỏ tin trùng lặp.
     */
    private void handleHistoryEnd() {
        List<ChatMessage> result = new ArrayList<>();
        synchronized (historyBuffer) {
            isReceivingHistory = false;
            // Sắp xếp theo ID tin nhắn của Database (thứ tự tăng dần = cũ trước, mới sau)
            historyBuffer.sort(Comparator.comparingInt(ChatMessage::getServerMessageId));

            for (ChatMessage msg : historyBuffer) {
                if (msg.getServerMessageId() != null) {
                    receivedServerMessageIds.add(msg.getServerMessageId());
                }
                result.add(msg);
            }
            historyBuffer.clear();
        }

        notifyListeners(l -> l.onHistoryLoaded(result));
    }

    private void handleNewPrivateMessage(String[] parts) {
        if (parts.length < 5) return;
        int senderId = Integer.parseInt(parts[1]);
        String senderName = ProtocolHandler.decode(parts[2]);
        String content = ProtocolHandler.decode(parts[3]);
        String timestamp = ProtocolHandler.decode(parts[4]);

        notifyListeners(l -> l.onPrivateMessage(senderId, senderName, content, timestamp));
    }

    private void handlePrivateMessageSent(String[] parts) {
        if (parts.length < 2) return;
        int targetUserId = Integer.parseInt(parts[1]);
        notifyListeners(l -> l.onPrivateMessageSent(targetUserId));
    }

    private void handleConnectionEvent(String event) {
        if (event == null) return;

        if (event.startsWith("REQUEST_TIMEOUT|")) {
            String[] parts = event.split("\\|", -1);
            if (parts.length >= 2) {
                String reqId = parts[1];
                ChatMessage pending = pendingMessages.remove(reqId);
                if (pending != null) {
                    // Tiêu chí AN-05: Timeout không chứng minh Server chưa lưu -> UNKNOWN_TIMEOUT, không tự động gửi lại
                    pending.setStatus(MessageStatus.UNKNOWN_TIMEOUT);
                    pending.setErrorMessage("Hết thời gian chờ phản hồi từ Server.");
                    notifyListeners(l -> l.onMessageStatusChanged(reqId, MessageStatus.UNKNOWN_TIMEOUT, pending.getErrorMessage()));
                }
            }
        } else if ("CLIENT_DISCONNECTED".equals(event)) {
            // Mất kết nối mạng: tất cả các tin nhắn PENDING chuyển sang UNKNOWN_TIMEOUT
            for (Map.Entry<String, ChatMessage> entry : pendingMessages.entrySet()) {
                ChatMessage msg = entry.getValue();
                msg.setStatus(MessageStatus.UNKNOWN_TIMEOUT);
                msg.setErrorMessage("Mất kết nối mạng.");
                notifyListeners(l -> l.onMessageStatusChanged(msg.getRequestId(), MessageStatus.UNKNOWN_TIMEOUT, "Mất kết nối mạng."));
            }
            pendingMessages.clear();
        }
    }

    private void notifyListeners(java.util.function.Consumer<ChatEventListener> action) {
        for (ChatEventListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Throwable t) {
                System.err.println("[ChatService] Lỗi listener callback: " + t.getMessage());
            }
        }
    }
}
