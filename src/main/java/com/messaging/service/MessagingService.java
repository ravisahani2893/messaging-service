package com.messaging.service;

import com.messaging.constant.MessageErrorCode;
import com.messaging.model.Message;
import com.messaging.model.SendResult;
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

    public SendResult sendMessage(
            String senderId,
            String recipientId,
            String messageId,
            String payload) {

        // 1. Validate sender
        if (!clientRegistry.isRegistered(senderId)) {
            return new SendResult(
                    false,
                    MessageErrorCode.SENDER_NOT_REGISTERED
            );
        }

        // 2. Validate recipient
        if (recipientId == null || recipientId.isBlank()) {
            return new SendResult(
                    false,
                    MessageErrorCode.INVALID_RECIPIENT
            );
        }

        if (clientRegistry.getClient(recipientId) == null) {
            return new SendResult(
                    false,
                    MessageErrorCode.INVALID_RECIPIENT
            );
        }

        // 3. Validate message details
        if (messageId == null || messageId.isBlank()
                || payload == null || payload.isBlank()) {
            return new SendResult(
                    false,
                    MessageErrorCode.INVALID_MESSAGE
            );
        }

        // 4. Create message
        Message message = new Message(
                messageId,
                senderId,
                recipientId,
                payload
        );

        // 5. Store in recipient's mailbox
        boolean stored = mailboxService.storeMessage(message);

        if (!stored) {
            return new SendResult(
                    false,
                    MessageErrorCode.MAILBOX_FULL
            );
        }

        return new SendResult(true, null);
    }
}