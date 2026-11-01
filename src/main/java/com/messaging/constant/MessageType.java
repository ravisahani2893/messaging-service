package com.messaging.constant;

/** The "type" field values of the wire protocol. */
public final class MessageType {

    private MessageType() {
    }

    // Client to server
    public static final String REGISTER = "REGISTER";
    public static final String SEND = "SEND";
    public static final String ACK = "ACK";

    // Server to client
    public static final String REGISTERED = "REGISTERED";
    public static final String SEND_ACCEPTED = "SEND_ACCEPTED";
    public static final String DELIVER = "DELIVER";
    public static final String ACK_ACCEPTED = "ACK_ACCEPTED";
    public static final String ERROR = "ERROR";
}
