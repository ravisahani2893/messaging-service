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


    public ClientConnectionHandler(Socket socket, ClientRegistry clientRegistry) {
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
                        socket.getOutputStream(), StandardCharsets.UTF_8
                        )
                )
        ) {

            String request;

            while ((request = reader.readLine()) != null) {

                try {
                    ProtocolMessage message =
                            objectMapper.readValue(request, ProtocolMessage.class);

                    System.out.println("Message type: " + message.type());
                    System.out.println("Client ID: " + message.clientId());

                    if ("REGISTER".equals(message.type())) {

                        handleRegistration(message, writer);

                    }

                } catch (Exception e) {
                    System.out.println("Invalid JSON: " + request);
                }
            }

        } catch (IOException e) {

            System.out.println(
                    "Client disconnected: " + e.getMessage()
            );

        } finally {

            if (clientSession != null) {
                clientRegistry.unregister(clientSession);
                System.out.println(
                        clientSession.clientId() + " unregistered"
                );
            }

            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleRegistration(ProtocolMessage message, BufferedWriter writer) throws IOException {

        String clientId = message.clientId();
        if(clientId == null || clientId.isBlank()) {

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

        if (clientSession != null) {

            sendResponse(
                    writer,
                    new ProtocolMessage(
                            "ERROR",
                            clientSession.clientId(),
                            null,
                            null,
                            "ALREADY_REGISTERED"
                    )
            );

            return;
        }

        ClientSession newSession =
                new ClientSession(clientId, socket);

        boolean registered =
                clientRegistry.register(newSession);

        if (registered) {

            clientSession = newSession;

            System.out.println(
                    "Client registered: " + clientId
            );

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

        }else {

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
        }


    }

    private void sendResponse(BufferedWriter writer, ProtocolMessage response) throws IOException {
        String json = objectMapper.writeValueAsString(response);

        writer.write(json);
        writer.newLine();
        writer.flush();
    }
}
