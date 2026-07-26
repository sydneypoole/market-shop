package com.marketshop.application.notification;

import org.springframework.stereotype.Service;

@Service
public class NotificationApplicationService implements NotificationUseCase {

    private final NotificationPort port;

    public NotificationApplicationService(NotificationPort port) {
        this.port = port;
    }

    @Override
    public NotificationPage userNotifications(long userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        return port.userNotifications(userId, safePage, safeSize);
    }

    @Override
    public long userUnreadCount(long userId) {
        return port.userUnreadCount(userId);
    }

    @Override
    public void markUserRead(long userId, long notificationId) {
        port.markUserRead(userId, notificationId);
    }
}
