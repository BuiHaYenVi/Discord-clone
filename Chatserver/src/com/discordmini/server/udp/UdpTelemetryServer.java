package com.discordmini.server.udp;

import com.discordmini.server.dao.UserDAO;
import com.discordmini.server.dao.RoomDAO;
import com.discordmini.server.model.Room;
import com.discordmini.server.protocol.ProtocolHandler;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.session.TransportType;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UDP Telemetry & Ephemeral Signal Server (:5001 DatagramSocket).
 * Handles connectionless UDP_PING/UDP_PONG RTT probes, UDP_TYPING indicators,
 * and UDP_PRESENCE beacons, recording live datagrams to EventBus.
 */
public class UdpTelemetryServer implements Runnable {
    public static final int DEFAULT_UDP_PORT = 5001;
    public static final int MAX_DATAGRAM_BYTES = 1024;

    private final int udpPort;
    private final EventBus eventBus;
    private final UserDAO userDAO;
    private final RoomDAO roomDAO;

    private DatagramSocket socket;
    private Thread listenerThread;
    private volatile boolean running = false;
    private volatile boolean udpActive = false;

    private final AtomicLong packetsReceived = new AtomicLong(0);
    private final AtomicLong packetsSent = new AtomicLong(0);
    private final AtomicInteger probeSeqGenerator = new AtomicInteger(1);

    public UdpTelemetryServer(EventBus eventBus) {
        this(resolveUdpPort(), eventBus, new UserDAO());
    }

    public UdpTelemetryServer(int udpPort, EventBus eventBus, UserDAO userDAO) {
        this(udpPort, eventBus, userDAO, new RoomDAO());
    }

    public UdpTelemetryServer(int udpPort, EventBus eventBus, UserDAO userDAO, RoomDAO roomDAO) {
        this.udpPort = udpPort;
        this.eventBus = eventBus != null ? eventBus : EventBus.getInstance();
        this.userDAO = userDAO != null ? userDAO : new UserDAO();
        this.roomDAO = roomDAO != null ? roomDAO : new RoomDAO();
    }

    private static int resolveUdpPort() {
        String singlePort = System.getenv("SINGLE_PORT_MODE");
        if ("true".equalsIgnoreCase(singlePort) || "1".equals(singlePort)) {
            return -1;
        }
        String envPort = System.getenv("UDP_PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                return Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_UDP_PORT;
    }

    public synchronized void start() {
        if (running) return;
        if (udpPort <= 0) {
            udpActive = false;
            System.out.println("[UDP] Single-Port / Fallback mode active (UDP listener disabled).");
            return;
        }
        try {
            socket = new DatagramSocket(udpPort);
            running = true;
            udpActive = true;
            listenerThread = new Thread(this, "UdpTelemetryServer-" + udpPort);
            listenerThread.setDaemon(true);
            listenerThread.start();
            System.out.println("[UDP] UdpTelemetryServer listening on UDP port :" + udpPort);
        } catch (SocketException e) {
            udpActive = false;
            running = false;
            System.err.println("[UDP WARNING] Could not bind UDP port :" + udpPort
                    + " (" + e.getMessage() + "). Falling back to WS/TCP telemetry.");
        }
    }

    public synchronized void stop() {
        running = false;
        udpActive = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    public boolean isUdpActive() {
        return udpActive && socket != null && !socket.isClosed();
    }

    public int getUdpPort() {
        return udpPort;
    }

    public long getPacketsReceived() {
        return packetsReceived.get();
    }

    public long getPacketsSent() {
        return packetsSent.get();
    }

    @Override
    public void run() {
        byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
        while (running && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                packetsReceived.incrementAndGet();

                String payload = new String(
                        packet.getData(),
                        packet.getOffset(),
                        Math.min(packet.getLength(), MAX_DATAGRAM_BYTES),
                        StandardCharsets.UTF_8
                ).trim();

                if (!payload.isEmpty()) {
                    handleDatagram(payload, packet);
                }
            } catch (IOException e) {
                if (!running || socket == null || socket.isClosed()) {
                    break;
                }
                System.err.println("[UDP ERROR] Datagram receive error: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("[UDP ERROR] Failed processing datagram: " + e.getMessage());
            }
        }
    }

    private void handleDatagram(String rawFrame, DatagramPacket packet) {
        String[] parts = rawFrame.split("\\|", -1);
        if (parts.length == 0) return;

        String cmd = parts[0].trim().toUpperCase();
        switch (cmd) {
            case "UDP_PING" -> handleUdpPing(rawFrame, parts, packet);
            case "UDP_TYPING" -> handleUdpTyping(rawFrame, parts, packet);
            case "UDP_PRESENCE" -> {
                if (packet.getAddress().isLoopbackAddress()) handleUdpPresence(rawFrame, parts);
            }
            default -> eventBus.recordProtocolFrame("IN", TransportType.UDP, rawFrame, null);
        }
    }

    private void handleUdpPing(String rawFrame, String[] parts, DatagramPacket packet) {
        String seq = parts.length > 1 ? parts[1].trim() : "0";
        String clientTs = parts.length > 2 ? parts[2].trim() : String.valueOf(System.currentTimeMillis());
        Integer userId = parseOptionalInt(parts.length > 3 ? parts[3] : null);

        eventBus.recordProtocolFrame("IN", TransportType.UDP, rawFrame, userId);

        long serverTs = System.currentTimeMillis();
        String pongFrame = "UDP_PONG|" + seq + "|" + clientTs + "|" + serverTs;
        byte[] outBytes = pongFrame.getBytes(StandardCharsets.UTF_8);

        try {
            DatagramPacket reply = new DatagramPacket(
                    outBytes,
                    outBytes.length,
                    packet.getAddress(),
                    packet.getPort()
            );
            socket.send(reply);
            packetsSent.incrementAndGet();
            eventBus.recordProtocolFrame("OUT", TransportType.UDP, pongFrame, userId);
        } catch (IOException e) {
            System.err.println("[UDP ERROR] Failed sending UDP_PONG: " + e.getMessage());
        }
    }

    private void handleUdpTyping(String rawFrame, String[] parts, DatagramPacket packet) {
        // UDP_TYPING|<userId>|<roomId>|<isTyping:1|0>|<base64-displayName>
        if (!packet.getAddress().isLoopbackAddress()) return;
        if (parts.length < 5) return;
        Integer userId = parseOptionalInt(parts[1]);
        Integer roomId = parseOptionalInt(parts[2]);
        if (userId == null || roomId == null) return;
        if (roomDAO.getMemberRole(roomId, userId) == null) return;
        Room room = roomDAO.findRoom(roomId);
        if (room == null || !room.getMemberIds().contains(userId)) return;

        boolean isTyping = "1".equals(parts[3].trim()) || "true".equalsIgnoreCase(parts[3].trim());
        String b64DisplayName = parts[4].trim();
        String displayName;
        try {
            displayName = ProtocolHandler.decode(b64DisplayName);
        } catch (Exception e) {
            displayName = b64DisplayName;
            b64DisplayName = ProtocolHandler.encode(displayName);
        }

        eventBus.recordProtocolFrame("IN", TransportType.UDP, rawFrame, userId);

        String tcpFrame = "TYPING_UPDATE|" + roomId + "|" + userId + "|" + (isTyping ? "1" : "0") + "|" + b64DisplayName;
        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("roomId", roomId);
        wsPayload.put("userId", userId);
        wsPayload.put("isTyping", isTyping);
        wsPayload.put("displayName", displayName);
        wsPayload.put("transport", "UDP");

        eventBus.publishToRoom(roomId, room.getMemberIds(), tcpFrame, "TYPING_UPDATE", wsPayload, userId);
    }

    private void handleUdpPresence(String rawFrame, String[] parts) {
        // UDP_PRESENCE|<userId>|<status:ONLINE|IDLE|DND|OFFLINE>
        if (parts.length < 3) return;
        Integer userId = parseOptionalInt(parts[1]);
        if (userId == null) return;

        String status = parts[2].trim().toUpperCase();
        if (status.isEmpty()) status = "ONLINE";

        eventBus.recordProtocolFrame("IN", TransportType.UDP, rawFrame, userId);

        try {
            userDAO.updatePresence(userId, status, null);
        } catch (Throwable ignored) {
            try {
                userDAO.updateUserStatus(userId, status);
            } catch (Exception ignored2) {}
        }

        String tcpFrame = "PRESENCE_UPDATED|" + userId + "|" + status;
        Map<String, Object> wsPayload = new LinkedHashMap<>();
        wsPayload.put("userId", userId);
        wsPayload.put("status", status);
        wsPayload.put("presenceStatus", status);
        wsPayload.put("transport", "UDP");

        eventBus.publishGlobal(tcpFrame, "PRESENCE_UPDATED", wsPayload, null);
    }

    /**
     * Sends a real OS UDP datagram to 127.0.0.1:udpPort with a 500ms timeout
     * and returns the measured round-trip time in milliseconds (minimum 1ms when >=0),
     * or -1 if UDP is inactive/blocked.
     */
    public long probeLocalUdpRtt(int seq, int userId) {
        long micros = probeLocalUdpRttMicros(seq, userId);
        if (micros < 0) return -1L;
        return Math.max(1L, Math.round(micros / 1000.0));
    }

    public long probeLocalUdpRtt() {
        return probeLocalUdpRtt(probeSeqGenerator.getAndIncrement(), 0);
    }

    /**
     * Sends a real OS UDP datagram to 127.0.0.1:udpPort with a 500ms timeout
     * and returns the measured round-trip time in microseconds, or -1 if UDP is inactive.
     */
    public long probeLocalUdpRttMicros(int seq, int userId) {
        if (!isUdpActive()) {
            return -1L;
        }
        try (DatagramSocket clientSocket = new DatagramSocket()) {
            clientSocket.setSoTimeout(500);
            long clientTs = System.currentTimeMillis();
            String pingFrame = "UDP_PING|" + seq + "|" + clientTs + "|" + userId;
            byte[] outBytes = pingFrame.getBytes(StandardCharsets.UTF_8);

            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            DatagramPacket req = new DatagramPacket(outBytes, outBytes.length, loopback, udpPort);

            long startNs = System.nanoTime();
            clientSocket.send(req);

            byte[] inBuf = new byte[MAX_DATAGRAM_BYTES];
            DatagramPacket resp = new DatagramPacket(inBuf, inBuf.length);
            clientSocket.receive(resp);
            long elapsedMicros = Math.max(1L, (System.nanoTime() - startNs) / 1_000L);

            String reply = new String(resp.getData(), resp.getOffset(), resp.getLength(), StandardCharsets.UTF_8).trim();
            if (reply.startsWith("UDP_PONG|")) {
                return elapsedMicros;
            }
            return -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private Integer parseOptionalInt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
