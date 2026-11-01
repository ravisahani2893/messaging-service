package com.messaging.server;

import com.messaging.constant.MessageErrorCode;
import com.messaging.constant.MessageType;
import com.messaging.model.ClientSession;
import com.messaging.model.ConnectResult;
import com.messaging.model.Message;
import com.messaging.model.SendResult;
import com.messaging.protocol.ProtocolMessage;
import com.messaging.service.MessagingService;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Serves one TCP connection on its own thread: read a line, parse it,
 * handle it, repeat. Connection lifecycle:
 *
 *   connected (unregistered) --REGISTER--> registered --close/timeout--> cleaned up
 *
 * Each connection has exactly one of these handlers, so its fields need no
 * locking. Everything shared with other connections lives in ClientRegistry
 * and the mailboxes, which are thread-safe on their own.
 */
public class ClientConnectionHandler implements Runnable {

    // After refusing an oversized line, at most this much more input is read and thrown away
    private static final int MAX_DISCARD_BYTES = 1024 * 1024;

    private final Socket socket;
    private final ClientRegistry clientRegistry;
    private final MessagingService messagingService;
    private final int maxMessageSize;
    private final int idleTimeoutMillis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private BufferedWriter writer;

    // null until REGISTER succeeds on this connection
    private ClientSession session;

    public ClientConnectionHandler(
            Socket socket,
            ClientRegistry clientRegistry,
            MessagingService messagingService,
            int maxMessageSize,
            int idleTimeoutMillis
    ) {
        this.socket = socket;
        this.clientRegistry = clientRegistry;
        this.messagingService = messagingService;
        this.maxMessageSize = maxMessageSize;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    @Override
    public void run() {

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        ) {
            this.writer = out;

            // read() now fails with SocketTimeoutException after this much silence
            socket.setSoTimeout(idleTimeoutMillis);

            processRequests(reader);

        } catch (SocketTimeoutException e) {
            // Frees the connection slot, and the client ID if it was registered.
            // Its messages stay in the mailbox for when it reconnects.
            System.out.println("Closing idle connection after " + idleTimeoutMillis + " ms");
        } catch (IOException e) {
            System.out.println("Client disconnected: " + e.getMessage());
        } finally {
            cleanUp();
        }
    }

    // ------------------------------------------------------------------
    // Request loop
    // ------------------------------------------------------------------

    private void processRequests(BufferedReader reader) throws IOException {

        while (true) {

            String line;

            try {
                line = BoundedLineReader.readLine(reader, maxMessageSize);
            } catch (BoundedLineReader.LineTooLongException e) {
                // The rest of the oversized line is unread, so the stream
                // cannot be resynchronised: reject and close the connection.
                replyError(MessageErrorCode.MESSAGE_TOO_LARGE);
                closeAfterReply();
                return;
            }

            if (line == null) {
                return; // client closed the connection
            }

            ProtocolMessage request = parse(line);

            if (request == null) {
                replyError(MessageErrorCode.INVALID_JSON);
                continue; // a bad line does not end the connection
            }

            dispatch(request);
        }
    }

    /** Returns null for anything that is not a JSON object. */
    private ProtocolMessage parse(String line) {
        try {
            return objectMapper.readValue(line, ProtocolMessage.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void dispatch(ProtocolMessage request) throws IOException {

        String type = request.type() == null ? "" : request.type();

        if (type.equals(MessageType.REGISTER)) {
            handleRegister(request);

        } else if (session == null) {
            replyError(MessageErrorCode.NOT_REGISTERED);

        } else if (type.equals(MessageType.SEND)) {
            handleSend(request);

        } else if (type.equals(MessageType.ACK)) {
            handleAck(request);

        } else {
            replyError(MessageErrorCode.UNSUPPORTED_OPERATION);
        }
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    private void handleRegister(ProtocolMessage request) throws IOException {

        String clientId = request.clientId();

        if (!MessagingService.isValidId(clientId)) {
            replyError(MessageErrorCode.INVALID_CLIENT_ID);
            return;
        }

        if (session != null) {
            replyError(MessageErrorCode.ALREADY_REGISTERED);
            return;
        }

        // Creates the client, or reattaches an offline one with its mailbox intact.
        ConnectResult result = clientRegistry.connect(clientId, socket, writer);

        switch (result.status()) {
            case DUPLICATE_CLIENT -> {
                replyError(MessageErrorCode.DUPLICATE_CLIENT);
                return;
            }
            case SERVER_FULL -> {
                replyError(MessageErrorCode.SERVER_FULL);
                return;
            }
            case CONNECTED -> this.session = result.session();
        }

        // Anything sent on a previous connection but never acked is sent again
        messagingService.resetDelivery(clientId);

        reply(ProtocolMessage.registered(clientId));
        System.out.println("Client connected: " + clientId);

        deliverNextTo(clientId);
    }

    private void handleSend(ProtocolMessage request) throws IOException {

        // The sender is always the registered identity, never a field the client supplies
        String senderId = session.getClientId();

        SendResult result = messagingService.sendMessage(
                senderId, request.recipientId(), request.messageId(), request.payload());

        if (!result.accepted()) {
            replyError(result.errorCode());
            return;
        }

        reply(ProtocolMessage.sendAccepted(senderId, request.recipientId(), request.messageId()));

        deliverNextTo(request.recipientId());
    }

    private void handleAck(ProtocolMessage request) throws IOException {

        // The recipient is always the registered identity, so one client
        // cannot acknowledge another client's messages.
        String recipientId = session.getClientId();

        boolean acknowledged = messagingService.acknowledgeMessage(recipientId, request.messageId());

        if (!acknowledged) {
            replyError(MessageErrorCode.INVALID_ACK);
            return;
        }

        reply(ProtocolMessage.ackAccepted(recipientId, request.messageId()));

        // The ack frees the head, so the next message can go out
        deliverNextTo(recipientId);
    }

    /**
     * Sends the recipient its next message if it is online and nothing is
     * already awaiting an ack. The claim is atomic, so when several threads
     * call this at once only one of them delivers.
     */
    private void deliverNextTo(String recipientId) {

        ClientSession recipient = clientRegistry.getClient(recipientId);

        // Check before claiming: claiming marks the message in flight
        if (recipient == null || !recipient.isOnline()) {
            return;
        }

        Message next = messagingService.claimNextMessage(recipientId);

        if (next == null) {
            return;
        }

        try {
            recipient.send(objectMapper.writeValueAsString(ProtocolMessage.deliver(next)));
        } catch (Exception e) {
            // Recipient is gone or too slow. The message stays in its mailbox
            // and is sent again when it reconnects.
            System.err.println("Failed to deliver message: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Replies and cleanup
    // ------------------------------------------------------------------

    /**
     * Once registered, replies go through the session's queue and its single
     * writer thread. Before that nobody else can reach this socket, so this
     * thread writes directly.
     */
    private void reply(ProtocolMessage response) throws IOException {

        String json = objectMapper.writeValueAsString(response);

        if (session != null) {
            session.send(json);
        } else {
            writer.write(json);
            writer.newLine();
            writer.flush();
        }
    }

    private void replyError(String errorCode) throws IOException {
        reply(ProtocolMessage.error(session == null ? null : session.getClientId(), errorCode));
    }

    /**
     * Closes the connection after a final reply, making sure the client gets
     * that reply. Closing a socket that still has unread data makes TCP reset
     * the connection, and a reset can throw away the reply before the client
     * reads it. So: send the reply, signal "no more output", read and discard
     * the rest of what the client is sending (a short time, a bounded amount),
     * and only then close.
     */
    private void closeAfterReply() {
        try {
            if (session != null) {
                session.flushAndStopWriter(1000);
            }
            socket.shutdownOutput();

            socket.setSoTimeout(500);
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            int discarded = 0;
            int read;
            while (discarded < MAX_DISCARD_BYTES && (read = in.read(buffer)) != -1) {
                discarded += read;
            }
        } catch (IOException ignored) {
            // Client already gone or silent: nothing more to do, just close
        }
    }

    private void cleanUp() {

        if (session != null) {

            // Only the connection that currently owns the session may detach it,
            // so a late cleanup cannot knock out a newer reconnect.
            if (clientRegistry.unregister(session, socket)) {
                messagingService.resetDelivery(session.getClientId());
            }

            System.out.println(session.getClientId() + " disconnected");
        }

        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
