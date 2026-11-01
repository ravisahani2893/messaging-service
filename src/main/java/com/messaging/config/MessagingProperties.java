package com.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "messaging.tcp")
public class MessagingProperties {

    private int port;
    private int maxConnections;
    private int mailboxCapacity;
    private int maxMessageSize;
    // Max distinct client IDs ever registered. Clients are never removed, so this bounds the registry.
    private int maxClients = 1000;

    public int getMaxClients() {
        return maxClients;
    }

    public void setMaxClients(int maxClients) {
        this.maxClients = maxClients;
    }

    // A connection that sends nothing for this long is closed (default 5 minutes)
    private int idleTimeoutMillis = 300_000;

    public int getIdleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    public void setIdleTimeoutMillis(int idleTimeoutMillis) {
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    // Max lines queued for one client's writer thread before it is dropped as too slow
    private int outboundQueueCapacity = 100;

    public int getOutboundQueueCapacity() {
        return outboundQueueCapacity;
    }

    public void setOutboundQueueCapacity(int outboundQueueCapacity) {
        this.outboundQueueCapacity = outboundQueueCapacity;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMailboxCapacity() {
        return mailboxCapacity;
    }

    public void setMailboxCapacity(int mailboxCapacity) {
        this.mailboxCapacity = mailboxCapacity;
    }

    public int getMaxMessageSize() {
        return maxMessageSize;
    }

    public void setMaxMessageSize(int maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }
}
