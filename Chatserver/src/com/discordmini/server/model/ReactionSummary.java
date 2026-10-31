package com.discordmini.server.model;

import java.util.ArrayList;
import java.util.List;

public class ReactionSummary {
    private String emoji;
    private int count;
    private List<Integer> userIds = new ArrayList<>();

    public ReactionSummary() {
    }

    public ReactionSummary(String emoji, int count, List<Integer> userIds) {
        this.emoji = emoji;
        this.count = count;
        this.userIds = userIds != null ? userIds : new ArrayList<>();
    }

    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public List<Integer> getUserIds() { return userIds; }
    public void setUserIds(List<Integer> userIds) {
        this.userIds = userIds != null ? userIds : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "ReactionSummary{emoji='" + emoji + "', count=" + count + ", userIds=" + userIds + "}";
    }
}
