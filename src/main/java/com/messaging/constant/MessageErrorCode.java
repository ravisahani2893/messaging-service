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

    public static final String INVALID_JSON =
            "INVALID_JSON";

    public static final String NOT_REGISTERED =
            "NOT_REGISTERED";

    public static final String UNSUPPORTED_OPERATION =
            "UNSUPPORTED_OPERATION";

    public static final String INVALID_CLIENT_ID =
            "INVALID_CLIENT_ID";

    public static final String ALREADY_REGISTERED =
            "ALREADY_REGISTERED";

    public static final String DUPLICATE_CLIENT =
            "DUPLICATE_CLIENT";

    public static final String DUPLICATE_MESSAGE = "DUPLICATE_MESSAGE";

    public static final String INVALID_ACK = "INVALID_ACK";

    public static final String MESSAGE_TOO_LARGE = "MESSAGE_TOO_LARGE";

    public static final String SERVER_BUSY = "SERVER_BUSY";

    public static final String SERVER_FULL = "SERVER_FULL";
}