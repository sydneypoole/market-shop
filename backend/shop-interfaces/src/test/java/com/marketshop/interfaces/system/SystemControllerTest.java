package com.marketshop.interfaces.system;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemControllerTest {

    @Test
    void capabilitiesReflectRunnableEnvironmentFeatures() {
        SystemController production = new SystemController(false, true);
        SystemController local = new SystemController(true, false);

        assertThat(production.capabilities().data().devLoginEnabled()).isFalse();
        assertThat(production.capabilities().data().wechatLoginEnabled()).isTrue();
        assertThat(local.capabilities().data().devLoginEnabled()).isTrue();
        assertThat(local.capabilities().data().wechatLoginEnabled()).isFalse();
    }
}
