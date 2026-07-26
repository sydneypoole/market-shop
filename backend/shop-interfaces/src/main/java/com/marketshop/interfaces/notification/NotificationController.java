package com.marketshop.interfaces.notification;

import com.marketshop.application.notification.NotificationUseCase;
import com.marketshop.interfaces.security.StpUserKit;
import com.marketshop.interfaces.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationUseCase notifications;

    public NotificationController(NotificationUseCase notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public ApiResponse<NotificationUseCase.NotificationPage> notifications(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(notifications.userNotifications(
                StpUserKit.logic().getLoginIdAsLong(), page, size
        ));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(notifications.userUnreadCount(StpUserKit.logic().getLoginIdAsLong()));
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Void> read(@PathVariable long notificationId) {
        notifications.markUserRead(StpUserKit.logic().getLoginIdAsLong(), notificationId);
        return ApiResponse.ok(null);
    }
}
