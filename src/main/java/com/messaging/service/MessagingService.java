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
        if (senderId == null || senderId.isBlank()) {
            return new SendResult(
                    false,
                    MessageErrorCode.SENDER_NOT_REGISTERED
            );
        }

        // 2. Validate sender is online
        if (!clientRegistry.isRegistered(senderId)) {
            return new SendResult(
                    false,
                    MessageErrorCode.SENDER_NOT_REGISTERED
            );
        }

        // 3. Validate recipient ID
        if (recipientId == null || recipientId.isBlank()) {
            return new SendResult(
                    false,
                    MessageErrorCode.INVALID_RECIPIENT
            );
        }

        // 4. Check recipient exists (online OR offline)
        if (!clientRegistry.exists(recipientId)) {
            return new SendResult(
                    false,
                    MessageErrorCode.INVALID_RECIPIENT
            );
        }

        // 5. Validate message details
        if (messageId == null || messageId.isBlank()
                || payload == null || payload.isBlank()) {
            return new SendResult(
                    false,
                    MessageErrorCode.INVALID_MESSAGE
            );
        }

        // 6. Create message
        Message message = new Message(
                messageId,
                senderId,
                recipientId,
                payload
        );

        // 7. Store message in recipient's mailbox
        boolean stored = mailboxService.storeMessage(message);

        if (!stored) {
            return new SendResult(
                    false,
                    MessageErrorCode.MAILBOX_FULL
            );
        }
        // 8. Message accepted by server
        return new SendResult(true, null);
    }


}