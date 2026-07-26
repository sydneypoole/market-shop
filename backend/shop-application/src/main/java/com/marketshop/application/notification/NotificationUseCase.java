package com.marketshop.application.notification;

import java.time.Instant;
import java.util.List;

public interface NotificationUseCase {

    NotificationPage userNotifications(long userId, int page, int size);

    long userUnreadCount(long userId);

    void markUserRead(long userId, long notificationId);

    record NotificationPage(List<NotificationView> items, long total, int page, int size) {
    }

    record NotificationView(long id, String channel, String templateCode, String title, String content,
                            String businessType, String businessId, String status,
                            Instant readAt, Instant createdAt) {
    }
}
