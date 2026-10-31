package com.discordmini.server.web;

import com.discordmini.server.network.ChatServer;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.udp.UdpTelemetryServer;
import com.discordmini.server.web.http.HttpApiServer;
import com.discordmini.server.web.ws.DiscordWebSocketServer;

import java.io.IOException;

/**
 * Bootstrapper that starts and stops the JDK HttpServer REST API (:8080)
 * and Java-WebSocket Server (:8081) alongside ChatServer (:5000 TCP / :5001 UDP).
 */
public class WebGatewayBootstrap {

    private ChatServer chatServer;
    private EventBus eventBus;
    private UdpTelemetryServer udpServer;
    private HttpApiServer httpApiServer;
    private DiscordWebSocketServer webSocketServer;
    private volatile boolean started = false;

    public WebGatewayBootstrap() {
        this(null);
    }

    public WebGatewayBootstrap(ChatServer chatServer) {
        this.chatServer = chatServer;
        this.eventBus = (chatServer != null && chatServer.getEventBus() != null)
                ? chatServer.getEventBus()
                : EventBus.getInstance();
        this.udpServer = (chatServer != null && chatServer.getUdpTelemetryServer() != null)
                ? chatServer.getUdpTelemetryServer()
                : null;
    }

    public static WebGatewayBootstrap startAll(ChatServer chatServer) {
        WebGatewayBootstrap bootstrap = new WebGatewayBootstrap(chatServer);
        bootstrap.start();
        return bootstrap;
    }

    public static WebGatewayBootstrap start(ChatServer chatServer) {
        return startAll(chatServer);
    }

    public synchronized void start() {
        if (started) return;

        if (eventBus == null) {
            eventBus = EventBus.getInstance();
        }
        if (udpServer == null && chatServer != null) {
            udpServer = chatServer.getUdpTelemetryServer();
        }

        int httpPort = HttpApiServer.resolveHttpPort();
        int wsPort = DiscordWebSocketServer.resolveWsPort();

        try {
            httpApiServer = new HttpApiServer(httpPort, wsPort, chatServer, eventBus, udpServer);
            httpApiServer.start();
        } catch (IOException e) {
            System.err.println("[WEB GATEWAY ERROR] Failed to start HttpApiServer on port :" + httpPort + " - " + e.getMessage());
        }

        try {
            webSocketServer = new DiscordWebSocketServer(wsPort, chatServer, eventBus, udpServer);
            webSocketServer.start();
        } catch (Exception e) {
            System.err.println("[WEB GATEWAY ERROR] Failed to start DiscordWebSocketServer on port :" + wsPort + " - " + e.getMessage());
        }

        started = true;
    }

    public synchronized void stopAll() {
        if (!started) return;
        started = false;

        if (httpApiServer != null) {
            try {
                httpApiServer.stop();
            } catch (Exception ignored) {}
            httpApiServer = null;
        }

        if (webSocketServer != null) {
            try {
                webSocketServer.stop(1000);
            } catch (Exception ignored) {}
            webSocketServer = null;
        }

        System.out.println("[WEB GATEWAY] Stopped HTTP REST and WebSocket servers.");
    }

    public synchronized void stop() {
        stopAll();
    }

    public HttpApiServer getHttpApiServer() {
        return httpApiServer;
    }

    public DiscordWebSocketServer getWebSocketServer() {
        return webSocketServer;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public UdpTelemetryServer getUdpServer() {
        return udpServer;
    }
}
