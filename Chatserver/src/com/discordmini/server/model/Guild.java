package com.discordmini.server.model;

import java.util.ArrayList;
import java.util.List;

public class Guild {
    private int id;
    private String name;
    private String iconText;
    private int ownerId;
    private String createdAt;
    private List<Room> channels = new ArrayList<>();

    public Guild() {
    }

    public Guild(int id, String name, String iconText, int ownerId, String createdAt) {
        this.id = id;
        this.name = name;
        this.iconText = iconText;
        this.ownerId = ownerId;
        this.createdAt = createdAt;
    }

    public Guild(int id, String name, String iconText, int ownerId, String createdAt, List<Room> channels) {
        this(id, name, iconText, ownerId, createdAt);
        this.channels = channels != null ? channels : new ArrayList<>();
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIconText() { return iconText; }
    public void setIconText(String iconText) { this.iconText = iconText; }

    public int getOwnerId() { return ownerId; }
    public void setOwnerId(int ownerId) { this.ownerId = ownerId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public List<Room> getChannels() { return channels; }
    public void setChannels(List<Room> channels) {
        this.channels = channels != null ? channels : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "Guild{id=" + id + ", name='" + name + "', iconText='" + iconText + "', ownerId=" + ownerId + "}";
    }
}
