package com.messaging.server;

import com.messaging.model.ClientSession;
import org.springframework.stereotype.Component;

import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ClientRegistry {

    private final ConcurrentMap<String, ClientSession> clients =
            new ConcurrentHashMap<>();

    public ClientSession connect(String clientId, Socket socket) {

        AtomicReference<ClientSession> connectedSession =
                new AtomicReference<>();

        clients.compute(clientId, (id, existingSession) -> {

            // First-time registration
            if (existingSession == null) {
                ClientSession newSession =
                        new ClientSession(clientId, socket);

                connectedSession.set(newSession);
                return newSession;
            }

            // Existing client reconnecting
            if (existingSession.reconnect(socket)) {
                connectedSession.set(existingSession);
            }

            return existingSession;
        });

        return connectedSession.get();
    }


    public void unregister(ClientSession session, Socket expectedSocket) {

        ClientSession current = clients.get(session.getClientId());

        if (current == session) {
            session.disconnect(expectedSocket);
        }
    }

    public ClientSession getClient(String clientId) {

        return clients.get(clientId);
    }

    public boolean isRegistered(String clientId) {

        ClientSession session = clients.get(clientId);
        return session != null && session.isOnline();
    }

    public int getActiveClientCount() {

        return (int) clients.values()
                .stream()
                .filter(ClientSession::isOnline)
                .count();
    }

    public boolean exists(String clientId) {
        return clients.containsKey(clientId);
    }
}