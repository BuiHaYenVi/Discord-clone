package com.discordmini.server.model;

import java.sql.Timestamp;

public class RoomMember {
    private int roomId;
    private int userId;
    private Timestamp joinedAt;

    public RoomMember(int roomId, int userId) {
        this(roomId, userId, null);
    }

    public RoomMember(int roomId, int userId, Timestamp joinedAt) {
        this.roomId = roomId;
        this.userId = userId;
        this.joinedAt = joinedAt;
    }

    public int getRoomId() { return roomId; }
    public void setRoomId(int roomId) { this.roomId = roomId; }

    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }

    public Timestamp getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Timestamp joinedAt) { this.joinedAt = joinedAt; }
}
