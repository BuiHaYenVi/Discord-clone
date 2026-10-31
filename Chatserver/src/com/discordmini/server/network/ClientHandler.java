package com.discordmini.server.network;

import com.discordmini.server.model.Guild;
import com.discordmini.server.model.Message;
import com.discordmini.server.model.ReactionSummary;
import com.discordmini.server.model.Room;
import com.discordmini.server.model.User;
import com.discordmini.server.protocol.MessageType;
import com.discordmini.server.protocol.ProtocolErrorCode;
import com.discordmini.server.protocol.ProtocolHandler;
import com.discordmini.server.session.TcpSession;
import com.discordmini.server.session.TransportType;

import java.io.IOException;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClientHandler implements Runnable {
    private final ClientConnection connection;
    private final ChatServer server;
    private final TcpSession tcpSession;
    private volatile boolean running = true;
    private User user;
    private Integer currentRoomId;
    /** Request metadata is scoped to the single reader thread for this client. */
    private String activeRequestId;
    private boolean requestFailed;

    private static final int MAX_USERNAME_LENGTH = 50;
    private static final int MAX_DISPLAY_NAME_LENGTH = 100;
    private static final int MAX_ROOM_NAME_LENGTH = 100;
    private static final int MAX_MESSAGE_LENGTH = 5000;

    public ClientHandler(Socket socket, ChatServer server) throws IOException {
        this.connection = new ClientConnection(socket);
        this.server = server;
        this.tcpSession = new TcpSession(this, server.getEventBus());
    }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Integer getCurrentRoomId() { return currentRoomId; }
    public void setCurrentRoomId(Integer currentRoomId) { this.currentRoomId = currentRoomId; }
    public TcpSession getTcpSession() { return tcpSession; }
    public boolean isConnectionOpen() { return running && !connection.isClosed(); }

    public void closeConnection() {
        running = false;
        try {
            connection.close();
        } catch (IOException ignored) {}
    }

    public void sendPacket(String packet) {
        connection.send(packet);
    }

    private void sendLine(String packet) {
        connection.send(packet);
        if (server.getEventBus() != null) {
            server.getEventBus().recordProtocolFrame(
                    "OUT",
                    TransportType.TCP,
                    packet,
                    user != null ? user.getId() : null
            );
        }
    }

    @Override
    public void run() {
        if (server.getEventBus() != null) {
            server.getEventBus().registerSession(tcpSession);
        }
        try {
            sendLine("WELCOME|" + ProtocolHandler.encode("Discord Mini Chat Server"));
            String line;
            while (running && (line = connection.readLine()) != null) {
                handle(line);
            }
        } catch (IOException e) {
            System.out.println("[DISCONNECT] " + (user != null ? user.getUsername() : "unknown"));
        } finally {
            cleanup();
        }
    }

    private void handle(String line) {
        activeRequestId = null;
        requestFailed = false;
        if (server.getEventBus() != null) {
            server.getEventBus().recordProtocolFrame(
                    "IN",
                    TransportType.TCP,
                    line,
                    user != null ? user.getId() : null
            );
        }
        try {
            String[] parts = ProtocolHandler.split(line);
            activeRequestId = ProtocolHandler.requestId(parts);
            MessageType type = MessageType.valueOf(parts[0]);

            switch (type) {
                case REGISTER -> handleRegister(parts);
                case LOGIN -> handleLogin(parts);
                case LOGOUT -> {
                    leaveCurrentRoom(false);
                    if (user != null) {
                        int uid = user.getId();
                        String dName = user.getDisplayName();
                        user.setOnline(false);
                        server.getClientManager().remove(user, this);
                        server.getUserDAO().updateUserStatus(uid, "OFFLINE");
                        String offlineFrame = "USER_OFFLINE|" + uid + "|" + ProtocolHandler.encode(dName);
                        broadcastGlobal(offlineFrame);
                        Map<String, Object> wsPresence = new LinkedHashMap<>();
                        wsPresence.put("userId", uid);
                        wsPresence.put("displayName", dName);
                        wsPresence.put("status", "OFFLINE");
                        server.getEventBus().publishGlobal(null, "PRESENCE_UPDATED", wsPresence, uid);
                        user = null;
                    }
                    sendLine("OK|LOGOUT");
                }
                case LIST_ROOMS -> {
                    requireLogin();
                    sendRooms();
                }
                case CREATE_ROOM -> handleCreateRoom(parts);
                case JOIN_ROOM -> handleJoinRoom(parts);
                case LEAVE_ROOM -> handleLeaveRoom();
                case SEND_MESSAGE -> handleSendMessage(parts);
                case SEND_PRIVATE_MESSAGE -> handleSendPrivateMessage(parts);
                case GET_HISTORY -> handleHistory();
                case GET_ONLINE_USERS -> sendOnlineUsers();
                case PING -> sendLine("PONG");
                case INVITE_ROOM -> handleInviteRoom(parts);
                case KICK_ROOM -> handleKickRoom(parts);
                case FRIEND_SEARCH -> handleFriendSearch(parts);
                case FRIEND_REQUEST -> handleFriendRequest(parts);
                case FRIEND_PENDING_LIST -> handleFriendPendingList();
                case FRIEND_ACCEPT -> handleFriendAccept(parts);
                case FRIEND_REJECT -> handleFriendReject(parts);
                case FRIEND_LIST -> handleFriendList();
                // Protocol v2 extensions
                case AUTH_TOKEN -> handleAuthToken(parts);
                case LIST_GUILDS -> handleListGuilds();
                case CREATE_GUILD -> handleCreateGuild(parts);
                case CREATE_CHANNEL -> handleCreateChannel(parts);
                case EDIT_MSG -> handleEditMsg(parts);
                case DELETE_MSG -> handleDeleteMsg(parts);
                case EDIT_MESSAGE -> {
                    handleEditMsg(parts);
                    if (!requestFailed) sendLine("OK|EDIT_MESSAGE|" + parts[1]);
                }
                case DELETE_MESSAGE -> {
                    handleDeleteMsg(parts);
                    if (!requestFailed) sendLine("OK|DELETE_MESSAGE|" + parts[1]);
                }
                case RENAME_ROOM -> handleRenameRoom(parts);
                case DELETE_ROOM -> handleDeleteRoom(parts);
                case SET_ROOM_DESC -> handleSetRoomDesc(parts);
                case TRANSFER_OWNER -> handleTransferOwner(parts);
                case SET_MEMBER_ROLE -> handleSetMemberRole(parts);
                case HISTORY_PAGE -> handleHistoryPage(parts);
                case REACT_MSG -> handleReactMsg(parts);
                case PIN_MSG -> handlePinMsg(parts);
                case MARK_READ -> handleMarkRead(parts);
                case PRESENCE -> handlePresence(parts);
                case SIGNAL_OFFER -> handleSignal(parts, "SIGNAL_OFFER");
                case SIGNAL_ANSWER -> handleSignal(parts, "SIGNAL_ANSWER");
                case SIGNAL_ICE -> handleSignal(parts, "SIGNAL_ICE");
            }
        } catch (Exception e) {
            ProtocolErrorCode code = classifyError(e);
            sendError(
                    code,
                    code == ProtocolErrorCode.INTERNAL_ERROR || code == ProtocolErrorCode.DATABASE_ERROR
                            ? "Không thể xử lý yêu cầu. Vui lòng thử lại."
                            : (e.getMessage() == null ? "Yêu cầu không hợp lệ." : e.getMessage())
            );
        } finally {
            // ACK means the application handler completed successfully; it is
            // not a TCP ACK and does not imply that a recipient read the data.
            if (activeRequestId != null && !requestFailed) {
                sendLine("ACK|" + activeRequestId + "|OK");
            }
            activeRequestId = null;
        }
    }

    private void handleRegister(String[] parts) throws Exception {
        requireArgs(parts, 4);

        String username = ProtocolHandler.decode(parts[1]).trim();
        String password = ProtocolHandler.decode(parts[2]);
        String displayName = ProtocolHandler.decode(parts[3]).trim();

        if (username.isBlank() || username.length() > MAX_USERNAME_LENGTH) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Username không hợp lệ.");
            return;
        }

        if (password.isBlank()) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Password không được để trống.");
            return;
        }

        if (displayName.isBlank()
                || displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Tên hiển thị không hợp lệ.");
            return;
        }

        User created = server.getAuthService().register(
                username, password, displayName
        );

        if (created == null) {
            sendError(
                    ProtocolErrorCode.USERNAME_TAKEN,
                    "Username đã tồn tại hoặc dữ liệu không hợp lệ."
            );
        } else {
            sendLine(
                    "OK|REGISTERED|"
                            + ProtocolHandler.encode("Đăng ký thành công.")
            );
        }
    }

    private void handleLogin(String[] parts) throws Exception {
        requireArgs(parts, 3);

        if (user != null) {
            sendError(
                    ProtocolErrorCode.ALREADY_AUTHENTICATED,
                    "Bạn đã đăng nhập."
            );
            return;
        }

        String username = ProtocolHandler.decode(parts[1]).trim();
        String password = ProtocolHandler.decode(parts[2]);

        if (username.isBlank() || username.length() > MAX_USERNAME_LENGTH) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Username không hợp lệ.");
            return;
        }

        User found = server.getAuthService().login(username, password);

        if (found == null) {
            sendError(
                    ProtocolErrorCode.AUTH_FAILED,
                    "Sai username hoặc password."
            );
            return;
        }

        /*
         * tryAdd cần thực hiện kiểm tra và thêm phiên đăng nhập một cách
         * nguyên tử để tránh hai client TCP đăng nhập cùng tài khoản đồng thời.
         */
        if (!server.getClientManager().tryAdd(found, this)) {
            sendError(
                    ProtocolErrorCode.ALREADY_ONLINE,
                    "Tài khoản đang được đăng nhập ở nơi khác."
            );
            return;
        }

        completeLoginSuccess(found);
    }

    private void handleAuthToken(String[] parts) {
        requireArgs(parts, 2);
        if (user != null) {
            sendError(ProtocolErrorCode.ALREADY_AUTHENTICATED, "Bạn đã đăng nhập.");
            return;
        }
        String token = parts[1].trim();
        User found = server.getAuthService().verifyJwt(token);
        if (found == null) {
            sendError(ProtocolErrorCode.INVALID_TOKEN, "JWT token không hợp lệ hoặc đã hết hạn.");
            return;
        }
        server.getClientManager().add(found, this);
        completeLoginSuccess(found);
    }

    private void completeLoginSuccess(User authenticatedUser) {
        this.user = authenticatedUser;
        this.user.setStatus("ONLINE");

        sendLine(
                "OK|LOGIN|"
                        + user.getId()
                        + "|"
                        + ProtocolHandler.encode(user.getDisplayName())
        );

        String onlinePacket = "USER_ONLINE|"
                + user.getId()
                + "|"
                + ProtocolHandler.encode(user.getDisplayName());
        boolean invisible = "OFFLINE".equalsIgnoreCase(user.getPresenceStatus());
        if (!invisible) broadcastGlobal(onlinePacket);

        Map<String, Object> wsPresence = new LinkedHashMap<>();
        wsPresence.put("userId", user.getId());
        wsPresence.put("username", user.getUsername());
        wsPresence.put("displayName", user.getDisplayName());
        wsPresence.put("status", user.getPresenceStatus());
        wsPresence.put("presenceStatus", user.getPresenceStatus());
        server.getEventBus().publishGlobal(null, "PRESENCE_UPDATED", wsPresence, user.getId());
    }

    private void handleCreateRoom(String[] parts) throws Exception {
        requireLogin();
        requireArgs(parts, 2);

        String name = ProtocolHandler.decode(parts[1]).trim();

        if (name.isBlank() || name.length() > MAX_ROOM_NAME_LENGTH) {
            sendError(
                    ProtocolErrorCode.INVALID_ROOM_NAME,
                    "Tên phòng không hợp lệ hoặc vượt quá 100 ký tự."
            );
            return;
        }

        Room room = server.getRoomService().createRoom(name, user.getId());

        if (room == null) {
            sendError(
                    ProtocolErrorCode.ROOM_NAME_TAKEN,
                    "Tên phòng đã tồn tại."
            );
        } else {
            sendLine(
                    "OK|ROOM_CREATED|"
                            + room.getId()
                            + "|"
                            + ProtocolHandler.encode(room.getName())
            );
            sendRooms();
            server.getEventBus().publishGlobal(null, "CHANNEL_CREATED", room, null);
        }
    }

    private void handleJoinRoom(String[] parts) {
        requireLogin();
        requireArgs(parts, 2);

        int roomId = parseId(parts[1], "ID phòng");

        Room room = server.getRoomService().findRoom(roomId);

        if (room == null) {
            sendError(
                    ProtocolErrorCode.ROOM_NOT_FOUND,
                    "Không tìm thấy phòng."
            );
            return;
        }

        // Không thực hiện join lại nếu client đang ở chính phòng đó.
        if (currentRoomId != null && currentRoomId == roomId) {
            sendError(
                    ProtocolErrorCode.INVALID_STATE,
                    "Bạn đang ở trong phòng này."
            );
            return;
        }

        leaveCurrentRoom(false);

        currentRoomId = roomId;
        server.getRoomService().joinRoom(roomId, user.getId());
        room.getMemberIds().add(user.getId());
        server.getRoomManager().join(room, this);

        sendLine(
                "OK|JOIN_ROOM|"
                        + roomId
                        + "|"
                        + ProtocolHandler.encode(room.getName())
        );

        handleHistory();

        broadcastRoom(
                "SYSTEM|"
                        + ProtocolHandler.encode(
                                user.getDisplayName() + " đã tham gia phòng."
                        ),
                false
        );

        sendRoomMembers();
        for (ClientHandler c : server.getRoomManager().clientsIn(roomId)) {
            if (c != this) {
                c.sendRoomMembers();
            }
        }
    }

    private void handleLeaveRoom() {
        requireLogin();
        leaveCurrentRoom(true);
    }

    private void handleSendMessage(String[] parts) throws Exception {
        requireLogin();

        if (currentRoomId == null) {
            sendError(
                    ProtocolErrorCode.NOT_IN_ROOM,
                    "Bạn chưa vào phòng."
            );
            return;
        }

        requireArgs(parts, 2);

        // Kiểm tra membership để ngăn gửi tin vào phòng không còn tham gia.
        Room room = server.getRoomService().findRoom(currentRoomId);

        if (room == null || !room.getMemberIds().contains(user.getId())) {
            sendError(
                    ProtocolErrorCode.NOT_IN_ROOM,
                    "Bạn không còn là thành viên của phòng này."
            );
            return;
        }

        String content = ProtocolHandler.decode(parts[1]).trim();

        if (content.isBlank()) {
            sendError(
                    ProtocolErrorCode.BAD_REQUEST,
                    "Nội dung tin nhắn không được để trống."
            );
            return;
        }

        if (content.length() > MAX_MESSAGE_LENGTH) {
            sendError(
                    ProtocolErrorCode.BAD_REQUEST,
                    "Tin nhắn không được vượt quá 5000 ký tự."
            );
            return;
        }

        Message message = server.getChatService().save(
                currentRoomId, user, content
        );

        String packet = "NEW_MESSAGE|"
                + message.getId() + "|"
                + message.getRoomId() + "|"
                + ProtocolHandler.encode(message.getSender()) + "|"
                + ProtocolHandler.encode(message.getContent()) + "|"
                + ProtocolHandler.encode(message.getTimestamp());

        broadcastRoom(packet, true);
        // Also deliver to WebSocket clients via EventBus without duplicating TCP packet
        server.getChatService().broadcastNewMessage(message, false);
    }

    private void handleSendPrivateMessage(String[] parts) {
        requireLogin();
        requireArgs(parts, 3);

        try {
            int targetUserId = parseId(parts[1], "ID người nhận");
            String content = ProtocolHandler.decode(parts[2]).trim();

            if (content.isBlank()) {
                sendError(
                        ProtocolErrorCode.BAD_REQUEST,
                        "Nội dung tin nhắn không được để trống."
                );
                return;
            }

            if (content.length() > MAX_MESSAGE_LENGTH) {
                sendError(
                        ProtocolErrorCode.BAD_REQUEST,
                        "Tin nhắn không được vượt quá 5000 ký tự."
                );
                return;
            }

            if (targetUserId == user.getId()) {
                sendError(
                        ProtocolErrorCode.INVALID_RECIPIENT,
                        "Không thể tự gửi tin nhắn cho chính mình."
                );
                return;
            }

            String timeStr = java.time.LocalTime.now().toString();

            if (timeStr.length() > 8) {
                timeStr = timeStr.substring(0, 8);
            }

            String packet = "NEW_PRIVATE_MESSAGE|"
                    + user.getId() + "|"
                    + ProtocolHandler.encode(user.getDisplayName()) + "|"
                    + ProtocolHandler.encode(content) + "|"
                    + ProtocolHandler.encode(timeStr);

            boolean sentTcp = server.getClientManager().sendToUser(
                    targetUserId, packet
            );
            boolean onlineWs = server.getEventBus().isUserOnline(targetUserId);

            // Persist 1-to-1 DM in PostgreSQL and notify any WebSocket session
            try {
                var conv = server.getDirectMessageService().getOrCreateConversation(user.getId(), targetUserId);
                if (conv != null) {
                    var dm = server.getDirectMessageService().getDirectMessageDAO()
                            .saveDirectMessage(conv.getId(), user.getId(), content, null, null);
                    if (dm != null) {
                        server.getEventBus().publishToUser(user.getId(), null, "DM_MESSAGE_CREATED", dm);
                        server.getEventBus().publishToUser(targetUserId, null, "DM_MESSAGE_CREATED", dm);
                    }
                }
            } catch (Exception ignored) {}

            if (!sentTcp && !onlineWs) {
                sendError(
                        ProtocolErrorCode.USER_OFFLINE,
                        "Người dùng này hiện không online."
                );
            } else {
                sendLine("PRIVATE_MESSAGE_SENT|" + targetUserId);
            }
        } catch (NumberFormatException e) {
            sendError(
                    ProtocolErrorCode.BAD_REQUEST,
                    "ID người nhận không hợp lệ."
            );
        }
    }

    private void handleHistory() {
        requireLogin();

        if (currentRoomId == null) {
            sendError(
                    ProtocolErrorCode.NOT_IN_ROOM,
                    "Bạn chưa vào phòng."
            );
            return;
        }

        List<Message> history = server.getChatService().history(currentRoomId);

        sendLine("HISTORY_BEGIN|" + currentRoomId);

        for (Message m : history) {
            sendLine(
                    "HISTORY_ITEM|"
                            + m.getId()
                            + "|"
                            + ProtocolHandler.encode(m.getSender())
                            + "|"
                            + ProtocolHandler.encode(m.getContent())
                            + "|"
                            + ProtocolHandler.encode(m.getTimestamp())
                            + "|" + (m.isEdited() ? "EDITED" : "NORMAL")
            );
        }

        sendLine("HISTORY_END");
    }

    private void sendRooms() {
        StringBuilder sb = new StringBuilder("ROOMS");
        List<Room> rooms = (user != null)
                ? server.getRoomService().listRoomsForUser(user.getId())
                : server.getRoomService().listRooms();

        for (Room r : rooms) {
            sb.append('|')
                    .append(r.getId())
                    .append(',')
                    .append(ProtocolHandler.encode(r.getName()))
                    .append(',')
                    .append(ProtocolHandler.encode(r.getTopic()))
                    .append(',')
                    .append(user != null ? java.util.Objects.toString(
                            server.getRoomService().getMemberRole(r.getId(), user.getId()), "MEMBER") : "MEMBER");
        }

        sendLine(sb.toString());
    }

    private void sendOnlineUsers() {
        StringBuilder sb = new StringBuilder("ONLINE_USERS");
        Map<Integer, User> merged = new LinkedHashMap<>();
        for (User u : server.getClientManager().onlineUsers()) {
            merged.put(u.getId(), u);
        }
        for (User u : server.getEventBus().getOnlineUsers()) {
            merged.putIfAbsent(u.getId(), u);
        }

        for (User u : merged.values()) {
            sb.append('|')
                    .append(u.getId())
                    .append(',')
                    .append(ProtocolHandler.encode(u.getDisplayName()));
        }

        sendLine(sb.toString());
    }

    private void sendRoomMembers() {
        if (currentRoomId == null) return;

        Room room = server.getRoomService().findRoom(currentRoomId);
        StringBuilder sb = new StringBuilder("ROOM_MEMBERS");

        if (room != null) {
            // Lấy toàn bộ thành viên phòng từ CSDL
            List<User> members = server.getRoomService().getRoomMembers(currentRoomId);
            for (User member : members) {
                boolean isOnline = isUserOnlineAnywhere(member.getId());
                String role = server.getRoomService().getMemberRole(currentRoomId, member.getId());
                if (role == null) role = "MEMBER";

                sb.append('|')
                        .append(member.getId())
                        .append(',')
                        .append(ProtocolHandler.encode(member.getDisplayName()))
                        .append(',')
                        .append(role.toUpperCase())
                        .append(',')
                        .append(isOnline ? "ONLINE" : "OFFLINE");
            }
        }

        sendLine(sb.toString());
    }

    private boolean isUserOnlineAnywhere(int userId) {
        return server.getClientManager().isOnline(userId) || server.getEventBus().isUserOnline(userId);
    }

    private void broadcastRoom(String packet, boolean includeSelf) {
        if (currentRoomId == null) return;

        for (ClientHandler c :
                server.getRoomManager().clientsIn(currentRoomId)) {
            if (includeSelf || c != this) {
                c.sendLine(packet);
            }
        }
    }

    private void broadcastGlobal(String packet) {
        for (ClientHandler c : server.getClientManager().all()) {
            c.sendLine(packet);
        }
    }

    private void leaveCurrentRoom(boolean announce) {
        if (currentRoomId == null) return;

        Room room = server.getRoomService().findRoom(currentRoomId);

        server.getRoomManager().leave(currentRoomId, this);

        if (room != null && user != null) {
            room.getMemberIds().remove(user.getId());
        }

        if (announce && user != null) {
            broadcastRoom(
                    "SYSTEM|"
                            + ProtocolHandler.encode(
                                    user.getDisplayName() + " đã rời phòng."
                            ),
                    false
            );
        }

        currentRoomId = null;

        if (user != null) {
            sendOnlineUsers();
        }
    }

    private void cleanup() {
        if (server.getEventBus() != null) {
            server.getEventBus().unregisterSession(tcpSession);
        }
        leaveCurrentRoom(false);

        if (user != null) {
            int uid = user.getId();
            String dName = user.getDisplayName();
            user.setOnline(false);
            server.getClientManager().remove(user, this);

            if (!server.getEventBus().isUserOnline(uid)) {
                server.getUserDAO().updateUserStatus(uid, "OFFLINE");
                broadcastGlobal(
                        "USER_OFFLINE|"
                                + uid
                                + "|"
                                + ProtocolHandler.encode(dName)
                );
                Map<String, Object> wsPresence = new LinkedHashMap<>();
                wsPresence.put("userId", uid);
                wsPresence.put("displayName", dName);
                wsPresence.put("status", "OFFLINE");
                server.getEventBus().publishGlobal(null, "PRESENCE_UPDATED", wsPresence, uid);
            }
        }

        try {
            connection.close();
        } catch (IOException ignored) {
        }
    }

    private int parseId(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " không hợp lệ.");
        }
        // Thử parse trực tiếp số nguyên
        try {
            int id = Integer.parseInt(value.trim());
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {}

        // Nếu client gửi dạng Base64 encode
        try {
            String decoded = ProtocolHandler.decode(value.trim());
            int id = Integer.parseInt(decoded.trim());
            if (id > 0) return id;
        } catch (Exception ignored) {}

        throw new IllegalArgumentException(fieldName + " không hợp lệ.");
    }

    private void requireLogin() {
        if (user == null) {
            throw new IllegalStateException("Bạn chưa đăng nhập.");
        }
    }

    private void requireArgs(String[] parts, int count) {
        if (parts.length < count) {
            throw new IllegalArgumentException("Thiếu tham số.");
        }
    }

    private void sendError(ProtocolErrorCode code, String message) {
        requestFailed = true;

        String requestId = activeRequestId == null
                ? ""
                : activeRequestId;

        sendLine(
                "ERROR|"
                        + ProtocolHandler.encode(message)
                        + "|"
                        + requestId
                        + "|"
                        + code.name()
        );
    }

    private ProtocolErrorCode classifyError(Exception error) {
        if (error instanceof NumberFormatException
                || error instanceof IllegalArgumentException) {
            return ProtocolErrorCode.BAD_REQUEST;
        }

        if (error instanceof IllegalStateException) {
            String message = error.getMessage();

            if (message != null && message.contains("chưa đăng nhập")) {
                return ProtocolErrorCode.AUTH_REQUIRED;
            }

            return ProtocolErrorCode.INVALID_STATE;
        }

        if (error instanceof java.sql.SQLException) {
            return ProtocolErrorCode.DATABASE_ERROR;
        }

        return ProtocolErrorCode.INTERNAL_ERROR;
    }

    /* =========================================================================
     * TÍNH NĂNG A: QUẢN LÝ NHÓM CHAT (GROUP CHAT MANAGEMENT)
     * ========================================================================= */

    private void handleInviteRoom(String[] parts) {
        requireLogin();
        requireArgs(parts, 3);

        int roomId = parseId(parts[1], "ID phòng");
        int targetUserId = parseId(parts[2], "ID người được mời");

        Room room = server.getRoomService().findRoom(roomId);
        if (room == null) {
            sendError(ProtocolErrorCode.ROOM_NOT_FOUND, "Không tìm thấy phòng.");
            return;
        }

        // Kiểm tra quyền: Chỉ thành viên của phòng mới được mời, ưu tiên kiểm tra quyền
        if (!room.getMemberIds().contains(user.getId())) {
            sendError(ProtocolErrorCode.PERMISSION_DENIED, "Bạn không phải thành viên của phòng này để mời người khác.");
            return;
        }

        User targetUser = server.getUserDAO().findById(targetUserId);
        if (targetUser == null) {
            sendError(ProtocolErrorCode.USER_NOT_FOUND, "Không tìm thấy người dùng được mời.");
            return;
        }

        if (room.getMemberIds().contains(targetUserId)) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Người dùng này đã là thành viên của phòng.");
            return;
        }

        // Thêm vào CSDL và bộ nhớ
        server.getRoomService().addMember(roomId, targetUserId, "MEMBER");
        room.getMemberIds().add(targetUserId);

        sendLine("OK|INVITE_ROOM|" + roomId + "|" + targetUserId + "|" + ProtocolHandler.encode(targetUser.getDisplayName()));

        // Thông báo cho phòng
        broadcastRoom("SYSTEM|" + ProtocolHandler.encode(targetUser.getDisplayName() + " đã được " + user.getDisplayName() + " mời vào phòng."), true);

        // Cập nhật lại danh sách thành viên cho các client trong phòng
        sendRoomMembers();

        // Gửi thông báo và cập nhật danh sách phòng cho người được mời nếu đang online
        var targetHandler = server.getClientManager().get(targetUserId);
        if (targetHandler != null) {
            targetHandler.sendLine("ROOM_INVITATION|" + roomId + "|" + ProtocolHandler.encode(room.getName()) + "|" + ProtocolHandler.encode(user.getDisplayName()));
            targetHandler.sendRooms();
        }
        server.getEventBus().publishToUser(targetUserId, null, "ROOM_INVITED", room);
    }

    private void handleKickRoom(String[] parts) {
        requireLogin();
        requireArgs(parts, 3);

        int roomId = parseId(parts[1], "ID phòng");
        int targetUserId = parseId(parts[2], "ID người cần xóa");

        Room room = server.getRoomService().findRoom(roomId);
        if (room == null) {
            sendError(ProtocolErrorCode.ROOM_NOT_FOUND, "Không tìm thấy phòng.");
            return;
        }

        // Kiểm tra quyền: CHỈ TRƯỞNG NHÓM (OWNER) mới có quyền kick/xóa thành viên
        if (!server.getRoomService().isRoomOwner(roomId, user.getId())) {
            sendError(ProtocolErrorCode.PERMISSION_DENIED, "Chỉ Trưởng nhóm (OWNER) mới có quyền xóa thành viên khỏi phòng.");
            return;
        }

        if (targetUserId == user.getId()) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Trưởng nhóm không thể tự kick chính mình. Hãy dùng tính năng rời phòng hoặc chuyển quyền.");
            return;
        }

        if (!room.getMemberIds().contains(targetUserId)) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Người dùng này không có trong phòng.");
            return;
        }

        // Xóa khỏi database và danh sách bộ nhớ
        if (!server.getRoomService().removeMember(roomId, targetUserId)) {
            sendError(ProtocolErrorCode.DATABASE_ERROR, "Failed to remove the member from the channel.");
            return;
        }
        server.getEventBus().clearCurrentRoomForUser(targetUserId, roomId);
        room.getMemberIds().remove(targetUserId);

        User targetUser = server.getUserDAO().findById(targetUserId);
        String targetName = (targetUser != null) ? targetUser.getDisplayName() : ("User#" + targetUserId);

        sendLine("OK|KICK_ROOM|" + roomId + "|" + targetUserId);

        broadcastRoom("SYSTEM|" + ProtocolHandler.encode(targetName + " đã bị Trưởng nhóm xóa khỏi phòng."), true);

        // Xử lý client bị kick nếu đang ở trong phòng này
        var targetHandler = server.getClientManager().get(targetUserId);
        if (targetHandler != null) {
            if (targetHandler.currentRoomId != null && targetHandler.currentRoomId == roomId) {
                targetHandler.server.getRoomManager().leave(roomId, targetHandler);
                targetHandler.currentRoomId = null;
                targetHandler.sendLine("SYSTEM|" + ProtocolHandler.encode("Bạn đã bị Trưởng nhóm mời ra khỏi phòng " + room.getName() + "."));
                targetHandler.sendLine("OK|LEAVE_ROOM|" + roomId);
            }
            targetHandler.sendRooms();
        }

        Map<String, Object> kickPayload = new LinkedHashMap<>();
        kickPayload.put("roomId", roomId);
        kickPayload.put("roomName", room.getName());
        server.getEventBus().publishToUser(targetUserId, null, "ROOM_KICKED", kickPayload);

        sendRoomMembers();
    }

    /* =========================================================================
     * TÍNH NĂNG B: QUẢN LÝ BẠN BÈ (FRIEND MANAGEMENT)
     * ========================================================================= */

    private void handleFriendSearch(String[] parts) {
        requireLogin();
        requireArgs(parts, 2);

        String query = ProtocolHandler.decode(parts[1]).trim();
        List<User> results = server.getFriendService().searchUsers(user.getId(), query);

        StringBuilder sb = new StringBuilder("FRIEND_SEARCH_RESULT");
        for (User u : results) {
            boolean isFriend = server.getFriendService().areFriends(user.getId(), u.getId());
            boolean isOnline = isUserOnlineAnywhere(u.getId());
            sb.append('|')
                    .append(u.getId())
                    .append(',')
                    .append(ProtocolHandler.encode(u.getUsername()))
                    .append(',')
                    .append(ProtocolHandler.encode(u.getDisplayName()))
                    .append(',')
                    .append(isFriend ? "FRIEND" : "NONE")
                    .append(',')
                    .append(isOnline ? "ONLINE" : "OFFLINE");
        }
        sendLine(sb.toString());
    }

    private void handleFriendRequest(String[] parts) {
        requireLogin();
        requireArgs(parts, 2);

        int targetUserId = parseId(parts[1], "ID người dùng");

        if (targetUserId == user.getId()) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Không thể tự gửi lời mời kết bạn cho chính mình.");
            return;
        }

        String result = server.getFriendService().sendFriendRequest(user.getId(), targetUserId);

        switch (result) {
            case "OK" -> {
                sendLine("OK|FRIEND_REQUEST|" + targetUserId);
                // Gửi thông báo thời gian thực đến người nhận nếu đang online
                var targetHandler = server.getClientManager().get(targetUserId);
                if (targetHandler != null) {
                    targetHandler.sendLine("INCOMING_FRIEND_REQUEST|" + user.getId() + "|" + ProtocolHandler.encode(user.getUsername()) + "|" + ProtocolHandler.encode(user.getDisplayName()));
                }
                Map<String, Object> wsPayload = new LinkedHashMap<>();
                wsPayload.put("senderId", user.getId());
                wsPayload.put("senderUsername", user.getUsername());
                wsPayload.put("senderDisplayName", user.getDisplayName());
                server.getEventBus().publishToUser(targetUserId, null, "FRIEND_REQUEST_INCOMING", wsPayload);
            }
            case "SELF_REQUEST" -> sendError(ProtocolErrorCode.BAD_REQUEST, "Không thể tự gửi lời mời kết bạn cho chính mình.");
            case "ALREADY_FRIENDS" -> sendError(ProtocolErrorCode.BAD_REQUEST, "Hai bạn đã là bạn bè từ trước.");
            case "ALREADY_REQUESTED" -> sendError(ProtocolErrorCode.BAD_REQUEST, "Lời mời kết bạn đã được gửi trước đó và đang chờ phản hồi.");
            case "INCOMING_EXISTS" -> sendError(ProtocolErrorCode.BAD_REQUEST, "Người dùng này đã gửi lời mời cho bạn trước đó. Bạn có thể chấp nhận lời mời.");
            default -> sendError(ProtocolErrorCode.DATABASE_ERROR, "Không thể gửi lời mời kết bạn vào lúc này.");
        }
    }

    private void handleFriendPendingList() {
        requireLogin();
        var list = server.getFriendService().getPendingRequests(user.getId());

        StringBuilder sb = new StringBuilder("FRIEND_PENDING_LIST");
        for (var item : list) {
            sb.append('|')
                    .append(item.requestId())
                    .append(',')
                    .append(item.senderId())
                    .append(',')
                    .append(ProtocolHandler.encode(item.senderUsername()))
                    .append(',')
                    .append(ProtocolHandler.encode(item.senderDisplayName()))
                    .append(',')
                    .append(ProtocolHandler.encode(item.createdAt() != null ? item.createdAt().toString() : ""));
        }
        sendLine(sb.toString());
    }

    private void handleFriendAccept(String[] parts) {
        requireLogin();
        requireArgs(parts, 2);

        int targetId = parseId(parts[1], "ID yêu cầu hoặc người gửi");

        // Hỗ trợ cả requestId hoặc senderId
        boolean success = server.getFriendService().acceptRequest(targetId, user.getId());
        if (!success) {
            success = server.getFriendService().acceptRequestFromUser(targetId, user.getId());
        }

        if (success) {
            sendLine("OK|FRIEND_ACCEPT|" + targetId);
            handleFriendList();

            // Gửi thông báo realtime cho người bạn được chấp nhận nếu đang online
            var otherHandler = server.getClientManager().get(targetId);
            if (otherHandler != null) {
                otherHandler.sendLine("FRIEND_ACCEPTED|" + user.getId() + "|" + ProtocolHandler.encode(user.getDisplayName()));
                otherHandler.handleFriendList();
            }
            Map<String, Object> wsPayload = new LinkedHashMap<>();
            wsPayload.put("userId", user.getId());
            wsPayload.put("displayName", user.getDisplayName());
            server.getEventBus().publishToUser(targetId, null, "FRIEND_ACCEPTED", wsPayload);
        } else {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Lời mời kết bạn không tồn tại hoặc đã được xử lý.");
        }
    }

    private void handleFriendReject(String[] parts) {
        requireLogin();
        requireArgs(parts, 2);

        int requestId = parseId(parts[1], "ID yêu cầu");
        boolean success = server.getFriendService().rejectRequest(requestId, user.getId());

        if (success) {
            sendLine("OK|FRIEND_REJECT|" + requestId);
            handleFriendPendingList();
        } else {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Không thể từ chối lời mời hoặc lời mời không tồn tại.");
        }
    }

    private void handleFriendList() {
        requireLogin();
        List<User> friends = server.getFriendService().getFriends(user.getId());

        StringBuilder sb = new StringBuilder("FRIEND_LIST");
        for (User f : friends) {
            boolean isOnline = isUserOnlineAnywhere(f.getId());
            sb.append('|')
                    .append(f.getId())
                    .append(',')
                    .append(ProtocolHandler.encode(f.getUsername()))
                    .append(',')
                    .append(ProtocolHandler.encode(f.getDisplayName()))
                    .append(',')
                    .append(isOnline ? "ONLINE" : "OFFLINE");
        }
        sendLine(sb.toString());
    }

    /* =========================================================================
     * PROTOCOL V2 EXTENSIONS (GUILDS, RICH MESSAGES, PRESENCE, WEBRTC SIGNALING)
     * ========================================================================= */

    private void handleListGuilds() {
        requireLogin();
        List<Guild> guilds = server.getGuildService().listGuildsForUser(user.getId());
        StringBuilder sb = new StringBuilder("GUILDS");
        for (Guild g : guilds) {
            sb.append('|')
                    .append(g.getId())
                    .append(':')
                    .append(ProtocolHandler.encode(g.getName()))
                    .append(':')
                    .append(ProtocolHandler.encode(g.getIconText()))
                    .append(':')
                    .append(g.getOwnerId());
        }
        sendLine(sb.toString());
    }

    private void handleCreateGuild(String[] parts) {
        requireLogin();
        requireArgs(parts, 2);
        String name = ProtocolHandler.decode(parts[1]).trim();
        if (name.isBlank() || name.length() > MAX_ROOM_NAME_LENGTH) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Tên máy chủ không hợp lệ.");
            return;
        }
        Guild created = server.getGuildService().createGuild(name, user.getId());
        if (created == null) {
            sendError(ProtocolErrorCode.DATABASE_ERROR, "Không thể tạo máy chủ.");
        }
    }

    private void handleCreateChannel(String[] parts) {
        requireLogin();
        requireArgs(parts, 4);
        int guildId = parseId(parts[1], "ID Guild");
        String name = ProtocolHandler.decode(parts[2]).trim();
        String type = parts[3].trim().toLowerCase();
        if (!"voice".equals(type)) type = "text";

        if (name.isBlank() || name.length() > MAX_ROOM_NAME_LENGTH) {
            sendError(ProtocolErrorCode.INVALID_ROOM_NAME, "Tên kênh không hợp lệ.");
            return;
        }
        Guild g = server.getGuildService().findById(guildId);
        if (g == null) {
            sendError(ProtocolErrorCode.GUILD_NOT_FOUND, "Không tìm thấy Guild.");
            return;
        }
        if (g.getOwnerId() != user.getId() && !server.getGuildService().isAdminOrOwner(guildId, user.getId())) {
            sendError(ProtocolErrorCode.FORBIDDEN, "Bạn không có quyền tạo kênh trong Guild này.");
            return;
        }
        Room channel = server.getGuildService().createChannel(guildId, name, type, user.getId());
        if (channel == null) {
            sendError(ProtocolErrorCode.ROOM_NAME_TAKEN, "Không thể tạo kênh hoặc tên kênh đã tồn tại.");
        }
    }

    private void handleEditMsg(String[] parts) {
        requireLogin();
        requireArgs(parts, 3);
        int messageId = parseId(parts[1], "ID tin nhắn");
        String newContent = ProtocolHandler.decode(parts[2]).trim();
        if (newContent.isBlank() || newContent.length() > MAX_MESSAGE_LENGTH) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Nội dung tin nhắn chỉnh sửa không hợp lệ.");
            return;
        }
        Message existing = server.getChatService().findById(messageId);
        if (existing == null || existing.getDeletedAt() != null) {
            sendError(ProtocolErrorCode.MESSAGE_NOT_FOUND, "Không tìm thấy tin nhắn.");
            return;
        }
        if (existing.getSenderId() != user.getId()) {
            sendError(ProtocolErrorCode.FORBIDDEN, "Chỉ người gửi mới có quyền chỉnh sửa tin nhắn.");
            return;
        }
        if (requireRoomMember(existing.getRoomId()) == null) return;
        Message updated = server.getChatService().editMessage(messageId, user.getId(), newContent);
        if (updated == null) {
            sendError(ProtocolErrorCode.DATABASE_ERROR, "Không thể chỉnh sửa tin nhắn.");
        }
    }

    private void handleDeleteMsg(String[] parts) {
        requireLogin();
        requireArgs(parts, 2);
        int messageId = parseId(parts[1], "ID tin nhắn");
        Message existing = server.getChatService().findById(messageId);
        if (existing == null || existing.getDeletedAt() != null) {
            sendError(ProtocolErrorCode.MESSAGE_NOT_FOUND, "Không tìm thấy tin nhắn.");
            return;
        }
        boolean isAdminOrOwner = server.getRoomService().isAdminOrOwner(existing.getRoomId(), user.getId());
        if (requireRoomMember(existing.getRoomId()) == null) return;
        if (existing.getSenderId() != user.getId() && !isAdminOrOwner) {
            sendError(ProtocolErrorCode.FORBIDDEN, "Bạn không có quyền xóa tin nhắn này.");
            return;
        }
        boolean deleted = server.getChatService().deleteMessage(messageId, user.getId(), isAdminOrOwner);
        if (!deleted) {
            sendError(ProtocolErrorCode.DATABASE_ERROR, "Không thể xóa tin nhắn.");
        }
    }

    private void handleReactMsg(String[] parts) {
        requireLogin();
        requireArgs(parts, 3);
        int messageId = parseId(parts[1], "ID tin nhắn");
        String emoji = ProtocolHandler.decode(parts[2]).trim();
        if (emoji.isBlank()) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Biểu tượng cảm xúc không hợp lệ.");
            return;
        }
        Message existing = server.getChatService().findById(messageId);
        if (existing == null || existing.getDeletedAt() != null) {
            sendError(ProtocolErrorCode.MESSAGE_NOT_FOUND, "Không tìm thấy tin nhắn.");
            return;
        }
        if (requireRoomMember(existing.getRoomId()) == null) return;
        List<ReactionSummary> updated = server.getChatService().toggleReaction(messageId, user.getId(), emoji);
        if (updated == null) {
            sendError(ProtocolErrorCode.DATABASE_ERROR, "Không thể cập nhật cảm xúc.");
        }
    }

    private void handlePinMsg(String[] parts) {
        requireLogin();
        requireArgs(parts, 3);
        int messageId = parseId(parts[1], "ID tin nhắn");
        boolean pinned = "1".equals(parts[2].trim()) || "true".equalsIgnoreCase(parts[2].trim());
        Message existing = server.getChatService().findById(messageId);
        if (existing == null || existing.getDeletedAt() != null) {
            sendError(ProtocolErrorCode.MESSAGE_NOT_FOUND, "Không tìm thấy tin nhắn.");
            return;
        }
        if (requireRoomMember(existing.getRoomId()) == null) return;
        Message updated = server.getChatService().pinMessage(messageId, pinned);
        if (updated == null) {
            sendError(ProtocolErrorCode.DATABASE_ERROR, "Không thể ghim/bỏ ghim tin nhắn.");
        }
    }

    private void handleMarkRead(String[] parts) {
        requireLogin();
        requireArgs(parts, 3);
        int roomId = parseId(parts[1], "ID phòng");
        int lastMsgId = parseId(parts[2], "ID tin nhắn");
        if (requireRoomMember(roomId) == null) return;
        Room room = server.getRoomService().findRoom(roomId);
        if (room == null) {
            sendError(ProtocolErrorCode.CHANNEL_NOT_FOUND, "Không tìm thấy phòng/kênh.");
            return;
        }
        server.getRoomService().markRead(user.getId(), roomId, lastMsgId);
    }

    public void clearDeletedRoom(int roomId) {
        server.getRoomManager().leave(roomId, this);
        if (Integer.valueOf(roomId).equals(currentRoomId)) currentRoomId = null;
        if (user != null) sendRooms();
    }

    private Room requireRoomMember(int roomId) {
        Room room = server.getRoomService().findRoom(roomId);
        if (room == null || server.getRoomService().getMemberRole(roomId, user.getId()) == null) {
            sendError(ProtocolErrorCode.PERMISSION_DENIED, "Bạn không có quyền truy cập phòng này.");
            return null;
        }
        return room;
    }

    private void handleRenameRoom(String[] parts) {
        requireLogin();
        requireArgs(parts, 3);
        int roomId = parseId(parts[1], "ID phòng");
        Room room = requireRoomMember(roomId);
        if (room == null) return;
        String name = ProtocolHandler.decode(parts[2]).trim();
        if (name.isBlank() || name.length() > MAX_ROOM_NAME_LENGTH) {
            sendError(ProtocolErrorCode.INVALID_ROOM_NAME, "Tên phòng không hợp lệ.");
            return;
        }
        if (!server.getRoomService().isAdminOrOwner(roomId, user.getId())) {
            sendError(ProtocolErrorCode.PERMISSION_DENIED, "Chỉ OWNER hoặc ADMIN mới được đổi tên phòng.");
            return;
        }
        Room updated = server.getRoomService().updateRoom(roomId, user.getId(), name, room.getTopic());
        if (updated == null) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Không thể cập nhật phòng.");
        } else {
            sendLine("OK|RENAME_ROOM|" + roomId);
            refreshRoomLists();
        }
    }

    private void handleSetRoomDesc(String[] parts) {
        requireLogin();
        requireArgs(parts, 3);
        int roomId = parseId(parts[1], "ID phòng");
        Room room = requireRoomMember(roomId);
        if (room == null) return;
        String topic = ProtocolHandler.decode(parts[2]).trim();
        if (topic.length() > 1024) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Mô tả phòng quá dài.");
            return;
        }
        if (!server.getRoomService().isAdminOrOwner(roomId, user.getId())) {
            sendError(ProtocolErrorCode.PERMISSION_DENIED, "Chỉ OWNER hoặc ADMIN mới được sửa mô tả phòng.");
            return;
        }
        if (server.getRoomService().updateRoom(roomId, user.getId(), room.getName(), topic) == null) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Không thể cập nhật phòng.");
        } else {
            sendLine("OK|SET_ROOM_DESC|" + roomId);
        }
    }

    private void handleDeleteRoom(String[] parts) {
        requireLogin();
        requireArgs(parts, 2);
        int roomId = parseId(parts[1], "ID phòng");
        if (!server.getRoomService().isRoomOwner(roomId, user.getId())) {
            sendError(ProtocolErrorCode.PERMISSION_DENIED, "Chỉ OWNER mới được xóa phòng.");
            return;
        }
        if (!server.getRoomService().deleteRoom(roomId, user.getId())) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Không thể xóa phòng.");
        } else {
            sendLine("OK|DELETE_ROOM|" + roomId);
            refreshRoomLists();
        }
    }

    private void handleTransferOwner(String[] parts) {
        requireLogin();
        requireArgs(parts, 3);
        int roomId = parseId(parts[1], "ID phòng");
        int targetId = parseId(parts[2], "ID thành viên");
        if (!server.getRoomService().isRoomOwner(roomId, user.getId())) {
            sendError(ProtocolErrorCode.PERMISSION_DENIED, "Chỉ OWNER mới được chuyển quyền.");
            return;
        }
        if (!server.getRoomService().transferOwnership(roomId, user.getId(), targetId)) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Người nhận phải là thành viên khác trong phòng.");
            return;
        }
        sendLine("OK|TRANSFER_OWNER|" + roomId + "|" + targetId);
        refreshRoomLists();
        refreshRoomMembers(roomId);
    }

    private void handleSetMemberRole(String[] parts) {
        requireLogin();
        requireArgs(parts, 4);
        int roomId = parseId(parts[1], "ID phòng");
        int targetId = parseId(parts[2], "ID thành viên");
        String role = ProtocolHandler.decode(parts[3]).trim().toUpperCase(java.util.Locale.ROOT);
        if (!server.getRoomService().isRoomOwner(roomId, user.getId())) {
            sendError(ProtocolErrorCode.PERMISSION_DENIED, "Chỉ OWNER mới được thiết lập vai trò.");
            return;
        }
        if (!server.getRoomService().setMemberRole(roomId, user.getId(), targetId, role)) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Chỉ đổi ADMIN/MEMBER cho thành viên khác; dùng chuyển quyền cho OWNER.");
            return;
        }
        sendLine("OK|SET_MEMBER_ROLE|" + roomId + "|" + targetId + "|" + role);
        refreshRoomMembers(roomId);
    }

    private void refreshRoomLists() {
        for (ClientHandler client : server.getClientManager().all()) client.sendRooms();
    }

    public void refreshRoomMembers(int roomId) {
        for (ClientHandler client : server.getRoomManager().clientsIn(roomId)) client.sendRoomMembers();
    }

    public void refreshVisibleRooms() {
        if (user != null) sendRooms();
    }

    private void handleHistoryPage(String[] parts) {
        requireLogin();
        requireArgs(parts, 4);
        int roomId = parseId(parts[1], "ID phòng");
        if (requireRoomMember(roomId) == null) return;
        int beforeId = parseId(parts[2], "ID tin nhắn");
        int limit = parseId(parts[3], "Số tin nhắn");
        if (limit <= 0 || limit > 200) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Số tin nhắn phải từ 1 đến 200.");
            return;
        }
        List<Message> history = server.getChatService().historyBefore(roomId, beforeId, limit);
        sendLine("HISTORY_PAGE_BEGIN|" + roomId);
        for (Message message : history) {
            sendLine("HISTORY_PAGE_ITEM|" + message.getId() + "|"
                    + ProtocolHandler.encode(message.getSender()) + "|"
                    + ProtocolHandler.encode(message.getContent()) + "|"
                    + ProtocolHandler.encode(message.getTimestamp()) + "|"
                    + (message.isEdited() ? "EDITED" : "NORMAL"));
        }
        sendLine("HISTORY_PAGE_END|" + roomId);
    }

    private void handlePresence(String[] parts) {
        requireLogin();
        requireArgs(parts, 2);
        String status = parts[1].trim().toUpperCase();
        if (!server.getAuthService().updatePresence(user.getId(), status, null)) {
            sendError(ProtocolErrorCode.BAD_REQUEST, "Trạng thái hiện diện không hợp lệ.");
        } else {
            user.setPresenceStatus(status);
        }
    }

    private void handleSignal(String[] parts, String signalType) {
        requireLogin();
        requireArgs(parts, 4);
        int roomId = parseId(parts[1], "ID phòng");
        int targetUserId = parseId(parts[2], "ID người nhận");
        String b64Data = parts[3].trim();

        if (!isUserOnlineAnywhere(targetUserId)) {
            sendError(ProtocolErrorCode.USER_OFFLINE, "Người nhận tín hiệu hiện không online.");
            return;
        }

        String tcpFrame = signalType + "|" + roomId + "|" + user.getId() + "|" + b64Data;
        String decodedData;
        try {
            decodedData = ProtocolHandler.decode(b64Data);
        } catch (Exception e) {
            decodedData = b64Data;
        }

        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("roomId", roomId);
        wsPayload.put("fromUserId", user.getId());
        wsPayload.put("fromDisplayName", user.getDisplayName());
        wsPayload.put("targetUserId", targetUserId);
        wsPayload.put("data", decodedData);
        wsPayload.put("b64Data", b64Data);

        server.getEventBus().publishToUser(targetUserId, tcpFrame, signalType, wsPayload);
    }
}
