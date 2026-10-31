package com.messaging.server;

import com.messaging.protocol.ProtocolMessage;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientConnectionHandler implements Runnable {

    private final Socket socket;
    private final ObjectMapper objectMapper = new ObjectMapper();


    public ClientConnectionHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.UTF_8
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

                } catch (Exception e) {
                    System.out.println("Invalid JSON: " + request);
                }
            }

        } catch (IOException e) {

            System.out.println(
                    "Client disconnected: " + e.getMessage()
            );

        } finally {

            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
