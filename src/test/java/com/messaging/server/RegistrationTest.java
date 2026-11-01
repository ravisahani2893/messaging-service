package com.messaging.server;

import com.messaging.config.MessagingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Registration tests. Both limits in src/test/resources/application.properties
 * are 4. A limit test raises the other limit, so the one under test is hit first.
 */
@DisplayName("Client registration")
@Timeout(10) // no test may run longer than 10 seconds
class RegistrationTest extends ServerTestSupport {

    private static final int LIMIT = 4;

    @BeforeEach
    void limitsInTestPropertiesAreFour() throws IOException {
        MessagingProperties props = loadTestProperties();
        assertEquals(LIMIT, props.getMaxClients());
        assertEquals(LIMIT, props.getMaxConnections());
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A client can register")
    void clientCanRegister() throws Exception {
        startServer(props -> { });

        TestClient john = connect();
        john.send(register("John"));

        assertReply(john.readLine(), "REGISTERED");
    }

    @Test
    @DisplayName("Several clients can register at the same time")
    void severalClientsCanRegisterAtTheSameTime() throws Exception {
        startServer(props -> { });

        TestClient john = connect();
        TestClient ravi = connect();
        john.send(register("John"));
        ravi.send(register("Ravi"));

        assertReply(john.readLine(), "REGISTERED");
        assertReply(ravi.readLine(), "REGISTERED");
    }

    // ------------------------------------------------------------------
    // Duplicate client
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Duplicate client: the same ID on a second connection is rejected (DUPLICATE_CLIENT)")
    void secondConnectionWithTheSameIdIsRejected() throws Exception {
        startServer(props -> { });

        TestClient first = connect();
        first.send(register("John"));
        assertReply(first.readLine(), "REGISTERED");

        TestClient second = connect();
        second.send(register("John"));

        assertReply(second.readLine(), "DUPLICATE_CLIENT");
    }

    // ------------------------------------------------------------------
    // Invalid client
    // ------------------------------------------------------------------

    static Stream<Arguments> invalidRegisterLines() {
        return Stream.of(
                Arguments.of("empty ID", register("")),
                Arguments.of("blank ID", register("   ")),
                Arguments.of("ID with a space", register("has space")),
                Arguments.of("ID with a bad character (!)", register("bad!id")),
                Arguments.of("ID longer than 64 characters", register("a".repeat(65))),
                Arguments.of("no clientId field", "{\"type\":\"REGISTER\"}")
        );
    }

    @ParameterizedTest(name = "Invalid client: {0} (INVALID_CLIENT_ID)")
    @MethodSource("invalidRegisterLines")
    void invalidClientIdIsRejected(String description, String registerLine) throws Exception {
        startServer(props -> { });

        TestClient client = connect();
        client.send(registerLine);

        assertReply(client.readLine(), "INVALID_CLIENT_ID");
    }

    // ------------------------------------------------------------------
    // Max clients: 4 succeed, the 5th is refused with SERVER_FULL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Max clients: 4 clients register, the 5th is refused (SERVER_FULL)")
    void fourClientsRegisterAndTheFifthGetsServerFull() throws Exception {
        // Allow more connections than clients, so the limit hit is max-clients
        startServer(props -> props.setMaxConnections(LIMIT + 1));

        for (int i = 1; i <= LIMIT; i++) {
            registerClient("client" + i);
        }

        TestClient fifth = connect();
        fifth.send(register("client5"));

        assertReply(fifth.readLine(), "SERVER_FULL");
    }

    // ------------------------------------------------------------------
    // Max connections: 4 are served, the 5th gets SERVER_BUSY
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Max connections: 4 connections are served, the 5th is refused (SERVER_BUSY)")
    void fourConnectionsAreServedAndTheFifthGetsServerBusy() throws Exception {
        // Allow more clients than connections, so the limit hit is max-connections
        startServer(props -> props.setMaxClients(LIMIT + 1));

        for (int i = 1; i <= LIMIT; i++) {
            registerClient("client" + i);
        }

        // The server answers without waiting for a REGISTER, then closes
        TestClient fifth = connect();

        assertReply(fifth.readLine(), "SERVER_BUSY");
        assertNull(fifth.readLine(), "server closes the refused connection");
    }
}
