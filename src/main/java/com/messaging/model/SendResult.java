package com.messaging.model;

public record SendResult(
        boolean accepted,
        String errorCode
) {}
