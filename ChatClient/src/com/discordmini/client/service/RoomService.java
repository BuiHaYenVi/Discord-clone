package com.discordmini.client.service;

import com.discordmini.client.model.Room;
import com.discordmini.client.network.SocketClient;
import com.discordmini.client.protocol.ProtocolHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Dịch vụ quản lý Phòng chat phía Client (Danh sách, Tạo, Vào phòng, Rời phòng).
 *
 * Tiêu chí AN-06:
 * 1. Giữ đúng định dạng room ID số nguyên theo protocol (JOIN_ROOM|<id>), không mã hóa Base64 ID số.
 * 2. Chỉ cập nhật phòng hiện tại (currentRoom) sau phản hồi OK|JOIN_ROOM thành công của Server.
 * 3. Ghi nhớ phòng vào thành công gần nhất (lastSuccessfulRoomId) phục vụ tính năng AN-10.
 * 4. Xử lý tạo phòng lỗi, phòng không tồn tại, chuyển phòng thất bại và rớt mạng.
 * 5. Cơ chế thế hệ phòng (roomEpoch) để tránh phản hồi hoặc lịch sử đến trễ của phòng cũ ghi đè phòng mới.
 * 6. Sau khi leave thành công, khóa thao tác gửi tin vào phòng đã rời (currentRoom = null).
 * 7. Giữ logic giao thức mạng trong service, không rải rác trong UI.
 */
public class RoomService {
    private final SocketClient client;
    private final ChatService chatService;

    private volatile Room currentRoom = null;
    private volatile Integer pendingJoinRoomId = null;
    private volatile int pendingJoinEpoch = 0;
    private volatile int lastLeaveEpoch = 0;
    private volatile Integer lastSuccessfulRoomId = null;

    // Thế hệ phòng nhằm ngăn chặn dữ liệu phòng cũ đến trễ
    private final AtomicInteger roomEpoch = new AtomicInteger(0);

    private final List<Room> cachedRooms = Collections.synchronizedList(new ArrayList<>());
    private final List<RoomEventListener> listeners = new CopyOnWriteArrayList<>();

    public RoomService(SocketClient client) {
        this(client, null);
    }

    public RoomService(SocketClient client, ChatService chatService) {
        this.client = Objects.requireNonNull(client, "SocketClient không được null");
        this.chatService = chatService;
        registerPacketListeners();
    }

    private void registerPacketListeners() {
        client.addMessageListener(this::handleServerPacket);
        client.addConnectionListener(this::handleConnectionEvent);
    }

    public SocketClient getClient() {
        return client;
    }

    public void addListener(RoomEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(RoomEventListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public Room getCurrentRoom() {
        return currentRoom;
    }

    public boolean isInRoom() {
        return currentRoom != null;
    }

    public Integer getLastSuccessfulRoomId() {
        return lastSuccessfulRoomId;
    }

    public Integer getPendingJoinRoomId() {
        return pendingJoinRoomId;
    }

    public List<Room> getCachedRooms() {
        synchronized (cachedRooms) {
            return new ArrayList<>(cachedRooms);
        }
    }

    public int getRoomEpoch() {
        return roomEpoch.get();
    }

    /**
     * Yêu cầu Server gửi danh sách tất cả các phòng hiện có.
     */
    public void listRooms() {
        if (client.isConnected()) {
            client.sendCommand("LIST_ROOMS");
        }
    }

    /**
     * Tạo phòng chat mới với tên phòng đã cho.
     * Tên phòng được mã hóa Base64 theo chuẩn giao thức.
     */
    public String createRoom(String name) {
        if (name == null || name.isBlank() || !client.isConnected()) {
            notifyListeners(l -> l.onRoomError("Tên phòng không được để trống."));
            return null;
        }
        return client.sendCommandWithId("CREATE_ROOM", name.trim());
    }

    /**
     * Tham gia vào phòng chat bằng roomId.
     * ĐẢM BẢO roomId LÀ SỐ NGUYÊN (không mã hóa Base64 cho ID số).
     */
    public String joinRoom(int roomId) {
        if (!client.isConnected()) {
            notifyListeners(l -> l.onRoomError("Chưa kết nối đến máy chủ. Vui lòng kết nối lại."));
            return null;
        }
        if (roomId <= 0) {
            notifyListeners(l -> l.onRoomError("ID phòng không hợp lệ."));
            return null;
        }

        // Tăng epoch để hủy bỏ mọi dữ liệu đến trễ của phòng trước đó
        int epoch = roomEpoch.incrementAndGet();
        this.pendingJoinRoomId = roomId;
        this.pendingJoinEpoch = epoch;

        if (chatService != null) {
            chatService.clearRoomState();
        }

        // Giữ nguyên định dạng: JOIN_ROOM|<id> (số nguyên trực tiếp)
        return client.sendWithId("JOIN_ROOM|" + roomId);
    }

    /**
     * Rời khỏi phòng chat hiện tại.
     * Cập nhật currentRoom = null để UI không còn cho phép gửi tin vào phòng đã rời.
     */
    public void leaveRoom() {
        int epoch = roomEpoch.incrementAndGet();
        this.lastLeaveEpoch = epoch;
        this.currentRoom = null;
        this.pendingJoinRoomId = null;
        this.lastSuccessfulRoomId = null; // Người dùng chủ động rời phòng: reconnect không được tự đưa lại phòng này

        if (chatService != null) {
            chatService.clearRoomState();
        }

        if (client.isConnected()) {
            client.sendCommand("LEAVE_ROOM");
        }
        notifyListeners(RoomEventListener::onRoomLeft);
    }

    /**
     * Tham gia lại phòng vào thành công gần nhất (phục vụ AN-10 Reconnect).
     */
    public boolean rejoinLastRoom() {
        Integer lastId = this.lastSuccessfulRoomId;
        if (lastId != null && lastId > 0 && client.isConnected()) {
            joinRoom(lastId);
            return true;
        }
        return false;
    }

    public void handleServerPacket(String packet) {
        try {
            String[] parts = ProtocolHandler.split(packet);
            if (parts.length == 0) return;

            String type = parts[0];

            switch (type) {
                case "ROOMS" -> handleRoomsList(parts);
                case "OK" -> handleOkResponse(parts);
                case "ERROR" -> handleErrorResponse(parts);
                case "ROOM_MEMBERS" -> handleRoomMembers(parts);
            }
        } catch (Exception ex) {
            System.err.println("[RoomService] Lỗi xử lý gói tin: " + ex.getMessage());
        }
    }

    private void handleRoomsList(String[] parts) {
        List<Room> newRooms = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String item = parts[i];
            String[] x = item.split(",", 4);
            if (x.length >= 2) {
                try {
                    int id = Integer.parseInt(x[0]);
                    String name = ProtocolHandler.decode(x[1]);
                    newRooms.add(new Room(id, name));
                } catch (Exception ignored) {}
            }
        }

        synchronized (cachedRooms) {
            cachedRooms.clear();
            cachedRooms.addAll(newRooms);
        }

        notifyListeners(l -> l.onRoomListUpdated(newRooms));
    }

    private void handleOkResponse(String[] parts) {
        if (parts.length < 2) return;
        String action = parts[1];

        if ("ROOM_CREATED".equals(action) && parts.length >= 4) {
            int id = Integer.parseInt(parts[2]);
            String name = ProtocolHandler.decode(parts[3]);
            Room room = new Room(id, name);
            notifyListeners(l -> l.onRoomCreated(room));
            listRooms(); // Tự động làm mới danh sách phòng
        } else if ("JOIN_ROOM".equals(action) && parts.length >= 4) {
            int id = Integer.parseInt(parts[2]);
            String name = ProtocolHandler.decode(parts[3]);

            // Đối chiếu tính hợp lệ:
            // 1. Nếu đang có phòng chờ khác id -> phản hồi của phòng cũ, loại bỏ
            if (this.pendingJoinRoomId != null && this.pendingJoinRoomId != id) {
                return;
            }
            // 2. Nếu đã có thao tác leaveRoom hoặc rớt mạng sau khi phát yêu cầu join -> loại bỏ phản hồi cũ
            if (this.pendingJoinEpoch > 0 && this.lastLeaveEpoch >= this.pendingJoinEpoch) {
                return;
            }

            // Chỉ cập nhật phòng hiện tại sau khi Server xác nhận thành công
            this.currentRoom = new Room(id, name);
            this.lastSuccessfulRoomId = id;
            this.pendingJoinRoomId = null;
            this.pendingJoinEpoch = 0;

            notifyListeners(l -> l.onRoomJoined(this.currentRoom));
        }
    }

    private void handleErrorResponse(String[] parts) {
        if (parts.length < 2) return;
        String errorMsg = ProtocolHandler.decode(parts[1]);

        // Chỉ thông báo lỗi phòng nếu đang có yêu cầu vào phòng hoặc lỗi liên quan tới phòng
        boolean isRoomRelated = pendingJoinRoomId != null
                || errorMsg.toLowerCase().contains("phòng")
                || errorMsg.toLowerCase().contains("room");

        this.pendingJoinRoomId = null;

        if (isRoomRelated) {
            notifyListeners(l -> l.onRoomError(errorMsg));
        }
    }

    private void handleRoomMembers(String[] parts) {
        List<String> memberNames = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String item = parts[i];
            String[] x = item.split(",", 4);
            if (x.length >= 2) {
                try {
                    String name = ProtocolHandler.decode(x[1]);
                    memberNames.add(name);
                } catch (Exception ignored) {}
            }
        }

        int currentId = (currentRoom != null) ? currentRoom.id() : 0;
        notifyListeners(l -> l.onRoomMembersUpdated(currentId, memberNames));
    }

    private void handleConnectionEvent(String event) {
        if ("CLIENT_DISCONNECTED".equals(event)) {
            // Khi rớt mạng: hủy phòng hiện tại nhưng vẫn giữ lastSuccessfulRoomId để AN-10 tự động vào lại
            this.currentRoom = null;
            this.pendingJoinRoomId = null;
            int epoch = roomEpoch.incrementAndGet();
            this.lastLeaveEpoch = epoch;
            notifyListeners(RoomEventListener::onRoomLeft);
        }
    }

    private void notifyListeners(Consumer<RoomEventListener> action) {
        for (RoomEventListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Throwable t) {
                System.err.println("[RoomService] Lỗi listener callback: " + t.getMessage());
            }
        }
    }

    public void inviteMember(int roomId, int targetUserId) {
        client.sendCommand("INVITE_ROOM", String.valueOf(roomId), String.valueOf(targetUserId));
    }

    public void kickMember(int roomId, int targetUserId) {
        client.sendCommand("KICK_ROOM", String.valueOf(roomId), String.valueOf(targetUserId));
    }
}
