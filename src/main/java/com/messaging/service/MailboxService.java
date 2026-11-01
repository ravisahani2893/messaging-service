package com.messaging.service;

import com.messaging.config.MessagingProperties;
import com.messaging.constant.StoreResult;
import com.messaging.model.Message;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class MailboxService {

    private final ConcurrentMap<String, Mailbox> mailboxes =
            new ConcurrentHashMap<>();

    private final MessagingProperties properties;

    public MailboxService(MessagingProperties properties) {
        this.properties = properties;
    }

    public StoreResult storeMessage(Message message) {

        Mailbox mailbox = mailboxes.computeIfAbsent(
                message.recipientId(),
                id -> new Mailbox(properties.getMailboxCapacity())
        );

        return mailbox.storeMessage(message);
    }

    public Message claimNextMessage(String recipientId) {
        Mailbox mailbox = mailboxes.get(recipientId);

        if (mailbox == null) {
            return null;
        }

        return mailbox.claimNextForDelivery();
    }

    public void resetInFlight(String recipientId) {
        Mailbox mailbox = mailboxes.get(recipientId);

        if (mailbox != null) {
            mailbox.resetInFlight();
        }
    }

    public boolean acknowledgeMessage(String recipientId, String messageId) {
        Mailbox mailbox = mailboxes.get(recipientId);

        if (mailbox == null) {
            return false;
        }

        return mailbox.acknowledgeMessage(messageId);
    }
}