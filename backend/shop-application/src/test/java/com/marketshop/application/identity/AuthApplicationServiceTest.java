package com.marketshop.application.identity;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.identity.IdentityPorts.RegistrationResult;
import com.marketshop.application.identity.IdentityPorts.UserIdentityPort;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatMiniprogramPort;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthApplicationServiceTest {

    @Test
    void miniprogramLoginRegistersNewUserWithInviteCode() {
        CapturingIdentityPort identities = new CapturingIdentityPort();
        AuthApplicationService service = service(identities, new FakeAuditPort(), false);

        var result = service.miniprogramLogin(new AuthUseCase.MiniprogramLoginCommand(
                "code-1", "INVITE-1", null
        ));

        assertThat(result.userId()).isEqualTo(42);
        assertThat(result.newlyRegistered()).isTrue();
        assertThat(result.publicId()).isEqualTo("PUBLIC-ID");
        assertThat(identities.inviteCode).isEqualTo("INVITE-1");
        assertThat(identities.claimSecretHash).isNull();
        assertThat(identities.lastProvider).isEqualTo("WECHAT_MP");
        assertThat(identities.recordedLoginUserId).isEqualTo(42);
    }

    @Test
    void miniprogramLoginRequiresInviteCodeForNewIdentity() {
        CapturingIdentityPort identities = new CapturingIdentityPort();
        identities.requireInviteForNewIdentity = true;
        AuthApplicationService service = service(identities, new FakeAuditPort(), false);

        assertThatThrownBy(() -> service.miniprogramLogin(new AuthUseCase.MiniprogramLoginCommand(
                "code-1", null, null
        )))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .asString()
                .startsWith("INVITE_CODE");
        assertThat(identities.recordedLoginUserId).isNull();
    }

    @Test
    void sponsorClaimSecretIsHashedBeforeIdentityPortAndAuditContainsOnlySafeClaimMetadata() {
        String rawClaimSecret = "owner-only-claim-secret-2026-abcdef";
        CapturingIdentityPort identities = new CapturingIdentityPort();
        identities.sponsorClaimed = true;
        identities.newlyRegistered = false;
        FakeAuditPort audit = new FakeAuditPort();
        AuthApplicationService service = service(identities, audit, false);

        service.miniprogramLogin(new AuthUseCase.MiniprogramLoginCommand(
                "mp-code", null, rawClaimSecret
        ));

        assertThat(identities.claimSecretHash)
                .isEqualTo(SponsorClaimSecrets.sha256(rawClaimSecret))
                .doesNotContain(rawClaimSecret);
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.action()).isEqualTo("BOOTSTRAP_SPONSOR_CLAIMED");
            assertThat(record.beforeJson()).isEqualTo("{\"status\":\"PENDING\"}");
            assertThat(record.afterJson())
                    .contains("\"status\":\"CLAIMED\"")
                    .contains("WECHAT_MP")
                    .contains("mp-app-fixture");
            String serialized = String.join("|",
                    nullSafe(record.beforeJson()), nullSafe(record.afterJson()),
                    nullSafe(record.reason()), nullSafe(record.requestId()),
                    nullSafe(record.userAgentSummary()));
            assertThat(serialized)
                    .doesNotContain(rawClaimSecret)
                    .doesNotContain(SponsorClaimSecrets.sha256(rawClaimSecret))
                    .doesNotContain("openid-1")
                    .doesNotContain("union-1")
                    .doesNotContain("mp-code");
        });
    }

    @Test
    void rejectsAmbiguousInviteAndSponsorCredentials() {
        CapturingIdentityPort identities = new CapturingIdentityPort();
        AuthApplicationService service = service(identities, new FakeAuditPort(), false);

        assertThatThrownBy(() -> service.miniprogramLogin(new AuthUseCase.MiniprogramLoginCommand(
                "code", "INVITE", "owner-only-claim-secret-2026-abcdef"
        )))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("AUTH_CREDENTIAL_AMBIGUOUS");
        assertThat(identities.recordedLoginUserId).isNull();
    }

    @Test
    void devLoginIsExplicitlyDisabledByDefault() {
        AuthApplicationService service = service(new CapturingIdentityPort(), new FakeAuditPort(), false);

        assertThatThrownBy(() -> service.devLogin(
                new AuthUseCase.DevLoginCommand("local-user", "用户", "INVITE")
        ))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("DEV_LOGIN_DISABLED");
    }

    @Test
    void miniprogramAndDevLoginRejectInactiveExistingMembersBeforeRecordingLogin() {
        for (String status : List.of("LOCKED", "DISABLED")) {
            CapturingIdentityPort identities = new CapturingIdentityPort();
            identities.status = status;
            AuthApplicationService service = service(identities, new FakeAuditPort(), true);

            assertThatThrownBy(() -> service.miniprogramLogin(new AuthUseCase.MiniprogramLoginCommand(
                    "code", null, null
            )))
                    .isInstanceOf(DomainException.class)
                    .extracting("code")
                    .isEqualTo("LOCKED".equals(status) ? "MEMBER_LOCKED" : "MEMBER_DISABLED");
            assertThat(identities.recordedLoginUserId).isNull();

            assertThatThrownBy(() -> service.devLogin(
                    new AuthUseCase.DevLoginCommand("local-user", "用户", null)
            ))
                    .isInstanceOf(DomainException.class)
                    .extracting("code")
                    .isEqualTo("LOCKED".equals(status) ? "MEMBER_LOCKED" : "MEMBER_DISABLED");
            assertThat(identities.recordedLoginUserId).isNull();
        }
    }

    @Test
    void miniprogramLoginDeclaresTheTransactionThatIncludesClaimLoginTimestampAndAudit() throws Exception {
        Method method = AuthApplicationService.class.getMethod(
                "miniprogramLogin", AuthUseCase.MiniprogramLoginCommand.class
        );
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    private static AuthApplicationService service(
            CapturingIdentityPort identities,
            FakeAuditPort audit,
            boolean mockEnabled
    ) {
        return new AuthApplicationService(
                new FakeWeChat(),
                identities,
                audit,
                mockEnabled
        );
    }

    private static final class FakeWeChat implements WeChatMiniprogramPort {
        @Override
        public WeChatIdentity exchangeMiniprogramCode(String jsCode) {
            return new WeChatIdentity(
                    "WECHAT_MP",
                    "mp-app-fixture",
                    "openid-1",
                    "union-1",
                    null,
                    null
            );
        }

        @Override
        public IdentityPorts.VerifiedPhone exchangePhoneCode(String dynamicCode) {
            return new IdentityPorts.VerifiedPhone("13800138000");
        }
    }

    private static final class CapturingIdentityPort implements UserIdentityPort {
        private String inviteCode;
        private String claimSecretHash;
        private String lastProvider;
        private String status = "ACTIVE";
        private Long recordedLoginUserId;
        private boolean sponsorClaimed;
        private boolean newlyRegistered = true;
        private boolean requireInviteForNewIdentity;

        @Override
        public RegistrationResult findOrRegister(
                WeChatIdentity identity,
                String inviteCode,
                String sponsorClaimSecretHash
        ) {
            this.inviteCode = inviteCode;
            this.claimSecretHash = sponsorClaimSecretHash;
            this.lastProvider = identity.provider();
            if (requireInviteForNewIdentity
                    && inviteCode == null
                    && sponsorClaimSecretHash == null) {
                throw new DomainException("INVITE_CODE_REQUIRED", "新用户必须提供邀请码");
            }
            return new RegistrationResult(
                    42, "PUBLIC-ID", identity.nickname() == null ? "微信用户" : identity.nickname(),
                    status, 7L, newlyRegistered, sponsorClaimed
            );
        }

        @Override
        public void recordLogin(long userId) {
            recordedLoginUserId = userId;
        }
    }

    private static final class FakeAuditPort implements AdminAuditPort {
        private final java.util.ArrayList<AuditRecord> records = new java.util.ArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }

        @Override
        public List<AuditView> search(AuditQuery query) {
            return List.of();
        }

        @Override
        public long count(AuditQuery query) {
            return 0;
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
