package com.discordmini.client.network;

import com.discordmini.client.protocol.ProtocolHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns one TCP connection and the client-side reliability checks.
 *
 * Requests carry a unique @RID trailer. The server responds with ACK|id|OK
 * after successful handling, or ERROR|encodedMessage|id on failure. Requests
 * are not retried automatically: blindly retrying SEND_MESSAGE could duplicate
 * a message if the original response was merely delayed.
 */
public class SocketClient {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    private static final long HEARTBEAT_TIMEOUT_MS = 40_000;
    private static final long REQUEST_TIMEOUT_MS = 10_000;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread readerThread;
    private final ServerListener serverListener = new ServerListener(this);
    private final ConnectionManager connectionManager = new ConnectionManager(this);
    private volatile boolean connected;
    private volatile long lastPongAt;
    private volatile long lastPingAt;
    private volatile long generation;
    private ScheduledFuture<?> monitorTask;

    private volatile String serverHost;
    private volatile int serverPort;
    private final java.util.Queue<Long> inFlightPingNanos = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean pingAwaitingPong = false;
    private volatile long currentLatencyMs = -1;
    private volatile String lastPingTimestamp = null;
    private volatile String lastPongTimestamp = null;
    private final List<HeartbeatListener> heartbeatListeners = new CopyOnWriteArrayList<>();

    private record OutboundFrame(String payload, String requestId, long targetGeneration) {}
    private final java.util.concurrent.BlockingQueue<OutboundFrame> outboundQueue = new java.util.concurrent.LinkedBlockingQueue<>(2000);
    private Thread outboundWorker;

    private final ScheduledExecutorService monitor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ChatClient-ReliabilityMonitor");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    private record PendingRequest(long createdAt, String command) {}

    public ServerListener getServerListener() {
        return serverListener;
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public long getGeneration() {
        return generation;
    }

    public void setListener(Consumer<String> listener) {
        serverListener.setActiveUiListener(listener);
    }

    public void addListener(Consumer<String> listener) {
        serverListener.addMessageListener(listener);
    }

    public void removeListener(Consumer<String> listener) {
        serverListener.removeMessageListener(listener);
    }

    public void addConnectionListener(Consumer<String> listener) {
        serverListener.addConnectionListener(listener);
    }

    public void removeConnectionListener(Consumer<String> listener) {
        serverListener.removeConnectionListener(listener);
    }

    public void addMessageListener(Consumer<String> listener) {
        serverListener.addMessageListener(listener);
    }

    public void removeMessageListener(Consumer<String> listener) {
        serverListener.removeMessageListener(listener);
    }

    Thread getReaderThread() {
        return readerThread;
    }

    public synchronized void connect(String host, int port) throws IOException {
        if (isConnected()) return;
        closeSocketQuietly();

        Socket newSocket = new Socket();
        try {
            newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            newSocket.setKeepAlive(true);

            socket = newSocket;
            in = new BufferedReader(new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8), true);
            connected = true;
            this.serverHost = host;
            this.serverPort = port;
            this.currentLatencyMs = -1;
            this.pingAwaitingPong = false;
            this.lastPingTimestamp = null;
            this.lastPongTimestamp = null;
            long thisGeneration = ++generation;
            lastPongAt = System.currentTimeMillis();
            lastPingAt = 0;

            BufferedReader reader = in;
            readerThread = new Thread(() -> readLoop(reader, thisGeneration), "ServerListener");
            readerThread.setDaemon(true);
            readerThread.start();

            outboundQueue.clear();
            inFlightPingNanos.clear();
            outboundWorker = new Thread(() -> outboundLoop(thisGeneration), "ChatClient-OutboundWorker-" + thisGeneration);
            outboundWorker.setDaemon(true);
            outboundWorker.start();

            if (monitorTask != null) monitorTask.cancel(false);
            monitorTask = monitor.scheduleAtFixedRate(
                    () -> monitorConnection(thisGeneration), 1, 1, TimeUnit.SECONDS);
            connectionManager.setConnected(thisGeneration);
        } catch (Exception ex) {
            connected = false;
            closeSocketQuietly();
            try {
                newSocket.close();
            } catch (IOException ignored) {
            }
            connectionManager.setDisconnected(DisconnectReason.CONNECTION_FAILED, generation);
            if (ex instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Không thể thiết lập kết nối tới " + host + ":" + port + ": " + ex.getMessage(), ex);
        }
    }

    public boolean isConnected() {
        Socket current = socket;
        return connected && current != null && current.isConnected() && !current.isClosed();
    }

    /**
     * Sends a command with an explicit request ID.
     */
    public boolean sendWithCustomId(String command, String requestId) {
        if (command == null || command.isBlank() || !isConnected()) return false;
        if (requestId == null || requestId.isBlank()) return false;

        final String commandName;
        try {
            commandName = ProtocolHandler.split(command)[0];
        } catch (IllegalArgumentException ex) {
            notifyListener("PROTOCOL_ERROR|" + ProtocolHandler.encode(ex.getMessage()));
            return false;
        }

        String framed = command + "|" + ProtocolHandler.REQUEST_ID_MARKER + "|" + requestId;
        if (framed.length() > ProtocolHandler.MAX_LINE_LENGTH) {
            notifyListener("PROTOCOL_ERROR|" + ProtocolHandler.encode("Request exceeds maximum frame length."));
            return false;
        }

        long currentGen = generation;
        pendingRequests.put(requestId, new PendingRequest(System.currentTimeMillis(), commandName));
        boolean enqueued = outboundQueue.offer(new OutboundFrame(framed, requestId, currentGen));
        if (!enqueued) {
            pendingRequests.remove(requestId);
            markDisconnected(currentGen);
            return false;
        }
        return true;
    }

    public boolean sendCommandWithExplicitId(String type, String requestId, String... fields) {
        return sendWithCustomId(ProtocolHandler.command(type, fields), requestId);
    }

    /**
     * Sends a command with an automatically generated request ID and returns that request ID.
     */
    public String sendWithId(String command) {
        String requestId = UUID.randomUUID().toString();
        boolean ok = sendWithCustomId(command, requestId);
        return ok ? requestId : null;
    }

    public void send(String command) {
        sendWithId(command);
    }

    public String sendCommandWithId(String type, String... fields) {
        return sendWithId(ProtocolHandler.command(type, fields));
    }

    public void sendCommand(String type, String... fields) {
        sendWithId(ProtocolHandler.command(type, fields));
    }

    public synchronized void close() {
        connected = false;
        long currentGen = generation++;
        if (monitorTask != null) {
            monitorTask.cancel(false);
            monitorTask = null;
        }
        outboundQueue.clear();
        if (outboundWorker != null) {
            outboundWorker.interrupt();
            outboundWorker = null;
        }
        pendingRequests.clear();
        inFlightPingNanos.clear();
        pingAwaitingPong = false;
        currentLatencyMs = -1;
        closeSocketQuietly();
        connectionManager.setDisconnected(DisconnectReason.MANUAL_CLOSE, currentGen);
        notifyHeartbeatListeners();
    }

    private void readLoop(BufferedReader reader, long readerGeneration) {
        try {
            String line;
            while (isGenerationConnected(readerGeneration) && (line = reader.readLine()) != null) {
                if (line.length() > ProtocolHandler.MAX_LINE_LENGTH) {
                    notifyListener("PROTOCOL_ERROR|" + ProtocolHandler.encode("Server frame exceeded maximum length."));
                    markDisconnected(readerGeneration);
                    return;
                }
                handleControlFrame(line);
                notifyListener(line);
            }
        } catch (IOException ex) {
            markDisconnected(readerGeneration);
        } finally {
            markDisconnected(readerGeneration);
        }
    }

    private void handleControlFrame(String line) {
        try {
            String[] parts = ProtocolHandler.split(line);
            if ("PONG".equals(parts[0])) {
                lastPongAt = System.currentTimeMillis();
                lastPongTimestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                Long pingSentNano = inFlightPingNanos.poll();
                if (pingSentNano != null) {
                    long elapsedNanos = System.nanoTime() - pingSentNano;
                    long latency = Math.max(0, elapsedNanos / 1_000_000L);
                    this.currentLatencyMs = latency;
                    this.pingAwaitingPong = !inFlightPingNanos.isEmpty();
                    notifyHeartbeatListeners();
                }
            } else if ("ACK".equals(parts[0]) && parts.length >= 2) {
                pendingRequests.remove(parts[1]);
            } else if ("ERROR".equals(parts[0]) && parts.length >= 3) {
                pendingRequests.remove(parts[2]);
            }
        } catch (IllegalArgumentException ignored) {
            // The UI receives the frame and can report a protocol problem.
        }
    }

    private void monitorConnection(long monitorGeneration) {
        if (!isGenerationConnected(monitorGeneration)) return;
        long now = System.currentTimeMillis();

        for (var entry : pendingRequests.entrySet()) {
            PendingRequest request = entry.getValue();
            if (now - request.createdAt() >= REQUEST_TIMEOUT_MS
                    && pendingRequests.remove(entry.getKey(), request)) {
                notifyListener("REQUEST_TIMEOUT|" + entry.getKey() + "|" + request.command());
            }
        }

        if (now - lastPongAt >= HEARTBEAT_TIMEOUT_MS) {
            markDisconnected(monitorGeneration);
            return;
        }

        if (now - lastPingAt >= HEARTBEAT_INTERVAL_MS) {
            lastPingAt = now;
            inFlightPingNanos.offer(System.nanoTime());
            pingAwaitingPong = true;
            lastPingTimestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            // Heartbeat is intentionally not tracked as a user request.
            if (!sendRawFrame("PING", monitorGeneration)) {
                markDisconnected(monitorGeneration);
            }
            notifyHeartbeatListeners();
        }
    }

    private void outboundLoop(long workerGeneration) {
        try {
            while (isGenerationConnected(workerGeneration)) {
                OutboundFrame frame = outboundQueue.poll(300, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                if (frame.targetGeneration() != workerGeneration || !isGenerationConnected(workerGeneration)) {
                    continue;
                }
                boolean writeOk = writeRaw(frame.payload());
                if (!writeOk) {
                    if (frame.requestId() != null) {
                        pendingRequests.remove(frame.requestId());
                    }
                    markDisconnected(workerGeneration);
                    break;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            markDisconnected(workerGeneration);
        }
    }

    private boolean sendRawFrame(String payload, long targetGen) {
        if (!isGenerationConnected(targetGen)) return false;
        return outboundQueue.offer(new OutboundFrame(payload, null, targetGen));
    }

    private boolean writeRaw(String line) {
        synchronized (this) {
            if (!connected || out == null) return false;
            out.println(line);
            return !out.checkError();
        }
    }

    private boolean isGenerationConnected(long expectedGeneration) {
        return connected && generation == expectedGeneration;
    }

    private void markDisconnected(long expectedGeneration) {
        synchronized (this) {
            if (generation != expectedGeneration || !connected) return;
            connected = false;
            if (monitorTask != null) {
                monitorTask.cancel(false);
                monitorTask = null;
            }
            outboundQueue.clear();
            if (outboundWorker != null) {
                outboundWorker.interrupt();
                outboundWorker = null;
            }
            pendingRequests.clear();
            inFlightPingNanos.clear();
            pingAwaitingPong = false;
            currentLatencyMs = -1;
            closeSocketQuietly();
        }
        notifyHeartbeatListeners();
        serverListener.dispatch("CLIENT_DISCONNECTED");
    }

    private synchronized void closeSocketQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
        try {
            if (out != null) out.close();
        } catch (Exception ignored) {
        } finally {
            socket = null;
            in = null;
            out = null;
        }
    }

    private void notifyListener(String packet) {
        serverListener.dispatch(packet);
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public long getCurrentLatencyMs() {
        return currentLatencyMs;
    }

    public HeartbeatInfo getHeartbeatInfo() {
        return new HeartbeatInfo(currentLatencyMs, pingAwaitingPong, lastPingTimestamp, lastPongTimestamp);
    }

    public void addHeartbeatListener(HeartbeatListener listener) {
        if (listener != null) {
            heartbeatListeners.add(listener);
        }
    }

    public void removeHeartbeatListener(HeartbeatListener listener) {
        if (listener != null) {
            heartbeatListeners.remove(listener);
        }
    }

    private void notifyHeartbeatListeners() {
        HeartbeatInfo info = getHeartbeatInfo();
        for (HeartbeatListener listener : heartbeatListeners) {
            try {
                listener.onHeartbeatUpdated(info);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Kích hoạt gửi heartbeat PING chủ động (hỗ trợ kiểm thử có kiểm soát).
     */
    public void sendPingForHeartbeat() {
        if (isConnected()) {
            long gen = generation;
            lastPingAt = System.currentTimeMillis();
            inFlightPingNanos.offer(System.nanoTime());
            pingAwaitingPong = true;
            lastPingTimestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            sendRawFrame("PING", gen);
            notifyHeartbeatListeners();
        }
    }

    /**
     * Giả lập mất kết nối ngoài ý muốn (phục vụ kiểm thử AN-10).
     */
    public void simulateUnexpectedDisconnectionForTest() {
        markDisconnected(generation);
        closeSocketQuietly();
    }

    public String getHost() { return serverHost != null ? serverHost : "127.0.0.1"; }
    public int getPort() { return serverPort > 0 ? serverPort : 5000; }
}
