package com.messaging.service;

import com.messaging.constant.StoreResult;
import com.messaging.model.Message;
import com.messaging.model.MessageKey;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;


public class Mailbox {

    // All state below is guarded by this mailbox's monitor. A plain deque is
    // enough: a BlockingQueue's own locking would be redundant, and the lock
    // is per recipient so unrelated mailboxes never contend.
    private final Deque<Message> messages = new ArrayDeque<>();
    private final int capacity;

    private final HashMap<MessageKey, Message> messagesById =
            new HashMap<>();

    // true while the head message has been sent and is waiting for its ACK
    private boolean inFlight = false;

    public Mailbox(int capacity) {
        this.capacity = capacity;
    }

    public synchronized StoreResult storeMessage(Message message) {

        MessageKey key = new MessageKey(
                message.senderId(),
                message.messageId()
        );

        if (messagesById.containsKey(key)) {
            return StoreResult.DUPLICATE;
        }

        if (messages.size() >= capacity) {
            return StoreResult.FULL;
        }

        messages.addLast(message);

        messagesById.put(key, message);

        return StoreResult.STORED;
    }

    /**
     * Returns the head message only if it has not already been sent on the
     * current connection, and marks it in flight. Returns null otherwise, so
     * the same unacked message is not delivered again until the client
     * reconnects.
     */
    public synchronized Message claimNextForDelivery() {

        if (inFlight) {
            return null;
        }

        Message head = messages.peek();

        if (head != null) {
            inFlight = true;
        }

        return head;
    }

    /** Called when the recipient connects or disconnects: the head must be sent again. */
    public synchronized void resetInFlight() {
        inFlight = false;
    }

    public synchronized boolean acknowledgeMessage(String messageId) {

        Message message = messages.peek();

        // No pending message
        if (message == null) {
            return false;
        }

        // Only acknowledge the message at the front
        if (!message.messageId().equals(messageId)) {
            return false;
        }

        // Remove the acknowledged message
        messages.pollFirst();
        inFlight = false;

        // Remove its duplicate-check entry
        MessageKey key = new MessageKey(
                message.senderId(),
                message.messageId()
        );

        messagesById.remove(key);

        return true;
    }


}
