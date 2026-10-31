package com.discordmini.client.service;

import com.discordmini.client.network.SocketClient;

public class FriendService {
    private final SocketClient client;

    public FriendService(SocketClient client) {
        this.client = client;
    }

    public void searchUsers(String query) {
        client.sendCommand("FRIEND_SEARCH", query);
    }

    public void sendFriendRequest(int targetUserId) {
        client.sendCommand("FRIEND_REQUEST", String.valueOf(targetUserId));
    }

    public void getPendingRequests() {
        client.sendCommand("FRIEND_PENDING_LIST");
    }

    public void acceptFriendRequest(int targetId) {
        client.sendCommand("FRIEND_ACCEPT", String.valueOf(targetId));
    }

    public void rejectFriendRequest(int requestId) {
        client.sendCommand("FRIEND_REJECT", String.valueOf(requestId));
    }

    public void getFriendList() {
        client.sendCommand("FRIEND_LIST");
    }
}
