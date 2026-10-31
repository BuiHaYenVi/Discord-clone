package com.discordmini.server.web.ws;

import com.discordmini.server.auth.JwtUtil;
import com.discordmini.server.dao.GuildDAO;
import com.discordmini.server.dao.MessageDAO;
import com.discordmini.server.dao.RoomDAO;
import com.discordmini.server.dao.UserDAO;
import com.discordmini.server.model.Message;
import com.discordmini.server.model.Room;
import com.discordmini.server.model.User;
import com.discordmini.server.network.ChatServer;
import com.discordmini.server.protocol.ProtocolHandler;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.session.Session;
import com.discordmini.server.session.TransportType;
import com.discordmini.server.web.http.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure Java RFC 6455 WebSocket Server & Frame Codec (Academic Bonus Feature - CMP180).
 *
 * <p>Implements the WebSocket protocol (RFC 6455) directly over raw TCP {@link ServerSocket}
 * and {@link Socket} streams without third-party WebSocket framing libraries:
 * <ul>
 *   <li>Section 4.2.2: HTTP/1.1 Upgrade Handshake with SHA-1 + Base64 {@code Sec-WebSocket-Accept}</li>
 *   <li>Section 5.2: Binary Frame Header parsing (FIN, 4-bit Opcode, MASK bit, 7/16/64-bit Payload Length)</li>
 *   <li>Section 5.3: Client-to-Server 32-bit XOR Masking/Unmasking ({@code payload[i] ^= maskKey[i & 3]})</li>
 *   <li>Section 5.5: Control Frames (Close {@code 0x8}, Ping {@code 0x9}, Pong {@code 0xA})</li>
 *   <li>Session Bridge: Implements {@link Session} to integrate with {@link EventBus} and {@link ChatServer}</li>
 * </ul>
 */
public class Rfc6455NativeWebSocketServer implements Session, AutoCloseable {

    /**
     * Globally Unique Identifier defined in RFC 6455 Section 1.3 / 4.2.2.
     */
    public static final String RFC6455_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // RFC 6455 Section 5.2 Opcodes
    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    /**
     * Maximum allowed frame payload size (65,536 bytes) matching PROTOCOL.md limits.
     */
    public static final int MAX_FRAME_SIZE = 65_536;

    public static final int DEFAULT_NATIVE_WS_PORT = 8082;

    /**
     * Immutable representation of an RFC 6455 WebSocket frame.
     */
    public record WsFrame(boolean fin, int opcode, boolean masked, byte[] payload) {
        public String payloadAsText() {
            return payload != null ? new String(payload, StandardCharsets.UTF_8) : "";
        }
    }

    /**
     * Parsed HTTP/1.1 WebSocket Upgrade request metadata.
     */
    public record HandshakeRequest(
            String requestLine,
            String path,
            String secWebSocketKey,
            String token,
            Map<String, String> headers,
            Map<String, String> queryParams
    ) {}

    private final int port;
    private final ChatServer chatServer;
    private final EventBus eventBus;
    private final UserDAO userDAO;
    private final RoomDAO roomDAO;
    private final MessageDAO messageDAO;
    private final GuildDAO guildDAO;

    private final Set<NativeWsSession> activeSessions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger workerCounter = new AtomicInteger(1);
    private ExecutorService executor;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    // Representative session fields when Rfc6455NativeWebSocketServer itself is inspected as a Session
    private final String serverSessionId = "native-ws-server-" + UUID.randomUUID();
    private volatile User serverSessionUser;
    private volatile Integer serverSessionRoomId;

    public Rfc6455NativeWebSocketServer() {
        this(resolveNativeWsPort(), null, EventBus.getInstance());
    }

    public Rfc6455NativeWebSocketServer(int port) {
        this(port, null, EventBus.getInstance());
    }

    public Rfc6455NativeWebSocketServer(int port, EventBus eventBus) {
        this(port, null, eventBus);
    }

    public Rfc6455NativeWebSocketServer(int port, ChatServer chatServer, EventBus eventBus) {
        this.port = port > 0 ? port : resolveNativeWsPort();
        this.chatServer = chatServer;
        this.eventBus = eventBus != null
                ? eventBus
                : (chatServer != null && chatServer.getEventBus() != null ? chatServer.getEventBus() : EventBus.getInstance());
        this.userDAO = chatServer != null && chatServer.getUserDAO() != null ? chatServer.getUserDAO() : new UserDAO();
        this.roomDAO = chatServer != null && chatServer.getRoomDAO() != null ? chatServer.getRoomDAO() : new RoomDAO();
        this.messageDAO = chatServer != null && chatServer.getMessageDAO() != null ? chatServer.getMessageDAO() : new MessageDAO();
        this.guildDAO = chatServer != null && chatServer.getGuildDAO() != null ? chatServer.getGuildDAO() : new GuildDAO();
    }

    public static int resolveNativeWsPort() {
        String envPort = System.getenv("RFC6455_WS_PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                return Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_NATIVE_WS_PORT;
    }

    public static boolean isNativeModeEnabled() {
        String flag = System.getenv("USE_NATIVE_RFC6455_WS");
        return "true".equalsIgnoreCase(flag) || "1".equals(flag);
    }

    // =========================================================================
    // 1. RFC 6455 Handshake (Section 4.2.2)
    // =========================================================================

    /**
     * Computes the RFC 6455 {@code Sec-WebSocket-Accept} header value:
     * {@code Base64(SHA-1(secWebSocketKey.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))}.
     */
    public static String computeAcceptKey(String secWebSocketKey) {
        if (secWebSocketKey == null || secWebSocketKey.isBlank()) {
            throw new IllegalArgumentException("Sec-WebSocket-Key must not be null or blank");
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            String concatenated = secWebSocketKey.trim() + RFC6455_GUID;
            byte[] digest = sha1.digest(concatenated.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available in JDK", e);
        }
    }

    /**
     * Builds the raw HTTP/1.1 101 Switching Protocols response bytes for a valid client key.
     */
    public static byte[] buildHandshakeResponse(String secWebSocketKey) {
        String acceptKey = computeAcceptKey(secWebSocketKey);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey + "\r\n"
                + "\r\n";
        return response.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses an incoming HTTP/1.1 WebSocket Upgrade request from the socket input stream
     * and writes the {@code 101 Switching Protocols} response to the output stream.
     */
    public static HandshakeRequest performHandshake(InputStream in, OutputStream out) throws IOException {
        HandshakeRequest req = parseHandshakeRequest(in);
        byte[] responseBytes = buildHandshakeResponse(req.secWebSocketKey());
        out.write(responseBytes);
        out.flush();
        return req;
    }

    /**
     * Reads and validates the HTTP/1.1 Upgrade header block terminated by {@code \r\n\r\n}.
     */
    public static HandshakeRequest parseHandshakeRequest(InputStream in) throws IOException {
        String rawHeaders = readHttpHeaderBlock(in);
        String[] lines = rawHeaders.split("\r?\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new IOException("Empty HTTP upgrade request");
        }

        String requestLine = lines[0].trim();
        String[] parts = requestLine.split("\\s+");
        if (parts.length < 2 || !"GET".equalsIgnoreCase(parts[0])) {
            throw new IOException("Invalid WebSocket HTTP upgrade request line: " + requestLine);
        }

        String targetUri = parts[1];
        String path = targetUri;
        Map<String, String> queryParams = Collections.emptyMap();
        int qIdx = targetUri.indexOf('?');
        if (qIdx >= 0) {
            path = targetUri.substring(0, qIdx);
            queryParams = JsonUtil.parseQueryString(targetUri.substring(qIdx + 1));
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = line.substring(colon + 1).trim();
                headers.put(name, val);
            }
        }

        String upgrade = headers.getOrDefault("upgrade", "");
        if (!upgrade.toLowerCase(Locale.ROOT).contains("websocket")) {
            throw new IOException("Missing or invalid 'Upgrade: websocket' header");
        }

        String secKey = headers.get("sec-websocket-key");
        if (secKey == null || secKey.isBlank()) {
            throw new IOException("Missing 'Sec-WebSocket-Key' header");
        }

        String token = queryParams.get("token");
        if ((token == null || token.isBlank()) && headers.containsKey("authorization")) {
            String auth = headers.get("authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = auth.substring(7).trim();
            }
        }

        return new HandshakeRequest(requestLine, path, secKey.trim(), token, headers, queryParams);
    }

    private static String readHttpHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0; // matches \r\n\r\n
        while (buf.size() < 16_384) {
            int b = in.read();
            if (b == -1) {
                if (buf.size() == 0) {
                    throw new EOFException("Connection closed before HTTP upgrade handshake");
                }
                break;
            }
            buf.write(b);
            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state++;
                if (state == 4) {
                    break;
                }
            } else if (b == '\n' && state == 1) {
                state = 2;
            } else {
                state = 0;
            }
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    // =========================================================================
    // 2. RFC 6455 Frame Codec (Section 5.2 & 5.3)
    // =========================================================================

    /**
     * Reads a single RFC 6455 frame from the given {@link InputStream}.
     *
     * <pre>
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-------+-+-------------+-------------------------------+
     * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
     * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
     * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
     * | |1|2|3|       |K|             |                               |
     * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
     * |     Extended payload length continued, if payload len == 127  |
     * + - - - - - - - - - - - - - - - +-------------------------------+
     * |                               |Masking-key, if MASK set to 1  |
     * +-------------------------------+-------------------------------+
     * | Masking-key (continued)       |          Payload Data         |
     * +-------------------------------- - - - - - - - - - - - - - - - +
     * </pre>
     *
     * @param in stream to read from
     * @return decoded {@link WsFrame}, or {@code null} if clean EOF occurs before the first byte
     * @throws IOException if the frame is malformed, exceeds {@link #MAX_FRAME_SIZE}, or EOF occurs mid-frame
     */
    public static WsFrame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) {
            return null;
        }
        int b1 = readByteStrict(in);

        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long payloadLen = b1 & 0x7F;

        if (payloadLen == 126) {
            int hi = readByteStrict(in);
            int lo = readByteStrict(in);
            payloadLen = ((hi & 0xFFL) << 8) | (lo & 0xFFL);
        } else if (payloadLen == 127) {
            long len64 = 0;
            for (int i = 0; i < 8; i++) {
                len64 = (len64 << 8) | (readByteStrict(in) & 0xFFL);
            }
            if (len64 < 0) {
                throw new IOException("Invalid negative 64-bit WebSocket frame length: " + len64);
            }
            payloadLen = len64;
        }

        if (payloadLen > MAX_FRAME_SIZE) {
            throw new IOException("WebSocket frame payload exceeds maximum size ("
                    + payloadLen + " > " + MAX_FRAME_SIZE + ")");
        }

        byte[] maskKey = null;
        if (masked) {
            maskKey = readFully(in, 4);
        }

        byte[] payload = readFully(in, (int) payloadLen);
        if (masked && maskKey != null) {
            applyMask(payload, maskKey);
        }

        return new WsFrame(fin, opcode, masked, payload);
    }

    /**
     * Encodes an unmasked Server-to-Client RFC 6455 frame with {@code FIN = 1}.
     */
    public static byte[] encodeFrame(int opcode, byte[] payload) {
        return encodeFrame(true, opcode, payload, null);
    }

    /**
     * Encodes a UTF-8 text Server-to-Client RFC 6455 frame with {@code FIN = 1}.
     */
    public static byte[] encodeTextFrame(String text) {
        byte[] bytes = text != null ? text.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return encodeFrame(OPCODE_TEXT, bytes);
    }

    /**
     * Encodes a masked Client-to-Server RFC 6455 frame (for round-trip unit tests and client simulation).
     */
    public static byte[] encodeMaskedClientFrame(int opcode, byte[] payload, byte[] maskKey) {
        if (maskKey == null || maskKey.length != 4) {
            throw new IllegalArgumentException("RFC 6455 client masking key must be exactly 4 bytes");
        }
        return encodeFrame(true, opcode, payload, maskKey);
    }

    /**
     * Encodes a WebSocket frame with configurable FIN bit, opcode, payload, and optional 4-byte mask key.
     */
    public static byte[] encodeFrame(boolean fin, int opcode, byte[] payload, byte[] maskKey) {
        byte[] data = payload != null ? payload : new byte[0];
        int len = data.length;
        boolean masked = maskKey != null;
        if (masked && maskKey.length != 4) {
            throw new IllegalArgumentException("Masking key must be 4 bytes");
        }

        int headerSize = 2 + (len <= 125 ? 0 : (len <= 65_535 ? 2 : 8)) + (masked ? 4 : 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream(headerSize + len);

        int b0 = (fin ? 0x80 : 0x00) | (opcode & 0x0F);
        out.write(b0);

        int maskBit = masked ? 0x80 : 0x00;
        if (len <= 125) {
            out.write(maskBit | len);
        } else if (len <= 65_535) {
            out.write(maskBit | 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(maskBit | 127);
            long l = len & 0xFFFFFFFFL;
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((l >>> shift) & 0xFF));
            }
        }

        if (masked) {
            out.write(maskKey, 0, 4);
            byte[] maskedPayload = data.clone();
            applyMask(maskedPayload, maskKey);
            out.write(maskedPayload, 0, maskedPayload.length);
        } else {
            out.write(data, 0, data.length);
        }

        return out.toByteArray();
    }

    /**
     * Applies the symmetric RFC 6455 Section 5.3 XOR mask in-place:
     * {@code payload[i] ^= maskKey[i & 3]}.
     */
    public static void applyMask(byte[] payload, byte[] maskKey) {
        if (payload == null || maskKey == null || maskKey.length < 4) {
            return;
        }
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= maskKey[i & 3];
        }
    }

    private static int readByteStrict(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("Unexpected EOF while reading RFC 6455 frame");
        }
        return b;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read == -1) {
                throw new EOFException("Unexpected EOF after reading " + offset + " of " + length + " bytes");
            }
            offset += read;
        }
        return buffer;
    }

    // =========================================================================
    // 3. Standalone Server Lifecycle & EventBus Session Bridge
    // =========================================================================

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
        running = true;

        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Rfc6455-WS-Worker-" + workerCounter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

        Thread acceptThread = new Thread(this::acceptLoop, "Rfc6455-WS-Acceptor-" + port);
        acceptThread.setDaemon(true);
        acceptThread.start();

        System.out.println("[RFC6455-WS] Native Pure-Java WebSocket Server listening on ws://0.0.0.0:" + getPort());
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                clientSocket.setKeepAlive(true);
                clientSocket.setSoTimeout(45_000);
                executor.submit(() -> handleClientConnection(clientSocket));
            } catch (IOException e) {
                if (!running || serverSocket == null || serverSocket.isClosed()) {
                    break;
                }
                System.err.println("[RFC6455-WS ACCEPT ERROR] " + e.getMessage());
            }
        }
    }

    private void handleClientConnection(Socket socket) {
        NativeWsSession session = null;
        try (Socket sock = socket;
             InputStream in = sock.getInputStream();
             OutputStream out = sock.getOutputStream()) {

            HandshakeRequest handshake = performHandshake(in, out);
            session = new NativeWsSession(sock, out, eventBus);
            activeSessions.add(session);

            if (handshake.token() != null && !handshake.token().isBlank()) {
                authenticateSession(session, handshake.token(), null);
            }

            ByteArrayOutputStream fragmentedBuffer = null;
            int fragmentedOpcode = OPCODE_TEXT;

            while (running && session.isOpen()) {
                WsFrame frame = readFrame(in);
                if (frame == null) {
                    break;
                }

                switch (frame.opcode()) {
                    case OPCODE_PING -> {
                        session.sendRawFrame(OPCODE_PONG, frame.payload());
                    }
                    case OPCODE_PONG -> {
                        // Heartbeat pong received
                    }
                    case OPCODE_CLOSE -> {
                        session.sendRawFrame(OPCODE_CLOSE, frame.payload());
                        return;
                    }
                    case OPCODE_TEXT, OPCODE_BINARY -> {
                        if (frame.fin()) {
                            onTextMessage(session, frame.payloadAsText());
                        } else {
                            fragmentedOpcode = frame.opcode();
                            fragmentedBuffer = new ByteArrayOutputStream();
                            fragmentedBuffer.write(frame.payload());
                        }
                    }
                    case OPCODE_CONTINUATION -> {
                        if (fragmentedBuffer != null) {
                            if (fragmentedBuffer.size() + frame.payload().length > MAX_FRAME_SIZE) {
                                throw new IOException("Fragmented WebSocket message exceeds MAX_FRAME_SIZE");
                            }
                            fragmentedBuffer.write(frame.payload());
                            if (frame.fin()) {
                                String fullText = fragmentedBuffer.toString(StandardCharsets.UTF_8);
                                fragmentedBuffer = null;
                                if (fragmentedOpcode == OPCODE_TEXT || fragmentedOpcode == OPCODE_BINARY) {
                                    onTextMessage(session, fullText);
                                }
                            }
                        }
                    }
                    default -> {
                        session.sendError(null, "BAD_REQUEST", "Unsupported RFC 6455 opcode: " + frame.opcode());
                    }
                }
            }
        } catch (IOException ignored) {
            // Client disconnected or read timed out
        } finally {
            if (session != null) {
                activeSessions.remove(session);
                cleanupSession(session);
            }
        }
    }

    private void onTextMessage(NativeWsSession session, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        User currentUser = session.getUser();
        if (!message.contains("\"PING_RTT\"") && !message.contains("\"PROTOCOL_FRAME_LOG\"")) {
            eventBus.recordProtocolFrame("IN", TransportType.WS, message, currentUser != null ? currentUser.getId() : null);
        }

        String rid = null;
        try {
            JsonElement parsed = JsonParser.parseString(message);
            if (!parsed.isJsonObject()) {
                session.sendError(null, "BAD_REQUEST", "WebSocket payload must be a JSON object.");
                return;
            }
            JsonObject envelope = parsed.getAsJsonObject();
            String type = JsonUtil.getString(envelope, "type", "").trim().toUpperCase(Locale.ROOT);
            rid = JsonUtil.getString(envelope, "rid", null);

            JsonObject payload = envelope.has("payload") && envelope.get("payload").isJsonObject()
                    ? envelope.getAsJsonObject("payload")
                    : envelope;

            if ("AUTH".equals(type) || "AUTH_TOKEN".equals(type)) {
                authenticateSession(session, JsonUtil.getString(payload, "token", ""), rid);
                return;
            }

            if ("PING".equals(type) || "PING_RTT".equals(type)) {
                int seq = JsonUtil.getInt(payload, "seq", 1);
                long clientTs = JsonUtil.getLong(payload, "clientTimestampMs", System.currentTimeMillis());
                Map<String, Object> pong = new LinkedHashMap<>();
                pong.put("seq", seq);
                pong.put("clientTimestampMs", clientTs);
                pong.put("serverTimestampMs", System.currentTimeMillis());
                pong.put("transport", "RFC6455_NATIVE_WS");
                session.sendWsEvent("PONG_RTT", rid, pong);
                return;
            }

            if (session.getUser() == null) {
                session.sendError(rid, "AUTH_REQUIRED", "Native WebSocket session is not authenticated.");
                return;
            }

            switch (type) {
                case "JOIN_CHANNEL", "JOIN_ROOM" -> {
                    int channelId = JsonUtil.getInt(payload, "channelId", JsonUtil.getInt(payload, "roomId", -1));
                    if (channelId <= 0) {
                        session.sendError(rid, "CHANNEL_NOT_FOUND", "Valid channelId is required.");
                        return;
                    }
                    session.setCurrentRoomId(channelId);
                    roomDAO.addMember(channelId, session.getUser().getId());
                    session.sendAck(rid, Map.of("channelId", channelId, "roomId", channelId));
                }
                case "SEND_MESSAGE" -> handleSendMessage(session, payload, rid);
                default -> session.sendAck(rid, Map.of("handledBy", "Rfc6455NativeWebSocketServer", "type", type));
            }
        } catch (Exception e) {
            session.sendError(rid, "INTERNAL_ERROR", e.getMessage() != null ? e.getMessage() : "Error processing frame");
        }
    }

    private void authenticateSession(NativeWsSession session, String token, String rid) {
        JwtUtil.JwtClaims claims = JwtUtil.verifyToken(token);
        if (claims == null) {
            session.sendError(rid, "INVALID_TOKEN", "Invalid or expired JWT token.");
            return;
        }
        User user = null;
        try {
            user = userDAO.findById(claims.userId());
        } catch (Exception ignored) {}
        if (user == null) {
            user = new User(claims.userId(), claims.username(), "", claims.displayName());
        }
        user.setStatus("ONLINE");
        try {
            userDAO.updateUserStatus(user.getId(), "ONLINE");
        } catch (Exception ignored) {}

        session.setUser(user);
        this.serverSessionUser = user;
        eventBus.registerSession(session);

        Map<String, Object> userDto = JsonUtil.toUserDto(user, eventBus);
        session.sendWsEvent("READY", rid, Map.of(
                "user", userDto,
                "sessionId", session.getSessionId(),
                "engine", "Rfc6455NativeWebSocketServer"
        ));
        session.sendAck(rid, Map.of("user", userDto));
    }

    private void handleSendMessage(NativeWsSession session, JsonObject payload, String rid) {
        User user = session.getUser();
        int channelId = JsonUtil.getInt(payload, "channelId",
                JsonUtil.getInt(payload, "roomId", session.getCurrentRoomId() != null ? session.getCurrentRoomId() : -1));
        String content = JsonUtil.getString(payload, "content", "").trim();
        if (channelId <= 0 || content.isEmpty()) {
            session.sendError(rid, "BAD_REQUEST", "channelId and non-empty content are required.");
            return;
        }

        session.setCurrentRoomId(channelId);
        Message saved = messageDAO.saveRichMessage(
                channelId,
                user.getId(),
                user.getDisplayName(),
                content,
                JsonUtil.getInteger(payload, "replyToId"),
                JsonUtil.getLongList(payload, "attachmentIds")
        );
        if (saved == null) {
            session.sendError(rid, "DATABASE_ERROR", "Failed to persist message.");
            return;
        }

        String tcpFrame = "NEW_MESSAGE|"
                + saved.getId() + "|"
                + saved.getRoomId() + "|"
                + ProtocolHandler.encode(saved.getSender()) + "|"
                + ProtocolHandler.encode(saved.getContent()) + "|"
                + ProtocolHandler.encode(saved.getTimestamp());

        Map<String, Object> msgDto = JsonUtil.toMessageDto(saved, user.getId(), "WS");
        Map<String, Object> wsPayload = new LinkedHashMap<>(msgDto);
        wsPayload.put("message", msgDto);

        Room room = roomDAO.findRoom(channelId);
        Set<Integer> memberIds = room != null ? room.getMemberIds() : Set.of();

        eventBus.publishToRoom(channelId, memberIds, tcpFrame, "MESSAGE_CREATED", wsPayload, null);
        session.sendAck(rid, wsPayload);
    }

    private void cleanupSession(NativeWsSession session) {
        eventBus.unregisterSession(session);
        session.close();
    }

    public synchronized void stop() {
        running = false;
        for (NativeWsSession s : activeSessions) {
            try {
                s.close();
            } catch (Exception ignored) {}
        }
        activeSessions.clear();
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public int getPort() {
        if (serverSocket != null && serverSocket.isBound()) {
            return serverSocket.getLocalPort();
        }
        return port;
    }

    // =========================================================================
    // Session Implementation (Server-level + Per-Connection NativeWsSession)
    // =========================================================================

    @Override
    public String getSessionId() {
        return serverSessionId;
    }

    @Override
    public TransportType getTransport() {
        return TransportType.WS;
    }

    @Override
    public User getUser() {
        return serverSessionUser;
    }

    @Override
    public void setUser(User user) {
        this.serverSessionUser = user;
    }

    @Override
    public Integer getCurrentRoomId() {
        return serverSessionRoomId;
    }

    @Override
    public void setCurrentRoomId(Integer roomId) {
        this.serverSessionRoomId = roomId;
    }

    @Override
    public void sendTcpFrame(String frame) {
        // WebSocket transport uses sendWsEvent
    }

    @Override
    public void sendWsEvent(String type, String rid, Object payload) {
        for (NativeWsSession session : activeSessions) {
            session.sendWsEvent(type, rid, payload);
        }
    }

    @Override
    public boolean isOpen() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Per-connection {@link Session} implementation backed by a raw TCP {@link Socket}
     * speaking RFC 6455 binary frames.
     */
    public static class NativeWsSession implements Session {
        private final String sessionId = "rfc6455-ws-" + UUID.randomUUID();
        private final Socket socket;
        private final OutputStream out;
        private final EventBus eventBus;
        private volatile User user;
        private volatile Integer currentRoomId;
        private volatile boolean open = true;

        public NativeWsSession(Socket socket, OutputStream out, EventBus eventBus) {
            this.socket = socket;
            this.out = out;
            this.eventBus = eventBus;
        }

        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public TransportType getTransport() {
            return TransportType.WS;
        }

        @Override
        public User getUser() {
            return user;
        }

        @Override
        public void setUser(User user) {
            this.user = user;
        }

        @Override
        public Integer getCurrentRoomId() {
            return currentRoomId;
        }

        @Override
        public void setCurrentRoomId(Integer roomId) {
            this.currentRoomId = roomId;
        }

        @Override
        public void sendTcpFrame(String frame) {
            // No-op for WebSocket sessions; EventBus invokes sendWsEvent
        }

        @Override
        public void sendWsEvent(String type, String rid, Object payload) {
            if (!isOpen() || type == null || type.isBlank()) {
                return;
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", type);
            if (rid != null && !rid.isBlank()) {
                envelope.put("rid", rid);
            }
            envelope.put("payload", payload);

            String json = JsonUtil.toJson(envelope);
            sendRawFrame(OPCODE_TEXT, json.getBytes(StandardCharsets.UTF_8));
            if (!"PROTOCOL_FRAME_LOG".equals(type) && eventBus != null) {
                User u = this.user;
                eventBus.recordProtocolFrame("OUT", TransportType.WS, json, u != null ? u.getId() : null);
            }
        }

        public void sendAck(String rid, Object data) {
            if (rid == null || rid.isBlank()) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rid", rid);
            payload.put("status", "OK");
            if (data != null) {
                payload.put("data", data);
            }
            sendWsEvent("ACK", rid, payload);
        }

        public void sendError(String rid, String code, String message) {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (rid != null && !rid.isBlank()) {
                payload.put("rid", rid);
            }
            payload.put("code", code != null ? code : "BAD_REQUEST");
            payload.put("message", message != null ? message : "Native WebSocket command failed");
            sendWsEvent("ERROR", rid, payload);
        }

        public synchronized void sendRawFrame(int opcode, byte[] payload) {
            if (!isOpen()) {
                return;
            }
            try {
                byte[] encoded = encodeFrame(opcode, payload);
                out.write(encoded);
                out.flush();
            } catch (IOException e) {
                open = false;
            }
        }

        @Override
        public boolean isOpen() {
            return open && socket != null && !socket.isClosed();
        }

        @Override
        public void close() {
            open = false;
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
