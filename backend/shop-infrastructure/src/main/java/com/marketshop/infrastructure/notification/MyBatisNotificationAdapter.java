package com.marketshop.infrastructure.notification;

import com.marketshop.application.notification.NotificationPort;
import com.marketshop.application.notification.NotificationUseCase.NotificationPage;
import com.marketshop.application.notification.NotificationUseCase.NotificationView;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.NotificationMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class MyBatisNotificationAdapter implements NotificationPort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);
    private final NotificationMapper mapper;

    public MyBatisNotificationAdapter(NotificationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public NotificationPage userNotifications(long userId, int page, int size) {
        int offset = Math.multiplyExact(page - 1, size);
        List<NotificationView> items = mapper.userNotifications(userId, offset, size).stream().map(row ->
                new NotificationView(
                        row.id, row.channel, row.templateCode, row.title, row.content,
                        row.businessType, row.businessId, row.status,
                        instant(row.readAt), instant(row.createdAt)
                )
        ).toList();
        return new NotificationPage(items, mapper.countUserNotifications(userId), page, size);
    }

    @Override
    public long userUnreadCount(long userId) {
        return mapper.countUserUnread(userId);
    }

    @Override
    public void markUserRead(long userId, long notificationId) {
        if (mapper.markUserRead(userId, notificationId) != 1) {
            throw new DomainException("NOTIFICATION_NOT_FOUND", "通知不存在");
        }
    }

    private static java.time.Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }
}
