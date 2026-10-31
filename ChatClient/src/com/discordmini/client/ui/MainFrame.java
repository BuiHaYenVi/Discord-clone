package com.discordmini.client.ui;

import com.discordmini.client.network.ConnectionState;
import com.discordmini.client.network.ReconnectManager;
import com.discordmini.client.network.SocketClient;
import com.discordmini.client.service.AuthService;
import com.discordmini.client.service.ChatService;
import com.discordmini.client.service.FriendService;
import com.discordmini.client.service.RoomService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;

/**
 * Màn hình chat chính (MainFrame) cho Discord Mini.
 *
 * Tiêu chí thiết kế:
 * 1. Bố cục 3 khu vực rõ ràng, co giãn theo chiều ngang/dọc (Responsive Layout):
 *    - Trái: Quản lý phòng chat (RoomPanel - danh sách phòng, làm mới, tạo phòng).
 *    - Giữa: Khu vực trò chuyện (ChatPanel - tiêu đề phòng, nút rời phòng, lịch sử tin, ô nhập, nút gửi).
 *    - Phải: Danh sách thành viên trong phòng (UserPanel - thành viên thực tế từ Server).
 *    - Đáy: Thanh trạng thái mạng (NetworkStatusPanel) tích hợp thông tin tài khoản và nút Đăng xuất.
 * 2. Tích hợp ReconnectManager trực tiếp vào vòng đời ứng dụng.
 * 3. Đăng xuất an toàn: Hủy reconnect, xóa phiên, giải phóng listener, trở về LoginFrame.
 * 4. Đóng cửa sổ giải phóng tài nguyên mạng sạch sẽ, không rò rỉ thread.
 * 5. Loại bỏ hoàn toàn các nút giả, icon giả không có chức năng.
 */
public class MainFrame extends JFrame {
    private final SocketClient client;
    private final String displayName;
    private final String username;
    private final String password;

    private final AuthService authService;
    private final RoomService roomService;
    private final ChatService chatService;
    private final FriendService friendService;
    private final ReconnectManager reconnectManager;

    // Các panel thành phần cốt lõi
    private RoomPanel roomPanel;
    private ChatPanel chatPanel;
    private UserPanel userPanel;
    private NetworkStatusPanel networkStatusPanel;

    private final JLabel lblCurrentUser = new JLabel();
    private final JButton btnLogout = new JButton("Đăng xuất");

    public MainFrame(SocketClient client, String displayName) {
        this(client, displayName, null, null);
    }

    public MainFrame(SocketClient client, String displayName, String username, String password) {
        this.client = Objects.requireNonNull(client, "SocketClient không được null");
        this.displayName = (displayName != null && !displayName.isBlank()) ? displayName.trim() : "Người dùng";
        this.username = (username != null) ? username.trim() : "";
        this.password = password;

        // 1. Khởi tạo các dịch vụ nghiệp vụ cốt lõi
        this.authService = new AuthService(client, client.getConnectionManager());
        this.chatService = new ChatService(client, this.displayName);
        this.roomService = new RoomService(client, chatService);
        this.friendService = new FriendService(client);

        // 2. Khởi tạo ReconnectManager với thông tin đăng nhập tạm trong RAM
        this.reconnectManager = new ReconnectManager(client, authService, roomService);
        if (!this.username.isBlank() && this.password != null) {
            String host = (client.getHost() != null) ? client.getHost() : "127.0.0.1";
            int port = (client.getPort() > 0) ? client.getPort() : 5000;
            this.reconnectManager.setCredentials(host, port, this.username, this.password);
        }

        // 3. Cấu hình JFrame
        setTitle("Discord Mini — " + this.displayName);
        setSize(1040, 700);
        setMinimumSize(new Dimension(860, 560));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        buildUi();
        registerLifecycleListeners();

        // 4. Nạp danh sách phòng ban đầu
        if (client.isConnected()) {
            roomService.listRooms();
        }
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(new Color(245, 245, 245));

        // 1. Khởi tạo các Panel thành phần
        roomPanel = new RoomPanel(roomService);
        chatPanel = new ChatPanel(client, chatService, roomService, displayName);
        userPanel = new UserPanel(client, chatService, roomService);
        networkStatusPanel = new NetworkStatusPanel(client);
        networkStatusPanel.setReconnectManager(reconnectManager);

        // 2. Bố cục 3 khu vực bằng JSplitPane co giãn linh hoạt
        // Tách Center và Right
        JSplitPane centerRightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chatPanel, userPanel);
        centerRightSplit.setResizeWeight(0.82);
        centerRightSplit.setDividerSize(4);
        centerRightSplit.setBorder(null);

        // Tách Left và (Center + Right)
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, roomPanel, centerRightSplit);
        mainSplit.setResizeWeight(0.20);
        mainSplit.setDividerSize(4);
        mainSplit.setBorder(null);

        root.add(mainSplit, BorderLayout.CENTER);

        // 3. Thanh trạng thái đáy (South bar) tích hợp NetworkStatusPanel + User info + Logout button
        JPanel southBar = new JPanel(new BorderLayout(10, 0));
        southBar.setBackground(new Color(248, 249, 250));
        southBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(220, 224, 230)),
                new EmptyBorder(3, 8, 3, 8)
        ));

        // Vùng bên trái thanh đáy: NetworkStatusPanel
        southBar.add(networkStatusPanel, BorderLayout.CENTER);

        // Vùng bên phải thanh đáy: Thông tin người dùng + Nút đăng xuất
        JPanel userActionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 2));
        userActionPanel.setOpaque(false);

        String userDisplayStr = "👤 " + displayName + (!username.isBlank() ? " (@" + username + ")" : "");
        lblCurrentUser.setText(userDisplayStr);
        lblCurrentUser.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblCurrentUser.setForeground(new Color(33, 33, 33));

        btnLogout.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btnLogout.setForeground(new Color(198, 40, 40));
        btnLogout.setFocusPainted(false);
        btnLogout.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnLogout.addActionListener(e -> doLogout());

        userActionPanel.add(lblCurrentUser);
        userActionPanel.add(new JSeparator(SwingConstants.VERTICAL));
        userActionPanel.add(btnLogout);

        southBar.add(userActionPanel, BorderLayout.EAST);
        root.add(southBar, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private void registerLifecycleListeners() {
        // Đóng cửa sổ qua nút X
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeAndExit();
            }
        });

        // Theo dõi trạng thái kết nối để cập nhật UI
        if (client.getConnectionManager() != null) {
            client.getConnectionManager().addStateChangeListener((oldState, newState, reason) -> {
                SwingUtilities.invokeLater(() -> {
                    if (newState == ConnectionState.DISCONNECTED) {
                        setTitle("Discord Mini — " + displayName + " [MẤT KẾT NỐI]");
                    } else if (newState == ConnectionState.RECONNECTING) {
                        setTitle("Discord Mini — " + displayName + " [ĐANG KẾT NỐI LẠI...]");
                    } else if (newState == ConnectionState.AUTHENTICATED || newState == ConnectionState.CONNECTED) {
                        setTitle("Discord Mini — " + displayName);
                    }
                });
            });
        }
    }

    /**
     * Đăng xuất khỏi hệ thống: Hủy reconnect, xóa phiên, giải phóng tài nguyên và quay về LoginFrame.
     */
    public void doLogout() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc chắn muốn đăng xuất khỏi Discord Mini?",
                "Xác nhận đăng xuất", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            performLogout();
        }
    }

    /**
     * Thực hiện toàn bộ quy trình đăng xuất: Hủy reconnect, xóa phiên, giải phóng listener và mở LoginFrame.
     */
    public void performLogout() {
        // 1. Hủy chuỗi reconnect tự động và xóa thông tin phiên trong RAM
        if (reconnectManager != null) {
            reconnectManager.cancel();
            reconnectManager.clearCredentials();
        }

        // 2. Rời phòng hiện tại nếu có
        if (roomService != null && roomService.isInRoom()) {
            try {
                roomService.leaveRoom();
            } catch (Exception ignored) {}
        }

        // 3. Gửi lệnh LOGOUT tới Server
        try {
            authService.logout();
        } catch (Exception ignored) {}

        // 4. Giải phóng networkStatusPanel
        if (networkStatusPanel != null) {
            networkStatusPanel.cleanup();
        }

        // 5. Đóng MainFrame và mở lại LoginFrame
        dispose();

        SwingUtilities.invokeLater(() -> {
            LoginFrame loginFrame = new LoginFrame(client);
            if (!username.isBlank()) {
                loginFrame.prefillUsername(username);
            }
            loginFrame.setVisible(true);
        });
    }

    /**
     * Đóng ứng dụng an toàn và giải phóng tài nguyên mạng.
     */
    private void closeAndExit() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Bạn có muốn thoát khỏi Discord Mini không?",
                "Thoát ứng dụng", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            if (reconnectManager != null) {
                reconnectManager.cancel();
                reconnectManager.clearCredentials();
                reconnectManager.shutdown();
            }
            if (networkStatusPanel != null) {
                networkStatusPanel.cleanup();
            }
            if (authService != null) {
                authService.logout();
            }
            client.close();
        } catch (Exception ignored) {
        } finally {
            dispose();
            System.exit(0);
        }
    }

    // Getters phục vụ kiểm thử và truy xuất thành phần
    public RoomPanel getRoomPanel() { return roomPanel; }
    public ChatPanel getChatPanel() { return chatPanel; }
    public UserPanel getUserPanel() { return userPanel; }
    public NetworkStatusPanel getNetworkStatusPanel() { return networkStatusPanel; }
    public ReconnectManager getReconnectManager() { return reconnectManager; }
    public RoomService getRoomService() { return roomService; }
    public ChatService getChatService() { return chatService; }
    public AuthService getAuthService() { return authService; }
    public FriendService getFriendService() { return friendService; }
}
