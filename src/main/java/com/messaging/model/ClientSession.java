package com.messaging.model;

import java.net.Socket;

public record ClientSession(
        String clientId,
        Socket socket
) {
}