package com.messaging.constant;

public final class MessageErrorCode {

    private MessageErrorCode() {
    }

    public static final String SENDER_NOT_REGISTERED =
            "SENDER_NOT_REGISTERED";

    public static final String INVALID_RECIPIENT =
            "INVALID_RECIPIENT";

    public static final String INVALID_MESSAGE =
            "INVALID_MESSAGE";

    public static final String MAILBOX_FULL =
            "MAILBOX_FULL";
}