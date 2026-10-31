package com.discordmini.client.ui;

import com.discordmini.client.network.ConnectionManager;
import com.discordmini.client.network.ConnectionState;
import com.discordmini.client.network.DisconnectReason;
import com.discordmini.client.network.HeartbeatInfo;
import com.discordmini.client.network.HeartbeatListener;
import com.discordmini.client.network.ReconnectManager;
import com.discordmini.client.network.SocketClient;

import javax.swing.*;
import java.awt.*;

/**
 * Thanh giám sát trạng thái mạng và kết nối Server (Network Status Monitor).
 *
 * Tiêu chí AN-09:
 * 1. Hiển thị trạng thái kết nối từ ConnectionManager.
 * 2. Hiển thị địa chỉ Server và Port.
 * 3. Hiển thị trạng thái PING/PONG.
 * 4. Hiển thị độ trễ phản hồi heartbeat (đơn vị ms) từ SocketClient.
 * 5. Tái sử dụng heartbeat trong SocketClient, không tạo thêm vòng PING riêng.
 * 6. Độ trễ là thời gian gửi PING -> nhận PONG tương ứng đo bằng System.nanoTime().
 * 7. Nếu chưa có mẫu hoặc mất kết nối, hiển thị "—" / "Chưa có dữ liệu", tuyệt đối không hiển thị 0 ms giả.
 * 8. Không gọi độ trễ tầng ứng dụng này là độ trễ ICMP.
 * 9. Cập nhật UI trên EDT và hỗ trợ cleanup() giải phóng listener khi đóng cửa sổ.
 * 10. Chuẩn bị hiển thị số lần retry kết nối lại cho AN-10.
 */
public class NetworkStatusPanel extends JPanel {
    private final SocketClient client;
    private final ConnectionManager connectionManager;

    private final JLabel lblConnectionStatus = new JLabel("Chưa kết nối");
    private final JLabel lblServerAddress = new JLabel("—");
    private final JLabel lblPingPongStatus = new JLabel("—");
    private final JLabel lblLatency = new JLabel("—");
    private final JLabel lblRetryCount = new JLabel("—");
    private final JButton btnRetry = new JButton("🔄 Thử lại kết nối");
    private ReconnectManager reconnectManager;

    // Giữ tham chiếu để giải phóng listener sạch sẽ trong cleanup()
    private final ConnectionManager.StateChangeListener stateChangeListener;
    private final HeartbeatListener heartbeatListener;

    public NetworkStatusPanel(SocketClient client) {
        this.client = client;
        this.connectionManager = (client != null) ? client.getConnectionManager() : null;

        this.stateChangeListener = (oldState, newState, reason) -> updateStateOnEdt();
        this.heartbeatListener = info -> updateHeartbeatOnEdt(info);

        buildUi();
        registerListeners();
        updateStateOnEdt();
    }

    private void buildUi() {
        setLayout(new FlowLayout(FlowLayout.LEFT, 15, 4));
        setBackground(new Color(248, 249, 250));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(222, 226, 230)),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)
        ));

        Font labelFont = new Font("Segoe UI", Font.PLAIN, 12);
        Font boldFont = new Font("Segoe UI", Font.BOLD, 12);

        // 1. Trạng thái kết nối
        add(createSection("Trạng thái:", lblConnectionStatus, boldFont));
        add(createSeparator());

        // 2. Địa chỉ Server
        add(createSection("Máy chủ:", lblServerAddress, labelFont));
        add(createSeparator());

        // 3. Trạng thái PING / PONG
        add(createSection("Heartbeat:", lblPingPongStatus, labelFont));
        add(createSeparator());

        // 4. Độ trễ ứng dụng (RTT)
        add(createSection("Độ trễ ứng dụng (RTT):", lblLatency, boldFont));
        add(createSeparator());

        // 5. Số lần thử lại (Chuẩn bị cho AN-10)
        add(createSection("Thử lại:", lblRetryCount, labelFont));

        // 6. Nút thử lại thủ công khi thất bại
        btnRetry.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btnRetry.setMargin(new Insets(1, 6, 1, 6));
        btnRetry.setVisible(false);
        btnRetry.addActionListener(e -> {
            if (reconnectManager != null) {
                btnRetry.setEnabled(false);
                reconnectManager.retryManually();
            }
        });
        add(btnRetry);
    }

    private JPanel createSection(String title, JLabel valueLabel, Font valueFont) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);

        JLabel lblTitle = new JLabel(title);
        lblTitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblTitle.setForeground(new Color(108, 117, 125));

        valueLabel.setFont(valueFont);
        valueLabel.setForeground(new Color(33, 37, 41));

        p.add(lblTitle);
        p.add(valueLabel);
        return p;
    }

    private JSeparator createSeparator() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 16));
        sep.setForeground(new Color(222, 226, 230));
        return sep;
    }

    private void registerListeners() {
        if (connectionManager != null) {
            connectionManager.addStateChangeListener(stateChangeListener);
        }
        if (client != null) {
            client.addHeartbeatListener(heartbeatListener);
        }
    }

    /**
     * Giải phóng đăng ký sự kiện khi đóng cửa sổ cha (EDT / Window dispose).
     */
    public void cleanup() {
        if (connectionManager != null) {
            connectionManager.removeStateChangeListener(stateChangeListener);
        }
        if (client != null) {
            client.removeHeartbeatListener(heartbeatListener);
        }
    }

    /**
     * Cập nhật toàn bộ trạng thái kết nối lên EDT.
     */
    public void updateStateOnEdt() {
        SwingUtilities.invokeLater(this::renderState);
    }

    private void renderState() {
        ConnectionState state = (connectionManager != null) ? connectionManager.getState() : ConnectionState.DISCONNECTED;
        DisconnectReason reason = (connectionManager != null) ? connectionManager.getLastDisconnectReason() : DisconnectReason.NONE;

        switch (state) {
            case AUTHENTICATED -> {
                lblConnectionStatus.setText("🟢 Đã đăng nhập");
                lblConnectionStatus.setForeground(new Color(40, 167, 69));
            }
            case CONNECTED -> {
                lblConnectionStatus.setText("🟢 Đã kết nối TCP");
                lblConnectionStatus.setForeground(new Color(40, 167, 69));
            }
            case CONNECTING -> {
                lblConnectionStatus.setText("🟡 Đang kết nối...");
                lblConnectionStatus.setForeground(new Color(255, 152, 0));
            }
            case RECONNECTING -> {
                lblConnectionStatus.setText("🟡 Đang kết nối lại...");
                lblConnectionStatus.setForeground(new Color(255, 152, 0));
            }
            case DISCONNECTED -> {
                String desc = (reason != null && reason != DisconnectReason.NONE) ? " (" + reason.name() + ")" : "";
                lblConnectionStatus.setText("🔴 Mất kết nối" + desc);
                lblConnectionStatus.setForeground(new Color(220, 53, 69));
            }
        }

        // Cập nhật địa chỉ Server
        if (client != null && client.isConnected() && client.getServerHost() != null) {
            lblServerAddress.setText(client.getServerHost() + ":" + client.getServerPort());
        } else {
            lblServerAddress.setText("—");
        }

        // Cập nhật số lần thử lại (AN-10)
        int retries = (connectionManager != null) ? connectionManager.getReconnectAttempts() : 0;
        lblRetryCount.setText((retries > 0) ? String.valueOf(retries) : "—");
        btnRetry.setVisible(state == ConnectionState.DISCONNECTED && retries >= 5);
        btnRetry.setEnabled(state == ConnectionState.DISCONNECTED);

        // Khi ngắt kết nối: Đưa độ trễ và heartbeat về trạng thái chưa có dữ liệu
        if (client == null || !client.isConnected()) {
            lblPingPongStatus.setText("—");
            lblLatency.setText("—");
            lblLatency.setForeground(new Color(108, 117, 125));
        } else {
            renderHeartbeat(client.getHeartbeatInfo());
        }
    }

    /**
     * Cập nhật thông tin Heartbeat và độ trễ lên EDT.
     */
    public void updateHeartbeatOnEdt(HeartbeatInfo info) {
        SwingUtilities.invokeLater(() -> {
            renderState();
            renderHeartbeat(info);
        });
    }

    private void renderHeartbeat(HeartbeatInfo info) {
        if (client == null || !client.isConnected() || info == null) {
            lblPingPongStatus.setText("—");
            lblLatency.setText("—");
            lblLatency.setForeground(new Color(108, 117, 125));
            return;
        }

        // Cập nhật trạng thái PING / PONG
        if (info.pingAwaitingPong()) {
            String time = (info.lastPingTime() != null) ? " (" + info.lastPingTime() + ")" : "";
            lblPingPongStatus.setText("⏳ Đã gửi PING" + time);
            lblPingPongStatus.setForeground(new Color(255, 152, 0));
        } else if (info.lastPongTime() != null) {
            lblPingPongStatus.setText("✓ PONG OK (" + info.lastPongTime() + ")");
            lblPingPongStatus.setForeground(new Color(40, 167, 69));
        } else {
            lblPingPongStatus.setText("Chờ PING");
            lblPingPongStatus.setForeground(new Color(108, 117, 125));
        }

        // Cập nhật độ trễ: Tiêu chí AN-09: Nếu chưa có mẫu hoặc mất kết nối -> hiển thị "—", không hiển thị 0 ms giả
        if (!info.hasSample()) {
            lblLatency.setText("—");
            lblLatency.setForeground(new Color(108, 117, 125));
        } else {
            long ms = info.latencyMs();
            lblLatency.setText(ms + " ms");

            // Đổi màu sắc độ trễ theo thang trực quan:
            // < 50ms: Xanh lá (tốt)
            // 50ms - 200ms: Vàng cam (trung bình)
            // > 200ms: Đỏ (chậm)
            if (ms < 50) {
                lblLatency.setForeground(new Color(40, 167, 69));
            } else if (ms < 200) {
                lblLatency.setForeground(new Color(255, 152, 0));
            } else {
                lblLatency.setForeground(new Color(220, 53, 69));
            }
        }
    }

    public void setReconnectManager(ReconnectManager reconnectManager) {
        this.reconnectManager = reconnectManager;
    }

    public JButton getBtnRetry() {
        return btnRetry;
    }

    public JLabel getConnectionStatusLabel() {
        return lblConnectionStatus;
    }

    public JLabel getServerAddressLabel() {
        return lblServerAddress;
    }

    public JLabel getPingPongStatusLabel() {
        return lblPingPongStatus;
    }

    public JLabel getLatencyLabel() {
        return lblLatency;
    }

    public JLabel getRetryCountLabel() {
        return lblRetryCount;
    }
}
