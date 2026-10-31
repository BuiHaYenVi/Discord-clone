package com.discordmini.client.ui;

import com.discordmini.client.model.ChatMessage;
import com.discordmini.client.model.MessageStatus;
import com.discordmini.client.model.Room;
import com.discordmini.client.network.SocketClient;
import com.discordmini.client.service.ChatEventListener;
import com.discordmini.client.service.ChatService;
import com.discordmini.client.service.RoomEventListener;
import com.discordmini.client.service.RoomService;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Objects;

/**
 * Panel Chat trung tâm (cột ở giữa).
 *
 * Tiêu chí AN-08:
 * 1. Hiển thị tiêu đề phòng, danh sách tin nhắn và lịch sử phòng chat.
 * 2. Hiển thị trạng thái từng tin: Đang gửi (⏳), Đã gửi (✓ Sent), Lỗi gửi (✕), Chưa xác nhận (?).
 * 3. Không coi "Sent" là người nhận đã đọc.
 * 4. Ngăn chặn trùng lặp tin nhắn giữa tin tạm vừa gửi và broadcast NEW_MESSAGE từ Server.
 * 5. Báo rõ giới hạn protocol: Phía Server không echo @RID trong NEW_MESSAGE broadcast,
 *    do đó Client đối chiếu an toàn bằng requestId khi nhận ACK và đối chiếu tin tạm để không nhân đôi dòng chat.
 * 6. Khóa ô nhập và nút gửi khi: Chưa đăng nhập, chưa vào phòng hoặc mất kết nối mạng.
 * 7. Dọn sạch tin nhắn phòng cũ đúng thời điểm khi chuyển hoặc rời phòng.
 * 8. Mọi cập nhật giao diện Swing chạy an toàn trên EDT.
 */
public class ChatPanel extends JPanel implements ChatEventListener, RoomEventListener {
    private final SocketClient client;
    private final ChatService chatService;
    private final RoomService roomService;
    private final String currentDisplayName;

    private final JLabel roomTitleLabel = new JLabel("Chưa chọn phòng", SwingConstants.LEFT);
    private final JButton btnLeaveRoom = new JButton("🚪 Rời phòng");
    private final DefaultListModel<ChatMessage> messageListModel = new DefaultListModel<>();
    private final JList<ChatMessage> messageList = new JList<>(messageListModel);
    private final JTextField messageInput = new JTextField();
    private final JButton btnSend = new JButton("Gửi");
    private final JScrollPane scrollPane;

    public ChatPanel(SocketClient client, ChatService chatService, RoomService roomService, String currentDisplayName) {
        this.client = Objects.requireNonNull(client, "SocketClient không được null");
        this.chatService = chatService;
        this.roomService = roomService;
        this.currentDisplayName = currentDisplayName != null ? currentDisplayName : "Bạn";

        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        scrollPane = new JScrollPane(messageList);
        buildUi();
        registerServices();
        updateSendState();
    }

    private void buildUi() {
        // 1. Tiêu đề phòng
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(245, 245, 245));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        roomTitleLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        roomTitleLabel.setForeground(new Color(33, 33, 33));
        headerPanel.add(roomTitleLabel, BorderLayout.WEST);

        btnLeaveRoom.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btnLeaveRoom.setFocusPainted(false);
        btnLeaveRoom.setVisible(false);
        btnLeaveRoom.addActionListener(e -> {
            if (roomService != null) roomService.leaveRoom();
        });
        headerPanel.add(btnLeaveRoom, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        // 2. Danh sách tin nhắn với custom renderer MessagePanel
        messageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        messageList.setCellRenderer(new MessageListCellRenderer());
        messageList.setBackground(new Color(250, 250, 250));
        messageList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = messageList.locationToIndex(e.getPoint());
                if (index >= 0 && index < messageListModel.size()) {
                    ChatMessage msg = messageListModel.get(index);
                    if (msg.isMine() && (msg.getStatus() == MessageStatus.FAILED || msg.getStatus() == MessageStatus.UNKNOWN_TIMEOUT)) {
                        int choice = JOptionPane.showConfirmDialog(
                                ChatPanel.this,
                                "Tin nhắn chưa được gửi thành công:\n\"" + msg.getContent() + "\"\n\nBạn có muốn thử gửi lại (Retry) không?",
                                "Gửi lại tin nhắn",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE
                        );
                        if (choice == JOptionPane.YES_OPTION) {
                            retrySendMessage(msg, index);
                        }
                    }
                }
            }
        });
        add(scrollPane, BorderLayout.CENTER);

        // 3. Khung nhập tin nhắn
        JPanel inputPanel = new JPanel(new BorderLayout(6, 0));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        messageInput.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        btnSend.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnSend.setPreferredSize(new Dimension(80, 32));

        inputPanel.add(messageInput, BorderLayout.CENTER);
        inputPanel.add(btnSend, BorderLayout.EAST);
        add(inputPanel, BorderLayout.SOUTH);

        // Sự kiện gửi tin nhắn
        btnSend.addActionListener(e -> doSendMessage());
        messageInput.addActionListener(e -> doSendMessage());
    }

    private void registerServices() {
        if (chatService != null) {
            chatService.addListener(this);
        }
        if (roomService != null) {
            roomService.addListener(this);
        }
        if (client != null && client.getConnectionManager() != null) {
            client.getConnectionManager().addStateChangeListener((oldState, newState, reason) -> {
                SwingUtilities.invokeLater(this::updateSendState);
            });
        }
    }

    /**
     * Khóa/Mở ô nhập và nút gửi dựa trên trạng thái kết nối và trạng thái phòng.
     * Tiêu chí AN-08: Khóa gửi khi chưa đăng nhập, chưa vào phòng hoặc mất kết nối.
     */
    public void updateSendState() {
        boolean isConnected = client != null && client.isConnected();
        boolean inRoom = roomService != null && roomService.isInRoom();
        boolean canSend = isConnected && inRoom;

        messageInput.setEnabled(canSend);
        btnSend.setEnabled(canSend);

        if (!isConnected) {
            messageInput.setToolTipText("Mất kết nối tới Server. Không thể gửi tin.");
        } else if (!inRoom) {
            messageInput.setToolTipText("Vui lòng chọn hoặc tạo phòng bên trái để bắt đầu chat.");
        } else {
            messageInput.setToolTipText("Nhập tin nhắn và nhấn Enter hoặc bấm Gửi");
        }
    }

    private void doSendMessage() {
        String content = messageInput.getText().trim();
        if (content.isBlank() || chatService == null) {
            return;
        }

        if (roomService == null || !roomService.isInRoom()) {
            JOptionPane.showMessageDialog(this, "Bạn phải chọn một phòng chat trước khi gửi tin nhắn.",
                    "Chưa chọn phòng", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Gửi tin nhắn qua ChatService (trả về requestId)
        String requestId = chatService.sendMessage(content);
        if (requestId != null) {
            // Thêm tin nhắn tạm vào danh sách với trạng thái PENDING
            ChatMessage outgoing = ChatMessage.createOutgoing(requestId, currentDisplayName, content);
            addOrUpdateMessage(outgoing);
            messageInput.setText("");
            scrollToBottom();
        }
    }

    private void retrySendMessage(ChatMessage oldMsg, int index) {
        if (chatService == null || roomService == null || !roomService.isInRoom()) {
            JOptionPane.showMessageDialog(this, "Bạn phải đang trong phòng chat để gửi lại tin nhắn.",
                    "Chưa vào phòng", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String newRequestId = chatService.sendMessage(oldMsg.getContent());
        if (newRequestId != null) {
            ChatMessage retryMsg = ChatMessage.createOutgoing(newRequestId, currentDisplayName, oldMsg.getContent());
            messageListModel.set(index, retryMsg);
            smartScrollToBottom(true);
        }
    }

    /**
     * Dọn sạch toàn bộ tin nhắn phòng cũ khi chuyển hoặc rời phòng.
     * Tiêu chí AN-08: Dọn dữ liệu phòng cũ đúng thời điểm.
     */
    public void clearMessages() {
        Runnable r = () -> {
            messageListModel.clear();
            roomTitleLabel.setText("Chưa chọn phòng");
            btnLeaveRoom.setVisible(false);
            updateSendState();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    public void addOrUpdateMessage(ChatMessage msg) {
        if (msg == null) return;
        Runnable task = () -> {
            boolean found = false;

            // 1. Khớp theo requestId (chuẩn nhất cho tin do client gửi đi hoặc ACK cập nhật)
            if (msg.getRequestId() != null) {
                for (int i = messageListModel.size() - 1; i >= 0; i--) {
                    ChatMessage existing = messageListModel.get(i);
                    if (msg.getRequestId().equals(existing.getRequestId())) {
                        messageListModel.set(i, msg);
                        found = true;
                        break;
                    }
                }
            }

            // 2. Khớp theo serverMessageId (cho tin nhắn từ Server/lịch sử/broadcast)
            if (!found && msg.getServerMessageId() != null) {
                // a) Kiểm tra xem serverMessageId này đã hiển thị chưa (tránh trùng do ACK lặp hoặc load history)
                for (int i = messageListModel.size() - 1; i >= 0; i--) {
                    ChatMessage existing = messageListModel.get(i);
                    if (msg.getServerMessageId().equals(existing.getServerMessageId())) {
                        messageListModel.set(i, msg);
                        found = true;
                        break;
                    }
                }

                // b) Hòa giải tin broadcast của chính mình với tin tạm PENDING/SENT chưa có serverMessageId
                // Chỉ duyệt FIFO (từ cũ đến mới) để khớp đúng thứ tự tin gửi
                if (!found && msg.isMine()) {
                    for (int i = 0; i < messageListModel.size(); i++) {
                        ChatMessage existing = messageListModel.get(i);
                        if (existing.isMine() && existing.getServerMessageId() == null
                                && Objects.equals(msg.getContent(), existing.getContent())) {
                            existing.setServerMessageId(msg.getServerMessageId());
                            existing.setStatus(MessageStatus.SENT);
                            messageListModel.set(i, existing);
                            found = true;
                            break;
                        }
                    }
                }
            }

            if (!found) {
                messageListModel.addElement(msg);
            }
            if (msg.isMine()) {
                scrollToBottom();
            } else {
                smartScrollToBottom(false);
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    private void smartScrollToBottom(boolean force) {
        SwingUtilities.invokeLater(() -> {
            int size = messageListModel.getSize();
            if (size == 0) return;
            if (force) {
                messageList.ensureIndexIsVisible(size - 1);
                return;
            }
            JScrollBar vbar = scrollPane.getVerticalScrollBar();
            if (vbar != null) {
                int extent = vbar.getModel().getExtent();
                int value = vbar.getValue();
                int max = vbar.getMaximum();
                if (value + extent >= max - 60) {
                    messageList.ensureIndexIsVisible(size - 1);
                }
            } else {
                messageList.ensureIndexIsVisible(size - 1);
            }
        });
    }

    private void scrollToBottom() {
        smartScrollToBottom(true);
    }

    // ================= ChatEventListener Callbacks =================

    @Override
    public void onNewMessage(ChatMessage message) {
        addOrUpdateMessage(message);
    }

    @Override
    public void onMessageStatusChanged(String requestId, MessageStatus status, String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            for (int i = messageListModel.size() - 1; i >= 0; i--) {
                ChatMessage msg = messageListModel.get(i);
                if (requestId != null && requestId.equals(msg.getRequestId())) {
                    msg.setStatus(status);
                    if (errorMessage != null) {
                        msg.setErrorMessage(errorMessage);
                    }
                    messageListModel.set(i, msg);
                    break;
                }
            }
            messageList.repaint();
        });
    }

    @Override
    public void onHistoryLoaded(List<ChatMessage> history) {
        SwingUtilities.invokeLater(() -> {
            messageListModel.clear();
            if (history != null) {
                for (ChatMessage m : history) {
                    messageListModel.addElement(m);
                }
            }
            scrollToBottom();
        });
    }

    @Override
    public void onPrivateMessage(int senderId, String senderName, String content, String timestamp) {
        SwingUtilities.invokeLater(() -> {
            ChatMessage pm = new ChatMessage(null, null, "🔒 [RIÊNG TỪ " + senderName + "]",
                    content, timestamp, null, false);
            messageListModel.addElement(pm);
            scrollToBottom();
        });
    }

    @Override
    public void onChatError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            if (isShowing()) {
                JOptionPane.showMessageDialog(this, errorMessage, "Lỗi chat", JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    // ================= RoomEventListener Callbacks =================

    @Override
    public void onRoomJoined(Room room) {
        SwingUtilities.invokeLater(() -> {
            roomTitleLabel.setText("# " + room.name());
            btnLeaveRoom.setVisible(true);
            messageListModel.clear(); // Xóa tin nhắn phòng cũ trước khi tải lịch sử phòng mới
            updateSendState();
            messageInput.requestFocusInWindow();
        });
    }

    @Override
    public void onRoomLeft() {
        clearMessages();
    }

    @Override
    public void onRoomListUpdated(List<Room> rooms) {
        // Cập nhật trạng thái nếu phòng hiện tại bị xóa
        SwingUtilities.invokeLater(this::updateSendState);
    }

    @Override
    public void onRoomCreated(Room room) {
        // Handled by RoomPanel
    }

    @Override
    public void onRoomError(String errorMessage) {
        // Handled by RoomPanel
    }

    // ================= Custom ListCellRenderer =================

    private static class MessageListCellRenderer implements ListCellRenderer<ChatMessage> {
        private final MessagePanel renderer = new MessagePanel();

        @Override
        public Component getListCellRendererComponent(JList<? extends ChatMessage> list,
                                                      ChatMessage value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            renderer.bind(value);
            return renderer;
        }
    }

    // Getters phục vụ kiểm thử và truy xuất
    public JLabel getRoomTitleLabel() { return roomTitleLabel; }
    public DefaultListModel<ChatMessage> getMessageListModel() { return messageListModel; }
    public int getMessageCount() { return messageListModel.getSize(); }
    public JTextField getMessageInput() { return messageInput; }
    public JButton getBtnSend() { return btnSend; }
}
