package com.discordmini.client.ui;

import com.discordmini.client.network.ConnectionManager;
import com.discordmini.client.network.ConnectionState;
import com.discordmini.client.network.SocketClient;
import com.discordmini.client.service.AuthCallback;
import com.discordmini.client.service.AuthResult;
import com.discordmini.client.service.AuthService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Giao diện Đăng nhập cho Discord Mini.
 *
 * Tiêu chí AN-07 và yêu cầu giao diện đơn giản, rõ ràng, hoạt động thật:
 * 1. Kiểm tra dữ liệu bắt buộc (username, password), dùng JPasswordField có nút/checkbox hiện/ẩn.
 * 2. Cụm cấu hình máy chủ/cổng (Host/Port) gọn gàng, không hard-code.
 * 3. Hiển thị trạng thái đang gửi yêu cầu và thông báo tiếng Việt rõ ràng, dễ thấy.
 * 4. Khóa thao tác gửi trùng (inFlight), mở lại khi thành công, lỗi, timeout hoặc mất mạng.
 * 5. Không thực hiện tác vụ mạng hay blocking trên EDT.
 * 6. Chỉ mở MainFrame sau phản hồi đăng nhập thành công.
 * 7. Hỗ trợ Enter để đăng nhập, Tab chuyển ô hợp lý.
 * 8. Hỗ trợ điền sẵn tên đăng nhập khi vừa đăng ký thành công.
 */
public class LoginFrame extends JFrame {
    private final SocketClient client;
    private final ConnectionManager connectionManager;
    private final AuthService authService;

    private final JTextField usernameField = new JTextField(18);
    private final JPasswordField passwordField = new JPasswordField(18);
    private final JCheckBox showPasswordCheckbox = new JCheckBox("Hiện mật khẩu");
    private final JLabel statusLabel = new JLabel("Sẵn sàng đăng nhập", SwingConstants.CENTER);
    private final JButton loginBtn = new JButton("Đăng nhập");
    private final JButton registerBtn = new JButton("Chưa có tài khoản? Đăng ký ngay");

    // Khu vực cấu hình kết nối
    private final JTextField hostField = new JTextField(12);
    private final JTextField portField = new JTextField(5);
    private final JButton btnReconnect = new JButton("Kết nối lại");

    private volatile boolean inFlight = false;
    private volatile boolean disposed = false;
    private RegisterFrame activeRegisterFrame = null;

    public LoginFrame(SocketClient client) {
        this.client = client;
        this.connectionManager = (client != null) ? client.getConnectionManager() : null;
        this.authService = new AuthService(client, connectionManager);

        setTitle("Discord Mini - Đăng nhập");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(460, 420);
        setMinimumSize(new Dimension(420, 380));
        setLocationRelativeTo(null);
        setResizable(false);

        buildUi();
        listenConnectionState();
    }

    public AuthService getAuthService() {
        return authService;
    }

    public boolean isInFlight() {
        return inFlight;
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(15, 20, 15, 20));
        root.setBackground(new Color(248, 249, 250));

        // 1. Header
        JPanel headerPanel = new JPanel(new GridLayout(2, 1, 0, 4));
        headerPanel.setOpaque(false);

        JLabel title = new JLabel("DISCORD MINI", SwingConstants.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        title.setForeground(new Color(33, 150, 243));

        JLabel subtitle = new JLabel("Ứng dụng chat nhóm Java TCP Socket", SwingConstants.CENTER);
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitle.setForeground(new Color(110, 110, 110));

        headerPanel.add(title);
        headerPanel.add(subtitle);
        root.add(headerPanel, BorderLayout.NORTH);

        // 2. Form đăng nhập ở giữa
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false);
        centerPanel.setBorder(new EmptyBorder(10, 10, 5, 10));

        // Tên đăng nhập
        JPanel userPanel = new JPanel(new BorderLayout(5, 4));
        userPanel.setOpaque(false);
        JLabel userLabel = new JLabel("Tên đăng nhập:");
        userLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        usernameField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        userPanel.add(userLabel, BorderLayout.NORTH);
        userPanel.add(usernameField, BorderLayout.CENTER);
        centerPanel.add(userPanel);
        centerPanel.add(Box.createVerticalStrut(10));

        // Mật khẩu
        JPanel passPanel = new JPanel(new BorderLayout(5, 4));
        passPanel.setOpaque(false);
        JLabel passLabel = new JLabel("Mật khẩu:");
        passLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        passwordField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        passPanel.add(passLabel, BorderLayout.NORTH);
        passPanel.add(passwordField, BorderLayout.CENTER);

        // Checkbox hiện mật khẩu
        showPasswordCheckbox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        showPasswordCheckbox.setOpaque(false);
        showPasswordCheckbox.setFocusPainted(false);
        showPasswordCheckbox.addActionListener(e -> {
            if (showPasswordCheckbox.isSelected()) {
                passwordField.setEchoChar((char) 0);
            } else {
                passwordField.setEchoChar('•');
            }
        });
        passPanel.add(showPasswordCheckbox, BorderLayout.SOUTH);

        centerPanel.add(passPanel);
        centerPanel.add(Box.createVerticalStrut(12));

        // Nút Đăng nhập
        loginBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        loginBtn.setBackground(new Color(33, 150, 243));
        loginBtn.setForeground(Color.WHITE);
        loginBtn.setFocusPainted(false);
        loginBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        loginBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        loginBtn.setPreferredSize(new Dimension(200, 38));
        loginBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        centerPanel.add(loginBtn);
        centerPanel.add(Box.createVerticalStrut(8));

        // Nút Đăng ký (dạng text button liên kết)
        registerBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        registerBtn.setForeground(new Color(25, 118, 210));
        registerBtn.setContentAreaFilled(false);
        registerBtn.setBorderPainted(false);
        registerBtn.setFocusPainted(false);
        registerBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        registerBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerPanel.add(registerBtn);

        root.add(centerPanel, BorderLayout.CENTER);

        // 3. Footer: Thông báo trạng thái + Cụm thông tin kết nối
        JPanel footerPanel = new JPanel(new BorderLayout(0, 6));
        footerPanel.setOpaque(false);

        // Vùng thông báo lỗi / trạng thái
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        statusLabel.setForeground(new Color(75, 75, 75));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(220, 220, 220)),
                new EmptyBorder(6, 4, 4, 4)
        ));
        footerPanel.add(statusLabel, BorderLayout.NORTH);

        // Cụm cấu hình kết nối gọn gàng
        JPanel connPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        connPanel.setOpaque(false);

        String currentHost = (client != null && client.getHost() != null) ? client.getHost() : "127.0.0.1";
        int currentPort = (client != null && client.getPort() > 0) ? client.getPort() : 5000;
        hostField.setText(currentHost);
        portField.setText(String.valueOf(currentPort));

        JLabel lblHost = new JLabel("Máy chủ:");
        lblHost.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        JLabel lblPort = new JLabel("Cổng:");
        lblPort.setFont(new Font("Segoe UI", Font.PLAIN, 11));

        hostField.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        portField.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btnReconnect.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btnReconnect.setMargin(new Insets(1, 6, 1, 6));

        connPanel.add(lblHost);
        connPanel.add(hostField);
        connPanel.add(lblPort);
        connPanel.add(portField);
        connPanel.add(btnReconnect);

        btnReconnect.addActionListener(e -> reconnectToServer());

        footerPanel.add(connPanel, BorderLayout.SOUTH);
        root.add(footerPanel, BorderLayout.SOUTH);

        setContentPane(root);

        // Sự kiện
        loginBtn.addActionListener(e -> doLogin());
        registerBtn.addActionListener(e -> openRegisterWindow());
        usernameField.addActionListener(e -> doLogin());
        passwordField.addActionListener(e -> doLogin());
    }

    private void reconnectToServer() {
        if (inFlight) return;
        String h = hostField.getText().trim();
        String pStr = portField.getText().trim();
        if (h.isBlank()) {
            statusLabel.setForeground(new Color(211, 47, 47));
            statusLabel.setText("Vui lòng nhập địa chỉ máy chủ.");
            return;
        }
        int p;
        try {
            p = Integer.parseInt(pStr);
            if (p <= 0 || p > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            statusLabel.setForeground(new Color(211, 47, 47));
            statusLabel.setText("Cổng TCP không hợp lệ (1-65535).");
            return;
        }

        setFormEnabled(false);
        statusLabel.setForeground(new Color(25, 118, 210));
        statusLabel.setText("Đang kết nối tới " + h + ":" + p + "...");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (client != null) {
                    client.close();
                    client.connect(h, p);
                }
                return null;
            }

            @Override
            protected void done() {
                setFormEnabled(true);
                try {
                    get();
                    statusLabel.setForeground(new Color(46, 125, 50));
                    statusLabel.setText("Đã kết nối máy chủ " + h + ":" + p);
                } catch (Exception ex) {
                    statusLabel.setForeground(new Color(211, 47, 47));
                    statusLabel.setText("Không thể kết nối tới " + h + ":" + p);
                }
            }
        }.execute();
    }

    public void openRegisterWindow() {
        if (inFlight) return;
        if (activeRegisterFrame != null && activeRegisterFrame.isDisplayable()) {
            activeRegisterFrame.toFront();
            activeRegisterFrame.requestFocus();
            return;
        }
        activeRegisterFrame = new RegisterFrame(client, this);
        activeRegisterFrame.setVisible(true);
    }

    private void listenConnectionState() {
        if (connectionManager == null) return;
        connectionManager.addStateChangeListener((oldState, newState, reason) -> {
            SwingUtilities.invokeLater(() -> {
                if (disposed) return;
                if (newState == ConnectionState.DISCONNECTED) {
                    inFlight = false;
                    statusLabel.setForeground(new Color(211, 47, 47));
                    statusLabel.setText("Mất kết nối tới Server.");
                    setFormEnabled(true);
                } else if (newState == ConnectionState.CONNECTED) {
                    statusLabel.setForeground(new Color(46, 125, 50));
                    statusLabel.setText("Đã kết nối Server. Sẵn sàng đăng nhập.");
                }
            });
        });
    }

    /**
     * Kiểm tra dữ liệu nhập hợp lệ trước khi gửi mạng.
     */
    public String validateInputs(String username, String password) {
        if (username == null || username.isBlank()) {
            return "Vui lòng nhập tên đăng nhập.";
        }
        if (username.contains("|") || username.contains("\n") || username.contains("\r")) {
            return "Tên đăng nhập không được chứa ký tự đặc biệt ('|').";
        }
        if (password == null || password.isEmpty()) {
            return "Vui lòng nhập mật khẩu.";
        }
        if (password.contains("|") || password.contains("\n") || password.contains("\r")) {
            return "Mật khẩu không được chứa ký tự đặc biệt ('|').";
        }
        return null; // Hợp lệ
    }

    public void doLogin() {
        if (inFlight) {
            return; // Khóa thao tác gửi trùng (chống spam click)
        }

        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        String validationError = validateInputs(username, password);
        if (validationError != null) {
            statusLabel.setForeground(new Color(211, 47, 47));
            statusLabel.setText(validationError);
            if (username.isBlank()) {
                usernameField.requestFocusInWindow();
            } else {
                passwordField.requestFocusInWindow();
            }
            return;
        }

        inFlight = true;
        setFormEnabled(false);
        loginBtn.setText("Đang đăng nhập…");
        statusLabel.setForeground(new Color(25, 118, 210));
        statusLabel.setText("Đang xác thực tài khoản, vui lòng chờ...");

        authService.login(username, password, new AuthCallback() {
            @Override
            public void onSuccess(AuthResult result) {
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    inFlight = false;
                    dispose();
                    new MainFrame(client, result.displayName(), username, password).setVisible(true);
                });
            }

            @Override
            public void onError(String errorMessage) {
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    inFlight = false;
                    setFormEnabled(true);
                    statusLabel.setForeground(new Color(211, 47, 47));
                    statusLabel.setText(formatErrorMessage(errorMessage));
                    passwordField.requestFocusInWindow();
                });
            }

            @Override
            public void onTimeout() {
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    inFlight = false;
                    setFormEnabled(true);
                    statusLabel.setForeground(new Color(211, 47, 47));
                    statusLabel.setText("Quá thời gian chờ phản hồi từ Server. Vui lòng thử lại.");
                });
            }

            @Override
            public void onNetworkError() {
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    inFlight = false;
                    setFormEnabled(true);
                    statusLabel.setForeground(new Color(211, 47, 47));
                    statusLabel.setText("Mất kết nối Server trong khi xác thực.");
                });
            }
        });
    }

    private String formatErrorMessage(String rawError) {
        if (rawError == null || rawError.isBlank()) {
            return "Đăng nhập thất bại. Vui lòng thử lại.";
        }
        if (rawError.contains("INVALID_CREDENTIALS") || rawError.contains("Sai tài khoản hoặc mật khẩu")) {
            return "Tên đăng nhập hoặc mật khẩu không chính xác.";
        }
        if (rawError.contains("ALREADY_LOGGED_IN") || rawError.contains("đang đăng nhập") || rawError.contains("ALREADY_ONLINE")) {
            return "Tài khoản này hiện đang đăng nhập ở một nơi khác.";
        }
        return rawError;
    }

    public void setFormEnabled(boolean enabled) {
        loginBtn.setEnabled(enabled);
        loginBtn.setText("Đăng nhập");
        registerBtn.setEnabled(enabled);
        usernameField.setEnabled(enabled);
        passwordField.setEnabled(enabled);
        showPasswordCheckbox.setEnabled(enabled);
        btnReconnect.setEnabled(enabled);
    }

    /**
     * Điền sẵn tên đăng nhập sau khi vừa đăng ký thành công.
     */
    public void prefillUsername(String username) {
        if (username != null && !username.isBlank()) {
            usernameField.setText(username.trim());
            passwordField.setText("");
            passwordField.requestFocusInWindow();
            statusLabel.setForeground(new Color(46, 125, 50));
            statusLabel.setText("Đăng ký thành công! Hãy nhập mật khẩu để đăng nhập.");
        }
    }

    /**
     * Được gọi khi RegisterFrame đóng để khôi phục quyền điều khiển cho LoginFrame.
     */
    public void onRegisterClosed() {
        activeRegisterFrame = null;
        if (disposed) return;
        setFormEnabled(true);
        if (!statusLabel.getText().contains("thành công")) {
            statusLabel.setForeground(Color.DARK_GRAY);
            statusLabel.setText("Sẵn sàng đăng nhập.");
        }
    }

    void activateListener() {
        onRegisterClosed();
    }

    @Override
    public void dispose() {
        this.disposed = true;
        if (activeRegisterFrame != null && activeRegisterFrame.isDisplayable()) {
            activeRegisterFrame.dispose();
        }
        super.dispose();
    }

    public boolean isDisposed() {
        return disposed;
    }

    public JTextField getUsernameField() {
        return usernameField;
    }

    public JPasswordField getPasswordField() {
        return passwordField;
    }

    public JLabel getStatusLabel() {
        return statusLabel;
    }

    public JButton getLoginBtn() {
        return loginBtn;
    }
}
