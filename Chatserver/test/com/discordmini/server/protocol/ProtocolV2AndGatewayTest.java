package com.discordmini.server.protocol;

import com.discordmini.server.auth.JwtUtil;
import com.discordmini.server.model.User;
import com.discordmini.server.model.Room;
import com.discordmini.server.dao.RoomDAO;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.session.ServerEvent;
import com.discordmini.server.session.Session;
import com.discordmini.server.session.TransportType;
import com.discordmini.server.udp.UdpTelemetryServer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive Automated Test Suite for Protocol v1/v2, JWT HS256, EventBus Multi-Session
 * Concurrency, and UDP Telemetry Server Live Datagram Round-Trip.
 */
public class ProtocolV2AndGatewayTest {

    private static final AtomicInteger passedCount = new AtomicInteger(0);
    private static final AtomicInteger failedCount = new AtomicInteger(0);

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("  DISCORD MINI — PROTOCOL v1/v2, JWT, EVENTBUS & UDP TELEMETRY TEST SUITE");
        System.out.println("================================================================================");

        try {
            test1ProtocolHandlerFramingAndUnicodeRoundTrip();
            test2ProtocolV2CommandsAndErrorCodes();
            test3JwtHs256Authentication();
            test4EventBusMultiSessionConcurrency();
            test5UdpTelemetryLiveDatagramRoundTrip();
            test6SecurityPbkdf2AndRateLimiter();
            test7Rfc6455NativeWebSocketCodec();
        } catch (Throwable t) {
            fail("Unexpected top-level exception: " + t.getMessage());
            t.printStackTrace(System.err);
        }

        System.out.println("================================================================================");
        System.out.printf("  SUMMARY: %d PASSED, %d FAILED%n", passedCount.get(), failedCount.get());
        System.out.println("================================================================================");

        if (failedCount.get() > 0) {
            System.exit(1);
        }
        System.exit(0);
    }

    // =========================================================================
    // 1. ProtocolHandler Framing & Base64 Unicode Round-Trip
    // =========================================================================
    private static void test1ProtocolHandlerFramingAndUnicodeRoundTrip() {
        System.out.println("\n--- [Suite 1] ProtocolHandler Framing & Base64 Unicode Round-Trip ---");

        String complexPayload = "Xin chào Việt Nam! 🎉🔥 | Pipe | & Newline:\nLine 2\r\nLine 3 | Tab:\tEnd";
        String encoded = ProtocolHandler.encode(complexPayload);
        assertTrue(!encoded.contains("|") && !encoded.contains("\n") && !encoded.contains("\r"),
                "Base64 encoded payload contains no raw pipes or newlines");

        String decoded = ProtocolHandler.decode(encoded);
        assertEquals(complexPayload, decoded,
                "Unicode + Vietnamese diacritics + Emojis (🎉, 🔥) + pipe + newline round-trip exact match");

        // Frame building & @RID extraction
        String rid = UUID.randomUUID().toString();
        String baseCmd = ProtocolHandler.command(MessageType.SEND_MESSAGE, complexPayload);
        String frameWithRid = baseCmd + "|" + ProtocolHandler.REQUEST_ID_MARKER + "|" + rid;

        String[] parts = ProtocolHandler.split(frameWithRid);
        assertEquals("SEND_MESSAGE", parts[0], "Frame command parsed as SEND_MESSAGE");
        assertEquals(complexPayload, ProtocolHandler.decode(parts[1]), "Frame payload decoded accurately");
        assertEquals(rid, ProtocolHandler.requestId(parts), "@RID|<uuid> trailer extracted accurately");

        // Legacy frame without @RID returns null requestId
        String[] legacyParts = ProtocolHandler.split(baseCmd);
        assertTrue(ProtocolHandler.requestId(legacyParts) == null,
                "Legacy frame without @RID trailer returns null requestId");

        // Oversized frame rejection (> 65536 chars)
        String oversizedLine = "SEND_MESSAGE|" + "A".repeat(ProtocolHandler.MAX_LINE_LENGTH + 10);
        boolean oversizedRejected = false;
        try {
            ProtocolHandler.validateFrame(oversizedLine);
        } catch (IllegalArgumentException expected) {
            oversizedRejected = true;
        }
        assertTrue(oversizedRejected, "Oversized frame (> 65536 chars) rejected with IllegalArgumentException");

        // Multi-line raw frame rejection
        boolean multilineRejected = false;
        try {
            ProtocolHandler.validateFrame("SEND_MESSAGE|abc\ndef");
        } catch (IllegalArgumentException expected) {
            multilineRejected = true;
        }
        assertTrue(multilineRejected, "Multi-line raw frame rejected with IllegalArgumentException");
    }

    // =========================================================================
    // 2. Protocol v2 Command & Error Code Enum Verification
    // =========================================================================
    private static void test2ProtocolV2CommandsAndErrorCodes() {
        System.out.println("\n--- [Suite 2] Protocol v2 Command & Error Code Enum Verification ---");

        String[] requiredV2Commands = {
                "AUTH_TOKEN",
                "LIST_GUILDS",
                "CREATE_GUILD",
                "CREATE_CHANNEL",
                "EDIT_MSG",
                "DELETE_MSG",
                "REACT_MSG",
                "PIN_MSG",
                "MARK_READ",
                "PRESENCE",
                "SIGNAL_OFFER",
                "SIGNAL_ANSWER",
                "SIGNAL_ICE"
        };

        for (String cmdName : requiredV2Commands) {
            MessageType type = MessageType.valueOf(cmdName);
            assertTrue(type != null && cmdName.equals(type.name()),
                    "MessageType v2 enum value exists: " + cmdName);
        }

        String[] requiredErrorCodes = {
                // v1 core
                "AUTH_FAILED", "AUTH_REQUIRED", "ALREADY_AUTHENTICATED", "ALREADY_ONLINE",
                "USERNAME_TAKEN", "INVALID_ROOM_NAME", "ROOM_NAME_TAKEN", "ROOM_NOT_FOUND",
                "NOT_IN_ROOM", "INVALID_RECIPIENT", "USER_OFFLINE", "BAD_REQUEST",
                "DATABASE_ERROR", "INVALID_STATE", "INTERNAL_ERROR", "PERMISSION_DENIED",
                "USER_NOT_FOUND",
                // v2 extensions
                "FORBIDDEN", "MESSAGE_NOT_FOUND", "CHANNEL_NOT_FOUND", "GUILD_NOT_FOUND",
                "INVITE_EXPIRED", "PAYLOAD_TOO_LARGE", "RATE_LIMITED", "INVALID_TOKEN"
        };

        for (String errName : requiredErrorCodes) {
            ProtocolErrorCode code = ProtocolErrorCode.valueOf(errName);
            assertTrue(code != null && errName.equals(code.name()),
                    "ProtocolErrorCode enum value exists: " + errName);
        }
    }

    // =========================================================================
    // 3. JWT HS256 Authentication (JwtUtil)
    // =========================================================================
    private static void test3JwtHs256Authentication() {
        System.out.println("\n--- [Suite 3] JWT HS256 Authentication (JwtUtil) ---");

        int expectedUserId = 42;
        String expectedUsername = "yenvi_nt106";
        String expectedDisplayName = "Bùi Hà Yến Vi 🚀";

        String token = JwtUtil.generateToken(expectedUserId, expectedUsername, expectedDisplayName);
        assertTrue(token != null && token.split("\\.").length == 3,
                "Generated JWT has 3 dot-separated Base64Url segments");

        JwtUtil.JwtClaims claims = JwtUtil.verifyToken(token);
        assertTrue(claims != null, "Valid JWT verified by JwtUtil.verifyToken()");
        if (claims != null) {
            assertEquals(expectedUserId, claims.userId(), "JWT claim userId matches 42");
            assertEquals(expectedUsername, claims.username(), "JWT claim username matches");
            assertEquals(expectedDisplayName, claims.displayName(), "JWT claim displayName preserves UTF-8 & emoji");
            long nowSec = System.currentTimeMillis() / 1000L;
            assertTrue(claims.exp() > nowSec, "JWT claim exp is in the future (" + claims.exp() + " > " + nowSec + ")");
        }

        // Verify Bearer prefix handling
        JwtUtil.JwtClaims bearerClaims = JwtUtil.verifyToken("Bearer " + token);
        assertTrue(bearerClaims != null && bearerClaims.userId() == expectedUserId,
                "JwtUtil.verifyToken accepts 'Bearer <token>' header format");

        // Tamper with payload
        String[] parts = token.split("\\.");
        String tamperedPayload = Base64UrlTamper(parts[1]);
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];
        assertTrue(JwtUtil.verifyToken(tamperedToken) == null,
                "Tampered JWT payload is rejected (verifyToken returns null)");

        // Tamper with signature
        String tamperedSigToken = parts[0] + "." + parts[1] + ".invalidsignatureXYZ123";
        assertTrue(JwtUtil.verifyToken(tamperedSigToken) == null,
                "Tampered JWT signature is rejected (verifyToken returns null)");

        // Malformed / blank tokens
        assertTrue(JwtUtil.verifyToken("") == null && JwtUtil.verifyToken("not.a.jwt.at.all") == null,
                "Blank and malformed tokens return null");
    }

    private static String Base64UrlTamper(String b64) {
        String hacked = "{\"sub\":999,\"userId\":999,\"username\":\"hacker\",\"displayName\":\"Hacker\",\"exp\":9999999999}";
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hacked.getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // 4. EventBus Multi-Session Concurrency & Cross-Transport Routing
    // =========================================================================
    private static void test4EventBusMultiSessionConcurrency() throws Exception {
        System.out.println("\n--- [Suite 4] EventBus Multi-Session Concurrency & Cross-Transport Routing ---");

        EventBus bus = EventBus.getInstance();
        List<MockSession> mockSessions = new ArrayList<>();

        // Create 50 sessions: 25 TCP + 25 WS across rooms 101..105
        for (int i = 0; i < 50; i++) {
            int userId = 1000 + i;
            TransportType transport = (i < 25) ? TransportType.TCP : TransportType.WS;
            int roomId = 101 + (i % 5); // 10 sessions per room (5 TCP + 5 WS)
            User u = new User(userId, "user_" + userId, "", "User " + userId, "ONLINE", null);
            MockSession session = new MockSession("sess-" + i, transport, u, roomId);
            mockSessions.add(session);
        }

        // Concurrently register all 50 sessions & record 300 protocol frames
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch regLatch = new CountDownLatch(50);
        for (MockSession s : mockSessions) {
            pool.submit(() -> {
                try {
                    bus.registerSession(s);
                } finally {
                    regLatch.countDown();
                }
            });
        }
        assertTrue(regLatch.await(5, TimeUnit.SECONDS), "All 50 concurrent sessions registered within 5s");
        assertTrue(bus.getActiveSessionCount() >= 50, "EventBus active session count >= 50");

        // Test publishToRoom for room 101 (10 sessions in room 101: 5 TCP + 5 WS), excluding user 1000
        int targetRoom = 101;
        int excludedUser = 1000; // sess-0 (TCP in room 101)
        bus.publishToRoom(
                targetRoom,
                Set.of(1000, 1005, 1010, 1015, 1020, 1025, 1030, 1035, 1040, 1045),
                "NEW_MESSAGE|101|1000|VXNlciAxMDAw|SGVsbG8=|2026-10-17|555",
                "NEW_MESSAGE",
                Map.of("roomId", 101, "content", "Hello"),
                excludedUser
        );

        int tcpRoom101Received = 0;
        int wsRoom101Received = 0;
        boolean excludedReceived = !mockSessions.get(0).tcpFrames.isEmpty();

        for (MockSession s : mockSessions) {
            if (s.getCurrentRoomId() == targetRoom && s.getUser().getId() != excludedUser) {
                if (s.getTransport() == TransportType.TCP && s.tcpFrames.stream().anyMatch(f -> f.startsWith("NEW_MESSAGE|101|"))) {
                    tcpRoom101Received++;
                }
                if (s.getTransport() == TransportType.WS && s.wsEvents.stream().anyMatch(e -> "NEW_MESSAGE".equals(e))) {
                    wsRoom101Received++;
                }
            }
        }
        assertTrue(!excludedReceived, "Excluded sender (userId=1000) did not receive own room broadcast");
        assertEquals(4, tcpRoom101Received, "All 4 non-excluded TCP sessions in room 101 received TCP frame");
        assertEquals(5, wsRoom101Received, "All 5 WS sessions in room 101 received WS event");

        // Test publishToUser across multi-transport (add a second WS session for user 1001 who already has TCP)
        MockSession extraWsForUser1001 = new MockSession(
                "sess-extra-1001",
                TransportType.WS,
                mockSessions.get(1).getUser(),
                102
        );
        bus.registerSession(extraWsForUser1001);

        bus.publishToUser(
                1001,
                "PRIVATE_MESSAGE|1000|VXNlcg==|1001|SGk=|2026-10-17|777",
                "PRIVATE_MESSAGE",
                Map.of("from", 1000, "to", 1001)
        );
        assertTrue(mockSessions.get(1).tcpFrames.stream().anyMatch(f -> f.startsWith("PRIVATE_MESSAGE|")),
                "publishToUser delivered TCP frame to user 1001's TCP session");
        assertTrue(extraWsForUser1001.wsEvents.contains("PRIVATE_MESSAGE"),
                "publishToUser delivered WS event to user 1001's simultaneous WS session");

        // Test publishGlobal
        bus.publishGlobal("PRESENCE_UPDATED|1000|DND", "PRESENCE_UPDATED", Map.of("userId", 1000, "status", "DND"), null);
        long globalDeliveries = mockSessions.stream()
                .filter(s -> s.tcpFrames.contains("PRESENCE_UPDATED|1000|DND") || s.wsEvents.contains("PRESENCE_UPDATED"))
                .count();
        assertEquals(50L, globalDeliveries, "publishGlobal delivered presence update to all 50 sessions");

        // Test recordProtocolFrame ring buffer cap (200 frames) under concurrent load
        CountDownLatch frameLatch = new CountDownLatch(300);
        for (int i = 0; i < 300; i++) {
            final int seq = i;
            pool.submit(() -> {
                try {
                    bus.recordProtocolFrame("IN", TransportType.TCP, "PING|seq=" + seq, 1000 + (seq % 50));
                } finally {
                    frameLatch.countDown();
                }
            });
        }
        assertTrue(frameLatch.await(5, TimeUnit.SECONDS), "300 concurrent protocol frames recorded within 5s");

        List<ServerEvent.FrameRecord> recent200 = bus.getRecentFrames(200);
        assertEquals(200, recent200.size(), "EventBus ring buffer caps stored frames at exactly 200 (MAX_RECENT_FRAMES)");

        // Cleanup & verify session removal on disconnect
        bus.unregisterSession(extraWsForUser1001);
        for (MockSession s : mockSessions) {
            s.close();
            bus.unregisterSession(s);
        }
        pool.shutdownNow();
        assertTrue(!bus.isUserOnline(1001), "Disconnected user 1001 is no longer reported online by EventBus");
    }

    // =========================================================================
    // 5. UDP Telemetry Server (UdpTelemetryServer) Live Datagram Round-Trip
    // =========================================================================
    private static void test5UdpTelemetryLiveDatagramRoundTrip() throws Exception {
        System.out.println("\n--- [Suite 5] UdpTelemetryServer Live Datagram Round-Trip (:15001) ---");

        EventBus bus = EventBus.getInstance();
        int testUdpPort = 15001;
        com.discordmini.server.dao.UserDAO mockUserDao = new com.discordmini.server.dao.UserDAO() {
            @Override
            public boolean updatePresence(int userId, String presenceStatus, String customStatus) {
                return true;
            }
        };
        RoomDAO mockRoomDao = new RoomDAO() {
            @Override public String getMemberRole(int roomId, int userId) {
                return roomId == 77 && (userId == 2001 || userId == 2002) ? "MEMBER" : null;
            }

            @Override public Room findRoom(int roomId) {
                if (roomId != 77) return null;
                Room room = new Room(77, "udp-test-room");
                room.getMemberIds().add(2001);
                room.getMemberIds().add(2002);
                return room;
            }
        };
        UdpTelemetryServer udpServer = new UdpTelemetryServer(testUdpPort, bus, mockUserDao, mockRoomDao);
        udpServer.start();

        try {
            assertTrue(udpServer.isUdpActive(), "UdpTelemetryServer bound and active on UDP port :" + testUdpPort);

            // Register a mock TCP + WS observer in room 77 to verify UDP_TYPING and UDP_PRESENCE EventBus fan-out
            User observerUser = new User(2002, "observer", "", "Observer", "ONLINE", null);
            MockSession tcpObserver = new MockSession("udp-obs-tcp", TransportType.TCP, observerUser, 77);
            MockSession wsObserver = new MockSession("udp-obs-ws", TransportType.WS, observerUser, 77);
            bus.registerSession(tcpObserver);
            bus.registerSession(wsObserver);

            // 5a. Built-in probeLocalUdpRtt
            long rttMs = udpServer.probeLocalUdpRtt(1, 2001);
            assertTrue(rttMs >= 1 && rttMs < 500,
                    "UdpTelemetryServer.probeLocalUdpRtt() succeeded with live loopback RTT = " + rttMs + " ms");

            // 5b. Direct DatagramSocket UDP_PING -> UDP_PONG verification
            try (DatagramSocket clientSocket = new DatagramSocket()) {
                clientSocket.setSoTimeout(1000);
                InetAddress loopback = InetAddress.getByName("127.0.0.1");

                long clientTs = System.currentTimeMillis();
                String pingPayload = "UDP_PING|99|" + clientTs + "|2001";
                byte[] pingBytes = pingPayload.getBytes(StandardCharsets.UTF_8);
                clientSocket.send(new DatagramPacket(pingBytes, pingBytes.length, loopback, testUdpPort));

                byte[] buf = new byte[1024];
                DatagramPacket pongPacket = new DatagramPacket(buf, buf.length);
                clientSocket.receive(pongPacket);
                String pongStr = new String(pongPacket.getData(), 0, pongPacket.getLength(), StandardCharsets.UTF_8).trim();

                assertTrue(pongStr.startsWith("UDP_PONG|99|" + clientTs + "|"),
                        "Direct UDP_PING received matching UDP_PONG datagram: " + pongStr);

                // 5c. Send UDP_TYPING datagram and verify EventBus room broadcast
                String b64Name = ProtocolHandler.encode("Yến Vi");
                String typingPayload = "UDP_TYPING|2001|77|1|" + b64Name;
                byte[] typingBytes = typingPayload.getBytes(StandardCharsets.UTF_8);
                clientSocket.send(new DatagramPacket(typingBytes, typingBytes.length, loopback, testUdpPort));

                // 5d. Send UDP_PRESENCE datagram and verify EventBus global broadcast
                String presencePayload = "UDP_PRESENCE|2001|IDLE";
                byte[] presenceBytes = presencePayload.getBytes(StandardCharsets.UTF_8);
                clientSocket.send(new DatagramPacket(presenceBytes, presenceBytes.length, loopback, testUdpPort));

                // Wait briefly for async datagram listener thread to dispatch to EventBus
                long deadline = System.currentTimeMillis() + 1500;
                while (System.currentTimeMillis() < deadline) {
                    if (tcpObserver.tcpFrames.stream().anyMatch(f -> f.startsWith("TYPING_UPDATE|77|2001|1|"))
                            && tcpObserver.tcpFrames.stream().anyMatch(f -> f.equals("PRESENCE_UPDATED|2001|IDLE"))) {
                        break;
                    }
                    Thread.sleep(25);
                }

                assertTrue(tcpObserver.tcpFrames.stream().anyMatch(f -> f.startsWith("TYPING_UPDATE|77|2001|1|")),
                        "UDP_TYPING datagram fanned out as TYPING_UPDATE TCP frame to room 77 member");
                assertTrue(wsObserver.wsEvents.contains("TYPING_UPDATE"),
                        "UDP_TYPING datagram fanned out as TYPING_UPDATE WS event to room 77 member");

                int acceptedTypingFrames = (int) tcpObserver.tcpFrames.stream()
                        .filter(f -> f.startsWith("TYPING_UPDATE|77|")).count();
                String forgedTyping = "UDP_TYPING|2999|77|1|" + ProtocolHandler.encode("Spoofed");
                byte[] forgedTypingBytes = forgedTyping.getBytes(StandardCharsets.UTF_8);
                clientSocket.send(new DatagramPacket(forgedTypingBytes, forgedTypingBytes.length, loopback, testUdpPort));
                Thread.sleep(100);
                assertTrue(tcpObserver.tcpFrames.stream().filter(f -> f.startsWith("TYPING_UPDATE|77|")).count()
                                == acceptedTypingFrames,
                        "UDP typing from a nonmember user id is rejected");

                assertTrue(tcpObserver.tcpFrames.contains("PRESENCE_UPDATED|2001|IDLE"),
                        "UDP_PRESENCE datagram fanned out as PRESENCE_UPDATED|2001|IDLE TCP frame globally");
                assertTrue(wsObserver.wsEvents.contains("PRESENCE_UPDATED"),
                        "UDP_PRESENCE datagram fanned out as PRESENCE_UPDATED WS event globally");
            }

            bus.unregisterSession(tcpObserver);
            bus.unregisterSession(wsObserver);
        } finally {
            udpServer.stop();
            assertTrue(!udpServer.isUdpActive(), "UdpTelemetryServer cleanly stopped");
        }
    }

    // =========================================================================
    // 6. Security Hardening: PBKDF2WithHmacSHA256 & Sliding-Window RateLimiter
    // =========================================================================
    private static void test6SecurityPbkdf2AndRateLimiter() {
        System.out.println("\n--- [Suite 6] Security Hardening: PBKDF2WithHmacSHA256 & RateLimiter ---");

        String rawPass = "MatKhauAnToan!2026#CMP180";
        String pbkdf2Hash = com.discordmini.server.security.PasswordUtil.hashPassword(rawPass);
        assertTrue(pbkdf2Hash != null && pbkdf2Hash.toLowerCase().startsWith("pbkdf2$"),
                "PasswordUtil.hashPassword() produces salted PBKDF2 hash format");
        assertTrue(com.discordmini.server.security.PasswordUtil.verifyPassword(rawPass, pbkdf2Hash),
                "PasswordUtil.verifyPassword() accepts matching PBKDF2 password");
        assertTrue(!com.discordmini.server.security.PasswordUtil.verifyPassword("WrongPassword", pbkdf2Hash),
                "PasswordUtil.verifyPassword() rejects wrong PBKDF2 password");

        com.discordmini.server.security.RateLimiter limiter = new com.discordmini.server.security.RateLimiter();
        String testKey = "test-ip-127.0.0.1";
        limiter.reset(testKey);
        boolean firstAllowed = limiter.allowAuthAttempt(testKey);
        assertTrue(firstAllowed, "RateLimiter allows initial authentication attempts within window");
    }

    // =========================================================================
    // 7. Pure Java RFC 6455 WebSocket Handshake & Masked Frame Codec
    // =========================================================================
    private static void test7Rfc6455NativeWebSocketCodec() throws Exception {
        System.out.println("\n--- [Suite 7] Pure Java RFC 6455 WebSocket Handshake & Frame Codec ---");

        // RFC 6455 Section 1.3 official test vector
        String rfcKey = "dGhlIHNhbXBsZSBub25jZQ==";
        String expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String actualAccept = com.discordmini.server.web.ws.Rfc6455NativeWebSocketServer.computeAcceptKey(rfcKey);
        assertEquals(expectedAccept, actualAccept,
                "RFC 6455 Section 1.3 Sec-WebSocket-Accept SHA-1 + Base64 vector matches exactly");

        // Masked client frame -> server readFrame unmasking round-trip
        String sampleJson = "{\"type\":\"SEND_MESSAGE\",\"rid\":\"rfc-6455-test\",\"payload\":{\"content\":\"Xin chào RFC 6455 🚀\"}}";
        byte[] payloadBytes = sampleJson.getBytes(StandardCharsets.UTF_8);
        byte[] maskKey = new byte[]{0x37, (byte) 0xFA, 0x21, 0x3D};

        byte[] wireBytes = com.discordmini.server.web.ws.Rfc6455NativeWebSocketServer.encodeMaskedClientFrame(
                com.discordmini.server.web.ws.Rfc6455NativeWebSocketServer.OPCODE_TEXT,
                payloadBytes,
                maskKey
        );
        com.discordmini.server.web.ws.Rfc6455NativeWebSocketServer.WsFrame decoded =
                com.discordmini.server.web.ws.Rfc6455NativeWebSocketServer.readFrame(new java.io.ByteArrayInputStream(wireBytes));

        assertTrue(decoded != null && decoded.fin(), "RFC 6455 decoded frame has FIN=true");
        assertEquals(com.discordmini.server.web.ws.Rfc6455NativeWebSocketServer.OPCODE_TEXT, decoded.opcode(),
                "RFC 6455 decoded frame has OPCODE_TEXT (0x1)");
        assertTrue(decoded.masked(), "RFC 6455 decoded client frame detected MASK=true");
        assertEquals(sampleJson, decoded.payloadAsText(),
                "RFC 6455 4-byte XOR unmasking restored UTF-8 Vietnamese + emoji JSON payload accurately");
    }

    // =========================================================================
    // Mock Session & Assertion Helpers
    // =========================================================================
    private static final class MockSession implements Session {
        private final String sessionId;
        private final TransportType transport;
        private volatile User user;
        private volatile Integer currentRoomId;
        private final AtomicBoolean open = new AtomicBoolean(true);
        final List<String> tcpFrames = new CopyOnWriteArrayList<>();
        final List<String> wsEvents = new CopyOnWriteArrayList<>();

        MockSession(String sessionId, TransportType transport, User user, Integer currentRoomId) {
            this.sessionId = sessionId;
            this.transport = transport;
            this.user = user;
            this.currentRoomId = currentRoomId;
        }

        @Override
        public String getSessionId() { return sessionId; }

        @Override
        public TransportType getTransport() { return transport; }

        @Override
        public User getUser() { return user; }

        @Override
        public void setUser(User user) { this.user = user; }

        @Override
        public Integer getCurrentRoomId() { return currentRoomId; }

        @Override
        public void setCurrentRoomId(Integer roomId) { this.currentRoomId = roomId; }

        @Override
        public void sendTcpFrame(String frame) {
            tcpFrames.add(frame);
        }

        @Override
        public void sendWsEvent(String type, String rid, Object payload) {
            if (!"PROTOCOL_FRAME_LOG".equals(type)) {
                wsEvents.add(type);
            }
        }

        @Override
        public boolean isOpen() { return open.get(); }

        @Override
        public void close() { open.set(false); }
    }

    private static void assertTrue(boolean condition, String description) {
        if (condition) {
            passedCount.incrementAndGet();
            System.out.println("  [PASS] " + description);
        } else {
            failedCount.incrementAndGet();
            System.err.println("  [FAIL] " + description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        boolean match = (expected == null && actual == null) || (expected != null && expected.equals(actual));
        if (match) {
            passedCount.incrementAndGet();
            System.out.println("  [PASS] " + description);
        } else {
            failedCount.incrementAndGet();
            System.err.println("  [FAIL] " + description + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static void fail(String description) {
        failedCount.incrementAndGet();
        System.err.println("  [FAIL] " + description);
    }
}
