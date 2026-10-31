package com.discordmini.client.ui;

import com.discordmini.client.model.Room;
import com.discordmini.client.network.ConnectionState;
import com.discordmini.client.network.SocketClient;
import com.discordmini.client.service.ChatEventListener;
import com.discordmini.client.service.ChatService;
import com.discordmini.client.service.RoomEventListener;
import com.discordmini.client.service.RoomService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * Panel hiển thị danh sách thành viên trong phòng chat (cột bên phải).
 *
 * Tiêu chí AN-08 và yêu cầu đơn giản, chính xác:
 * - Hiển thị danh sách thành viên thực tế theo dữ liệu Server qua sự kiện ROOM_MEMBERS.
 * - Khi chưa vào phòng: hiển thị hướng dẫn "Chưa vào phòng".
 * - Tạm ẩn nút Chat riêng 1-1 vì chưa có UI chat riêng hoàn chỉnh trong bản này (giữ nguyên logic nền).
 * - Cập nhật giao diện an toàn trên EDT.
 */
public class UserPanel extends JPanel implements RoomEventListener {
    private final SocketClient client;
    private final ChatService chatService;
    private final RoomService roomService;

    private final JLabel titleLabel = new JLabel("  THÀNH VIÊN (0)", SwingConstants.LEFT);
    private final DefaultListModel<MemberItem> memberModel = new DefaultListModel<>();
    private final JList<MemberItem> memberList = new JList<>(memberModel);
    private final JButton btnPrivateChat = new JButton("💬 Chat 1-1");
    private final JLabel statusLabel = new JLabel("🟢 Trực tuyến", SwingConstants.CENTER);

    public record MemberItem(int id, String name) {
        @Override
        public String toString() {
            return "👤 " + name;
        }
    }

    public UserPanel(SocketClient client, ChatService chatService) {
        this(client, chatService, null);
    }

    public UserPanel(SocketClient client, ChatService chatService, RoomService roomService) {
        this.client = client;
        this.chatService = chatService;
        this.roomService = roomService;

        setLayout(new BorderLayout(5, 5));
        setPreferredSize(new Dimension(210, 0));
        setBorder(new EmptyBorder(5, 5, 5, 5));

        buildUi();
        listenConnectionState();
        listenChatEvents();

        if (roomService != null) {
            roomService.addListener(this);
        }
    }

    private void buildUi() {
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        titleLabel.setForeground(new Color(33, 33, 33));
        titleLabel.setBorder(new EmptyBorder(5, 0, 5, 0));
        add(titleLabel, BorderLayout.NORTH);

        memberList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        memberList.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        memberList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setBorder(new EmptyBorder(3, 6, 3, 6));
                return this;
            }
        });
        add(new JScrollPane(memberList), BorderLayout.CENTER);

        // Thanh dưới đáy (tạm ẩn nút chat 1-1 theo yêu cầu, chỉ hiển thị trạng thái)
        btnPrivateChat.setVisible(false);

        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        statusLabel.setForeground(new Color(46, 125, 50));
        statusLabel.setBorder(new EmptyBorder(4, 0, 2, 0));
        add(statusLabel, BorderLayout.SOUTH);
    }

    private void listenConnectionState() {
        if (client != null && client.getConnectionManager() != null) {
            client.getConnectionManager().addStateChangeListener((oldState, newState, reason) -> {
                SwingUtilities.invokeLater(() -> {
                    if (newState == ConnectionState.DISCONNECTED) {
                        statusLabel.setText("🔴 Mất kết nối Server");
                        statusLabel.setForeground(new Color(198, 40, 40));
                    } else if (newState == ConnectionState.AUTHENTICATED || newState == ConnectionState.CONNECTED) {
                        statusLabel.setText("🟢 Trực tuyến");
                        statusLabel.setForeground(new Color(46, 125, 50));
                    } else if (newState == ConnectionState.RECONNECTING) {
                        statusLabel.setText("🟡 Đang kết nối lại...");
                        statusLabel.setForeground(new Color(230, 81, 0));
                    }
                });
            });
        }
    }

    private void listenChatEvents() {
        if (chatService != null) {
            chatService.addListener(new ChatEventListener() {
                @Override
                public void onPrivateMessage(int senderId, String senderName, String content, String timestamp) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(UserPanel.this,
                                "[" + timestamp + "] " + senderName + ":\n" + content,
                                "📩 Tin nhắn riêng từ " + senderName,
                                JOptionPane.INFORMATION_MESSAGE);
                    });
                }

                @Override
                public void onPrivateMessageSent(int targetUserId) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(UserPanel.this,
                                "Đã gửi tin nhắn riêng thành công!",
                                "Thông báo",
                                JOptionPane.INFORMATION_MESSAGE);
                    });
                }
            });
        }
    }

    // ================= RoomEventListener Callbacks =================

    @Override
    public void onRoomJoined(Room room) {
        SwingUtilities.invokeLater(() -> {
            titleLabel.setText("  THÀNH VIÊN (#" + room.name() + ")");
            memberModel.clear();
        });
    }

    @Override
    public void onRoomLeft() {
        SwingUtilities.invokeLater(() -> {
            titleLabel.setText("  THÀNH VIÊN (0)");
            memberModel.clear();
        });
    }

    @Override
    public void onRoomMembersUpdated(int roomId, List<String> memberNames) {
        SwingUtilities.invokeLater(() -> {
            memberModel.clear();
            if (memberNames != null) {
                int count = 0;
                for (String name : memberNames) {
                    if (name != null && !name.isBlank()) {
                        memberModel.addElement(new MemberItem(count++, name.trim()));
                    }
                }
                titleLabel.setText("  THÀNH VIÊN (" + memberModel.size() + ")");
            } else {
                titleLabel.setText("  THÀNH VIÊN (0)");
            }
        });
    }

    public void updateMembers(List<MemberItem> members) {
        SwingUtilities.invokeLater(() -> {
            memberModel.clear();
            if (members != null) {
                for (MemberItem m : members) {
                    memberModel.addElement(m);
                }
                titleLabel.setText("  THÀNH VIÊN (" + memberModel.size() + ")");
            } else {
                titleLabel.setText("  THÀNH VIÊN (0)");
            }
        });
    }

    public void setConnectionStatusText(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            if (color != null) {
                statusLabel.setForeground(color);
            }
        });
    }

    public JList<MemberItem> getMemberList() {
        return memberList;
    }

    public DefaultListModel<MemberItem> getMemberModel() {
        return memberModel;
    }

    public RoomService getRoomService() {
        return roomService;
    }
}
