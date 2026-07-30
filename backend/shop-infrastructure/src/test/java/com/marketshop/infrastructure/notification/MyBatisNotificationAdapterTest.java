package com.marketshop.infrastructure.notification;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.NotificationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyBatisNotificationAdapterTest {

    @Mock
    private NotificationMapper mapper;

    @InjectMocks
    private MyBatisNotificationAdapter adapter;

    @Test
    void repeatedReadOfOwnedNotificationIsIdempotent() {
        when(mapper.markUserRead(42, 9)).thenReturn(0);
        when(mapper.userNotificationExists(42, 9)).thenReturn(1);

        assertThatCode(() -> adapter.markUserRead(42, 9)).doesNotThrowAnyException();

        verify(mapper).userNotificationExists(42, 9);
    }

    @Test
    void anotherUsersNotificationRemainsInvisible() {
        when(mapper.markUserRead(42, 9)).thenReturn(0);
        when(mapper.userNotificationExists(42, 9)).thenReturn(0);

        assertThatThrownBy(() -> adapter.markUserRead(42, 9))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("通知不存在");
    }
}
