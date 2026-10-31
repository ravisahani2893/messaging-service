package com.messaging.service;

import com.messaging.model.Message;
import com.messaging.server.ClientRegistry;
import org.springframework.stereotype.Service;

@Service
public class MessagingService {

    private final ClientRegistry clientRegistry;
    private final MailboxService mailboxService;

    public MessagingService(
            ClientRegistry clientRegistry,
            MailboxService mailboxService) {

        this.clientRegistry = clientRegistry;
        this.mailboxService = mailboxService;
    }

    public boolean sendMessage(
            String senderId,
            String recipientId,
            String messageId,
            String payload) {

        // 1. Validate sender
        if (!clientRegistry.isRegistered(senderId)) {
            return false;
        }

        // 2. Validate recipient
        if (recipientId == null || recipientId.isBlank()) {
            return false;
        }

        // 3. Validate message details
        if (messageId == null || messageId.isBlank()
                || payload == null || payload.isBlank()) {
            return false;
        }

        // 4. Create message
        Message message = new Message(
                messageId,
                senderId,
                recipientId,
                payload
        );

        // 5. Store in recipient's mailbox
        return mailboxService.storeMessage(message);
    }
}