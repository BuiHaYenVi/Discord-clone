package com.discordmini.server.protocol;

import com.discordmini.server.dao.MessageDAO;
import com.discordmini.server.dao.RoomDAO;
import com.discordmini.server.model.Message;
import com.discordmini.server.model.Room;
import com.discordmini.server.model.User;
import com.discordmini.server.network.ChatServer;
import com.discordmini.server.network.ClientHandler;
import com.discordmini.server.service.RoomService;
import com.discordmini.server.service.ChatService;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.session.Session;
import com.discordmini.server.session.TransportType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Regression checks for the legacy Swing/TCP message contract after the issue 9 merge.
 * This test uses fakes only and does not connect to PostgreSQL or network listeners.
 */
public final class Issue9MergeCompatibilityTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        testMessageCompatibilityState();
        testLegacyCommandNamesRemainParseable();
        testDaoCompatibilityAdaptersDispatchToCanonicalMethods();
        testChatServiceLegacyAdaptersDispatchToCanonicalMethods();
        testAuthTelemetryRedactsCredentials();
        testEditAndDeletePublishSingleWsEventAndTcpCompatibilityFrames();
        testHistoryPageMembershipAndLimitChecks();
        testLegacyEditAndRoomManagementRejectNonOwners();
        System.out.println("Issue9MergeCompatibilityTest: " + assertions + " assertions passed");
    }

    private static void testMessageCompatibilityState() {
        Message legacy = new Message(1, 20, 30, "sender", "body", "2026-10-18 10:00:00");
        check(!legacy.isEdited(), "six-argument legacy constructor defaults edited=false");

        Message edited = new Message(2, 20, 30, "sender", "body", "2026-10-18 10:00:00", true);
        check(edited.isEdited(), "seven-argument constructor preserves edited flag");
        edited.setEditedAt("2026-10-18 10:01:00");
        check(edited.isEdited(), "editedAt implies edited=true");
        edited.setEditedAt(null);
        check(!edited.isEdited(), "clearing editedAt clears derived edited state");
    }

    private static void testLegacyCommandNamesRemainParseable() {
        check(MessageType.valueOf("EDIT_MESSAGE") == MessageType.EDIT_MESSAGE,
                "legacy EDIT_MESSAGE enum command remains available");
        check(MessageType.valueOf("DELETE_MESSAGE") == MessageType.DELETE_MESSAGE,
                "legacy DELETE_MESSAGE enum command remains available");
        check(MessageType.valueOf("HISTORY_PAGE") == MessageType.HISTORY_PAGE,
                "legacy HISTORY_PAGE enum command remains available");
        check(MessageType.valueOf("EDIT_MSG") == MessageType.EDIT_MSG,
                "v2 EDIT_MSG enum command remains available");
        check(MessageType.valueOf("DELETE_MSG") == MessageType.DELETE_MSG,
                "v2 DELETE_MSG enum command remains available");
    }

    private static void testDaoCompatibilityAdaptersDispatchToCanonicalMethods() {
        RecordingDao dao = new RecordingDao();
        Message found = dao.getMessageById(71);
        check(found == dao.canonicalMessage, "getMessageById returns canonical findById result");
        check(dao.findByIdArgument == 71, "getMessageById dispatches to findById");

        List<Message> page = dao.getMessageHistoryBefore(9, 71, 12);
        check(page == dao.canonicalPage, "getMessageHistoryBefore returns canonical cursor result");
        check(dao.cursorRoomId == 9 && Integer.valueOf(71).equals(dao.cursorBeforeId)
                        && dao.cursorLimit == 12,
                "getMessageHistoryBefore dispatches room, before id and limit to getMessagesCursor");
    }

    private static void testChatServiceLegacyAdaptersDispatchToCanonicalMethods() {
        RecordingDao dao = new RecordingDao();
        ChatService service = new ChatService(dao, new RoomDAO(), new EventBus());

        check(service.getMessageById(81) == dao.canonicalMessage,
                "ChatService.getMessageById adapts to canonical DAO lookup");
        List<Message> result = service.historyBefore(7, 81, 15);
        check(result == dao.canonicalPage, "ChatService.historyBefore adapts to canonical cursor query");
        check(dao.findByIdArgument == 81 && dao.cursorRoomId == 7
                        && Integer.valueOf(81).equals(dao.cursorBeforeId) && dao.cursorLimit == 15,
                "ChatService compatibility calls preserve all DAO arguments");
    }

    private static void testAuthTelemetryRedactsCredentials() {
        EventBus bus = new EventBus();
        int testUserId = 700_987;
        String password = "issue9-test-password-marker";
        String registrationSecret = "issue9-test-register-marker";
        String token = "issue9-test-token-marker";
        bus.recordProtocolFrame("IN", TransportType.TCP,
                "LOGIN|test-user|" + ProtocolHandler.encode(password), testUserId);
        bus.recordProtocolFrame("IN", TransportType.TCP,
                "REGISTER|test-user|" + ProtocolHandler.encode(registrationSecret) + "|Test", testUserId);
        bus.recordProtocolFrame("IN", TransportType.TCP,
                "AUTH_TOKEN|" + ProtocolHandler.encode(token), testUserId);

        List<com.discordmini.server.session.ServerEvent.FrameRecord> records = bus.getRecentFrames(10).stream()
                .filter(record -> Integer.valueOf(testUserId).equals(record.getUserId()))
                .toList();
        check(records.size() == 3, "three auth telemetry records were retained");
        check(records.stream().noneMatch(record -> record.getRawFrame().contains(password)
                        || record.getRawFrame().contains(ProtocolHandler.encode(password))),
                "LOGIN password and its Base64 form are absent from telemetry");
        check(records.stream().noneMatch(record -> record.getRawFrame().contains(registrationSecret)
                        || record.getRawFrame().contains(ProtocolHandler.encode(registrationSecret))),
                "REGISTER password and its Base64 form are absent from telemetry");
        check(records.stream().noneMatch(record -> record.getRawFrame().contains(token)
                        || record.getRawFrame().contains(ProtocolHandler.encode(token))),
                "AUTH_TOKEN and its Base64 form are absent from telemetry");
    }

    private static void testEditAndDeletePublishSingleWsEventAndTcpCompatibilityFrames() {
        int roomId = 700_019;
        int userId = 700_031;
        RecordingDao dao = new RecordingDao();
        dao.canonicalMessage = new Message(91, roomId, userId, "legacy", "updated | text", "2026-10-18 10:02:00");
        dao.deleteLookup = new Message(92, roomId, userId, "legacy", "before delete", "2026-10-18 10:03:00");
        dao.editResult = dao.canonicalMessage;
        dao.deleteResult = true;

        Room room = new Room(roomId, "compat-room");
        room.getMemberIds().add(userId);
        RoomDAO rooms = new RoomDAO() {
            @Override
            public Room findRoom(int id) {
                return id == roomId ? room : null;
            }
        };

        EventBus bus = new EventBus();
        CapturingSession tcp = new CapturingSession("issue9-tcp-" + UUID.randomUUID(), TransportType.TCP,
                new User(userId, "legacy", "", "Legacy"), roomId);
        CapturingSession ws = new CapturingSession("issue9-ws-" + UUID.randomUUID(), TransportType.WS,
                new User(userId, "legacy", "", "Legacy"), roomId);
        CapturingSession outsider = new CapturingSession("issue9-outsider-" + UUID.randomUUID(), TransportType.WS,
                new User(userId + 1, "outsider", "", "Outsider"), null);
        bus.registerSession(tcp);
        bus.registerSession(ws);
        bus.registerSession(outsider);
        try {
            ChatService service = new ChatService(dao, rooms, bus);
            check(service.editMessage(91, userId, "updated | text") == dao.canonicalMessage,
                    "edit returns updated message object");
            check(tcp.tcpFrames.stream().anyMatch(frame -> frame.startsWith("MSG_EDITED|")),
                    "edit publishes canonical MSG_EDITED TCP frame");
            check(tcp.tcpFrames.stream().anyMatch(frame -> frame.startsWith("MESSAGE_EDITED|")),
                    "edit publishes legacy MESSAGE_EDITED TCP frame");
            check(ws.wsEvents.size() == 1,
                    "edit publishes exactly one WS event despite TCP compatibility aliases");
            check(outsider.wsEvents.isEmpty(), "room edit event is not delivered to a non-member WS session");

            tcp.tcpFrames.clear();
            ws.wsEvents.clear();
            check(service.deleteMessage(92, userId, false), "delete reports DAO success");
            check(tcp.tcpFrames.stream().anyMatch(frame -> frame.startsWith("MSG_DELETED|")),
                    "delete publishes canonical MSG_DELETED TCP frame");
            check(tcp.tcpFrames.stream().anyMatch(frame -> frame.startsWith("MESSAGE_DELETED|")),
                    "delete publishes legacy MESSAGE_DELETED TCP frame");
            check(ws.wsEvents.size() == 1,
                    "delete publishes exactly one WS event despite TCP compatibility aliases");
            check(outsider.wsEvents.isEmpty(), "room delete event is not delivered to a non-member WS session");
        } finally {
            bus.unregisterSession(tcp);
            bus.unregisterSession(ws);
            bus.unregisterSession(outsider);
        }
    }

    private static void testHistoryPageMembershipAndLimitChecks() throws java.io.IOException {
        TestRoomService rooms = new TestRoomService();
        TestChatService chats = new TestChatService();
        TestServer server = new TestServer(rooms, chats, new EventBus());

        rooms.memberRole = null;
        List<String> denied = invokeHandler(server, "HISTORY_PAGE|700019|500|20");
        check(hasErrorCode(denied, "PERMISSION_DENIED"),
                "HISTORY_PAGE denies a non-member; response=" + denied);
        check(chats.historyCalls == 0 && denied.stream().noneMatch(line -> line.startsWith("HISTORY_PAGE_BEGIN|")),
                "non-member HISTORY_PAGE does not read or emit history");

        rooms.memberRole = "MEMBER";
        List<String> invalidLimit = invokeHandler(server, "HISTORY_PAGE|700019|500|201");
        check(hasErrorCode(invalidLimit, "BAD_REQUEST"),
                "HISTORY_PAGE rejects limits above the maximum");
        check(chats.historyCalls == 0, "invalid HISTORY_PAGE limit does not query history");

        chats.history = List.of(new Message(111, 700019, 700032, "author", "older", "2026-10-18 10:00:00"));
        List<String> allowed = invokeHandler(server, "HISTORY_PAGE|700019|500|20");
        check(chats.historyCalls == 1 && chats.lastHistoryRoom == 700019
                        && chats.lastBeforeId == 500 && chats.lastLimit == 20,
                "member HISTORY_PAGE queries the requested room, cursor and page size");
        check(allowed.stream().anyMatch(line -> line.equals("HISTORY_PAGE_BEGIN|700019"))
                        && allowed.stream().anyMatch(line -> line.startsWith("HISTORY_PAGE_ITEM|111|"))
                        && allowed.stream().anyMatch(line -> line.equals("HISTORY_PAGE_END|700019")),
                "member HISTORY_PAGE emits the legacy begin/item/end packet sequence");
    }

    private static void testLegacyEditAndRoomManagementRejectNonOwners() throws java.io.IOException {
        int actorId = 700_031;
        int roomId = 700_019;
        TestRoomService rooms = new TestRoomService();
        rooms.memberRole = "MEMBER";
        TestChatService chats = new TestChatService();
        chats.message = new Message(222, roomId, actorId + 1, "someone-else", "original", "ts");
        TestServer server = new TestServer(rooms, chats, new EventBus());

        List<String> edit = invokeHandler(server, "EDIT_MESSAGE|222|" + ProtocolHandler.encode("changed"));
        check(hasErrorCode(edit, "FORBIDDEN"),
                "legacy EDIT_MESSAGE rejects editing another user's message");
        check(chats.editCalls == 0, "rejected legacy EDIT_MESSAGE does not mutate chat service");

        List<String> rename = invokeHandler(server,
                "RENAME_ROOM|" + roomId + "|" + ProtocolHandler.encode("renamed"));
        check(hasErrorCode(rename, "PERMISSION_DENIED"),
                "room member without admin/owner role cannot rename room");
        check(rooms.updateCalls == 0, "rejected room rename does not update room service");

        List<String> delete = invokeHandler(server, "DELETE_ROOM|" + roomId);
        check(hasErrorCode(delete, "PERMISSION_DENIED"),
                "non-owner cannot delete room");
        check(rooms.deleteCalls == 0, "rejected room delete does not mutate room service");
    }

    private static List<String> invokeHandler(TestServer server, String frame) {
        try (ServerSocket listener = new ServerSocket(0);
             Socket clientSocket = new Socket("127.0.0.1", listener.getLocalPort());
             Socket serverSocket = listener.accept()) {
            ClientHandler handler = new ClientHandler(serverSocket, server);
            handler.setUser(new User(700031, "test-user", "", "Test User"));
            Method handle = ClientHandler.class.getDeclaredMethod("handle", String.class);
            handle.setAccessible(true);
            handle.invoke(handler, frame);
            handler.closeConnection();

            List<String> response = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    clientSocket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.add(line);
            }
            return response;
        } catch (ReflectiveOperationException | java.io.IOException e) {
            throw new AssertionError("Could not execute ClientHandler request", e);
        }
    }

    private static boolean hasErrorCode(List<String> response, String code) {
        return response.stream().anyMatch(line -> line.startsWith("ERROR|") && line.endsWith("|" + code));
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RecordingDao extends MessageDAO {
        private Message canonicalMessage = new Message(71, 9, 3, "sender", "body", "ts");
        private final List<Message> canonicalPage = new ArrayList<>();
        private int findByIdArgument;
        private int cursorRoomId;
        private Integer cursorBeforeId;
        private int cursorLimit;
        private Message deleteLookup;
        private Message editResult;
        private boolean deleteResult;

        @Override
        public Message findById(int messageId) {
            findByIdArgument = messageId;
            return deleteLookup != null && deleteLookup.getId() == messageId ? deleteLookup : canonicalMessage;
        }

        @Override
        public List<Message> getMessagesCursor(int roomId, Integer beforeId, int limit) {
            cursorRoomId = roomId;
            cursorBeforeId = beforeId;
            cursorLimit = limit;
            return canonicalPage;
        }

        @Override
        public Message editMessage(int messageId, int actorUserId, String newContent) {
            return editResult;
        }

        @Override
        public boolean deleteMessage(int messageId, int actorUserId, boolean isAdminOrOwner) {
            return deleteResult;
        }
    }

    private static final class TestServer extends ChatServer {
        private final TestRoomService rooms;
        private final TestChatService chats;
        private final EventBus events;

        private TestServer(TestRoomService rooms, TestChatService chats, EventBus events) throws java.io.IOException {
            super(0, false);
            this.rooms = rooms;
            this.chats = chats;
            this.events = events;
        }

        @Override public RoomService getRoomService() { return rooms; }
        @Override public ChatService getChatService() { return chats; }
        @Override public EventBus getEventBus() { return events; }
    }

    private static final class TestRoomService extends RoomService {
        private final Room room = new Room(700019, "compat-room");
        private String memberRole = "MEMBER";
        private int updateCalls;
        private int deleteCalls;

        private TestRoomService() { super(new RoomDAO(), new EventBus()); }
        @Override public Room findRoom(int roomId) { return roomId == room.getId() ? room : null; }
        @Override public String getMemberRole(int roomId, int userId) { return memberRole; }
        @Override public boolean isAdminOrOwner(int roomId, int userId) { return "ADMIN".equals(memberRole) || "OWNER".equals(memberRole); }
        @Override public boolean isRoomOwner(int roomId, int userId) { return false; }
        @Override public Room updateRoom(int roomId, int actorId, String name, String topic) {
            updateCalls++;
            return room;
        }
        @Override public boolean deleteRoom(int roomId, int actorId) {
            deleteCalls++;
            return true;
        }
    }

    private static final class TestChatService extends ChatService {
        private Message message;
        private List<Message> history = new ArrayList<>();
        private int historyCalls;
        private int lastHistoryRoom;
        private int lastBeforeId;
        private int lastLimit;
        private int editCalls;

        private TestChatService() { super(new MessageDAO(), new RoomDAO(), new EventBus()); }
        @Override public Message findById(int messageId) { return message; }
        @Override public List<Message> historyBefore(int roomId, int beforeId, int limit) {
            historyCalls++;
            lastHistoryRoom = roomId;
            lastBeforeId = beforeId;
            lastLimit = limit;
            return history;
        }
        @Override public Message editMessage(int messageId, int actorUserId, String newContent) {
            editCalls++;
            return message;
        }
    }

    private static final class CapturingSession implements Session {
        private final String id;
        private final TransportType transport;
        private User user;
        private Integer roomId;
        private final List<String> tcpFrames = new ArrayList<>();
        private final List<String> wsEvents = new ArrayList<>();

        private CapturingSession(String id, TransportType transport, User user, Integer roomId) {
            this.id = id;
            this.transport = transport;
            this.user = user;
            this.roomId = roomId;
        }

        @Override public String getSessionId() { return id; }
        @Override public TransportType getTransport() { return transport; }
        @Override public User getUser() { return user; }
        @Override public void setUser(User user) { this.user = user; }
        @Override public Integer getCurrentRoomId() { return roomId; }
        @Override public void setCurrentRoomId(Integer roomId) { this.roomId = roomId; }
        @Override public void sendTcpFrame(String frame) { tcpFrames.add(frame); }
        @Override public void sendWsEvent(String type, String rid, Object payload) { wsEvents.add(type); }
        @Override public boolean isOpen() { return true; }
        @Override public void close() { }
    }
}
