package com.messaging.server;

import com.messaging.config.MessagingProperties;
import com.messaging.model.ClientSession;
import com.messaging.model.ConnectResult;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * All known clients, online or offline. A client is never removed: a
 * disconnected client stays here so it can reconnect to its mailbox.
 * The number of distinct clients is therefore capped by max-clients.
 */
@Component
public class ClientRegistry {

    private final ConcurrentMap<String, ClientSession> clients =
            new ConcurrentHashMap<>();

    // Number of distinct clients ever registered (online + offline)
    private final AtomicInteger clientCount = new AtomicInteger();

    private final MessagingProperties properties;

    public ClientRegistry(MessagingProperties properties) {
        this.properties = properties;
    }

    /**
     * Registers a new client or reattaches an offline one. compute() runs
     * atomically per key, so two simultaneous REGISTERs for the same ID
     * cannot both succeed.
     */
    public ConnectResult connect(
            String clientId,
            Socket socket,
            BufferedWriter writer
    ) {

        AtomicReference<ConnectResult> result = new AtomicReference<>();

        clients.compute(clientId, (id, existing) -> {

            // Known client coming back: reattach, unless it is still connected
            if (existing != null) {
                result.set(existing.attach(socket, writer)
                        ? ConnectResult.connected(existing)
                        : ConnectResult.duplicateClient());
                return existing;
            }

            // Brand-new client: only if there is room for another one
            if (!reserveClientSlot()) {
                result.set(ConnectResult.serverFull());
                return null; // nothing is stored
            }

            ClientSession created =
                    new ClientSession(clientId, properties.getOutboundQueueCapacity());
            created.attach(socket, writer);
            result.set(ConnectResult.connected(created));
            return created;
        });

        return result.get();
    }

    /** Claims one slot with compareAndSet, so concurrent new IDs cannot exceed the cap. */
    private boolean reserveClientSlot() {
        while (true) {
            int current = clientCount.get();
            if (current >= properties.getMaxClients()) {
                return false;
            }
            if (clientCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Returns true if this socket was the session's active connection. */
    public boolean unregister(
            ClientSession session,
            Socket expectedSocket
    ) {

        ClientSession current = clients.get(session.getClientId());

        return current == session && session.disconnect(expectedSocket);
    }

    public ClientSession getClient(String clientId) {
        return clients.get(clientId);
    }

    public boolean exists(String clientId) {
        return clients.containsKey(clientId);
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
}
