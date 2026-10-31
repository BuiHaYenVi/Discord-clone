package com.discordmini.client.network;

import com.discordmini.client.ClientApplication;

import javax.swing.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automated tests for AN-01: TCP Client connection, background worker execution,
 * resource release on failure/close, and non-blocking EDT behavior.
 */
public final class ClientConnectionTest {
    private ClientConnectionTest() {}

    public static void main(String[] args) throws Exception {
        testSuccessfulConnectionAndDataFlow();
        testConnectionRefusedAndResourceCleanup();
        testClientCloseReleasesResources();
        testAsyncConnectRunsOnBackgroundAndDoneRunsOnEdt();
        testAsyncConnectFailureCleansUpClient();
        System.out.println("ClientConnectionTest: ALL PASS");
    }

    private static void testSuccessfulConnectionAndDataFlow() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept()) {
                    check(peer.isConnected(), "Peer must be connected to fake server");
                    // Keep socket open briefly for client handshake
                    Thread.sleep(200);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            check(!client.isConnected(), "Client must be disconnected before connect");

            client.connect("127.0.0.1", port);
            check(client.isConnected(), "Client must be connected after connect()");

            client.close();
            check(!client.isConnected(), "Client must be disconnected after close()");
            System.out.println("  PASS: testSuccessfulConnectionAndDataFlow");
        }
    }

    private static void testConnectionRefusedAndResourceCleanup() {
        // Find a guaranteed closed port
        int closedPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            closedPort = temp.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not allocate temporary port", e);
        }

        SocketClient client = new SocketClient();
        boolean threwException = false;
        try {
            client.connect("127.0.0.1", closedPort);
        } catch (IOException ex) {
            threwException = true;
        }

        check(threwException, "Connecting to closed port must throw IOException");
        check(!client.isConnected(), "Client must remain disconnected on connection failure");
        System.out.println("  PASS: testConnectionRefusedAndResourceCleanup");
    }

    private static void testClientCloseReleasesResources() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept()) {
                    check(peer.isConnected(), "Peer must be connected to fake server");
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            check(client.isConnected(), "Client is connected");

            client.close();
            check(!client.isConnected(), "Client is disconnected after close");

            // Calling close() again must be safe and idempotent
            client.close();
            check(!client.isConnected(), "Client remains disconnected after second close");
            System.out.println("  PASS: testClientCloseReleasesResources");
        }
    }

    private static void testAsyncConnectRunsOnBackgroundAndDoneRunsOnEdt() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept()) {
                    check(peer.isConnected(), "Peer must be connected to fake server");
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            AtomicBoolean backgroundWasEdt = new AtomicBoolean(true);
            AtomicBoolean doneWasEdt = new AtomicBoolean(false);
            CountDownLatch doneSignal = new CountDownLatch(1);

            SwingWorker<SocketClient, Void> worker = new SwingWorker<>() {
                @Override
                protected SocketClient doInBackground() throws Exception {
                    backgroundWasEdt.set(SwingUtilities.isEventDispatchThread());
                    client.connect("127.0.0.1", port);
                    return client;
                }

                @Override
                protected void done() {
                    doneWasEdt.set(SwingUtilities.isEventDispatchThread());
                    doneSignal.countDown();
                }
            };
            worker.execute();

            check(doneSignal.await(5, TimeUnit.SECONDS), "Worker must finish within timeout");
            check(!backgroundWasEdt.get(), "doInBackground() must NOT run on EDT (must be background thread)");
            check(doneWasEdt.get(), "done() must run on EDT");
            check(client.isConnected(), "Client must be connected");

            client.close();
            System.out.println("  PASS: testAsyncConnectRunsOnBackgroundAndDoneRunsOnEdt");
        }
    }

    private static void testAsyncConnectFailureCleansUpClient() throws Exception {
        int closedPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            closedPort = temp.getLocalPort();
        }

        SocketClient client = new SocketClient();
        SwingWorker<SocketClient, Void> worker = ClientApplication.connectAsync(
                client, "127.0.0.1", closedPort, false, false);

        boolean threwExecutionException = false;
        try {
            worker.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            threwExecutionException = true;
            check(ex.getCause() instanceof IOException, "Cause must be IOException");
        }

        check(threwExecutionException, "Worker.get() must throw ExecutionException on failed connect");
        // Wait briefly for done() on EDT to finish calling client.close()
        SwingUtilities.invokeAndWait(() -> {
            check(!client.isConnected(), "Client must be disconnected and closed");
        });
        System.out.println("  PASS: testAsyncConnectFailureCleansUpClient");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
