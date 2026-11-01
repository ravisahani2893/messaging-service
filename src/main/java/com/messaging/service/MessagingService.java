package com.messaging.service;

import com.messaging.config.MessagingProperties;
import com.messaging.constant.MessageErrorCode;
import com.messaging.constant.StoreResult;
import com.messaging.model.Message;
import com.messaging.model.SendResult;
import com.messaging.server.ClientRegistry;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class MessagingService {

    private final ClientRegistry clientRegistry;
    private final MailboxService mailboxService;
    private final MessagingProperties properties;

    private static final Pattern ID_PATTERN =
            Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public MessagingService(
            ClientRegistry clientRegistry,
            MailboxService mailboxService,
            MessagingProperties properties) {

        this.clientRegistry = clientRegistry;
        this.mailboxService = mailboxService;
        this.properties = properties;
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
        if (isValidId(messageId)
                || payload == null || payload.isBlank()) {
            return new SendResult(
                    false,
                    MessageErrorCode.INVALID_MESSAGE
            );
        }

        if (payload.length() > properties.getMaxMessageSize()) {
            return new SendResult(
                    false,
                    MessageErrorCode.MESSAGE_TOO_LARGE
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
        StoreResult result = mailboxService.storeMessage(message);

        // 8. Handle storage result
        if (result == StoreResult.DUPLICATE) {
            return new SendResult(
                    false,
                    MessageErrorCode.DUPLICATE_MESSAGE
            );
        }

        if (result == StoreResult.FULL) {
            return new SendResult(
                    false,
                    MessageErrorCode.MAILBOX_FULL
            );
        }

        // 9. Message accepted by server
        return new SendResult(true, null);
    }

    public Message claimNextMessage(String recipientId) {
        return mailboxService.claimNextMessage(recipientId);
    }

    public void resetDelivery(String recipientId) {
        mailboxService.resetInFlight(recipientId);
    }

    /** IDs: 1-64 chars of letters, digits, '.', '_' or '-'. */
    public static boolean isValidId(String id) {
        return id == null || !ID_PATTERN.matcher(id).matches();
    }

    public boolean acknowledgeMessage(String recipientId, String messageId) {
        return mailboxService.acknowledgeMessage(recipientId, messageId);
    }


}