package com.discordmini.server.session;

public class ServerEvent {
    public String type;
    public Integer roomId;
    public Integer targetUserId;
    public Integer excludeUserId;
    public String tcpFrame;
    public Object wsPayload;
    public TransportType sourceTransport;
    public long timestampMs;

    public record FrameRecord(
            String direction,
            String transport,
            String rawFrame,
            Integer userId,
            long timestampMs
    ) {
        public String getDirection() { return direction; }
        public String getTransport() { return transport; }
        public String getRawFrame() { return rawFrame; }
        public Integer getUserId() { return userId; }
        public long getTimestampMs() { return timestampMs; }
    }

    public ServerEvent() {
        this.timestampMs = System.currentTimeMillis();
    }

    public ServerEvent(
            String type,
            Integer roomId,
            Integer targetUserId,
            Integer excludeUserId,
            String tcpFrame,
            Object wsPayload,
            TransportType sourceTransport,
            long timestampMs) {
        this.type = type;
        this.roomId = roomId;
        this.targetUserId = targetUserId;
        this.excludeUserId = excludeUserId;
        this.tcpFrame = tcpFrame;
        this.wsPayload = wsPayload;
        this.sourceTransport = sourceTransport;
        this.timestampMs = timestampMs;
    }

    public String getType() { return type; }
    public Integer getRoomId() { return roomId; }
    public Integer getTargetUserId() { return targetUserId; }
    public Integer getExcludeUserId() { return excludeUserId; }
    public String getTcpFrame() { return tcpFrame; }
    public Object getWsPayload() { return wsPayload; }
    public TransportType getSourceTransport() { return sourceTransport; }
    public long getTimestampMs() { return timestampMs; }
}
