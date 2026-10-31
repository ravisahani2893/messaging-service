package com.messaging.service;

import com.messaging.config.MessagingProperties;
import com.messaging.model.Message;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

@Service
public class MailboxService {

    private final ConcurrentMap<String, BlockingQueue<Message>> mailboxes =
            new ConcurrentHashMap<>();

    private final MessagingProperties properties;

    public MailboxService(MessagingProperties properties) {
        this.properties = properties;
    }

    public boolean storeMessage(Message message) {

        BlockingQueue<Message> mailbox =mailboxes
                .computeIfAbsent(
                        message.recipientId(),
                        id -> new ArrayBlockingQueue<>(
                                properties.getMailboxCapacity()
                        )
                );
        return mailbox.offer(message);
    }

    public Message getMessage(String recipientId) {
        BlockingQueue<Message> mailbox =mailboxes.get(recipientId);
        if (mailbox != null) {
            return mailbox.peek();
        }
        return null;
    }

    public boolean acknowledgeMessage(String recipientId, String messageId) {
        BlockingQueue<Message> mailbox = mailboxes.get(recipientId);

        if (mailbox == null) {
            return false;
        }

        return mailbox.removeIf(
                message -> message.messageId().equals(messageId)
        );
    }
}