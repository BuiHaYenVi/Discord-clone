package com.discordmini.server.web.http;

import com.discordmini.server.auth.JwtUtil;
import com.discordmini.server.dao.AttachmentDAO;
import com.discordmini.server.dao.DatabaseConnection;
import com.discordmini.server.dao.DirectMessageDAO;
import com.discordmini.server.dao.FriendDAO;
import com.discordmini.server.dao.GuildDAO;
import com.discordmini.server.dao.MessageDAO;
import com.discordmini.server.dao.RoomDAO;
import com.discordmini.server.dao.UserDAO;
import com.discordmini.server.model.Attachment;
import com.discordmini.server.model.DirectConversation;
import com.discordmini.server.model.DirectMessage;
import com.discordmini.server.model.Guild;
import com.discordmini.server.model.Message;
import com.discordmini.server.model.Room;
import com.discordmini.server.model.User;
import com.discordmini.server.network.ChatServer;
import com.discordmini.server.protocol.ProtocolHandler;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.session.ServerEvent;
import com.discordmini.server.session.Session;
import com.discordmini.server.session.TransportType;
import com.discordmini.server.udp.UdpTelemetryServer;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure JDK 21+ HttpServer REST API Gateway for the Discord Web Clone.
 * Powered by Virtual Threads (Executors.newVirtualThreadPerTaskExecutor()).
 */
public class HttpApiServer {

    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024; // 5 MB

    private final int httpPort;
    private final int wsPort;
    private final ChatServer chatServer;
    private final EventBus eventBus;
    private final UdpTelemetryServer udpServer;

    private final UserDAO userDAO;
    private final RoomDAO roomDAO;
    private final MessageDAO messageDAO;
    private final FriendDAO friendDAO;
    private final GuildDAO guildDAO;
    private final AttachmentDAO attachmentDAO;
    private final DirectMessageDAO directMessageDAO;

    private HttpServer server;
    private ExecutorService virtualExecutor;

    public HttpApiServer(
            int httpPort,
            int wsPort,
            ChatServer chatServer,
            EventBus eventBus,
            UdpTelemetryServer udpServer) {
        this.httpPort = httpPort > 0 ? httpPort : resolveHttpPort();
        this.wsPort = wsPort > 0 ? wsPort : 8081;
        this.chatServer = chatServer;
        this.eventBus = eventBus != null ? eventBus : EventBus.getInstance();
        this.udpServer = udpServer;

        this.userDAO = chatServer != null && chatServer.getUserDAO() != null ? chatServer.getUserDAO() : new UserDAO();
        this.roomDAO = chatServer != null && chatServer.getRoomDAO() != null ? chatServer.getRoomDAO() : new RoomDAO();
        this.messageDAO = chatServer != null && chatServer.getMessageDAO() != null ? chatServer.getMessageDAO() : new MessageDAO();
        this.friendDAO = chatServer != null && chatServer.getFriendDAO() != null ? chatServer.getFriendDAO() : new FriendDAO();
        this.guildDAO = chatServer != null && chatServer.getGuildDAO() != null ? chatServer.getGuildDAO() : new GuildDAO();
        this.attachmentDAO = new AttachmentDAO();
        this.directMessageDAO = chatServer != null && chatServer.getDirectMessageDAO() != null
                ? chatServer.getDirectMessageDAO() : new DirectMessageDAO();
    }

    public static int resolveHttpPort() {
        String renderPort = System.getenv("PORT");
        if (renderPort != null && !renderPort.isBlank()) {
            try {
                return Integer.parseInt(renderPort.trim());
            } catch (NumberFormatException ignored) {}
        }
        String httpEnv = System.getenv("HTTP_PORT");
        if (httpEnv != null && !httpEnv.isBlank()) {
            try {
                return Integer.parseInt(httpEnv.trim());
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_HTTP_PORT;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", httpPort), 0);
        virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(virtualExecutor);
        server.createContext("/", this::dispatch);
        server.start();
        System.out.println("[HTTP] HttpApiServer listening on http://0.0.0.0:" + httpPort + " (Virtual Threads)");
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (virtualExecutor != null) {
            virtualExecutor.shutdownNow();
            virtualExecutor = null;
        }
    }

    public int getPort() {
        return httpPort;
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            if (JsonUtil.handlePreflight(exchange)) {
                return;
            }

            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            // Root & Health check
            if ("/".equals(path) || "/api/health".equals(path)) {
                if ("GET".equals(method)) {
                    handleHealth(exchange);
                    return;
                }
            }

            // Auth endpoints
            if ("/api/auth/register".equals(path) && "POST".equals(method)) {
                handleRegister(exchange);
                return;
            }
            if ("/api/auth/login".equals(path) && "POST".equals(method)) {
                handleLogin(exchange);
                return;
            }
            if ("/api/auth/me".equals(path) && "GET".equals(method)) {
                handleAuthMe(exchange);
                return;
            }

            // File downloads require an active session and channel/conversation access.
            if (path.startsWith("/api/files/") && "GET".equals(method)) {
                String idStr = path.substring("/api/files/".length());
                handleFileDownload(exchange, idStr);
                return;
            }

            // File upload
            if ("/api/files".equals(path) && ("PUT".equals(method) || "POST".equals(method))) {
                handleFileUpload(exchange);
                return;
            }

            // Search
            if ("/api/search".equals(path) && "GET".equals(method)) {
                handleSearch(exchange);
                return;
            }

            // Monitor endpoints
            if ("/api/monitor/udp-probe".equals(path) && ("POST".equals(method) || "GET".equals(method))) {
                handleMonitorUdpProbe(exchange);
                return;
            }
            if ("/api/monitor/frames".equals(path) && "GET".equals(method)) {
                handleMonitorFrames(exchange);
                return;
            }

            // Friends endpoints
            if ("/api/friends".equals(path) && "GET".equals(method)) {
                handleGetFriends(exchange);
                return;
            }
            if ("/api/friends/search".equals(path) && "GET".equals(method)) {
                handleSearchFriends(exchange);
                return;
            }
            if ("/api/friends/request".equals(path) && "POST".equals(method)) {
                handleFriendRequest(exchange);
                return;
            }
            if ("/api/friends/respond".equals(path) && "POST".equals(method)) {
                handleFriendRespond(exchange);
                return;
            }

            // DMs endpoints
            if ("/api/dms".equals(path)) {
                if ("GET".equals(method)) {
                    handleListDms(exchange);
                    return;
                } else if ("POST".equals(method)) {
                    handleCreateDm(exchange);
                    return;
                }
            }
            if (path.startsWith("/api/dms/") && path.endsWith("/messages") && "GET".equals(method)) {
                String middle = path.substring("/api/dms/".length(), path.length() - "/messages".length());
                handleGetDmMessages(exchange, middle);
                return;
            }
            if (path.startsWith("/api/dms/") && path.endsWith("/read") && "POST".equals(method)) {
                String middle = path.substring("/api/dms/".length(), path.length() - "/read".length());
                handleMarkDmRead(exchange, middle);
                return;
            }

            // Guilds & Invites endpoints
            if ("/api/guilds".equals(path)) {
                if ("GET".equals(method)) {
                    handleListGuilds(exchange);
                    return;
                } else if ("POST".equals(method)) {
                    handleCreateGuild(exchange);
                    return;
                }
            }
            if ("/api/guilds/join".equals(path) && "POST".equals(method)) {
                handleJoinInviteBody(exchange);
                return;
            }
            if (path.startsWith("/api/invites/") && path.endsWith("/join") && "POST".equals(method)) {
                String code = path.substring("/api/invites/".length(), path.length() - "/join".length());
                handleJoinInvite(exchange, code);
                return;
            }
            if (path.startsWith("/api/guilds/")) {
                String sub = path.substring("/api/guilds/".length());
                String[] parts = sub.split("/");
                if (parts.length == 2 && "invites".equals(parts[1]) && "POST".equals(method)) {
                    handleCreateInvite(exchange, parts[0]);
                    return;
                }
                if (parts.length == 2 && "channels".equals(parts[1])) {
                    if ("GET".equals(method)) {
                        handleListGuildChannels(exchange, parts[0]);
                        return;
                    } else if ("POST".equals(method)) {
                        handleCreateGuildChannel(exchange, parts[0]);
                        return;
                    }
                }
            }

            // Channels endpoints (/api/channels/{channelId}/...)
            if (path.startsWith("/api/channels/")) {
                String sub = path.substring("/api/channels/".length());
                String[] parts = sub.split("/");
                if (parts.length == 1 && ("PATCH".equals(method) || "DELETE".equals(method))) {
                    handleManageChannel(exchange, parts[0], method);
                    return;
                } else if (parts.length == 2) {
                    String channelIdStr = parts[0];
                    String action = parts[1];
                    if ("members".equals(action) && "GET".equals(method)) {
                        handleGetChannelMembers(exchange, channelIdStr);
                        return;
                    }
                    if ("messages".equals(action) && "GET".equals(method)) {
                        handleGetChannelMessages(exchange, channelIdStr);
                        return;
                    }
                    if ("pins".equals(action) && "GET".equals(method)) {
                        handleGetChannelPins(exchange, channelIdStr);
                        return;
                    }
                    if ("read".equals(action) && "POST".equals(method)) {
                        handleMarkChannelRead(exchange, channelIdStr);
                        return;
                    }
                } else if (parts.length == 4 && "members".equals(parts[1]) && "role".equals(parts[3])
                        && ("PATCH".equals(method) || "PUT".equals(method) || "POST".equals(method))) {
                    handleUpdateMemberRole(exchange, parts[0], parts[2]);
                    return;
                }
            }

            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Endpoint not found: " + method + " " + path);
        } catch (Exception e) {
            System.err.println("[HTTP ERROR] Unhandled error in dispatch: " + e.getMessage());
            try {
                JsonUtil.sendError(exchange, 500, "INTERNAL_ERROR",
                        "Internal server error");
            } catch (Exception ignored) {}
        }
    }

    private JwtUtil.JwtClaims authenticate(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String token = null;
        if (authHeader != null && !authHeader.isBlank()) {
            token = authHeader.trim();
        } else {
            Map<String, String> query = JsonUtil.parseQueryParams(exchange.getRequestURI());
            token = query.get("token");
        }

        JwtUtil.JwtClaims claims = JwtUtil.verifyToken(token);
        if (claims == null) {
            JsonUtil.sendError(exchange, 401, "UNAUTHORIZED", "Missing, invalid, or expired JWT Bearer token.");
            return null;
        }
        return claims;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        boolean dbConnected = false;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1");
             ResultSet rs = ps.executeQuery()) {
            dbConnected = rs.next();
        } catch (Exception ignored) {
        }

        int wsCount = 0;
        int tcpCount = 0;
        for (Session s : eventBus.getAllSessions()) {
            if (s.getTransport() == TransportType.WS) {
                wsCount++;
            } else if (s.getTransport() == TransportType.TCP) {
                tcpCount++;
            }
        }
        if (chatServer != null && chatServer.getClientManager() != null) {
            int legacyTcp = chatServer.getClientManager().all().size();
            if (legacyTcp > tcpCount) {
                tcpCount = legacyTcp;
            }
        }

        int tcpPort = chatServer != null ? chatServer.getPort() : 5000;
        int udpPort = udpServer != null ? udpServer.getUdpPort() : UdpTelemetryServer.DEFAULT_UDP_PORT;
        boolean udpActive = udpServer != null && udpServer.isUdpActive();
        String singlePortEnv = System.getenv("SINGLE_PORT_MODE");
        boolean singlePortMode = "true".equalsIgnoreCase(singlePortEnv)
                || "1".equals(singlePortEnv)
                || System.getenv("PORT") != null;

        Map<String, Object> ports = new LinkedHashMap<>();
        ports.put("tcp", tcpPort);
        ports.put("udp", udpPort > 0 ? udpPort : 5001);
        ports.put("http", httpPort);
        ports.put("ws", wsPort);

        Map<String, Object> onlineSessions = new LinkedHashMap<>();
        onlineSessions.put("total", wsCount + tcpCount);
        onlineSessions.put("ws", wsCount);
        onlineSessions.put("tcp", tcpCount);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbConnected ? "UP" : "DEGRADED");
        body.put("dbConnected", dbConnected);
        body.put("ports", ports);
        body.put("onlineSessions", onlineSessions);
        body.put("udpActive", udpActive);
        body.put("singlePortMode", singlePortMode);

        JsonUtil.sendJson(exchange, 200, body);
    }

    private void handleRegister(HttpExchange exchange) throws IOException, SQLException {
        JsonObject req = JsonUtil.readJsonObject(exchange);
        String username = JsonUtil.getString(req, "username", "").trim();
        String password = JsonUtil.getString(req, "password", "");
        String displayName = JsonUtil.getString(req, "displayName", username).trim();
        if (displayName.isEmpty()) {
            displayName = username;
        }

        if (username.isEmpty() || username.length() > 50) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Username is required (1-50 chars).");
            return;
        }
        if (password.isEmpty()) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Password is required.");
            return;
        }
        if (userDAO.findByUsername(username) != null) {
            JsonUtil.sendError(exchange, 409, "CONFLICT", "Username already exists.");
            return;
        }

        User created = userDAO.register(username, password, displayName);
        if (created == null) {
            JsonUtil.sendError(exchange, 409, "CONFLICT", "Could not register username.");
            return;
        }

        userDAO.updateUserStatus(created.getId(), "ONLINE");
        created.setOnline(true);

        String token = JwtUtil.generateToken(created);
        eventBus.recordProtocolFrame("IN", TransportType.HTTP, "POST /api/auth/register username=" + username, created.getId());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("user", JsonUtil.toUserDto(created, eventBus));
        JsonUtil.sendJson(exchange, 201, resp);
    }

    private void handleLogin(HttpExchange exchange) throws IOException, SQLException {
        JsonObject req = JsonUtil.readJsonObject(exchange);
        String username = JsonUtil.getString(req, "username", "").trim();
        String password = JsonUtil.getString(req, "password", "");

        if (username.isEmpty() || password.isEmpty()) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Username and password are required.");
            return;
        }

        User user = userDAO.login(username, password);
        if (user == null) {
            JsonUtil.sendError(exchange, 401, "UNAUTHORIZED", "Invalid username or password.");
            return;
        }

        String token = JwtUtil.generateToken(user);
        eventBus.recordProtocolFrame("IN", TransportType.HTTP, "POST /api/auth/login username=" + username, user.getId());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("user", JsonUtil.toUserDto(user, eventBus));
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleAuthMe(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        User user = userDAO.findById(claims.userId());
        if (user == null) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "User not found.");
            return;
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("user", JsonUtil.toUserDto(user, eventBus));
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleListGuilds(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        List<Guild> guilds = guildDAO.listGuildsForUser(claims.userId());
        List<Map<String, Object>> guildDtos = new ArrayList<>();
        for (Guild g : guilds) {
            guildDtos.add(JsonUtil.toGuildDto(g));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("guilds", guildDtos);
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleCreateGuild(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        JsonObject req = JsonUtil.readJsonObject(exchange);
        String name = JsonUtil.getString(req, "name", "").trim();
        String iconText = JsonUtil.getString(req, "iconText", null);

        if (name.isEmpty() || name.length() > 100) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Guild name must be between 1 and 100 characters.");
            return;
        }

        Guild created = guildDAO.createGuild(name, iconText, claims.userId());
        if (created == null) {
            JsonUtil.sendError(exchange, 500, "INTERNAL_ERROR", "Failed to create guild.");
            return;
        }

        Map<String, Object> guildDto = JsonUtil.toGuildDto(created);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("guild", guildDto);
        JsonUtil.sendJson(exchange, 201, resp);
    }

    private void handleCreateInvite(HttpExchange exchange, String guildIdStr) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        int guildId = parsePositiveInt(guildIdStr);
        if (guildId <= 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid guildId.");
            return;
        }

        Guild guild = guildDAO.findById(guildId);
        if (guild == null) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Guild not found.");
            return;
        }
        if (!isGuildManager(guildId, claims.userId())) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Guild owner or admin role is required to create invites.");
            return;
        }

        JsonObject req = JsonUtil.readJsonObject(exchange);
        int ttlMinutes = JsonUtil.getInt(req, "ttlMinutes", 1440);
        int maxUses = JsonUtil.getInt(req, "maxUses", 0);

        String code = guildDAO.createInvite(guildId, claims.userId(), ttlMinutes, maxUses);
        if (code == null) {
            JsonUtil.sendError(exchange, 500, "INTERNAL_ERROR", "Failed to generate invite code.");
            return;
        }

        String expiresAt = Instant.now().plus(ttlMinutes > 0 ? ttlMinutes : 1440, ChronoUnit.MINUTES).toString();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", code);
        resp.put("inviteCode", code);
        resp.put("guildId", guildId);
        resp.put("guildName", guild.getName());
        resp.put("creatorId", claims.userId());
        resp.put("expiresAt", expiresAt);
        resp.put("maxUses", maxUses);
        resp.put("uses", 0);

        JsonUtil.sendJson(exchange, 201, resp);
    }

    private void handleJoinInviteBody(HttpExchange exchange) throws IOException {
        JsonObject req = JsonUtil.readJsonObject(exchange);
        String code = JsonUtil.getString(req, "code", JsonUtil.getString(req, "inviteCode", "")).trim();
        handleJoinInvite(exchange, code);
    }

    private void handleJoinInvite(HttpExchange exchange, String code) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        if (code == null || code.isBlank()) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invite code is required.");
            return;
        }

        Guild joined = guildDAO.joinByInviteCode(code.trim(), claims.userId());
        if (joined == null) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Invite code is invalid or expired.");
            return;
        }

        User joiner = userDAO.findById(claims.userId());
        if (joiner != null) {
            joiner.setRole("MEMBER");
            Map<String, Object> wsPayload = new LinkedHashMap<>();
            wsPayload.put("guildId", joined.getId());
            wsPayload.put("member", JsonUtil.toUserDto(joiner, eventBus));
            eventBus.publishGlobal(null, "MEMBER_JOINED", wsPayload, null);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("guild", JsonUtil.toGuildDto(joined));
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleListGuildChannels(HttpExchange exchange, String guildIdStr) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        int guildId = parsePositiveInt(guildIdStr);
        if (guildId <= 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid guildId.");
            return;
        }

        if (guildDAO.getMemberRole(guildId, claims.userId()) == null) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Guild membership is required to view its channels.");
            return;
        }

        List<Room> channels = roomDAO.listChannelsByGuild(guildId, claims.userId());
        List<Map<String, Object>> channelDtos = new ArrayList<>();
        for (Room r : channels) {
            channelDtos.add(JsonUtil.toChannelDto(r));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("channels", channelDtos);
        resp.put("rooms", channelDtos);
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleCreateGuildChannel(HttpExchange exchange, String guildIdStr) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        int guildId = parsePositiveInt(guildIdStr);
        if (guildId <= 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid guildId.");
            return;
        }

        Guild guild = guildDAO.findById(guildId);
        if (guild == null) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Guild not found.");
            return;
        }
        if (!isGuildManager(guildId, claims.userId())) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Guild owner or admin role is required to create channels.");
            return;
        }

        JsonObject req = JsonUtil.readJsonObject(exchange);
        String name = JsonUtil.getString(req, "name", "").trim();
        String type = JsonUtil.getString(req, "type", JsonUtil.getString(req, "channelType", "text")).trim().toLowerCase(Locale.ROOT);
        String topic = JsonUtil.getString(req, "topic", "");
        int position = JsonUtil.getInt(req, "position", 0);

        if (name.isEmpty() || name.length() > 100) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Channel name must be 1-100 characters.");
            return;
        }
        if (!"voice".equals(type)) {
            type = "text";
        }

        Room created = roomDAO.createChannel(guildId, name, type, topic, position, claims.userId());
        if (created == null) {
            JsonUtil.sendError(exchange, 409, "CONFLICT", "Channel name already exists or could not be created.");
            return;
        }

        Map<String, Object> channelDto = JsonUtil.toChannelDto(created);
        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("guildId", guildId);
        wsPayload.put("channel", channelDto);
        String tcpFrame = "CHANNEL_CREATED|" + guildId + "|" + created.getId() + "|"
                + ProtocolHandler.encode(created.getName()) + "|" + type;
        // Channel creation is a guild-wide event; room id 0 routes it to guild members on both transports.
        eventBus.publishToRoom(0, created.getMemberIds(), tcpFrame, "CHANNEL_CREATED", wsPayload, null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("channel", channelDto);
        resp.put("room", channelDto);
        JsonUtil.sendJson(exchange, 201, resp);
    }

    private void handleManageChannel(HttpExchange exchange, String channelIdStr, String method) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;
        int channelId = parsePositiveInt(channelIdStr);
        if (channelId <= 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid channelId.");
            return;
        }
        String role = roomDAO.getMemberRole(channelId, claims.userId());
        boolean allowed = "DELETE".equals(method) ? "OWNER".equalsIgnoreCase(role)
                : "OWNER".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);
        if (!allowed) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Insufficient channel permissions.");
            return;
        }
        Room room = roomDAO.findRoom(channelId);
        if (room == null) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Channel not found.");
            return;
        }
        if ("DELETE".equals(method)) {
            if (!chatServer.getRoomService().deleteRoom(channelId, claims.userId())) {
                JsonUtil.sendError(exchange, 409, "CONFLICT", "Channel could not be deleted.");
                return;
            }
            JsonUtil.sendJson(exchange, 200, Map.of("deleted", true, "channelId", channelId));
            return;
        }
        JsonObject request = JsonUtil.readJsonObject(exchange);
        String name = JsonUtil.getString(request, "name", room.getName()).trim();
        String topic = JsonUtil.getString(request, "topic", room.getTopic()).trim();
        if (name.isBlank() || name.length() > 100 || topic.length() > 1024) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid channel name or topic.");
            return;
        }
        Room updated = chatServer.getRoomService().updateRoom(channelId, claims.userId(), name, topic);
        if (updated == null) {
            JsonUtil.sendError(exchange, 409, "CONFLICT", "Channel could not be updated.");
            return;
        }
        JsonUtil.sendJson(exchange, 200, JsonUtil.toChannelDto(updated));
    }

    private void handleGetChannelMembers(HttpExchange exchange, String channelIdStr) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        int channelId = parsePositiveInt(channelIdStr);
        if (channelId <= 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid channelId.");
            return;
        }

        Room room = roomDAO.findRoom(channelId);
        if (room == null) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Channel not found.");
            return;
        }

        if (roomDAO.getMemberRole(channelId, claims.userId()) == null) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Channel membership required.");
            return;
        }

        List<User> members = roomDAO.getRoomMembers(channelId);
        List<Map<String, Object>> memberDtos = new ArrayList<>();
        for (User m : members) {
            memberDtos.add(JsonUtil.toUserDto(m, eventBus));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("members", memberDtos);
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleUpdateMemberRole(HttpExchange exchange, String channelIdStr, String targetUserIdStr) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        int channelId = parsePositiveInt(channelIdStr);
        int targetUserId = parsePositiveInt(targetUserIdStr);
        if (channelId <= 0 || targetUserId <= 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid channelId or userId.");
            return;
        }

        String actorRole = roomDAO.getMemberRole(channelId, claims.userId());
        if (!"OWNER".equalsIgnoreCase(actorRole)) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Only OWNER can update member roles.");
            return;
        }

        JsonObject req = JsonUtil.readJsonObject(exchange);
        String newRole = JsonUtil.getString(req, "role", "MEMBER").trim().toUpperCase(Locale.ROOT);
        if (!"OWNER".equals(newRole) && !"ADMIN".equals(newRole) && !"MEMBER".equals(newRole)) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Role must be OWNER, ADMIN, or MEMBER.");
            return;
        }

        boolean ok = "OWNER".equals(newRole)
                ? chatServer.getRoomService().transferOwnership(channelId, claims.userId(), targetUserId)
                : chatServer.getRoomService().setMemberRole(channelId, claims.userId(), targetUserId, newRole);
        if (!ok) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Failed to update member role.");
            return;
        }

        User updatedUser = userDAO.findById(targetUserId);
        if (updatedUser != null) {
            updatedUser.setRole(newRole);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("member", updatedUser != null ? JsonUtil.toUserDto(updatedUser, eventBus) : Map.of("userId", targetUserId, "role", newRole));
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleGetChannelMessages(HttpExchange exchange, String channelIdStr) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        int channelId = parsePositiveInt(channelIdStr);
        if (channelId <= 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid channelId.");
            return;
        }
        if (roomDAO.getMemberRole(channelId, claims.userId()) == null) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Channel membership is required.");
            return;
        }

        Map<String, String> query = JsonUtil.parseQueryParams(exchange.getRequestURI());
        Integer beforeId = parseOptionalPositiveInt(query.get("beforeId"));
        int limit = parsePositiveIntOrDefault(query.get("limit"), 50);
        if (limit > 100) limit = 100;

        List<Message> messages = messageDAO.getMessagesCursor(channelId, beforeId, limit);
        List<Map<String, Object>> msgDtos = new ArrayList<>();
        for (Message m : messages) {
            msgDtos.add(JsonUtil.toMessageDto(m, claims.userId(), "WS"));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("messages", msgDtos);
        resp.put("hasMore", messages.size() >= limit);
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleGetChannelPins(HttpExchange exchange, String channelIdStr) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        int channelId = parsePositiveInt(channelIdStr);
        if (channelId <= 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid channelId.");
            return;
        }
        if (roomDAO.getMemberRole(channelId, claims.userId()) == null) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Channel membership is required.");
            return;
        }

        List<Message> pins = messageDAO.getPinnedMessages(channelId);
        List<Map<String, Object>> pinDtos = new ArrayList<>();
        for (Message m : pins) {
            pinDtos.add(JsonUtil.toMessageDto(m, claims.userId(), "WS"));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("messages", pinDtos);
        resp.put("pins", pinDtos);
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleMarkChannelRead(HttpExchange exchange, String channelIdStr) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        int channelId = parsePositiveInt(channelIdStr);
        if (channelId <= 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid channelId.");
            return;
        }
        if (roomDAO.getMemberRole(channelId, claims.userId()) == null) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Channel membership is required.");
            return;
        }

        JsonObject req = JsonUtil.readJsonObject(exchange);
        int lastReadMessageId = JsonUtil.getInt(req, "lastReadMessageId", 0);
        RoomDAO.ReadResult result = roomDAO.markRead(claims.userId(), channelId, lastReadMessageId);
        if (result == RoomDAO.ReadResult.INVALID_MESSAGE) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Message does not belong to this channel.");
            return;
        }
        if (result != RoomDAO.ReadResult.UPDATED) {
            JsonUtil.sendError(exchange, 500, "INTERNAL_ERROR", "Could not update the read position.");
            return;
        }

        Integer unreadCount = roomDAO.getUnreadMessageCount(claims.userId(), channelId);
        Map<String, Object> readPayload = new LinkedHashMap<>();
        readPayload.put("channelId", channelId);
        readPayload.put("roomId", channelId);
        readPayload.put("userId", claims.userId());
        readPayload.put("lastReadMessageId", lastReadMessageId);
        readPayload.put("lastMessageId", lastReadMessageId);
        if (unreadCount != null) readPayload.put("unreadCount", unreadCount);
        eventBus.publishToUser(claims.userId(),
                "READ_UPDATED|" + channelId + "|" + claims.userId() + "|" + lastReadMessageId,
                "READ_UPDATED", readPayload);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("channelId", channelId);
        resp.put("roomId", channelId);
        resp.put("lastReadMessageId", lastReadMessageId);
        if (unreadCount != null) resp.put("unreadCount", unreadCount);
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        Map<String, String> query = JsonUtil.parseQueryParams(exchange.getRequestURI());
        String q = query.getOrDefault("q", "").trim();
        String rawGuildId = query.get("guildId");
        String rawChannelId = query.containsKey("channelId") ? query.get("channelId") : query.get("roomId");
        Integer guildId = parseOptionalPositiveInt(rawGuildId);
        Integer channelId = parseOptionalPositiveInt(rawChannelId);
        int limit = parsePositiveIntOrDefault(query.get("limit"), 25);
        if (limit > 50) limit = 50;

        if (rawGuildId != null && !rawGuildId.isBlank() && guildId == null
                || rawChannelId != null && !rawChannelId.isBlank() && channelId == null) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid search scope.");
            return;
        }
        if (guildId == null && channelId == null) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Choose a group or channel to search.");
            return;
        }

        if (q.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("messages", List.of());
            empty.put("results", List.of());
            JsonUtil.sendJson(exchange, 200, empty);
            return;
        }
        if (channelId != null && roomDAO.getMemberRole(channelId, claims.userId()) == null) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Channel membership is required to search messages.");
            return;
        }
        if (guildId != null
                && guildDAO.listGuildsForUser(claims.userId()).stream().noneMatch(guild -> guild.getId() == guildId)) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Guild membership is required to search messages.");
            return;
        }
        if (guildId != null && channelId != null) {
            Room room = roomDAO.findRoom(channelId);
            if (room == null || room.getGuildId() != guildId) {
                JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Channel does not belong to the selected group.");
                return;
            }
        }

        List<Message> matches = messageDAO.searchMessages(claims.userId(), guildId, channelId, q, limit);
        List<Map<String, Object>> dtos = new ArrayList<>();
        for (Message m : matches) {
            dtos.add(JsonUtil.toMessageDto(m, claims.userId(), "WS"));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("messages", dtos);
        resp.put("results", dtos);
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleFileUpload(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        Map<String, String> query = JsonUtil.parseQueryParams(exchange.getRequestURI());
        Integer channelId = parseOptionalPositiveInt(query.getOrDefault("channelId", query.get("roomId")));
        Long dmId = null;
        if (query.containsKey("dmId") && !query.get("dmId").isBlank()) {
            try {
                dmId = Long.parseLong(query.get("dmId").trim());
            } catch (NumberFormatException ignored) {
                JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid direct conversation id.");
                return;
            }
            if (dmId <= 0) {
                JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid direct conversation id.");
                return;
            }
        }
        if (query.containsKey("channelId") && channelId == null || query.containsKey("dmId") && dmId == null) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid upload destination.");
            return;
        }
        if (channelId == null && dmId == null) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Choose a channel or a direct conversation.");
            return;
        }
        if (channelId != null && dmId != null) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Choose a channel or a direct conversation for the file.");
            return;
        }
        if (channelId != null && roomDAO.getMemberRole(channelId, claims.userId()) == null) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Channel membership is required to upload files.");
            return;
        }
        Long selectedDmId = dmId;
        if (selectedDmId != null && directMessageDAO.listConversations(claims.userId()).stream().noneMatch(c -> c.getId() == selectedDmId)) {
            JsonUtil.sendError(exchange, 403, "FORBIDDEN", "Direct conversation membership is required to upload files.");
            return;
        }

        String rawFileName = exchange.getRequestHeaders().getFirst("X-File-Name");
        if (rawFileName == null || rawFileName.isBlank()) {
            rawFileName = query.getOrDefault("filename", "upload.bin");
        }
        String fileName = JsonUtil.urlDecode(rawFileName).trim();
        if (fileName.isEmpty()) {
            fileName = "upload.bin";
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || contentType.isBlank()) {
            contentType = guessMimeType(fileName);
        } else {
            int semi = contentType.indexOf(';');
            if (semi > 0) {
                contentType = contentType.substring(0, semi).trim();
            }
        }

        if (!isAllowedMimeType(contentType)) {
            JsonUtil.sendError(exchange, 415, "UNSUPPORTED_MEDIA_TYPE",
                    "Unsupported file type: " + contentType + ". Allowed: image/*, application/pdf, application/zip, text/plain.");
            return;
        }

        byte[] data;
        try (InputStream in = exchange.getRequestBody();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                total += read;
                if (total > MAX_UPLOAD_BYTES) {
                    JsonUtil.sendError(exchange, 413, "FILE_TOO_LARGE", "File exceeds maximum allowed size of 5 MB.");
                    return;
                }
                baos.write(buf, 0, read);
            }
            data = baos.toByteArray();
        }

        if (data.length == 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Uploaded file is empty.");
            return;
        }

        Attachment saved = attachmentDAO.saveAttachment(claims.userId(), channelId, dmId, fileName, contentType, data);
        if (saved == null) {
            JsonUtil.sendError(exchange, 500, "INTERNAL_ERROR", "Failed to save attachment to database.");
            return;
        }

        eventBus.recordProtocolFrame("IN", TransportType.HTTP,
                "PUT /api/files name=" + fileName + " size=" + data.length + "B", claims.userId());

        JsonUtil.sendJson(exchange, 201, JsonUtil.toAttachmentDto(saved));
    }

    private void handleFileDownload(HttpExchange exchange, String attachmentIdStr) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;
        long attachmentId;
        try {
            attachmentId = Long.parseLong(attachmentIdStr.trim());
        } catch (NumberFormatException e) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid attachmentId.");
            return;
        }

        Attachment att = attachmentDAO.getAttachmentWithData(attachmentId);
        if (att == null || att.getData() == null) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Attachment not found.");
            return;
        }
        boolean allowed = att.getUploaderId() == claims.userId();
        if (!allowed && att.getRoomId() != null) {
            allowed = roomDAO.getMemberRole(att.getRoomId(), claims.userId()) != null;
        }
        if (!allowed && att.getDirectConversationId() != null) {
            long conversationId = att.getDirectConversationId();
            allowed = directMessageDAO.listConversations(claims.userId()).stream()
                    .anyMatch(conversation -> conversation.getId() == conversationId);
        }
        if (!allowed) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Attachment not found.");
            return;
        }

        byte[] data = att.getData();
        String mime = att.getContentType() != null && !att.getContentType().isBlank()
                ? att.getContentType()
                : "application/octet-stream";
        String safeName = (att.getOriginalName() != null ? att.getOriginalName() : "file.bin")
                .replace("\"", "").replace("\r", "").replace("\n", "");
        boolean inline = "image/png".equalsIgnoreCase(mime)
                || "image/jpeg".equalsIgnoreCase(mime)
                || "image/gif".equalsIgnoreCase(mime)
                || "image/webp".equalsIgnoreCase(mime);
        String disposition = (inline ? "inline" : "attachment") + "; filename=\"" + safeName + "\"";

        JsonUtil.addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        exchange.getResponseHeaders().set("Content-Disposition", disposition);
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private void handleGetFriends(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        Map<String, Object> overview = buildFriendsOverview(claims.userId());
        JsonUtil.sendJson(exchange, 200, overview);
    }

    private void handleSearchFriends(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        Map<String, String> query = JsonUtil.parseQueryParams(exchange.getRequestURI());
        String q = query.getOrDefault("q", "").trim();
        List<User> users = friendDAO.searchUsers(claims.userId(), q);
        List<Map<String, Object>> userDtos = new ArrayList<>();
        for (User u : users) {
            userDtos.add(JsonUtil.toUserDto(u, eventBus));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("users", userDtos);
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleFriendRequest(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        JsonObject req = JsonUtil.readJsonObject(exchange);
        String username = JsonUtil.getString(req, "username", "").trim();
        Integer targetUserId = JsonUtil.getInteger(req, "targetUserId");
        if (targetUserId == null) {
            targetUserId = JsonUtil.getInteger(req, "userId");
        }

        User targetUser = null;
        if (targetUserId != null && targetUserId > 0) {
            targetUser = userDAO.findById(targetUserId);
        } else if (!username.isEmpty()) {
            targetUser = userDAO.findByUsername(username);
        }

        if (targetUser == null) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Target user not found.");
            return;
        }
        if (targetUser.getId() == claims.userId()) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Cannot send a friend request to yourself.");
            return;
        }

        String result = friendDAO.sendFriendRequest(claims.userId(), targetUser.getId());
        if ("ALREADY_FRIENDS".equals(result) || "ALREADY_REQUESTED".equals(result)) {
            JsonUtil.sendError(exchange, 409, "CONFLICT", "Friend request already exists or users are already friends (" + result + ").");
            return;
        }
        if ("INCOMING_EXISTS".equals(result)) {
            friendDAO.acceptRequestFromUser(targetUser.getId(), claims.userId());
            directMessageDAO.getOrCreateConversation(claims.userId(), targetUser.getId());
        } else if (!"OK".equals(result)) {
            JsonUtil.sendError(exchange, 500, "INTERNAL_ERROR", "Failed to send friend request.");
            return;
        }

        User sender = userDAO.findById(claims.userId());
        String tcpFrame = "FRIEND_REQUEST_INCOMING|" + claims.userId() + "|"
                + ProtocolHandler.encode(claims.username()) + "|"
                + ProtocolHandler.encode(claims.displayName());
        Map<String, Object> targetOverview = Map.of("overview", buildFriendsOverview(targetUser.getId()));
        eventBus.publishToUser(targetUser.getId(), tcpFrame, "FRIEND_UPDATED", targetOverview);

        Map<String, Object> reqDto = new LinkedHashMap<>();
        reqDto.put("id", 0);
        reqDto.put("fromUser", sender != null ? JsonUtil.toUserDto(sender, eventBus) : null);
        reqDto.put("toUser", JsonUtil.toUserDto(targetUser, eventBus));
        reqDto.put("status", "INCOMING_EXISTS".equals(result) ? "ACCEPTED" : "PENDING");
        reqDto.put("createdAt", Instant.now().toString());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", result);
        resp.put("request", reqDto);
        resp.put("overview", buildFriendsOverview(claims.userId()));
        JsonUtil.sendJson(exchange, 201, resp);
    }

    private void handleFriendRespond(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        JsonObject req = JsonUtil.readJsonObject(exchange);
        Integer requestId = JsonUtil.getInteger(req, "requestId");
        Integer senderId = JsonUtil.getInteger(req, "senderId");
        if (senderId == null) {
            senderId = JsonUtil.getInteger(req, "requesterId");
        }
        String action = JsonUtil.getString(req, "action", "").trim().toUpperCase(Locale.ROOT);
        boolean accept = "ACCEPT".equals(action) || "ACCEPTED".equals(action) || JsonUtil.getBoolean(req, "accept", false);

        // Lookup requesterId if only requestId was given
        if (senderId == null && requestId != null && requestId > 0) {
            for (FriendDAO.FriendRequestItem item : friendDAO.getPendingRequests(claims.userId())) {
                if (item.requestId() == requestId) {
                    senderId = item.senderId();
                    break;
                }
            }
        }

        boolean ok;
        if (accept) {
            if (requestId != null && requestId > 0) {
                ok = friendDAO.acceptRequest(requestId, claims.userId());
            } else if (senderId != null && senderId > 0) {
                ok = friendDAO.acceptRequestFromUser(senderId, claims.userId());
            } else {
                JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "requestId or senderId is required.");
                return;
            }
        } else {
            if (requestId != null && requestId > 0) {
                ok = friendDAO.rejectRequest(requestId, claims.userId());
            } else {
                JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "requestId is required to reject.");
                return;
            }
        }

        if (!ok) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Pending friend request not found.");
            return;
        }

        Long conversationId = null;
        if (accept && senderId != null && senderId > 0) {
            DirectConversation conv = directMessageDAO.getOrCreateConversation(claims.userId(), senderId);
            if (conv != null) {
                conversationId = conv.getId();
            }
            String tcpFrame = "FRIEND_ACCEPTED|" + claims.userId() + "|" + ProtocolHandler.encode(claims.displayName());
            Map<String, Object> senderOverview = Map.of("overview", buildFriendsOverview(senderId));
            eventBus.publishToUser(senderId, tcpFrame, "FRIEND_UPDATED", senderOverview);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", accept ? "ACCEPTED" : "REJECTED");
        resp.put("conversationId", conversationId);
        resp.put("overview", buildFriendsOverview(claims.userId()));
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private Map<String, Object> buildFriendsOverview(int userId) {
        User me = userDAO.findById(userId);
        Map<String, Object> meDto = me != null ? JsonUtil.toUserDto(me, eventBus) : Map.of("id", userId);

        List<User> friends = friendDAO.getFriends(userId);
        List<Map<String, Object>> friendDtos = new ArrayList<>();
        for (User f : friends) {
            friendDtos.add(JsonUtil.toUserDto(f, eventBus));
        }

        List<FriendDAO.FriendRequestItem> pendingIn = friendDAO.getPendingRequests(userId);
        List<Map<String, Object>> incomingDtos = new ArrayList<>();
        for (FriendDAO.FriendRequestItem item : pendingIn) {
            User from = userDAO.findById(item.senderId());
            Map<String, Object> fromDto = from != null ? JsonUtil.toUserDto(from, eventBus) : Map.of(
                    "id", item.senderId(),
                    "username", item.senderUsername(),
                    "displayName", item.senderDisplayName(),
                    "status", eventBus.isUserOnline(item.senderId()) ? "ONLINE" : "OFFLINE"
            );
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("id", item.requestId());
            req.put("requestId", item.requestId());
            req.put("senderId", item.senderId());
            req.put("senderUsername", item.senderUsername());
            req.put("senderDisplayName", item.senderDisplayName());
            req.put("fromUser", fromDto);
            req.put("toUser", meDto);
            req.put("status", "PENDING");
            req.put("createdAt", JsonUtil.toIsoTimestamp(item.createdAt()));
            incomingDtos.add(req);
        }

        List<Map<String, Object>> outgoingDtos = loadOutgoingFriendRequests(userId, meDto);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("friends", friendDtos);
        overview.put("pendingIncoming", incomingDtos);
        overview.put("pendingRequests", incomingDtos);
        overview.put("pendingOutgoing", outgoingDtos);
        return overview;
    }

    private List<Map<String, Object>> loadOutgoingFriendRequests(int userId, Map<String, Object> meDto) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT f.id, f.addressee_id, u.username, u.display_name, f.created_at " +
                     "FROM friendships f JOIN users u ON f.addressee_id = u.id " +
                     "WHERE f.requester_id = ? AND f.status = 'PENDING' ORDER BY f.created_at DESC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int reqId = rs.getInt("id");
                    int toId = rs.getInt("addressee_id");
                    Timestamp ts = rs.getTimestamp("created_at");
                    User toUser = userDAO.findById(toId);
                    Map<String, Object> toDto = toUser != null ? JsonUtil.toUserDto(toUser, eventBus) : Map.of(
                            "id", toId,
                            "username", rs.getString("username"),
                            "displayName", rs.getString("display_name")
                    );
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", reqId);
                    item.put("requestId", reqId);
                    item.put("fromUser", meDto);
                    item.put("toUser", toDto);
                    item.put("status", "PENDING");
                    item.put("createdAt", JsonUtil.toIsoTimestamp(ts));
                    list.add(item);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private void handleListDms(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        List<DirectConversation> convs = directMessageDAO.listConversations(claims.userId());
        List<Map<String, Object>> dtos = new ArrayList<>();
        for (DirectConversation dc : convs) {
            dtos.add(JsonUtil.toDirectConversationDto(dc, eventBus));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("conversations", dtos);
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleCreateDm(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        JsonObject req = JsonUtil.readJsonObject(exchange);
        Integer peerUserId = JsonUtil.getInteger(req, "peerUserId");
        if (peerUserId == null) {
            peerUserId = JsonUtil.getInteger(req, "targetUserId");
        }
        if (peerUserId == null) {
            peerUserId = JsonUtil.getInteger(req, "userId");
        }

        if (peerUserId == null || peerUserId <= 0 || peerUserId == claims.userId()) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Valid peerUserId is required.");
            return;
        }

        DirectConversation conv = directMessageDAO.getOrCreateConversation(claims.userId(), peerUserId);
        if (conv == null) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Could not create DM conversation.");
            return;
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("conversation", JsonUtil.toDirectConversationDto(conv, eventBus));
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleGetDmMessages(HttpExchange exchange, String convIdStr) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        long convId;
        try {
            convId = Long.parseLong(convIdStr.trim());
        } catch (NumberFormatException e) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid conversationId.");
            return;
        }
        boolean conversationMember = directMessageDAO.listConversations(claims.userId()).stream()
                .anyMatch(conversation -> conversation.getId() == convId);
        if (!conversationMember) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Conversation not found.");
            return;
        }

        Map<String, String> query = JsonUtil.parseQueryParams(exchange.getRequestURI());
        Long beforeId = null;
        if (query.containsKey("beforeId") && !query.get("beforeId").isBlank()) {
            try {
                beforeId = Long.parseLong(query.get("beforeId").trim());
            } catch (NumberFormatException ignored) {}
        }
        int limit = parsePositiveIntOrDefault(query.get("limit"), 50);
        if (limit > 100) limit = 100;

        List<DirectMessage> messages = directMessageDAO.getMessages(convId, beforeId, limit);
        List<Map<String, Object>> dtos = new ArrayList<>();
        for (DirectMessage dm : messages) {
            dtos.add(JsonUtil.toDirectMessageDto(dm, claims.userId()));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("messages", dtos);
        resp.put("hasMore", messages.size() >= limit);
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private void handleMarkDmRead(HttpExchange exchange, String convIdStr) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;
        long conversationId;
        try {
            conversationId = Long.parseLong(convIdStr.trim());
        } catch (NumberFormatException e) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid conversationId.");
            return;
        }
        if (conversationId <= 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "Invalid conversationId.");
            return;
        }
        boolean conversationMember = directMessageDAO.listConversations(claims.userId()).stream()
                .anyMatch(conversation -> conversation.getId() == conversationId);
        if (!conversationMember) {
            JsonUtil.sendError(exchange, 404, "NOT_FOUND", "Conversation not found.");
            return;
        }

        JsonObject req = JsonUtil.readJsonObject(exchange);
        long requestedMessageId = JsonUtil.getLong(req, "lastReadMessageId", 0L);
        if (requestedMessageId <= 0) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "A positive lastReadMessageId is required.");
            return;
        }
        if (directMessageDAO.findConversationIdForMessage(requestedMessageId) != conversationId) {
            JsonUtil.sendError(exchange, 400, "VALIDATION_ERROR", "lastReadMessageId must belong to this conversation.");
            return;
        }
        long appliedMessageId = directMessageDAO.markConversationRead(conversationId, claims.userId(), requestedMessageId);
        if (appliedMessageId <= 0) {
            JsonUtil.sendError(exchange, 500, "DATABASE_ERROR", "Could not update conversation read state.");
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("lastReadMessageId", appliedMessageId);
        response.put("unreadCount", directMessageDAO.getUnreadCount(conversationId, claims.userId()));
        JsonUtil.sendJson(exchange, 200, response);
    }

    private void handleMonitorUdpProbe(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        JsonObject req = "POST".equalsIgnoreCase(exchange.getRequestMethod())
                ? JsonUtil.readJsonObject(exchange)
                : new JsonObject();
        int seq = JsonUtil.getInt(req, "seq", 1);
        long clientTs = JsonUtil.getLong(req, "clientTimestampMs", System.currentTimeMillis());

        int targetUdpPort = udpServer != null && udpServer.getUdpPort() > 0
                ? udpServer.getUdpPort()
                : UdpTelemetryServer.DEFAULT_UDP_PORT;

        long micros = -1L;
        if (udpServer != null && udpServer.isUdpActive()) {
            micros = udpServer.probeLocalUdpRttMicros(seq, claims.userId());
        }
        if (micros < 0) {
            micros = directLoopbackUdpProbeMicros(targetUdpPort, seq, clientTs, claims.userId());
        }

        long serverTs = System.currentTimeMillis();
        boolean udpActive = micros >= 0;
        double udpRttMs = udpActive ? Math.round(micros / 10.0) / 100.0 : -1.0;
        String pingFrame = "UDP_PING|" + seq + "|" + clientTs + "|" + claims.userId();
        String pongFrame = "UDP_PONG|" + seq + "|" + clientTs + "|" + serverTs;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("seq", seq);
        resp.put("clientTimestampMs", clientTs);
        resp.put("serverTimestampMs", serverTs);
        resp.put("udpRttMs", udpActive ? udpRttMs : null);
        resp.put("udpPort", targetUdpPort);
        resp.put("udpActive", udpActive);
        resp.put("rawDatagramSent", pingFrame);
        resp.put("rawDatagramReceived", udpActive ? pongFrame : null);
        resp.put("transport", udpActive ? "UDP" : "UDP_FALLBACK");
        resp.put("frames", eventBus.getRecentFrames(30));

        JsonUtil.sendJson(exchange, 200, resp);
    }

    private long directLoopbackUdpProbeMicros(int udpPort, int seq, long clientTs, int userId) {
        if (udpPort <= 0) return -1L;
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(400);
            String ping = "UDP_PING|" + seq + "|" + clientTs + "|" + userId;
            byte[] out = ping.getBytes(StandardCharsets.UTF_8);
            DatagramPacket req = new DatagramPacket(out, out.length, InetAddress.getByName("127.0.0.1"), udpPort);
            long startNs = System.nanoTime();
            sock.send(req);

            byte[] buf = new byte[512];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            sock.receive(resp);
            long elapsedMicros = Math.max(1L, (System.nanoTime() - startNs) / 1_000L);
            String reply = new String(resp.getData(), resp.getOffset(), resp.getLength(), StandardCharsets.UTF_8).trim();
            return reply.startsWith("UDP_PONG|") ? elapsedMicros : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private void handleMonitorFrames(HttpExchange exchange) throws IOException {
        JwtUtil.JwtClaims claims = authenticate(exchange);
        if (claims == null) return;

        Map<String, String> query = JsonUtil.parseQueryParams(exchange.getRequestURI());
        int limit = parsePositiveIntOrDefault(query.get("limit"), 50);
        List<ServerEvent.FrameRecord> frames = eventBus.getRecentFrames(limit);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("frames", frames);
        JsonUtil.sendJson(exchange, 200, resp);
    }

    private static boolean isAllowedMimeType(String mime) {
        if (mime == null || mime.isBlank()) return false;
        String lower = mime.toLowerCase(Locale.ROOT).trim();
        return !lower.equals("text/html")
                && !lower.equals("application/xhtml+xml")
                && !lower.equals("image/svg+xml")
                && !lower.contains("javascript")
                && !lower.contains("x-sh")
                && !lower.contains("x-msdownload")
                && !lower.contains("x-executable");
    }

    private static String guessMimeType(String fileName) {
        String lower = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md")) return "text/plain";
        return "application/octet-stream";
    }

    private static int parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        try {
            int val = Integer.parseInt(raw.trim());
            return val > 0 ? val : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean isGuildManager(int guildId, int userId) {
        String role = guildDAO.getMemberRole(guildId, userId);
        return "OWNER".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);
    }

    private static Integer parseOptionalPositiveInt(String raw) {
        int val = parsePositiveInt(raw);
        return val > 0 ? val : null;
    }

    private static int parsePositiveIntOrDefault(String raw, int defaultVal) {
        int val = parsePositiveInt(raw);
        return val > 0 ? val : defaultVal;
    }
}
