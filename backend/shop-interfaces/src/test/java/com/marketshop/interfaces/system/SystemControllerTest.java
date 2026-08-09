package com.marketshop.interfaces.system;

import com.marketshop.application.proof.OrderProofUseCase;
import com.marketshop.application.proof.OrderProofUseCase.UploadLimits;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class SystemControllerTest {

    @Test
    void aboutUsesThePublicPlatformBrand() {
        SystemController controller = new SystemController(false, true, proofLimits(3, 8_388_608));

        assertThat(controller.about().data())
                .containsEntry("name", "宏杉生物")
                .containsEntry("onlinePaymentEnabled", false)
                .containsEntry("rewardDepth", 1);
    }

    @Test
    void capabilitiesReflectRunnableEnvironmentFeatures() {
        OrderProofUseCase proofs = proofLimits(4, 6_291_456);
        SystemController production = new SystemController(false, true, proofs);
        SystemController local = new SystemController(true, false, proofs);

        assertThat(production.capabilities().data().devLoginEnabled()).isFalse();
        assertThat(production.capabilities().data().wechatLoginEnabled()).isTrue();
        assertThat(production.capabilities().data().maxProofFiles()).isEqualTo(4);
        assertThat(production.capabilities().data().maxProofSizeBytes()).isEqualTo(6_291_456);
        assertThat(local.capabilities().data().devLoginEnabled()).isTrue();
        assertThat(local.capabilities().data().wechatLoginEnabled()).isFalse();
    }

    private static OrderProofUseCase proofLimits(int maxProofFiles, long maxProofSizeBytes) {
        return (OrderProofUseCase) Proxy.newProxyInstance(
                OrderProofUseCase.class.getClassLoader(),
                new Class<?>[]{OrderProofUseCase.class},
                (proxy, method, arguments) -> {
                    if ("uploadLimits".equals(method.getName())) {
                        return new UploadLimits(maxProofFiles, maxProofSizeBytes);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}
