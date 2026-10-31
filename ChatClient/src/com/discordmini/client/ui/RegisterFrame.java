package com.discordmini.client.ui;

import com.discordmini.client.network.SocketClient;
import com.discordmini.client.service.AuthCallback;
import com.discordmini.client.service.AuthResult;
import com.discordmini.client.service.AuthService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Giao diện Đăng ký tài khoản cho Discord Mini.
 *
 * Tiêu chí AN-07 và yêu cầu giao diện đơn giản, rõ ràng, hoạt động thật:
 * 1. Tên hiển thị, Tên đăng nhập, Mật khẩu, Nhập lại mật khẩu.
 * 2. Nút "Hiện mật khẩu" hỗ trợ kiểm tra trực quan.
 * 3. Kiểm tra tính hợp lệ dữ liệu, kiểm tra 2 mật khẩu trùng khớp.
 * 4. Khóa thao tác gửi trùng (inFlight), hiển thị trạng thái đang xử lý.
 * 5. Thành công thì thông báo, đóng màn hình và điền sẵn tên đăng nhập sang LoginFrame.
 * 6. Nút "Quay lại đăng nhập" khôi phục LoginFrame an toàn.
 */
public class RegisterFrame extends JFrame {
    private final LoginFrame parent;
    private final AuthService authService;

    private final JTextField displayField = new JTextField(18);
    private final JTextField usernameField = new JTextField(18);
    private final JPasswordField passwordField = new JPasswordField(18);
    private final JPasswordField confirmPasswordField = new JPasswordField(18);
    private final JCheckBox showPasswordCheckbox = new JCheckBox("Hiện mật khẩu");
    private final JLabel statusLabel = new JLabel("Vui lòng điền thông tin để đăng ký", SwingConstants.CENTER);
    private final JButton btnRegister = new JButton("Đăng ký tài khoản");
    private final JButton btnBack = new JButton("← Quay lại đăng nhập");

    private volatile boolean inFlight = false;
    private volatile boolean disposed = false;

    public RegisterFrame(SocketClient client, LoginFrame parent) {
        this.parent = parent;
        this.authService = new AuthService(client);

        setTitle("Discord Mini - Đăng ký tài khoản");
        setSize(460, 460);
        setMinimumSize(new Dimension(420, 420));
        setLocationRelativeTo(parent);
        setResizable(false);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        buildUi();

        // Bắt sự kiện đóng cửa sổ qua nút X
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeAndRestore();
            }
        });
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

        JLabel title = new JLabel("ĐĂNG KÝ TÀI KHOẢN", SwingConstants.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 20));
        title.setForeground(new Color(33, 150, 243));

        JLabel subtitle = new JLabel("Tạo tài khoản mới để tham gia phòng chat", SwingConstants.CENTER);
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitle.setForeground(new Color(110, 110, 110));

        headerPanel.add(title);
        headerPanel.add(subtitle);
        root.add(headerPanel, BorderLayout.NORTH);

        // 2. Form ở giữa
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(5, 10, 5, 10));

        // Tên hiển thị
        form.add(createFieldPanel("Tên hiển thị (Tùy chọn):", displayField));
        form.add(Box.createVerticalStrut(6));

        // Tên đăng nhập
        form.add(createFieldPanel("Tên đăng nhập (*):", usernameField));
        form.add(Box.createVerticalStrut(6));

        // Mật khẩu
        form.add(createFieldPanel("Mật khẩu (*):", passwordField));
        form.add(Box.createVerticalStrut(6));

        // Nhập lại mật khẩu
        form.add(createFieldPanel("Nhập lại mật khẩu (*):", confirmPasswordField));
        form.add(Box.createVerticalStrut(4));

        // Checkbox hiện mật khẩu
        showPasswordCheckbox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        showPasswordCheckbox.setOpaque(false);
        showPasswordCheckbox.setFocusPainted(false);
        showPasswordCheckbox.addActionListener(e -> {
            char echo = showPasswordCheckbox.isSelected() ? (char) 0 : '•';
            passwordField.setEchoChar(echo);
            confirmPasswordField.setEchoChar(echo);
        });
        form.add(showPasswordCheckbox);
        form.add(Box.createVerticalStrut(10));

        // Nút Đăng ký
        btnRegister.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnRegister.setBackground(new Color(46, 125, 50));
        btnRegister.setForeground(Color.WHITE);
        btnRegister.setFocusPainted(false);
        btnRegister.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        btnRegister.setPreferredSize(new Dimension(200, 36));
        btnRegister.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnRegister.setAlignmentX(Component.CENTER_ALIGNMENT);
        form.add(btnRegister);
        form.add(Box.createVerticalStrut(6));

        // Nút Quay lại đăng nhập
        btnBack.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btnBack.setForeground(new Color(25, 118, 210));
        btnBack.setContentAreaFilled(false);
        btnBack.setBorderPainted(false);
        btnBack.setFocusPainted(false);
        btnBack.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnBack.setAlignmentX(Component.CENTER_ALIGNMENT);
        form.add(btnBack);

        root.add(form, BorderLayout.CENTER);

        // 3. Footer thông báo trạng thái
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        statusLabel.setForeground(new Color(75, 75, 75));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(220, 220, 220)),
                new EmptyBorder(6, 4, 4, 4)
        ));
        root.add(statusLabel, BorderLayout.SOUTH);

        setContentPane(root);

        // Sự kiện
        btnRegister.addActionListener(e -> register());
        btnBack.addActionListener(e -> closeAndRestore());
        usernameField.addActionListener(e -> register());
        displayField.addActionListener(e -> register());
        passwordField.addActionListener(e -> register());
        confirmPasswordField.addActionListener(e -> register());
    }

    private JPanel createFieldPanel(String labelText, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(5, 2));
        p.setOpaque(false);
        JLabel lbl = new JLabel(labelText);
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
        field.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        p.add(lbl, BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    public void closeAndRestore() {
        authService.cancelPendingRequest();
        if (parent != null) {
            parent.onRegisterClosed();
        }
        dispose();
    }

    /**
     * Tương thích với các bài test hiện có (3 tham số).
     */
    public String validateInputs(String u, String p, String d) {
        return validateInputs(u, p, p, d);
    }

    /**
     * Kiểm tra tính hợp lệ dữ liệu đăng ký đầy đủ (4 tham số).
     */
    public String validateInputs(String u, String p, String confirmP, String d) {
        if (u == null || u.isBlank()) {
            return "Vui lòng nhập tên đăng nhập.";
        }
        if (u.contains("|") || u.contains("\n") || u.contains("\r")) {
            return "Tên đăng nhập không được chứa ký tự đặc biệt ('|').";
        }
        if (p == null || p.isEmpty()) {
            return "Vui lòng nhập mật khẩu.";
        }
        if (p.contains("|") || p.contains("\n") || p.contains("\r")) {
            return "Mật khẩu không được chứa ký tự đặc biệt ('|').";
        }
        if (confirmP != null && !p.equals(confirmP)) {
            return "Mật khẩu nhập lại không khớp.";
        }
        if (d != null && (d.contains("|") || d.contains("\n") || d.contains("\r"))) {
            return "Tên hiển thị không được chứa ký tự đặc biệt ('|').";
        }
        return null; // Hợp lệ
    }

    public void register() {
        if (inFlight) {
            return; // Khóa thao tác gửi trùng
        }

        String u = usernameField.getText().trim();
        String d = displayField.getText().trim();
        String p = new String(passwordField.getPassword());
        String confirmP = new String(confirmPasswordField.getPassword());

        String validationError = validateInputs(u, p, confirmP, d);
        if (validationError != null) {
            statusLabel.setForeground(new Color(211, 47, 47));
            statusLabel.setText(validationError);
            if (u.isBlank()) {
                usernameField.requestFocusInWindow();
            } else if (p.isEmpty()) {
                passwordField.requestFocusInWindow();
            } else {
                confirmPasswordField.requestFocusInWindow();
            }
            return;
        }

        if (d.isBlank()) {
            d = u;
        }

        inFlight = true;
        setFormEnabled(false);
        btnRegister.setText("Đang tạo tài khoản…");
        statusLabel.setForeground(new Color(25, 118, 210));
        statusLabel.setText("Đang gửi yêu cầu đăng ký, vui lòng chờ...");

        final String registeredUsername = u;
        authService.register(u, p, d, new AuthCallback() {
            @Override
            public void onSuccess(AuthResult result) {
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    inFlight = false;
                    JOptionPane.showMessageDialog(RegisterFrame.this,
                            "Tạo tài khoản thành công! Bạn có thể đăng nhập ngay.",
                            "Đăng ký thành công", JOptionPane.INFORMATION_MESSAGE);

                    if (parent != null) {
                        parent.prefillUsername(registeredUsername);
                    }
                    closeAndRestore();
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
                    statusLabel.setText("Mất kết nối Server trong khi đăng ký.");
                });
            }
        });
    }

    private String formatErrorMessage(String rawError) {
        if (rawError == null || rawError.isBlank()) {
            return "Đăng ký thất bại. Vui lòng thử lại.";
        }
        if (rawError.contains("USER_EXISTS") || rawError.contains("đã tồn tại")) {
            return "Tên đăng nhập này đã có người sử dụng. Vui lòng chọn tên khác.";
        }
        return rawError;
    }

    public void setFormEnabled(boolean enabled) {
        btnRegister.setEnabled(enabled);
        btnRegister.setText("Đăng ký tài khoản");
        btnBack.setEnabled(enabled);
        usernameField.setEnabled(enabled);
        displayField.setEnabled(enabled);
        passwordField.setEnabled(enabled);
        confirmPasswordField.setEnabled(enabled);
        showPasswordCheckbox.setEnabled(enabled);
    }

    @Override
    public void dispose() {
        this.disposed = true;
        super.dispose();
    }

    public boolean isDisposed() {
        return disposed;
    }

    public JTextField getDisplayField() {
        return displayField;
    }

    public JTextField getUsernameField() {
        return usernameField;
    }

    public JPasswordField getPasswordField() {
        return passwordField;
    }

    public JPasswordField getConfirmPasswordField() {
        return confirmPasswordField;
    }

    public JLabel getStatusLabel() {
        return statusLabel;
    }

    public JButton getBtnRegister() {
        return btnRegister;
    }
}
