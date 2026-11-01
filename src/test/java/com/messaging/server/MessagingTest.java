package com.messaging.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sending, receiving and acknowledging messages. John and Ravi are
 * registered in every test, and John sends to Ravi unless stated.
 */
@DisplayName("Sending and receiving messages")
@Timeout(10) // no test may run longer than 10 seconds
class MessagingTest extends ServerTestSupport {

    private TestClient john;
    private TestClient ravi;

    @BeforeEach
    void startServerWithJohnAndRavi() throws Exception {
        startServer(props -> props.setMailboxCapacity(3));
        john = registerClient("John");
        ravi = registerClient("Ravi");
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Send: the sender is told the message was accepted (SEND_ACCEPTED)")
    void sendIsAccepted() throws Exception {
        john.send(sendMessage("Ravi", "j1", "Hi!! How are you?"));

        String reply = john.readLine();
        assertReply(reply, "SEND_ACCEPTED");
        assertReply(reply, "\"messageId\":\"j1\"");
    }

    @Test
    @DisplayName("Receive: the recipient gets the message with sender and text")
    void recipientReceivesTheMessage() throws Exception {
        john.send(sendMessage("Ravi", "j1", "Hi!! How are you?"));

        String delivered = ravi.readLine();
        assertReply(delivered, "DELIVER");
        assertReply(delivered, "\"messageId\":\"j1\"");
        assertReply(delivered, "\"senderId\":\"John\"");
        assertReply(delivered, "\"payload\":\"Hi!! How are you?\"");
    }

    @Test
    @DisplayName("Receive: the sender is the registered client, a senderId in the request is ignored")
    void senderCannotBeFaked() throws Exception {
        john.send("{\"type\":\"SEND\",\"senderId\":\"Mallory\",\"messageId\":\"j1\","
                + "\"recipientId\":\"Ravi\",\"payload\":\"hello\"}");

        assertReply(ravi.readLine(), "\"senderId\":\"John\"");
    }

    // ------------------------------------------------------------------
    // Duplicate message
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Duplicate message: the same messageId while still waiting is rejected (DUPLICATE_MESSAGE)")
    void duplicateMessageIdIsRejected() throws Exception {
        john.send(sendMessage("Ravi", "j1", "first"));
        assertReply(john.readLine(), "SEND_ACCEPTED");

        john.send(sendMessage("Ravi", "j1", "same id again"));

        assertReply(john.readLine(), "DUPLICATE_MESSAGE");
    }

    @Test
    @DisplayName("Duplicate message: the same messageId can be used again after it was acknowledged")
    void messageIdCanBeReusedAfterAck() throws Exception {
        john.send(sendMessage("Ravi", "j1", "first"));
        assertReply(john.readLine(), "SEND_ACCEPTED");
        assertReply(ravi.readLine(), "DELIVER");
        ravi.send(ack("j1"));
        assertReply(ravi.readLine(), "ACK_ACCEPTED");

        john.send(sendMessage("Ravi", "j1", "reused"));

        assertReply(john.readLine(), "SEND_ACCEPTED");
    }

    // ------------------------------------------------------------------
    // Acknowledge
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Acknowledge: the recipient confirms a message (ACK_ACCEPTED)")
    void recipientCanAcknowledge() throws Exception {
        john.send(sendMessage("Ravi", "j1", "Hi!!"));
        assertReply(ravi.readLine(), "DELIVER");

        ravi.send(ack("j1"));

        String reply = ravi.readLine();
        assertReply(reply, "ACK_ACCEPTED");
        assertReply(reply, "\"messageId\":\"j1\"");
    }

    @Test
    @DisplayName("Acknowledge: the next message is delivered only after the previous one is acknowledged")
    void nextMessageWaitsForTheAck() throws Exception {
        john.send(sendMessage("Ravi", "j1", "first"));
        john.send(sendMessage("Ravi", "j2", "second"));
        assertReply(john.readLine(), "SEND_ACCEPTED");
        assertReply(john.readLine(), "SEND_ACCEPTED");

        // Ravi gets j1 only. If j2 had been pushed early, it would arrive
        // before the ACK_ACCEPTED below and the second assertion would fail.
        assertReply(ravi.readLine(), "\"messageId\":\"j1\"");
        ravi.send(ack("j1"));
        assertReply(ravi.readLine(), "ACK_ACCEPTED");

        assertReply(ravi.readLine(), "\"messageId\":\"j2\"");
    }

    // ------------------------------------------------------------------
    // Error cases
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Error: unknown recipient (INVALID_RECIPIENT)")
    void unknownRecipientIsRejected() throws Exception {
        john.send(sendMessage("Nobody", "j1", "Hi"));

        assertReply(john.readLine(), "INVALID_RECIPIENT");
    }

    static Stream<Arguments> invalidMessages() {
        return Stream.of(
                Arguments.of("messageId with a space", sendMessage("Ravi", "bad id", "Hi")),
                Arguments.of("messageId longer than 64 characters", sendMessage("Ravi", "m".repeat(65), "Hi")),
                Arguments.of("empty payload", sendMessage("Ravi", "j1", "")),
                Arguments.of("no messageId or payload", "{\"type\":\"SEND\",\"recipientId\":\"Ravi\"}")
        );
    }

    @ParameterizedTest(name = "Error: {0} (INVALID_MESSAGE)")
    @MethodSource("invalidMessages")
    void invalidMessageIsRejected(String description, String sendLine) throws Exception {
        john.send(sendLine);

        assertReply(john.readLine(), "INVALID_MESSAGE");
    }

    // ------------------------------------------------------------------
    // Long messages. The limit (max-message-size, 4096 in the test
    // properties) is the length of the whole request line, including the
    // JSON around the text.
    // ------------------------------------------------------------------

    /** A payload that makes the whole SEND line exactly {@code lineLength} characters long. */
    private static String payloadFillingLine(int lineLength) {
        int jsonAroundThePayload = sendMessage("Ravi", "j1", "").length();
        return "x".repeat(lineLength - jsonAroundThePayload);
    }

    @Test
    @DisplayName("Long message: a message exactly at the size limit is accepted and delivered")
    void messageAtTheSizeLimitIsAccepted() throws Exception {
        String payload = payloadFillingLine(loadTestProperties().getMaxMessageSize());

        john.send(sendMessage("Ravi", "j1", payload));

        assertReply(john.readLine(), "SEND_ACCEPTED");
        assertReply(ravi.readLine(), "\"payload\":\"" + payload + "\"");
    }

    static Stream<Arguments> tooLongMessages() throws Exception {
        int limit = loadTestProperties().getMaxMessageSize();
        return Stream.of(
                Arguments.of("one character over the limit",
                        sendMessage("Ravi", "j1", payloadFillingLine(limit) + "x")),
                Arguments.of("100,000 characters",
                        sendMessage("Ravi", "j1", "x".repeat(100_000)))
        );
    }

    @ParameterizedTest(name = "Error: message of {0} (MESSAGE_TOO_LARGE, connection closed)")
    @MethodSource("tooLongMessages")
    void tooLongMessageIsRejectedAndTheConnectionIsClosed(String description, String sendLine)
            throws Exception {
        john.send(sendLine);

        assertReply(john.readLine(), "MESSAGE_TOO_LARGE");
        assertNull(john.readLine(), "server closes the connection after an oversized line");
    }

    @Test
    @DisplayName("Long message: a too long message from John does not affect Ravi's connection")
    void tooLongMessageDoesNotAffectOtherClients() throws Exception {
        john.send(sendMessage("Ravi", "j1", payloadFillingLine(100_000)));
        assertReply(john.readLine(), "MESSAGE_TOO_LARGE");

        // Ravi is still connected and can use the service
        ravi.send(sendMessage("John", "r1", "still here"));
        assertReply(ravi.readLine(), "SEND_ACCEPTED");
    }

    @Test
    @DisplayName("Error: sending before registering (NOT_REGISTERED)")
    void sendingBeforeRegisteringIsRejected() throws Exception {
        TestClient stranger = connect();

        stranger.send(sendMessage("Ravi", "x1", "Hi"));

        assertReply(stranger.readLine(), "NOT_REGISTERED");
    }

    @Test
    @DisplayName("Error: recipient's mailbox is full, limit 3 (MAILBOX_FULL)")
    void fullMailboxRejectsTheNextMessage() throws Exception {
        for (int i = 1; i <= 3; i++) {
            john.send(sendMessage("Ravi", "j" + i, "message " + i));
            assertReply(john.readLine(), "SEND_ACCEPTED");
        }

        john.send(sendMessage("Ravi", "j4", "one too many"));

        assertReply(john.readLine(), "MAILBOX_FULL");
    }

    @Test
    @DisplayName("Error: acknowledging an unknown message (INVALID_ACK)")
    void ackOfUnknownMessageIsRejected() throws Exception {
        ravi.send(ack("nope"));

        assertReply(ravi.readLine(), "INVALID_ACK");
    }

    @Test
    @DisplayName("Error: acknowledging the same message twice (INVALID_ACK)")
    void secondAckOfTheSameMessageIsRejected() throws Exception {
        john.send(sendMessage("Ravi", "j1", "Hi"));
        assertReply(ravi.readLine(), "DELIVER");
        ravi.send(ack("j1"));
        assertReply(ravi.readLine(), "ACK_ACCEPTED");

        ravi.send(ack("j1"));

        assertReply(ravi.readLine(), "INVALID_ACK");
    }

    @Test
    @DisplayName("Error: a client cannot acknowledge a message that was sent to someone else (INVALID_ACK)")
    void senderCannotAckTheRecipientsMessage() throws Exception {
        john.send(sendMessage("Ravi", "j1", "Hi"));
        assertReply(ravi.readLine(), "DELIVER");

        john.send(ack("j1"));
        assertReply(john.readLine(), "SEND_ACCEPTED"); // reply to the SEND above
        assertReply(john.readLine(), "INVALID_ACK");

        // The message is still Ravi's to acknowledge
        ravi.send(ack("j1"));
        assertReply(ravi.readLine(), "ACK_ACCEPTED");
    }
}
