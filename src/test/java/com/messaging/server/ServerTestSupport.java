package com.messaging.server;

import com.messaging.config.MessagingProperties;
import com.messaging.service.MailboxService;
import com.messaging.service.MessagingService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared set-up for the tests. Each test starts the real server on a free
 * port and talks to it over a real socket, the same as `nc localhost 9090`.
 * Settings come from src/test/resources/application.properties.
 */
abstract class ServerTestSupport {

    private TcpMessagingServer server;
    private int port;
    private final List<TestClient> clients = new ArrayList<>();

    @AfterEach
    void cleanUp() throws IOException {
        for (TestClient client : clients) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    /** Starts the server. A test that needs a different setting changes it in {@code adjust}. */
    protected void startServer(Consumer<MessagingProperties> adjust) throws IOException {
        MessagingProperties props = loadTestProperties();
        adjust.accept(props);

        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        props.setPort(port);

        ClientRegistry registry = new ClientRegistry(props);
        MessagingService messaging =
                new MessagingService(registry, new MailboxService(props), props);

        server = new TcpMessagingServer(props, registry, messaging);
        server.start();
    }

    /** Reads messaging.tcp.* from the test application.properties, as Spring would. */
    protected static MessagingProperties loadTestProperties() throws IOException {
        Properties file = new Properties();
        try (InputStream in = ServerTestSupport.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            file.load(in);
        }
        return new Binder(new MapConfigurationPropertySource(file))
                .bind("messaging.tcp", MessagingProperties.class)
                .get();
    }

    /** Opens a new connection to the server. */
    protected TestClient connect() throws IOException {
        TestClient client = new TestClient(port);
        clients.add(client);
        return client;
    }

    /** Opens a connection and registers it, so the test starts from a registered client. */
    protected TestClient registerClient(String clientId) throws IOException {
        TestClient client = connect();
        client.send(register(clientId));
        assertReply(client.readLine(), "REGISTERED");
        return client;
    }

    /**
     * Registers an ID that was used by a connection that has just been closed.
     * The server notices a closed connection a moment later, and until then it
     * answers DUPLICATE_CLIENT, so this retries (for at most 3 seconds).
     */
    protected TestClient reconnect(String clientId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        while (true) {
            TestClient client = connect();
            client.send(register(clientId));
            String reply = client.readLine();

            if (reply != null && reply.contains("REGISTERED")) {
                return client;
            }

            client.close();
            if (reply == null || !reply.contains("DUPLICATE_CLIENT") || System.nanoTime() > deadline) {
                fail("could not register " + clientId + " again, last reply: " + reply);
            }
            Thread.sleep(20);
        }
    }

    // ------------------------------------------------------------------
    // Messages a client can send
    // ------------------------------------------------------------------

    protected static String register(String clientId) {
        return "{\"type\":\"REGISTER\",\"clientId\":\"" + clientId + "\"}";
    }

    protected static String sendMessage(String recipientId, String messageId, String payload) {
        return "{\"type\":\"SEND\",\"messageId\":\"" + messageId
                + "\",\"recipientId\":\"" + recipientId
                + "\",\"payload\":\"" + payload + "\"}";
    }

    protected static String ack(String messageId) {
        return "{\"type\":\"ACK\",\"messageId\":\"" + messageId + "\"}";
    }

    protected static void assertReply(String reply, String expected) {
        assertTrue(reply != null && reply.contains(expected),
                "expected " + expected + " but got: " + reply);
    }

    /** A plain socket client. Reads time out after 3 seconds, so a missing reply fails the test. */
    protected static class TestClient implements AutoCloseable {

        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;

        TestClient(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(3000);
            in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        }

        void send(String line) {
            out.println(line);
        }

        /** Returns the next line from the server, or null if the server closed the connection. */
        String readLine() throws IOException {
            return in.readLine();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
