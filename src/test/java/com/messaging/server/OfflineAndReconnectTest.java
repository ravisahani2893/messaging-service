package com.messaging.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Offline messages and reconnecting. John and Ravi are registered in every
 * test, and John sends to Ravi. "Reconnect" means Ravi closes his connection
 * and registers again with the same ID.
 */
@DisplayName("Offline messages and reconnect")
@Timeout(10) // no test may run longer than 10 seconds
class OfflineAndReconnectTest extends ServerTestSupport {

    private TestClient john;
    private TestClient ravi;

    @BeforeEach
    void startServerWithJohnAndRavi() throws Exception {
        startServer(props -> { });
        john = registerClient("John");
        ravi = registerClient("Ravi");
    }

    @Test
    @DisplayName("Offline: a message sent while the recipient is away is kept and delivered when he returns")
    void offlineMessageIsDeliveredAfterReconnect() throws Exception {
        ravi.close();

        john.send(sendMessage("Ravi", "j1", "Available for quick call?"));
        assertReply(john.readLine(), "SEND_ACCEPTED"); // accepted although Ravi is offline

        TestClient raviAgain = reconnect("Ravi");

        String delivered = raviAgain.readLine();
        assertReply(delivered, "DELIVER");
        assertReply(delivered, "\"messageId\":\"j1\"");
        assertReply(delivered, "\"payload\":\"Available for quick call?\"");
    }

    @Test
    @DisplayName("Redelivery: a message delivered but not acknowledged is delivered again after reconnecting")
    void unacknowledgedMessageIsDeliveredAgain() throws Exception {
        john.send(sendMessage("Ravi", "j1", "Hi!!"));
        assertReply(ravi.readLine(), "\"messageId\":\"j1\""); // delivered, but Ravi does not ack
        ravi.close();

        TestClient raviAgain = reconnect("Ravi");

        assertReply(raviAgain.readLine(), "\"messageId\":\"j1\"");
    }

    @Test
    @DisplayName("Acknowledged: a message that was acknowledged is not delivered again after reconnecting")
    void acknowledgedMessageIsNotDeliveredAgain() throws Exception {
        john.send(sendMessage("Ravi", "j1", "first"));
        assertReply(ravi.readLine(), "\"messageId\":\"j1\"");
        ravi.send(ack("j1"));
        assertReply(ravi.readLine(), "ACK_ACCEPTED");
        ravi.close();

        john.send(sendMessage("Ravi", "j2", "second"));
        TestClient raviAgain = reconnect("Ravi");

        // The first thing Ravi receives is j2. If j1 came back, this would fail.
        assertReply(raviAgain.readLine(), "\"messageId\":\"j2\"");
    }

    @Test
    @DisplayName("Order: several offline messages arrive in the order they were sent, one after each ack")
    void offlineMessagesKeepTheirOrder() throws Exception {
        ravi.close();

        john.send(sendMessage("Ravi", "j1", "one"));
        john.send(sendMessage("Ravi", "j2", "two"));
        john.send(sendMessage("Ravi", "j3", "three"));
        assertReply(john.readLine(), "SEND_ACCEPTED");
        assertReply(john.readLine(), "SEND_ACCEPTED");
        assertReply(john.readLine(), "SEND_ACCEPTED");

        TestClient raviAgain = reconnect("Ravi");

        assertReply(raviAgain.readLine(), "\"messageId\":\"j1\"");
        raviAgain.send(ack("j1"));
        assertReply(raviAgain.readLine(), "ACK_ACCEPTED");
        assertReply(raviAgain.readLine(), "\"messageId\":\"j2\"");
        raviAgain.send(ack("j2"));
        assertReply(raviAgain.readLine(), "ACK_ACCEPTED");
        assertReply(raviAgain.readLine(), "\"messageId\":\"j3\"");
    }
}
