package com.discordmini.server.service;

import com.discordmini.server.dao.FriendDAO;
import com.discordmini.server.model.User;
import com.discordmini.server.protocol.ProtocolHandler;
import com.discordmini.server.session.EventBus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FriendService {
    private final FriendDAO friendDAO;
    private final EventBus eventBus;

    public FriendService() {
        this(new FriendDAO(), EventBus.getInstance());
    }

    public FriendService(FriendDAO friendDAO) {
        this(friendDAO, EventBus.getInstance());
    }

    public FriendService(FriendDAO friendDAO, EventBus eventBus) {
        this.friendDAO = friendDAO != null ? friendDAO : new FriendDAO();
        this.eventBus = eventBus != null ? eventBus : EventBus.getInstance();
    }

    public List<User> searchUsers(int currentUserId, String query) {
        try {
            return friendDAO.searchUsers(currentUserId, query);
        } catch (Exception e) {
            System.err.println("Lỗi searchUsers FriendService: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public String sendFriendRequest(int senderId, int receiverId) {
        try {
            return friendDAO.sendFriendRequest(senderId, receiverId);
        } catch (Exception e) {
            System.err.println("Lỗi sendFriendRequest FriendService: " + e.getMessage());
            return "ERROR";
        }
    }

    public String sendFriendRequestAndNotify(User sender, int receiverId) {
        if (sender == null) return "ERROR";
        String res = sendFriendRequest(sender.getId(), receiverId);
        if ("OK".equals(res)) {
            String tcpFrame = "INCOMING_FRIEND_REQUEST|"
                    + sender.getId() + "|"
                    + ProtocolHandler.encode(sender.getUsername()) + "|"
                    + ProtocolHandler.encode(sender.getDisplayName());
            Map<String, Object> wsPayload = new LinkedHashMap<>();
            wsPayload.put("senderId", sender.getId());
            wsPayload.put("senderUsername", sender.getUsername());
            wsPayload.put("senderDisplayName", sender.getDisplayName());
            eventBus.publishToUser(receiverId, tcpFrame, "FRIEND_REQUEST_INCOMING", wsPayload);
        }
        return res;
    }

    public List<FriendDAO.FriendRequestItem> getPendingRequests(int userId) {
        try {
            return friendDAO.getPendingRequests(userId);
        } catch (Exception e) {
            System.err.println("Lỗi getPendingRequests FriendService: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public boolean acceptRequest(int requestId, int currentUserId) {
        try {
            return friendDAO.acceptRequest(requestId, currentUserId);
        } catch (Exception e) {
            System.err.println("Lỗi acceptRequest FriendService: " + e.getMessage());
            return false;
        }
    }

    public boolean acceptRequestFromUser(int senderId, int currentUserId) {
        try {
            return friendDAO.acceptRequestFromUser(senderId, currentUserId);
        } catch (Exception e) {
            System.err.println("Lỗi acceptRequestFromUser FriendService: " + e.getMessage());
            return false;
        }
    }

    public boolean rejectRequest(int requestId, int currentUserId) {
        try {
            return friendDAO.rejectRequest(requestId, currentUserId);
        } catch (Exception e) {
            System.err.println("Lỗi rejectRequest FriendService: " + e.getMessage());
            return false;
        }
    }

    public List<User> getFriends(int userId) {
        try {
            return friendDAO.getFriends(userId);
        } catch (Exception e) {
            System.err.println("Lỗi getFriends FriendService: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public boolean areFriends(int userId1, int userId2) {
        try {
            return friendDAO.areFriends(userId1, userId2);
        } catch (Exception e) {
            return false;
        }
    }

    public FriendDAO getFriendDAO() {
        return friendDAO;
    }
}
