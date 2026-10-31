package com.messaging.model;

import java.net.Socket;


public class ClientSession {

    private final String clientId;
    private volatile Socket socket;

    public ClientSession(String clientId, Socket socket) {
        this.clientId = clientId;
        this.socket = socket;
    }

    public String getClientId() {
        return clientId;
    }

    public Socket getSocket() {
        return socket;
    }

    public boolean isOnline() {
        return socket != null;
    }

    public synchronized boolean reconnect(Socket newSocket) {
        if (this.socket != null) {
            return false;
        }

        this.socket = newSocket;
        return true;
    }

    public synchronized boolean disconnect(Socket expectedSocket) {
        if (this.socket != expectedSocket) {
            return false;
        }

        this.socket = null;
        return true;
    }
}