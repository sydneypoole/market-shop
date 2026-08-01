package com.marketshop.interfaces.security;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.config.SaCookieConfig;
import cn.dev33.satoken.stp.StpLogic;
import com.marketshop.domain.shared.DomainException;

import java.util.Set;

public final class StpAdminKit {

    public static final String TYPE = "admin";
    private static final StpLogic LOGIC = new StpLogic(TYPE).setConfig(
            new SaTokenConfig()
                    .setTokenName("market-shop-admin-token")
                    .setTimeout(28_800)
                    .setActiveTimeout(1_800)
                    .setIsConcurrent(false)
                    .setIsShare(false)
                    .setIsReadBody(false)
                    .setIsReadHeader(false)
                    .setIsReadCookie(true)
                    .setIsWriteHeader(false)
                    .setCookie(cookie(false))
                    .setTokenStyle("tik")
    );

    private StpAdminKit() {
    }

    public static StpLogic logic() {
        return LOGIC;
    }

    static void configureCookie(boolean secure) {
        LOGIC.getConfig().setCookie(cookie(secure));
    }

    public static void requirePermission(String permission) {
        LOGIC.checkLogin();
        if (Boolean.TRUE.equals(LOGIC.getTokenSession().get("mustChangePassword"))) {
            throw new DomainException("ADMIN_PASSWORD_CHANGE_REQUIRED", "首次登录必须先修改临时密码");
        }
        Object stored = LOGIC.getTokenSession().get("permissions");
        if (!(stored instanceof Set<?> permissions) || !permissions.contains(permission)) {
            throw new DomainException("ADMIN_PERMISSION_DENIED", "当前后台账号无此操作权限");
        }
    }

    public static void requireAnyPermission(String... required) {
        LOGIC.checkLogin();
        if (Boolean.TRUE.equals(LOGIC.getTokenSession().get("mustChangePassword"))) {
            throw new DomainException("ADMIN_PASSWORD_CHANGE_REQUIRED", "首次登录必须先修改临时密码");
        }
        Object stored = LOGIC.getTokenSession().get("permissions");
        if (!(stored instanceof Set<?> permissions)) {
            throw new DomainException("ADMIN_PERMISSION_DENIED", "当前后台账号无此操作权限");
        }
        for (String permission : required) {
            if (permissions.contains(permission)) {
                return;
            }
        }
        throw new DomainException("ADMIN_PERMISSION_DENIED", "当前后台账号无此操作权限");
    }

    private static SaCookieConfig cookie(boolean secure) {
        return new SaCookieConfig()
                .setPath("/")
                .setHttpOnly(true)
                .setSecure(secure)
                .setSameSite("Lax");
    }
}
