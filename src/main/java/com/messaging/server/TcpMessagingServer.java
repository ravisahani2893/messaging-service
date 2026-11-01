package com.messaging.server;


import com.messaging.config.MessagingProperties;
import com.messaging.service.MessagingService;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.messaging.constant.MessageErrorCode;
import com.messaging.protocol.ProtocolMessage;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


@Component
public class TcpMessagingServer implements SmartLifecycle {

    private final MessagingProperties properties;

    private ServerSocket serverSocket;

    private ExecutorService clientExecutor;

    private volatile boolean running = false;

    private volatile Semaphore connectionPermits;

    // Live client sockets, so shutdown can close them
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ClientRegistry clientRegistry;

    private final MessagingService messagingService;



    public TcpMessagingServer(MessagingProperties properties, ClientRegistry clientRegistry, MessagingService messagingService) {
        this.properties = properties;
        this.clientRegistry = clientRegistry;
        this.messagingService = messagingService;
    }

    @Override
    public void start() {

        try {
            serverSocket = new ServerSocket(properties.getPort());

            clientExecutor = Executors.newFixedThreadPool(
                    properties.getMaxConnections()
            );

            connectionPermits = new Semaphore(properties.getMaxConnections());

            running = true;

            System.out.println(
                    "TCP server started on port "
                            + properties.getPort()
            );

            Thread acceptThread = new Thread(
                    this::acceptConnections,
                    "tcp-accept-thread"
            );

            acceptThread.start();

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to start TCP server", e
            );
        }
    }

    private void acceptConnections() {

        while (running) {

            try {
                Socket clientSocket = serverSocket.accept();

                // Bound active connections: one permit per live connection.
                // Without it the executor's queue would hold extra sockets
                // open indefinitely with no reply.
                if (!connectionPermits.tryAcquire()) {
                    rejectBusy(clientSocket);
                    continue;
                }

                ClientConnectionHandler handler = new ClientConnectionHandler(
                        clientSocket, clientRegistry, messagingService,
                        properties.getMaxMessageSize(), properties.getIdleTimeoutMillis());

                // Tracked so stop() can close it and unblock its handler thread
                openSockets.add(clientSocket);

                try {
                    clientExecutor.submit(() -> {
                        try {
                            handler.run();
                        } finally {
                            openSockets.remove(clientSocket);
                            connectionPermits.release();
                        }
                    });
                } catch (RejectedExecutionException e) {
                    // Executor already shut down
                    openSockets.remove(clientSocket);
                    connectionPermits.release();
                    closeQuietly(clientSocket);
                }

            } catch (IOException e) {

                if (running) {
                    System.err.println(
                            "Error accepting connection: "
                                    + e.getMessage()
                    );
                }
            }
        }
    }

    /** Tells the client why it was refused, then closes. One short line, so it cannot block. */
    private void rejectBusy(Socket socket) {
        try {
            socket.setSoTimeout(1000);
            String json = objectMapper.writeValueAsString(
                    ProtocolMessage.error(null, MessageErrorCode.SERVER_BUSY));
            OutputStream out = socket.getOutputStream();
            out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        } finally {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void stop() {

        running = false;

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing server socket");
        }

        // Handler threads sit in a blocking socket read, which an interrupt
        // does not wake. Closing the sockets makes those reads fail, so every
        // handler runs its cleanup and exits.
        openSockets.forEach(TcpMessagingServer::closeQuietly);

        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
            try {
                clientExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("TCP server stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}