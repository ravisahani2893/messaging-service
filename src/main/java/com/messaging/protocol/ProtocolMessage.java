package com.messaging.protocol;

import com.messaging.constant.MessageType;
import com.messaging.model.Message;

/**
 * Every line on the wire, in both directions, is one JSON object of this shape.
 * Fields that do not apply to a given type are null. The static factories below
 * name each server reply, so callers never pass a row of positional nulls.
 *
 * Client to server: REGISTER, SEND, ACK
 * Server to client: REGISTERED, SEND_ACCEPTED, DELIVER, ACK_ACCEPTED, ERROR
 */
public record ProtocolMessage(
        String type,
        String clientId,
        String messageId,
        String senderId,
        String recipientId,
        String payload
) {

    public static ProtocolMessage registered(String clientId) {
        return new ProtocolMessage(MessageType.REGISTERED, clientId,
                null, null, null, "Registration successful");
    }

    public static ProtocolMessage sendAccepted(String senderId, String recipientId, String messageId) {
        return new ProtocolMessage(MessageType.SEND_ACCEPTED, senderId,
                messageId, senderId, recipientId, "Message accepted");
    }

    public static ProtocolMessage deliver(Message m) {
        return new ProtocolMessage(MessageType.DELIVER, m.recipientId(),
                m.messageId(), m.senderId(), m.recipientId(), m.payload());
    }

    public static ProtocolMessage ackAccepted(String recipientId, String messageId) {
        return new ProtocolMessage(MessageType.ACK_ACCEPTED, recipientId,
                messageId, null, recipientId, "Message acknowledged successfully");
    }

    /** clientId may be null when the connection is not registered yet. */
    public static ProtocolMessage error(String clientId, String errorCode) {
        return new ProtocolMessage(MessageType.ERROR, clientId,
                null, null, null, errorCode);
    }
}
