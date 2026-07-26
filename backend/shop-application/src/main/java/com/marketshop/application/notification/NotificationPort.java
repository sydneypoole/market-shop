package com.marketshop.application.notification;

import com.marketshop.application.notification.NotificationUseCase.NotificationPage;

public interface NotificationPort {

    NotificationPage userNotifications(long userId, int page, int size);

    long userUnreadCount(long userId);

    void markUserRead(long userId, long notificationId);
}
