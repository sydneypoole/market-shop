package com.marketshop.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityMapperContractTest {

    @Test
    void initialMemberInsertPersistsIdentityWithoutPhoneProfileFields() throws Exception {
        Insert insert = IdentityMapper.class
                .getMethod("insertUser", com.marketshop.infrastructure.persistence.model
                        .IdentityPersistenceModels.UserAccountPo.class)
                .getAnnotation(Insert.class);
        String sql = String.join("\n", insert.value()).toLowerCase();

        assertThat(sql)
                .contains("public_id", "nickname", "avatar_url")
                .doesNotContain("phone_masked", "phone_verified_at", "phone_number", "pure_phone");
    }

    @Test
    void nicknameUpdateIsAColumnLevelVersionedCompareAndSet() throws Exception {
        Update update = IdentityMapper.class
                .getMethod("updateMemberNickname", long.class, int.class, String.class)
                .getAnnotation(Update.class);
        String sql = String.join("\n", update.value()).toLowerCase();

        assertThat(sql)
                .contains("set nickname = #{nickname}")
                .contains("version = version + 1")
                .contains("where id = #{userid} and version = #{expectedversion}")
                .doesNotContain("phone_masked", "phone_verified_at", "avatar_");
    }

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
