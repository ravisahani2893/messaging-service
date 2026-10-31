package com.messaging.model;

public record Message(
        String messageId,
        String senderId,
        String recipientId,
        String payload
) {}
