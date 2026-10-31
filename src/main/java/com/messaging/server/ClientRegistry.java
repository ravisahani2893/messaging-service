package com.messaging.server;

import com.messaging.model.ClientSession;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ClientRegistry {

    private final ConcurrentMap<String, ClientSession> clients =
            new ConcurrentHashMap<>();

    public boolean register(ClientSession session) {

        return clients.putIfAbsent(
                session.clientId(),
                session
        ) == null;
    }

    public void unregister(ClientSession session) {

        clients.remove(
                session.clientId(),
                session
        );
    }

    public ClientSession getClient(String clientId) {

        return clients.get(clientId);
    }

    public boolean isRegistered(String clientId) {

        return clients.containsKey(clientId);
    }

    public int getActiveClientCount() {

        return clients.size();
    }
}