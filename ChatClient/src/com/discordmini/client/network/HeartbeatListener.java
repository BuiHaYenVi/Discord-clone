package com.discordmini.client.network;

/**
 * Giao diện lắng nghe sự kiện cập nhật Heartbeat và độ trễ tầng ứng dụng.
 */
@FunctionalInterface
public interface HeartbeatListener {
    void onHeartbeatUpdated(HeartbeatInfo info);
}
