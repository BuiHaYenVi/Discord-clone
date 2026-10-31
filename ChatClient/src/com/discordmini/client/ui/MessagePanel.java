package com.discordmini.client.ui;

import com.discordmini.client.model.ChatMessage;
import com.discordmini.client.model.MessageStatus;

import javax.swing.*;
import java.awt.*;

/**
 * Component hiển thị một tin nhắn trong phòng chat.
 *
 * Tiêu chí AN-08:
 * - Hiển thị rõ: Thời gian, Người gửi, Nội dung.
 * - Hiển thị trạng thái tin nhắn trực quan:
 *     * PENDING: "⏳ Đang gửi..."
 *     * SENT: "✓ Đã gửi" (Lưu ý: Không coi Sent là người nhận đã đọc).
 *     * FAILED: "✕ Lỗi gửi" (kèm lý do nếu có).
 *     * UNKNOWN_TIMEOUT: "? Chưa xác nhận (Timeout)".
 * - Phân biệt tin nhắn của chính mình (màu xanh), của người khác và tin hệ thống.
 */
public class MessagePanel extends JPanel {
    private final JLabel headerLabel = new JLabel();
    private final JLabel contentLabel = new JLabel();
    private final JLabel statusBadge = new JLabel();

    public MessagePanel() {
        setLayout(new BorderLayout(5, 3));
        setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        setOpaque(true);

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        topPanel.setOpaque(false);
        topPanel.add(headerLabel);
        topPanel.add(statusBadge);

        add(topPanel, BorderLayout.NORTH);
        add(contentLabel, BorderLayout.CENTER);
    }

    /**
     * Cập nhật dữ liệu tin nhắn vào panel hiển thị.
     */
    public void bind(ChatMessage msg) {
        if (msg == null) return;

        String time = formatTime(msg.getTimestamp());
        String sender = msg.getSender() != null ? msg.getSender() : "Ẩn danh";
        boolean isMine = msg.isMine();

        // Định dạng header
        headerLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        if (isMine) {
            headerLabel.setForeground(new Color(25, 118, 210)); // Màu xanh dương cho mình
            headerLabel.setText("[" + time + "] " + sender + " (Bạn):");
            setBackground(new Color(240, 247, 255)); // Nền xanh nhạt
        } else if ("HỆ THỐNG".equalsIgnoreCase(sender) || "SYSTEM".equalsIgnoreCase(sender)) {
            headerLabel.setForeground(new Color(198, 40, 40)); // Màu đỏ hệ thống
            headerLabel.setText("[" + time + "] ⚠️ HỆ THỐNG:");
            setBackground(new Color(255, 243, 224)); // Nền cam nhạt
        } else {
            headerLabel.setForeground(new Color(46, 125, 50)); // Màu xanh lá cho bạn bè
            headerLabel.setText("[" + time + "] " + sender + ":");
            setBackground(Color.WHITE);
        }

        // Nội dung tin nhắn
        contentLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        contentLabel.setForeground(Color.BLACK);
        contentLabel.setText("<html><body style='width: 450px; word-wrap: break-word;'>"
                + escapeHtml(msg.getContent()) + "</body></html>");

        // Trạng thái tin nhắn (chỉ áp dụng cho tin của chính mình gửi đi)
        if (isMine && msg.getStatus() != null) {
            renderStatusBadge(msg.getStatus(), msg.getErrorMessage());
            statusBadge.setVisible(true);
        } else {
            statusBadge.setVisible(false);
        }
    }

    private String formatTime(String raw) {
        if (raw == null || raw.isBlank()) return "--:--";
        String t = raw.trim();
        if (t.contains(" ")) {
            String[] parts = t.split(" ");
            t = parts[parts.length - 1];
        }
        if (t.contains("T")) {
            String[] parts = t.split("T");
            t = parts[parts.length - 1];
        }
        if (t.length() >= 5 && t.charAt(2) == ':') {
            return t.substring(0, 5);
        }
        return t;
    }

    private void renderStatusBadge(MessageStatus status, String errorMessage) {
        statusBadge.setFont(new Font("Segoe UI", Font.BOLD, 11));
        switch (status) {
            case PENDING -> {
                statusBadge.setText("⏳ Đang gửi (PENDING)...");
                statusBadge.setForeground(new Color(117, 117, 117));
                statusBadge.setToolTipText("Tin nhắn đang được gửi tới Server...");
            }
            case SENT -> {
                statusBadge.setText("✓ Đã gửi (SENT)");
                statusBadge.setForeground(new Color(46, 125, 50));
                statusBadge.setToolTipText("Server đã nhận và lưu tin nhắn (Chưa đồng nghĩa người nhận đã đọc)");
            }
            case FAILED -> {
                statusBadge.setText("✕ Thất bại (FAILED)");
                statusBadge.setForeground(new Color(198, 40, 40));
                statusBadge.setToolTipText((errorMessage != null ? errorMessage : "Không thể gửi tin nhắn") + " - Nhấn chuột vào tin nhắn để gửi lại");
            }
            case UNKNOWN_TIMEOUT -> {
                statusBadge.setText("? Chưa xác nhận (Timeout)");
                statusBadge.setForeground(new Color(230, 81, 0));
                statusBadge.setToolTipText("Server không phản hồi kịp thời - Nhấn chuột vào tin nhắn để gửi lại");
            }
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br/>");
    }
}
