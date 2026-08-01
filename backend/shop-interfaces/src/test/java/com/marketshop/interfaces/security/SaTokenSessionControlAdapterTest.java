package com.marketshop.interfaces.security;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.exception.NotLoginException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaTokenSessionControlAdapterTest {

    private SaTokenDao previousDao;

    @BeforeEach
    void useIsolatedSessionStore() {
        previousDao = SaManager.getSaTokenDao();
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
    }

    @AfterEach
    void restoreSessionStore() {
        SaTokenContextMockUtil.clearContext();
        SaManager.setSaTokenDao(previousDao);
    }

    @Test
    void invalidationMakesTheExistingMemberAndAdminTokensFailOnTheirNextCheck() {
        SaTokenSessionControlAdapter adapter = new SaTokenSessionControlAdapter();
        SaTokenContextMockUtil.setMockContext(() -> {
            StpUserKit.logic().login(42L);
            assertThat(StpUserKit.logic().isLogin()).isTrue();

            adapter.invalidateMemberSessions(42L);

            assertThatThrownBy(StpUserKit.logic()::checkLogin)
                    .isInstanceOf(NotLoginException.class);
        });
        SaTokenContextMockUtil.setMockContext(() -> {
            StpAdminKit.logic().login(7L);
            assertThat(StpAdminKit.logic().isLogin()).isTrue();

            adapter.invalidateAdminSessions(7L);

            assertThatThrownBy(StpAdminKit.logic()::checkLogin)
                    .isInstanceOf(NotLoginException.class);
        });
    }
}
