package com.discordmini.server.model;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class Room {
    private int id;
    private String name;
    private int createdBy;
    private Timestamp createdAt;
    private String description = "";
    private String inviteCode = "";
    private final Set<Integer> memberIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private int guildId = 1;
    private String channelType = "text";
    private int position = 0;
    private String topic = "";
    private int unreadCount = 0;

    public Room() {
    }

    public Room(int id, String name) {
        this(id, name, 0, null);
    }

    public Room(int id, String name, int createdBy, Timestamp createdAt) {
        this.id = id;
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Room(int id, String name, int createdBy, Timestamp createdAt,
                int guildId, String channelType, int position, String topic) {
        this(id, name, createdBy, createdAt);
        this.guildId = guildId > 0 ? guildId : 1;
        this.channelType = (channelType != null && !channelType.isBlank()) ? channelType : "text";
        this.position = position;
        setTopic(topic);
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getCreatedBy() { return createdBy; }
    public void setCreatedBy(int createdBy) { this.createdBy = createdBy; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public String getDescription() { return getTopic(); }
    public void setDescription(String description) { setTopic(description); }

    public String getInviteCode() { return inviteCode != null ? inviteCode : ""; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }

    public Set<Integer> getMemberIds() { return memberIds; }

    public int getGuildId() { return guildId; }
    public void setGuildId(int guildId) { this.guildId = guildId; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType != null ? channelType : "text"; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) {
        this.topic = topic != null ? topic : "";
        this.description = this.topic;
    }

    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }

    @Override
    public String toString() {
        return "Room{id=" + id + ", guildId=" + guildId + ", name='" + name +
               "', channelType='" + channelType + "', createdBy=" + createdBy + "}";
    }
}
