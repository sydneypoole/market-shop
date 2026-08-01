package com.marketshop.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityMapperContractTest {

    @Test
    void bootstrapRepairCannotAttachMockIdentityToAnArbitraryInvitationOwner() throws Exception {
        for (String methodName : new String[]{
                "repairBootstrapSponsorExternalIdentity",
                "repairBootstrapSponsorUnionPrincipal",
                "ensureBootstrapSponsorClaim"
        }) {
            Class<?>[] parameterTypes = "ensureBootstrapSponsorClaim".equals(methodName)
                    ? new Class<?>[]{String.class, String.class}
                    : new Class<?>[]{String.class};
            Insert insert = IdentityMapper.class
                    .getMethod(methodName, parameterTypes)
                    .getAnnotation(Insert.class);
            String sql = String.join("\n", insert.value());

            assertThat(sql)
                    .contains("iam_user_account sponsor")
                    .contains("商城发起人")
                    .contains("NOT EXISTS")
                    .contains("iam_bootstrap_sponsor_claim");
        }
    }
}
