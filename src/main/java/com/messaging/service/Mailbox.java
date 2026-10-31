package com.messaging.service;

import com.messaging.model.Message;
import com.messaging.model.MessageKey;

import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class Mailbox {

    private final BlockingQueue<Message> messages;

    private final HashMap<MessageKey, Message> messagesById =
            new HashMap<>();

    public Mailbox(int capacity) {
        this.messages = new ArrayBlockingQueue<>(capacity);
    }

    public synchronized boolean storeMessage(Message message) {

        MessageKey key = new MessageKey(
                message.senderId(),
                message.messageId()
        );

        // 1. Check for duplicate message
        if (messagesById.containsKey(key)) {
            return false;
        }

        // 2. Try adding the message to the queue
        boolean added = messages.offer(message);

        // 3. Check whether the mailbox is full
        if (!added) {
            return false;
        }

        // 4. Store the message in our duplicate-check index
        messagesById.put(key, message);

        return true;
    }

    public synchronized Message getNextMessage() {
        return messages.peek();
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
        messages.poll();

        // Remove its duplicate-check entry
        MessageKey key = new MessageKey(
                message.senderId(),
                message.messageId()
        );

        messagesById.remove(key);

        return true;
    }
}