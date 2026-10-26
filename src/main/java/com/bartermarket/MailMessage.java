package com.bartermarket;

import java.util.UUID;

public record MailMessage(
        String id,
        UUID senderUuid,
        String senderName,
        UUID recipientUuid,
        String recipientName,
        String body,
        long createdAt,
        Long readAt) {
    public boolean isRead() {
        return readAt != null;
    }
}
