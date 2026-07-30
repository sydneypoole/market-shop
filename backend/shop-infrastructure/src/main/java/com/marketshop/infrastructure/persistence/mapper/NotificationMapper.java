package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.NotificationPersistenceModels.NotificationRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface NotificationMapper {

    @Insert("""
            INSERT IGNORE INTO operation_notification
                (recipient_type, recipient_id, channel, template_code, title, content,
                 business_type, business_id, status, idempotency_key)
            VALUES
                ('USER', #{userId}, 'IN_APP', #{templateCode}, #{title}, #{content},
                 #{businessType}, #{businessId}, 'UNREAD', #{idempotencyKey})
            """)
    int insertUser(@Param("userId") long userId,
                   @Param("templateCode") String templateCode,
                   @Param("title") String title,
                   @Param("content") String content,
                   @Param("businessType") String businessType,
                   @Param("businessId") String businessId,
                   @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT id, channel, template_code, title, content, business_type, business_id,
                   status, read_at, created_at
            FROM operation_notification
            WHERE recipient_type = 'USER' AND recipient_id = #{userId}
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<NotificationRow> userNotifications(@Param("userId") long userId,
                                            @Param("offset") int offset,
                                            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM operation_notification
            WHERE recipient_type = 'USER' AND recipient_id = #{userId}
            """)
    long countUserNotifications(@Param("userId") long userId);

    @Select("""
            SELECT COUNT(*) FROM operation_notification
            WHERE recipient_type = 'USER' AND recipient_id = #{userId} AND status = 'UNREAD'
            """)
    long countUserUnread(@Param("userId") long userId);

    @Update("""
            UPDATE operation_notification
            SET status = 'READ', read_at = COALESCE(read_at, CURRENT_TIMESTAMP(3))
            WHERE id = #{notificationId} AND recipient_type = 'USER'
              AND recipient_id = #{userId}
            """)
    int markUserRead(@Param("userId") long userId, @Param("notificationId") long notificationId);

    @Select("""
            SELECT COUNT(*)
            FROM operation_notification
            WHERE id = #{notificationId}
              AND recipient_type = 'USER'
              AND recipient_id = #{userId}
            """)
    int userNotificationExists(@Param("userId") long userId,
                               @Param("notificationId") long notificationId);
}
