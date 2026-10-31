package com.discordmini.server.protocol;

import com.discordmini.server.auth.JwtUtil;
import com.discordmini.server.dao.RoomDAO;
import com.discordmini.server.dao.GuildDAO;
import com.discordmini.server.dao.DirectMessageDAO;
import com.discordmini.server.dao.MessageDAO;
import com.discordmini.server.model.DirectConversation;
import com.discordmini.server.model.Guild;
import com.discordmini.server.model.Room;
import com.discordmini.server.network.ChatServer;
import com.discordmini.server.service.RoomService;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.web.http.HttpApiServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** No-database HTTP authorization regressions for room/channel management. */
public final class Issue9HttpChannelManagementTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        FakeRoomDAO dao = new FakeRoomDAO();
        FakeRoomService roomService = new FakeRoomService(dao.room);
        FakeGuildDAO guildDAO = new FakeGuildDAO();
        FakeDirectMessageDAO directMessageDAO = new FakeDirectMessageDAO();
        FakeMessageDAO messageDAO = new FakeMessageDAO();
        TestServer chatServer = new TestServer(dao, roomService, guildDAO, directMessageDAO, messageDAO);
        HttpApiServer httpServer = new HttpApiServer(port, 1, chatServer, new EventBus(), null);
        httpServer.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port + "/api/channels/700019";
            String memberToken = JwtUtil.generateToken(700031, "member", "Member");
            String adminToken = JwtUtil.generateToken(700032, "admin", "Admin");
            String ownerToken = JwtUtil.generateToken(700033, "owner", "Owner");

            check(send(client, base, "PATCH", null, "{\"name\":\"unauth\"}").statusCode() == 401,
                    "PATCH requires authentication");
            check(send(client, base, "DELETE", null, "").statusCode() == 401,
                    "DELETE requires authentication");

            dao.role = "MEMBER";
            check(send(client, base, "PATCH", memberToken, "{\"name\":\"denied\"}").statusCode() == 403,
                    "member cannot rename channel");
            check(send(client, base, "DELETE", memberToken, "").statusCode() == 403,
                    "member cannot delete channel");
            check(roomService.updateCalls == 0 && roomService.deleteCalls == 0,
                    "member requests do not call mutating room service methods");

            dao.role = "ADMIN";
            check(send(client, base, "PATCH", adminToken, "{\"name\":\"renamed\",\"topic\":\"topic\"}")
                    .statusCode() == 200, "admin can update channel name and topic");
            check(send(client, base, "DELETE", adminToken, "").statusCode() == 403,
                    "admin cannot delete channel");
            check(roomService.updateCalls == 1 && roomService.deleteCalls == 0,
                    "admin update reaches service, while delete remains blocked");

            dao.role = "OWNER";
            check(send(client, base, "PATCH", ownerToken, "{\"name\":\"   \"}").statusCode() == 400,
                    "owner cannot update channel with a blank name");
            check(roomService.updateCalls == 1, "invalid owner update is rejected before mutation");
            check(send(client, base, "DELETE", ownerToken, "").statusCode() == 200,
                    "owner can delete channel");
            check(roomService.deleteCalls == 1, "owner delete reaches service once");

            String inviteUri = "http://127.0.0.1:" + port + "/api/guilds/700041/invites";
            String channelUri = "http://127.0.0.1:" + port + "/api/guilds/700041/channels";
            guildDAO.role = null;
            check(send(client, channelUri, "GET", memberToken, "").statusCode() == 403,
                    "nonmember cannot enumerate guild channels");
            check(dao.listChannelCalls == 0, "forbidden channel listing does not query channel data");
            check(send(client, inviteUri, "POST", memberToken, "{}").statusCode() == 403,
                    "nonmember cannot create guild invite");
            check(send(client, channelUri, "POST", memberToken, "{\"name\":\"blocked\"}").statusCode() == 403,
                    "nonmember cannot create guild channel");
            check(guildDAO.inviteCalls == 0 && dao.createChannelCalls == 0,
                    "forbidden guild mutations do not reach DAOs");

            dao.role = "MEMBER";
            String channelReadUri = "http://127.0.0.1:" + port + "/api/channels/700019/read";
            check(send(client, channelReadUri, "POST", memberToken,
                    "{\"lastReadMessageId\":51}").statusCode() == 200,
                    "member can advance channel read position to a channel message");
            check(send(client, channelReadUri, "POST", memberToken,
                    "{\"lastReadMessageId\":999}").statusCode() == 400,
                    "channel read rejects a message from another channel");
            check(send(client, channelReadUri, "POST", memberToken,
                    "{\"lastReadMessageId\":-1}").statusCode() == 400,
                    "channel read rejects a negative cursor");

            String searchUri = "http://127.0.0.1:" + port + "/api/search?q=private+phrase";
            check(send(client, searchUri, "GET", memberToken, "").statusCode() == 400,
                    "search requires a channel or group scope");
            check(messageDAO.searchCalls == 0, "unscoped search does not reach message data");
            check(send(client, searchUri + "&guildId=not-a-number", "GET", memberToken, "").statusCode() == 400,
                    "malformed search scope is rejected instead of becoming global");
            dao.role = null;
            check(send(client, "http://127.0.0.1:" + port + "/api/search?q=private+phrase&channelId=700019",
                    "GET", memberToken, "").statusCode() == 403,
                    "search cannot read a channel the user has left");
            check(messageDAO.searchCalls == 0, "forbidden channel search does not query messages");
            dao.role = "MEMBER";
            HttpResponse<String> scopedSearch = send(client,
                    "http://127.0.0.1:" + port + "/api/search?q=private+phrase&channelId=700019",
                    "GET", memberToken, "");
            check(scopedSearch.statusCode() == 200 && messageDAO.lastSearchUserId == 700031
                            && messageDAO.lastSearchChannelId == 700019,
                    "authorized channel search is scoped to the authenticated user and channel");

            guildDAO.role = "MEMBER";
            check(send(client, inviteUri, "POST", memberToken, "{}").statusCode() == 403,
                    "guild member cannot create invite");
            check(send(client, channelUri, "POST", memberToken, "{\"name\":\"blocked\"}").statusCode() == 403,
                    "guild member cannot create channel");
            guildDAO.role = "ADMIN";
            check(send(client, channelUri, "GET", adminToken, "").statusCode() == 200,
                    "guild admin can list channels");
            check(dao.listChannelCalls == 1, "authorized channel listing queries channel data once");
            check(send(client, inviteUri, "POST", adminToken, "{}").statusCode() == 201,
                    "guild admin can create invite");
            check(send(client, channelUri, "POST", adminToken, "{\"name\":\"staff-only\"}").statusCode() == 201,
                    "guild admin can create channel");

            String dmUri = "http://127.0.0.1:" + port + "/api/dms/700051";
            HttpResponse<String> dmList = send(client, "http://127.0.0.1:" + port + "/api/dms", "GET", memberToken, "");
            check(dmList.statusCode() == 200 && dmList.body().contains("\"unreadCount\":2"),
                    "conversation list exposes persisted unread count");
            HttpResponse<String> readAck = send(client, dmUri + "/read", "POST", memberToken,
                    "{\"lastReadMessageId\":51}");
            check(readAck.statusCode() == 200 && readAck.body().contains("\"lastReadMessageId\":51")
                            && readAck.body().contains("\"unreadCount\":0"),
                    "member can acknowledge a message in their conversation");
            check(send(client, dmUri + "/read", "POST", memberToken,
                    "{\"lastReadMessageId\":999}").statusCode() == 400,
                    "read acknowledgement rejects message ids from another conversation");
        } finally {
            httpServer.stop();
            chatServer.stop();
        }

        System.out.println("Issue9HttpChannelManagementTest: " + assertions + " assertions passed");
    }

    private static HttpResponse<String> send(HttpClient client, String uri, String method,
                                              String token, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        HttpRequest.BodyPublisher publisher = body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestServer extends ChatServer {
        private final FakeRoomDAO dao;
        private final FakeRoomService rooms;
        private final FakeGuildDAO guilds;
        private final FakeDirectMessageDAO directMessages;
        private final FakeMessageDAO messages;

        private TestServer(FakeRoomDAO dao, FakeRoomService rooms, FakeGuildDAO guilds,
                           FakeDirectMessageDAO directMessages, FakeMessageDAO messages) throws IOException {
            super(0, false);
            this.dao = dao;
            this.rooms = rooms;
            this.guilds = guilds;
            this.directMessages = directMessages;
            this.messages = messages;
        }

        @Override public RoomDAO getRoomDAO() { return dao; }
        @Override public RoomService getRoomService() { return rooms; }
        @Override public GuildDAO getGuildDAO() { return guilds; }
        @Override public DirectMessageDAO getDirectMessageDAO() { return directMessages; }
        @Override public MessageDAO getMessageDAO() { return messages; }
    }

    private static final class FakeRoomDAO extends RoomDAO {
        private final Room room = new Room(700019, "initial");
        private String role;
        private int createChannelCalls;
        private int listChannelCalls;

        @Override public ReadResult markRead(int userId, int roomId, int lastReadMessageId) {
            if (lastReadMessageId < 0 || (lastReadMessageId > 0 && lastReadMessageId != 51)) {
                return ReadResult.INVALID_MESSAGE;
            }
            return ReadResult.UPDATED;
        }

        @Override public String getMemberRole(int roomId, int userId) { return role; }
        @Override public Room findRoom(int roomId) { return roomId == room.getId() ? room : null; }
        @Override public Integer getUnreadMessageCount(int userId, int roomId) { return 0; }
        @Override public java.util.List<Room> listChannelsByGuild(int guildId, int userId) {
            listChannelCalls++;
            return java.util.List.of(room);
        }
        @Override public Room createChannel(int guildId, String name, String type, String topic, int position, int createdBy) {
            createChannelCalls++;
            return new Room(700020, name, createdBy, null, guildId, type, position, topic);
        }
    }

    private static final class FakeGuildDAO extends GuildDAO {
        private String role;
        private int inviteCalls;

        @Override public Guild findById(int guildId) {
            return guildId == 700041 ? new Guild(guildId, "Test Guild", "TG", 700033, "") : null;
        }

        @Override public String getMemberRole(int guildId, int userId) { return role; }
        @Override public java.util.List<Guild> listGuildsForUser(int userId) {
            return role == null ? java.util.List.of() : java.util.List.of(findById(700041));
        }

        @Override public String createInvite(int guildId, int createdBy, int ttlMinutes, int maxUses) {
            inviteCalls++;
            return "TESTCODE";
        }
    }

    private static final class FakeMessageDAO extends MessageDAO {
        private int searchCalls;
        private int lastSearchUserId;
        private Integer lastSearchChannelId;

        @Override public java.util.List<com.discordmini.server.model.Message> searchMessages(
                int userId, Integer guildId, Integer roomId, String query, int limit) {
            searchCalls++;
            lastSearchUserId = userId;
            lastSearchChannelId = roomId;
            return java.util.List.of();
        }
    }

    private static final class FakeDirectMessageDAO extends DirectMessageDAO {
        private long lastReadMessageId;
        private int unreadCount = 2;

        @Override public java.util.List<DirectConversation> listConversations(int userId) {
            DirectConversation conversation = new DirectConversation(700051, 700031, 700032, 700032,
                    "peer", "Peer", "#2563EB", "ONLINE", "hi", "");
            conversation.setUnreadCount(unreadCount);
            return java.util.List.of(conversation);
        }

        @Override public long findConversationIdForMessage(long messageId) {
            return messageId == 51 ? 700051 : 700052;
        }

        @Override public long markConversationRead(long conversationId, int userId, long messageId) {
            lastReadMessageId = Math.max(lastReadMessageId, messageId);
            unreadCount = 0;
            return lastReadMessageId;
        }

        @Override public int getUnreadCount(long conversationId, int userId) { return unreadCount; }
    }

    private static final class FakeRoomService extends RoomService {
        private final Room room;
        private int updateCalls;
        private int deleteCalls;

        private FakeRoomService(Room room) {
            super(new RoomDAO(), new EventBus());
            this.room = room;
        }

        @Override public Room updateRoom(int roomId, int actorId, String name, String topic) {
            updateCalls++;
            room.setName(name);
            room.setTopic(topic);
            return room;
        }

        @Override public boolean deleteRoom(int roomId, int actorId) {
            deleteCalls++;
            return true;
        }
    }
}
