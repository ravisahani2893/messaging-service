package com.messaging.server;


import com.messaging.config.MessagingProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Component
public class TcpMessagingServer implements SmartLifecycle {

    private final MessagingProperties properties;

    private ServerSocket serverSocket;

    private ExecutorService clientExecutor;

    private volatile boolean running = false;

    private final ClientRegistry clientRegistry;



    public TcpMessagingServer(MessagingProperties properties, ClientRegistry clientRegistry) {
        this.properties = properties;
        this.clientRegistry = clientRegistry;
    }

    @Override
    public void start() {

        try {
            serverSocket = new ServerSocket(properties.getPort());

            clientExecutor = Executors.newFixedThreadPool(
                    properties.getMaxConnections()
            );

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

                clientExecutor.submit(
                        new ClientConnectionHandler(clientSocket,clientRegistry)
                );

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

        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
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