package com.marketshop.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityMapperContractTest {

    @Test
    void initialMemberInsertPersistsIdentityWithoutPhoneProfileFields() throws Exception {
        Insert insert = IdentityMapper.class
                .getMethod("insertUser", com.marketshop.infrastructure.persistence.model
                        .IdentityPersistenceModels.UserAccountPo.class)
                .getAnnotation(Insert.class);
        String sql = normalizedSql(insert.value());

        assertThat(sql)
                .contains("public_id", "nickname", "avatar_url")
                .doesNotContain("phone_masked", "phone_verified_at", "phone_number", "pure_phone");
    }

    @Test
    void nicknameUpdateIsAColumnLevelVersionedCompareAndSet() throws Exception {
        Update update = IdentityMapper.class
                .getMethod("updateMemberNickname", long.class, int.class, String.class)
                .getAnnotation(Update.class);
        String sql = normalizedSql(update.value());

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
        String ownerSql = normalizedSql(owner.value());
        String eligibilitySql = normalizedSql(eligibility.value());
        String invitationSql = normalizedSql(invitation.value());

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
    void bootstrapInvitationInsertIsMarkedSingleUseAndReturnsItsId() throws Exception {
        Insert insert = IdentityMapper.class
                .getMethod("insertBootstrapInvitation",
                        com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationPo.class)
                .getAnnotation(Insert.class);
        String sql = normalizedSql(insert.value());

        assertThat(sql)
                .contains("max_uses", "is_bootstrap")
                .contains("'active', 1, 1");
        assertThat(IdentityMapper.class
                .getMethod("insertBootstrapInvitation",
                        com.marketshop.infrastructure.persistence.model.IdentityPersistenceModels.InvitationPo.class)
                .getAnnotation(org.apache.ibatis.annotations.Options.class).keyProperty())
                .isEqualTo("id");
    }

    @Test
    void bootstrapClaimInsertLinksTheExactInvitationAndClearsRepairFlag() throws Exception {
        Insert insert = IdentityMapper.class
                .getMethod("insertBootstrapSponsorClaim", long.class, long.class, String.class)
                .getAnnotation(Insert.class);
        String sql = normalizedSql(insert.value());

        assertThat(sql)
                .contains("bootstrap_invitation_id", "invitation_repair_required")
                .contains("#{invitationid}", "0");
    }

    @Test
    void bootstrapInvitationConsumptionIsConditionalAndTerminal() throws Exception {
        Update update = IdentityMapper.class
                .getMethod("consumeBootstrapInvitation", long.class)
                .getAnnotation(Update.class);
        String sql = normalizedSql(update.value());

        assertThat(sql)
                .contains("is_bootstrap = 1", "status = 'revoked'", "status = 'active'")
                .contains("use_count < max_uses")
                .contains("revoked_at = current_timestamp")
                .contains("version = version + 1");
    }

    @Test
    void bootstrapRepairLocksUnresolvedClaimsAndLinksOnlyAfterExplicitChecks() throws Exception {
        Select pending = IdentityMapper.class
                .getMethod("lockUnresolvedBootstrapInvitationRepairs")
                .getAnnotation(Select.class);
        Update markInvitation = IdentityMapper.class
                .getMethod("markBootstrapInvitation", long.class, long.class)
                .getAnnotation(Update.class);
        Update linkClaim = IdentityMapper.class
                .getMethod("linkBootstrapInvitation", long.class, long.class, int.class)
                .getAnnotation(Update.class);
        String pendingSql = normalizedSql(pending.value());
        String markSql = normalizedSql(markInvitation.value());
        String linkSql = normalizedSql(linkClaim.value());

        assertThat(pendingSql)
                .contains("invitation_repair_required = 1", "for update")
                .doesNotContain("customer_invitation_code");
        assertThat(markSql)
                .contains("is_bootstrap = 1", "max_uses = 1", "inviter_user_id = #{sponsoruserid}")
                .contains("status in ('active', 'revoked')");
        assertThat(linkSql)
                .contains("bootstrap_invitation_id = #{invitationid}", "invitation_repair_required = 0")
                .contains("bootstrap_invitation_id is null", "version = #{expectedversion}");
    }

    @Test
    void bootstrapRepairGuardIsSingletonAndReadinessIncludesBothGuardAndClaims() throws Exception {
        Select guard = IdentityMapper.class
                .getMethod("lockBootstrapInvitationRepairGuard")
                .getAnnotation(Select.class);
        Select readiness = IdentityMapper.class
                .getMethod("countUnresolvedBootstrapInvitationRepairs")
                .getAnnotation(Select.class);
        Update clear = IdentityMapper.class
                .getMethod("clearBootstrapInvitationRepairGuard", int.class)
                .getAnnotation(Update.class);
        String guardSql = normalizedSql(guard.value());
        String readinessSql = normalizedSql(readiness.value());
        String clearSql = normalizedSql(clear.value());

        assertThat(guardSql).contains("id = 1", "repair_required", "for update");
        assertThat(readinessSql)
                .contains("iam_bootstrap_invitation_repair_guard", "not exists")
                .contains("repair_required is null", "repair_required <> 0", "version is null")
                .contains("iam_bootstrap_sponsor_claim", "invitation_repair_required = 1");
        assertThat(clearSql)
                .contains("repair_required = 0", "version = version + 1", "version = #{expectedversion}");
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
            String sql = normalizedSql(insert.value());

            assertThat(sql)
                    .contains("iam_user_account sponsor")
                    .contains("商城发起人")
                    .contains("not exists")
                    .contains("iam_bootstrap_sponsor_claim");
            if ("ensureBootstrapSponsorClaim".equals(methodName)) {
                assertThat(sql).contains("invitation_repair_required", "1");
            }
        }
    }

    private static String normalizedSql(String... fragments) {
        return String.join(" ", fragments)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
