package com.discordmini.server.service;

import com.discordmini.server.dao.RoomDAO;
import com.discordmini.server.model.Room;
import com.discordmini.server.model.User;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.session.Session;
import com.discordmini.server.protocol.ProtocolHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

public class RoomService {
    private final RoomDAO roomDAO;
    private final EventBus eventBus;

    public RoomService() {
        this(new RoomDAO(), EventBus.getInstance());
    }

    public RoomService(RoomDAO roomDAO) {
        this(roomDAO, EventBus.getInstance());
    }

    public RoomService(RoomDAO roomDAO, EventBus eventBus) {
        this.roomDAO = roomDAO != null ? roomDAO : new RoomDAO();
        this.eventBus = eventBus != null ? eventBus : EventBus.getInstance();
    }

    public List<Room> listRooms() {
        try {
            return roomDAO.listRooms();
        } catch (Exception e) {
            System.err.println("Lỗi danh sách phòng Database: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public Room findRoom(int roomId) {
        try {
            return roomDAO.findRoom(roomId);
        } catch (Exception e) {
            return null;
        }
    }

    public Room createRoom(String name, int createdByUserId) throws Exception {
        return roomDAO.createRoom(name, createdByUserId);
    }

    public Room createChannel(int guildId, String name, String channelType, String topic, int position, int createdBy) {
        return roomDAO.createChannel(guildId, name, channelType, topic, position, createdBy);
    }

    public List<Room> listChannelsByGuild(int guildId, int userId) {
        try {
            return roomDAO.listChannelsByGuild(guildId, userId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void joinRoom(int roomId, int userId) {
        try {
            roomDAO.joinRoom(roomId, userId);
        } catch (Exception e) {
            System.err.println("Lỗi vào phòng Database: " + e.getMessage());
        }
    }

    public boolean removeMember(int roomId, int userId) {
        try {
            return roomDAO.removeMember(roomId, userId);
        } catch (Exception e) {
            System.err.println("Lỗi xóa thành viên Database: " + e.getMessage());
            return false;
        }
    }

    public List<Room> listRoomsForUser(int userId) {
        try {
            return roomDAO.listRoomsForUser(userId);
        } catch (Exception e) {
            System.err.println("Lỗi danh sách phòng Database: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public boolean isRoomOwner(int roomId, int userId) {
        try {
            return roomDAO.isRoomOwner(roomId, userId);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isAdminOrOwner(int roomId, int userId) {
        try {
            String role = roomDAO.getMemberRole(roomId, userId);
            return "OWNER".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean addMember(int roomId, int userId, String role) {
        try {
            return roomDAO.addMember(roomId, userId, role);
        } catch (Exception e) {
            System.err.println("Lỗi thêm thành viên Database: " + e.getMessage());
            return false;
        }
    }

    public boolean updateMemberRole(int roomId, int userId, String role) {
        try {
            boolean updated = roomDAO.updateMemberRole(roomId, userId, role);
            if (updated) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("roomId", roomId);
                payload.put("userId", userId);
                payload.put("role", role != null ? role.toUpperCase() : "MEMBER");
                eventBus.publishToRoom(roomId, null, "MEMBER_ROLE_UPDATED", payload, null);
            }
            return updated;
        } catch (Exception e) {
            return false;
        }
    }

    public String getMemberRole(int roomId, int userId) {
        try {
            return roomDAO.getMemberRole(roomId, userId);
        } catch (Exception e) {
            return null;
        }
    }

    public List<User> getRoomMembers(int roomId) {
        try {
            return roomDAO.getRoomMembers(roomId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public boolean markRead(int userId, int roomId, int lastReadMessageId) {
        try {
            boolean ok = roomDAO.markRead(userId, roomId, lastReadMessageId) == RoomDAO.ReadResult.UPDATED;
            if (ok) {
                String tcpFrame = "READ_UPDATED|" + roomId + "|" + userId + "|" + lastReadMessageId;
                Map<String, Object> wsPayload = new LinkedHashMap<>();
                wsPayload.put("roomId", roomId);
                wsPayload.put("channelId", roomId);
                wsPayload.put("userId", userId);
                wsPayload.put("lastMessageId", lastReadMessageId);
                wsPayload.put("lastReadMessageId", lastReadMessageId);
                Integer unreadCount = roomDAO.getUnreadMessageCount(userId, roomId);
                if (unreadCount != null) wsPayload.put("unreadCount", unreadCount);
                eventBus.publishToUser(userId, tcpFrame, "READ_UPDATED", wsPayload);
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    public RoomDAO getRoomDAO() {
        return roomDAO;
    }

    public Room updateRoom(int roomId, int actorId, String name, String topic) {
        if (!isAdminOrOwner(roomId, actorId) || !roomDAO.updateRoom(roomId, actorId, name, topic)) return null;
        Room room = findRoom(roomId);
        if (room != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("guildId", room.getGuildId());
            payload.put("channel", room);
            eventBus.publishToRoom(roomId, audience(room, actorId),
                    "ROOM_RENAMED|" + roomId + "|" + ProtocolHandler.encode(room.getName()),
                    "CHANNEL_UPDATED", payload, null);
            eventBus.publishToRoom(roomId, audience(room, actorId),
                    "ROOM_DESC_UPDATED|" + roomId + "|" + ProtocolHandler.encode(room.getTopic()),
                    null, null, null);
            refreshTcpRoomLists(room);
        }
        return room;
    }

    public boolean deleteRoom(int roomId, int actorId) {
        Room room = findRoom(roomId);
        if (room == null || !isRoomOwner(roomId, actorId) || !roomDAO.deleteRoom(roomId, actorId)) return false;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", roomId);
        payload.put("channelId", roomId);
        payload.put("guildId", room.getGuildId());
        eventBus.publishToRoom(roomId, audience(room, actorId), "ROOM_DELETED|" + roomId,
                "CHANNEL_DELETED", payload, null);
        for (Session session : eventBus.getAllSessions()) {
            if (Integer.valueOf(roomId).equals(session.getCurrentRoomId())) {
                if (session instanceof com.discordmini.server.session.TcpSession tcp) {
                    tcp.getHandler().clearDeletedRoom(roomId);
                } else {
                    session.setCurrentRoomId(null);
                }
            }
        }
        refreshTcpRoomLists(room);
        return true;
    }

    private void refreshTcpRoomLists(Room room) {
        for (Session session : eventBus.getAllSessions()) {
            User user = session.getUser();
            if (session instanceof com.discordmini.server.session.TcpSession tcp && user != null
                    && room.getMemberIds().contains(user.getId())) tcp.getHandler().refreshVisibleRooms();
        }
    }

    public boolean setMemberRole(int roomId, int actorId, int targetId, String role) {
        if (!isRoomOwner(roomId, actorId) || !roomDAO.setMemberRole(roomId, actorId, targetId, role)) return false;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomId", roomId);
        payload.put("channelId", roomId);
        payload.put("userId", targetId);
        payload.put("role", role);
        Room room = findRoom(roomId);
        if (room != null) eventBus.publishToRoom(roomId, audience(room, actorId), null, "MEMBER_ROLE_UPDATED", payload, null);
        return true;
    }

    public boolean transferOwnership(int roomId, int actorId, int targetId) {
        if (!roomDAO.transferOwnership(roomId, actorId, targetId)) return false;
        Room room = findRoom(roomId);
        if (room != null) {
            for (int memberId : new int[] {actorId, targetId}) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("roomId", roomId);
                payload.put("userId", memberId);
                payload.put("role", memberId == targetId ? "OWNER" : "ADMIN");
                eventBus.publishToRoom(roomId, audience(room, actorId), null, "MEMBER_ROLE_UPDATED", payload, null);
            }
        }
        return true;
    }

    private Set<Integer> audience(Room room, int actorId) {
        Set<Integer> members = new LinkedHashSet<>(room.getMemberIds());
        members.add(actorId);
        return members;
    }
}
