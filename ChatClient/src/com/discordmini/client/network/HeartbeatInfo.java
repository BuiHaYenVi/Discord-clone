package com.discordmini.client.network;

/**
 * Dữ liệu đo độ trễ tầng ứng dụng (Heartbeat RTT) giữa Client và Server.
 *
 * Tiêu chí AN-09:
 * - latencyMs: Độ trễ phản hồi heartbeat tính bằng mili-giây (ms). Giá trị < 0 nghĩa là chưa có mẫu hoặc mất kết nối.
 * - pingAwaitingPong: Đang chờ Server phản hồi PONG cho gói PING vừa gửi.
 * - lastPingTime: Thời điểm gửi PING gần nhất (định dạng HH:mm:ss).
 * - lastPongTime: Thời điểm nhận PONG gần nhất (định dạng HH:mm:ss).
 */
public record HeartbeatInfo(long latencyMs, boolean pingAwaitingPong, String lastPingTime, String lastPongTime) {
    public static HeartbeatInfo empty() {
        return new HeartbeatInfo(-1, false, null, null);
    }

    public boolean hasSample() {
        return latencyMs >= 0;
    }
}
