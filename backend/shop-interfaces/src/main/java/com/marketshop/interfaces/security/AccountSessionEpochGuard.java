package com.marketshop.interfaces.security;

import cn.dev33.satoken.stp.StpLogic;
import com.marketshop.application.identity.IdentityPorts.AccountAuthState;
import com.marketshop.application.identity.IdentityPorts.AccountAuthStatePort;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public final class AccountSessionEpochGuard {

    private final AccountAuthStatePort states;

    public AccountSessionEpochGuard(AccountAuthStatePort states) {
        this.states = states;
    }

    public void requireMemberSession() {
        StpLogic logic = StpUserKit.logic();
        logic.checkLogin();
        long userId = logic.getLoginIdAsLong();
        AccountAuthState state = states.memberState(userId).orElse(null);
        if (state == null
                || !"ACTIVE".equals(state.status())
                || sessionEpoch(logic) != state.authEpoch()) {
            rejectCurrentSession(logic);
        }
    }

    public void requireAdminSession() {
        StpLogic logic = StpAdminKit.logic();
        logic.checkLogin();
        long adminId = logic.getLoginIdAsLong();
        AccountAuthState state = states.adminState(adminId).orElse(null);
        if (state == null
                || !"ACTIVE".equals(state.status())
                || state.lockedUntil() != null && state.lockedUntil().isAfter(Instant.now())
                || sessionEpoch(logic) != state.authEpoch()) {
            rejectCurrentSession(logic);
        }
    }

    private static long sessionEpoch(StpLogic logic) {
        Object stored = logic.getTokenSession().get("authEpoch");
        if (stored instanceof Number number) {
            return number.longValue();
        }
        if (stored instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return Long.MIN_VALUE;
            }
        }
        return Long.MIN_VALUE;
    }

    private static void rejectCurrentSession(StpLogic logic) {
        logic.logout();
        logic.checkLogin();
        throw new IllegalStateException("Sa-Token did not reject a logged-out session");
    }
}
