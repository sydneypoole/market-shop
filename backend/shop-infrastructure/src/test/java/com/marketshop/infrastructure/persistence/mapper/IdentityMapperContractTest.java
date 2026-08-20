package com.marketshop.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
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
    void invitationConsumptionResolvesOwnerBeforeLockingTheInviterRoot() throws Exception {
        Select owner = IdentityMapper.class
                .getMethod("findInvitationOwner", String.class)
                .getAnnotation(Select.class);
        Select eligibility = IdentityMapper.class
                .getMethod("lockInviterEligibility", long.class)
                .getAnnotation(Select.class);
        Select invitation = IdentityMapper.class
                .getMethod("lockInvitation", String.class)
                .getAnnotation(Select.class);
        String ownerSql = String.join("\n", owner.value()).toLowerCase();
        String eligibilitySql = String.join("\n", eligibility.value()).toLowerCase();
        String invitationSql = String.join("\n", invitation.value()).toLowerCase();

        assertThat(ownerSql)
                .contains("customer_invitation_code", "inviter_user_id")
                .doesNotContain("for update", "join iam_user_account");
        assertThat(eligibilitySql)
                .contains("iam_user_account", "membership_account", "membership_level")
                .contains("user_status", "level_status", "invitation_enabled", "for update");
        assertThat(eligibilitySql.indexOf("from iam_user_account"))
                .isLessThan(eligibilitySql.indexOf("join membership_account"));
        assertThat(invitationSql)
                .contains("customer_invitation_code", "inviter_user_id", "for update")
                .doesNotContain("join iam_user_account", "join membership_account", "join membership_level");
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
