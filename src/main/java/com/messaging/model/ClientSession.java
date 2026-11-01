package com.messaging.model;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * One logical client. It outlives individual TCP connections: the same
 * session object is reattached each time the client reconnects.
 *
 * Threading model:
 *  - attach/disconnect are synchronized, so connection state changes are atomic
 *    (two connections can never both own the session).
 *  - socket and outbound are volatile, so isOnline()/send() can read them
 *    without taking the lock.
 *  - send() never touches the socket. It only offers to a bounded queue, and a
 *    single writer thread per connection drains it. Callers (other clients'
 *    handler threads) therefore never block on a slow recipient, and only one
 *    thread ever writes to a socket, so output lines cannot interleave.
 *  - If the queue is full the recipient is too slow: its connection is closed.
 *    Its messages stay safe in the mailbox and are redelivered on reconnect.
 */
public class ClientSession {

    private final String clientId;
    private final int outboundCapacity;

    // Put on the queue to tell the writer thread: send everything before this, then stop
    private static final String STOP = new String("<stop>");

    private volatile Socket socket;
    private volatile BlockingQueue<String> outbound;
    private Thread writerThread; // guarded by this

    public ClientSession(String clientId, int outboundCapacity) {
        this.clientId = clientId;
        this.outboundCapacity = outboundCapacity;
    }

    public String getClientId() {
        return clientId;
    }

    public boolean isOnline() {
        return socket != null;
    }

    /** Attaches a new connection. Returns false if one is already attached. */
    public synchronized boolean attach(Socket newSocket, BufferedWriter newWriter) {

        if (this.socket != null) {
            return false;
        }

        BlockingQueue<String> queue = new LinkedBlockingQueue<>(outboundCapacity);

        this.outbound = queue;
        this.socket = newSocket;
        this.writerThread = Thread.ofVirtual()
                .name("writer-" + clientId)
                .start(() -> drain(queue, newSocket, newWriter));

        return true;
    }

    /** Detaches the connection, but only if it is still the current one. */
    public synchronized boolean disconnect(Socket expectedSocket) {

        if (this.socket != expectedSocket) {
            return false;
        }

        this.socket = null;
        this.outbound = null;

        if (writerThread != null) {
            writerThread.interrupt();
            writerThread = null;
        }

        return true;
    }

    /** Non-blocking: queues a line for the writer thread. */
    public void send(String json) throws IOException {

        BlockingQueue<String> queue = outbound;
        Socket current = socket;

        if (queue == null || current == null) {
            throw new IOException("Client is offline");
        }

        if (!queue.offer(json)) {
            // Slow consumer: drop the connection rather than block or buffer forever
            closeQuietly(current);
            throw new IOException("Outbound queue full, closing slow client");
        }
    }

    /**
     * Waits (up to timeoutMillis) until every line already queued has been
     * written to the socket, and stops the writer thread. Used before closing
     * a connection on purpose, so a final error reply is not lost.
     */
    public void flushAndStopWriter(long timeoutMillis) {

        Thread writer;
        BlockingQueue<String> queue;

        synchronized (this) {
            writer = writerThread;
            queue = outbound;
        }

        if (writer == null || queue == null || !queue.offer(STOP)) {
            return;
        }

        try {
            writer.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain(BlockingQueue<String> queue, Socket conn, BufferedWriter writer) {
        try {
            while (true) {
                String json = queue.take();
                if (json == STOP) { // compared by identity: only our own marker matches
                    return;
                }
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
        } catch (InterruptedException | IOException e) {
            // Interrupted = disconnected normally; IOException = write failed.
            // Closing the socket unblocks the reader thread so it cleans up.
            closeQuietly(conn);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
