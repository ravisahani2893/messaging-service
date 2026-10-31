package com.messaging.protocol;

public record ProtocolMessage(
        String type,
        String clientId,
        String messageId,
        String recipientId,
        String payload
) {
}