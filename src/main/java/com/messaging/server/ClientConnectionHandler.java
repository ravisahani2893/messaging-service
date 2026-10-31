package com.messaging.server;

import com.messaging.model.ClientSession;
import com.messaging.protocol.ProtocolMessage;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientConnectionHandler implements Runnable {

    private final Socket socket;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClientRegistry clientRegistry;

    private ClientSession clientSession;

    public ClientConnectionHandler(
            Socket socket,
            ClientRegistry clientRegistry
    ) {
        this.socket = socket;
        this.clientRegistry = clientRegistry;
    }

    @Override
    public void run() {

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8
                        )
                )
        ) {

            String request;

            while ((request = reader.readLine()) != null) {

                try {
                    ProtocolMessage message =
                            objectMapper.readValue(
                                    request,
                                    ProtocolMessage.class
                            );

                    System.out.println("Message type: " + message.type());
                    System.out.println("Client ID: " + message.clientId());

                    if ("REGISTER".equals(message.type())) {

                        handleRegistration(message, writer);

                    } else {

                        sendResponse(
                                writer,
                                new ProtocolMessage(
                                        "ERROR",
                                        clientSession == null
                                                ? null
                                                : clientSession.getClientId(),
                                        null,
                                        null,
                                        "NOT_REGISTERED_OR_UNSUPPORTED_OPERATION"
                                )
                        );
                    }

                } catch (Exception e) {

                    System.out.println("Invalid JSON: " + request);

                    sendResponse(
                            writer,
                            new ProtocolMessage(
                                    "ERROR",
                                    null,
                                    null,
                                    null,
                                    "INVALID_JSON"
                            )
                    );
                }
            }

        } catch (IOException e) {

            System.out.println(
                    "Client disconnected: " + e.getMessage()
            );

        } finally {

            if (clientSession != null) {

                clientRegistry.unregister(clientSession, socket);

                System.out.println(
                        clientSession.getClientId() + " disconnected"
                );
            }

            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleRegistration(
            ProtocolMessage message,
            BufferedWriter writer
    ) throws IOException {

        String clientId = message.clientId();

        // Validate client ID
        if (clientId == null || clientId.isBlank()) {

            sendResponse(
                    writer,
                    new ProtocolMessage(
                            "ERROR",
                            null,
                            null,
                            null,
                            "INVALID_CLIENT_ID"
                    )
            );

            return;
        }

        // Prevent registering twice on the same connection
        if (clientSession != null) {

            sendResponse(
                    writer,
                    new ProtocolMessage(
                            "ERROR",
                            clientSession.getClientId(),
                            null,
                            null,
                            "ALREADY_REGISTERED"
                    )
            );

            return;
        }

        // Register a new client or reconnect an offline client
        ClientSession connectedSession =
                clientRegistry.connect(clientId, socket);

        // Null means the client is already online
        if (connectedSession == null) {

            sendResponse(
                    writer,
                    new ProtocolMessage(
                            "ERROR",
                            clientId,
                            null,
                            null,
                            "DUPLICATE_CLIENT"
                    )
            );

            return;
        }

        // Retain the session for cleanup when this handler disconnects
        this.clientSession = connectedSession;

        sendResponse(
                writer,
                new ProtocolMessage(
                        "REGISTERED",
                        clientId,
                        null,
                        null,
                        "Registration successful"
                )
        );

        System.out.println("Client connected: " + clientId);
    }

    private void sendResponse(
            BufferedWriter writer,
            ProtocolMessage response
    ) throws IOException {

        String json = objectMapper.writeValueAsString(response);

        writer.write(json);
        writer.newLine();
        writer.flush();
    }
}