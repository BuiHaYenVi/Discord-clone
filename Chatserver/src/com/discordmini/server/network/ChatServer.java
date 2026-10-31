package com.discordmini.server.network;

import com.discordmini.server.dao.AttachmentDAO;
import com.discordmini.server.dao.DirectMessageDAO;
import com.discordmini.server.dao.FriendDAO;
import com.discordmini.server.dao.GuildDAO;
import com.discordmini.server.dao.MessageDAO;
import com.discordmini.server.dao.RoomDAO;
import com.discordmini.server.dao.UserDAO;
import com.discordmini.server.manager.ClientManager;
import com.discordmini.server.manager.RoomManager;
import com.discordmini.server.model.Room;
import com.discordmini.server.model.User;
import com.discordmini.server.protocol.ProtocolHandler;
import com.discordmini.server.service.AuthService;
import com.discordmini.server.service.ChatService;
import com.discordmini.server.service.DirectMessageService;
import com.discordmini.server.service.FriendService;
import com.discordmini.server.service.GuildService;
import com.discordmini.server.service.RoomService;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.udp.UdpTelemetryServer;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatServer {
    public static final int PORT = 5000;

    private final int port;
    private final EventBus eventBus = EventBus.getInstance();

    private final UserDAO userDAO = new UserDAO();
    private final RoomDAO roomDAO = new RoomDAO();
    private final MessageDAO messageDAO = new MessageDAO();
    private final FriendDAO friendDAO = new FriendDAO();
    private final GuildDAO guildDAO = new GuildDAO();
    private final AttachmentDAO attachmentDAO = new AttachmentDAO();
    private final DirectMessageDAO directMessageDAO = new DirectMessageDAO();

    private final ClientManager clientManager = new ClientManager();
    private final RoomManager roomManager = new RoomManager();

    private final AuthService authService;
    private final RoomService roomService;
    private final ChatService chatService;
    private final FriendService friendService;
    private final GuildService guildService;
    private final DirectMessageService directMessageService;
    private final UdpTelemetryServer udpServer;

    private Object webGatewayInstance;

    private final AtomicInteger threadCounter = new AtomicInteger(1);
    private final ExecutorService pool;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public ChatServer() throws IOException {
        this(resolveTcpPort());
    }

    public ChatServer(int port) throws IOException {
        this(port, true);
    }

    protected ChatServer(int port, boolean initializeDatabase) throws IOException {
        this.port = port;
        this.authService = new AuthService(userDAO, eventBus);
        this.roomService = new RoomService(roomDAO, eventBus);
        this.chatService = new ChatService(messageDAO, roomDAO, eventBus);
        this.friendService = new FriendService(friendDAO, eventBus);
        this.guildService = new GuildService(guildDAO, roomDAO, eventBus);
        this.directMessageService = new DirectMessageService(directMessageDAO, userDAO, eventBus);
        this.udpServer = new UdpTelemetryServer(eventBus);

        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ChatServer-Worker-" + threadCounter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

    }

    private static int resolveTcpPort() {
        String envPort = System.getenv("TCP_PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                return Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {}
        }
        return PORT;
    }

    public void start() throws IOException {
        synchronized (this) {
            if (running) return;
            serverSocket = new ServerSocket(port);
            running = true;
        }

        // Start UDP telemetry server (:5001)
        udpServer.start();

        // Start HTTP REST + WebSocket Gateway (:8080) if WebGatewayBootstrap is compiled
        startWebGatewayIfPresent();

        System.out.println("====================================");
        System.out.println(" Discord Mini Chat Server (PostgreSQL)");
        System.out.println(" TCP Port: " + port);
        System.out.println(" UDP Port: " + (udpServer.isUdpActive() ? udpServer.getUdpPort() : "Fallback Mode"));
        System.out.println(" Database: PostgreSQL (discord_mini)");
        System.out.println("====================================");

        try {
            while (running && serverSocket != null && !serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setKeepAlive(true);
                    System.out.println("[CONNECT] " + socket.getRemoteSocketAddress());
                    pool.submit(new ClientHandler(socket, this));
                } catch (IOException e) {
                    if (!running || serverSocket.isClosed()) {
                        break;
                    }
                    System.err.println("[SERVER ACCEPT ERROR] " + e.getMessage());
                }
            }
        } finally {
            stop();
        }
    }

    private void startWebGatewayIfPresent() {
        try {
            Class<?> bootstrapClass = Class.forName("com.discordmini.server.web.WebGatewayBootstrap");
            // 1. Try static start(ChatServer)
            try {
                Method staticStart = bootstrapClass.getMethod("start", ChatServer.class);
                if (java.lang.reflect.Modifier.isStatic(staticStart.getModifiers())) {
                    webGatewayInstance = staticStart.invoke(null, this);
                    return;
                }
            } catch (NoSuchMethodException ignored) {}

            // 2. Try constructor WebGatewayBootstrap(ChatServer) + start()
            try {
                var ctor = bootstrapClass.getConstructor(ChatServer.class);
                webGatewayInstance = ctor.newInstance(this);
                Method startMethod = bootstrapClass.getMethod("start");
                startMethod.invoke(webGatewayInstance);
                return;
            } catch (NoSuchMethodException ignored) {}

            // 3. Try default constructor + start(ChatServer) or start()
            var defaultCtor = bootstrapClass.getDeclaredConstructor();
            defaultCtor.setAccessible(true);
            webGatewayInstance = defaultCtor.newInstance();
            try {
                Method startWithServer = bootstrapClass.getMethod("start", ChatServer.class);
                startWithServer.invoke(webGatewayInstance, this);
            } catch (NoSuchMethodException e) {
                Method startNoArgs = bootstrapClass.getMethod("start");
                startNoArgs.invoke(webGatewayInstance);
            }
        } catch (ClassNotFoundException ignored) {
            // WebGatewayBootstrap not yet on classpath
        } catch (Exception e) {
            System.err.println("[WEB GATEWAY WARNING] Could not start WebGatewayBootstrap: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (!running && (serverSocket == null || serverSocket.isClosed())) {
            return;
        }
        running = false;
        try {
            udpServer.stop();
        } catch (Exception ignored) {}

        if (webGatewayInstance != null) {
            try {
                Method stopMethod = webGatewayInstance.getClass().getMethod("stop");
                stopMethod.invoke(webGatewayInstance);
            } catch (Exception ignored) {}
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        // Thông báo ngắt tới toàn bộ client đang online
        for (ClientHandler client : clientManager.all()) {
            try {
                client.sendPacket("SYSTEM|" + ProtocolHandler.encode("Server đang tắt để bảo trì."));
            } catch (Exception ignored) {}
        }

        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("[SERVER] Đã dừng server an toàn trên cổng " + port);
    }

    public int getPort() { return port; }
    public boolean isRunning() { return running && serverSocket != null && !serverSocket.isClosed(); }
    public EventBus getEventBus() { return eventBus; }
    public UserDAO getUserDAO() { return userDAO; }
    public RoomDAO getRoomDAO() { return roomDAO; }
    public MessageDAO getMessageDAO() { return messageDAO; }
    public FriendDAO getFriendDAO() { return friendDAO; }
    public GuildDAO getGuildDAO() { return guildDAO; }
    public AttachmentDAO getAttachmentDAO() { return attachmentDAO; }
    public DirectMessageDAO getDirectMessageDAO() { return directMessageDAO; }
    public ClientManager getClientManager() { return clientManager; }
    public RoomManager getRoomManager() { return roomManager; }
    public AuthService getAuthService() { return authService; }
    public RoomService getRoomService() { return roomService; }
    public ChatService getChatService() { return chatService; }
    public FriendService getFriendService() { return friendService; }
    public GuildService getGuildService() { return guildService; }
    public DirectMessageService getDirectMessageService() { return directMessageService; }
    public UdpTelemetryServer getUdpServer() { return udpServer; }
    public UdpTelemetryServer getUdpTelemetryServer() { return udpServer; }
}
