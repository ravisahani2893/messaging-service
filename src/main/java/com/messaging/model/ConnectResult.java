package com.messaging.model;

/** Outcome of a REGISTER: the session on success, otherwise why it was refused. */
public record ConnectResult(Status status, ClientSession session) {

    public enum Status {
        CONNECTED,
        DUPLICATE_CLIENT,   // that ID already has a live connection
        SERVER_FULL         // too many distinct clients are registered
    }

    public static ConnectResult connected(ClientSession session) {
        return new ConnectResult(Status.CONNECTED, session);
    }

    public static ConnectResult duplicateClient() {
        return new ConnectResult(Status.DUPLICATE_CLIENT, null);
    }

    public static ConnectResult serverFull() {
        return new ConnectResult(Status.SERVER_FULL, null);
    }
}
