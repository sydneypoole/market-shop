package com.marketshop.interfaces.security;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.config.SaCookieConfig;
import cn.dev33.satoken.stp.StpLogic;

public final class StpUserKit {

    public static final String TYPE = "user";
    private static final StpLogic LOGIC = new StpLogic(TYPE).setConfig(
            new SaTokenConfig()
                    .setTokenName("market-shop-user-token")
                    .setTimeout(2_592_000)
                    .setActiveTimeout(7_200)
                    .setIsConcurrent(false)
                    .setIsShare(false)
                    .setIsReadBody(false)
                    .setIsReadHeader(true)
                    .setIsReadCookie(true)
                    .setIsWriteHeader(false)
                    .setCookie(cookie(false))
                    .setTokenStyle("tik")
    );

    private StpUserKit() {
    }

    public static StpLogic logic() {
        return LOGIC;
    }

    static void configureCookie(boolean secure) {
        LOGIC.getConfig().setCookie(cookie(secure));
    }

    private static SaCookieConfig cookie(boolean secure) {
        return new SaCookieConfig()
                .setPath("/")
                .setHttpOnly(true)
                .setSecure(secure)
                .setSameSite("Lax");
    }
}
