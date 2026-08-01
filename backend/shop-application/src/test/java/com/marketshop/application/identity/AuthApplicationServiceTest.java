package com.marketshop.application.identity;

import com.marketshop.application.audit.AdminAuditPort;
import com.marketshop.application.identity.IdentityPorts.OAuthStateStore;
import com.marketshop.application.identity.IdentityPorts.RegistrationResult;
import com.marketshop.application.identity.IdentityPorts.StateConsumeResult;
import com.marketshop.application.identity.IdentityPorts.StateConsumeStatus;
import com.marketshop.application.identity.IdentityPorts.StatePayload;
import com.marketshop.application.identity.IdentityPorts.UserIdentityPort;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatOAuthPort;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthApplicationServiceTest {

    private static final String BROWSER_BINDING = "browser-binding-secret";
    private static final String BINDING_HASH = IdentitySecretHashes.sha256(BROWSER_BINDING);

    @Test
    void completesWechatLoginWithAtomicBrowserBoundStateAndInvite() {
        FakeStateStore states = new FakeStateStore();
        CapturingIdentityPort identities = new CapturingIdentityPort();
        AuthApplicationService service = service(states, identities, new FakeAuditPort(), false);

        var start = service.begin(new AuthUseCase.BeginCommand(
                "h5", "INVITE-1", null, "https://store.example.com/orders", BINDING_HASH
        ));
        var result = service.complete(new AuthUseCase.CompleteCommand(
                "wechat-code", start.state(), BROWSER_BINDING
        ));

        assertThat(start.authorizationUrl()).contains("state-1");
        assertThat(result.userId()).isEqualTo(42);
        assertThat(result.redirectUri()).isEqualTo("/orders");
        assertThat(identities.inviteCode).isEqualTo("INVITE-1");
        assertThat(identities.claimSecretHash).isNull();
        assertThat(identities.recordedLoginUserId).isEqualTo(42);
        assertThat(states.payload).isNull();
    }

    @Test
    void wrongBrowserCannotConsumeStateAndLegitimateBrowserCanStillFinish() {
        FakeStateStore states = new FakeStateStore();
        AuthApplicationService service = service(
                states, new CapturingIdentityPort(), new FakeAuditPort(), false
        );
        String state = service.begin(new AuthUseCase.BeginCommand(
                "WEB", "INVITE-1", null, "/orders", BINDING_HASH
        )).state();

        assertThatThrownBy(() -> service.complete(new AuthUseCase.CompleteCommand(
                "code", state, "another-browser"
        )))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("OAUTH_BROWSER_MISMATCH");
        assertThat(states.payload).isNotNull();

        assertThat(service.complete(new AuthUseCase.CompleteCommand(
                "code", state, BROWSER_BINDING
        )).userId()).isEqualTo(42);
        assertThat(states.payload).isNull();
    }

    @Test
    void sponsorClaimSecretIsHashedBeforeStateAndAuditContainsOnlySafeClaimMetadata() {
        String rawClaimSecret = "owner-only-claim-secret-2026-abcdef";
        FakeStateStore states = new FakeStateStore();
        CapturingIdentityPort identities = new CapturingIdentityPort();
        identities.sponsorClaimed = true;
        identities.newlyRegistered = false;
        FakeAuditPort audit = new FakeAuditPort();
        AuthApplicationService service = service(states, identities, audit, false);

        String state = service.begin(new AuthUseCase.BeginCommand(
                "H5", null, rawClaimSecret, "/", BINDING_HASH
        )).state();

        assertThat(states.payload.sponsorClaimSecretHash())
                .isEqualTo(SponsorClaimSecrets.sha256(rawClaimSecret))
                .doesNotContain(rawClaimSecret);
        service.complete(new AuthUseCase.CompleteCommand("oauth-code", state, BROWSER_BINDING));

        assertThat(identities.claimSecretHash).isEqualTo(SponsorClaimSecrets.sha256(rawClaimSecret));
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.action()).isEqualTo("BOOTSTRAP_SPONSOR_CLAIMED");
            assertThat(record.beforeJson()).isEqualTo("{\"status\":\"PENDING\"}");
            assertThat(record.afterJson())
                    .contains("\"status\":\"CLAIMED\"")
                    .contains("WECHAT_H5")
                    .contains("oa-app-fixture");
            String serialized = String.join("|",
                    nullSafe(record.beforeJson()), nullSafe(record.afterJson()),
                    nullSafe(record.reason()), nullSafe(record.requestId()),
                    nullSafe(record.userAgentSummary()));
            assertThat(serialized)
                    .doesNotContain(rawClaimSecret)
                    .doesNotContain(SponsorClaimSecrets.sha256(rawClaimSecret))
                    .doesNotContain("fixture-open-id")
                    .doesNotContain("fixture-union-id")
                    .doesNotContain("oauth-code");
        });
    }

    @Test
    void rejectsExternalRedirectAndAmbiguousCredentialsBeforeCreatingState() {
        FakeStateStore states = new FakeStateStore();
        AuthApplicationService service = service(
                states, new CapturingIdentityPort(), new FakeAuditPort(), false
        );

        assertThatThrownBy(() -> service.begin(new AuthUseCase.BeginCommand(
                "WEB", null, null, "https://evil.example/steal", BINDING_HASH
        )))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("REDIRECT_URI_INVALID");
        assertThatThrownBy(() -> service.begin(new AuthUseCase.BeginCommand(
                "WEB", "INVITE", "owner-only-claim-secret-2026-abcdef", "/", BINDING_HASH
        )))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("AUTH_CREDENTIAL_AMBIGUOUS");
        assertThat(states.payload).isNull();
    }

    @Test
    void devLoginIsExplicitlyDisabledByDefault() {
        AuthApplicationService service = service(
                new FakeStateStore(), new CapturingIdentityPort(), new FakeAuditPort(), false
        );

        assertThatThrownBy(() -> service.devLogin(
                new AuthUseCase.DevLoginCommand("local-user", "用户", "INVITE")
        ))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("DEV_LOGIN_DISABLED");
    }

    @Test
    void wechatAndDevLoginRejectInactiveExistingMembersBeforeRecordingLogin() {
        for (String status : List.of("LOCKED", "DISABLED")) {
            FakeStateStore states = new FakeStateStore();
            CapturingIdentityPort identities = new CapturingIdentityPort();
            identities.status = status;
            AuthApplicationService service = service(states, identities, new FakeAuditPort(), true);
            String state = service.begin(new AuthUseCase.BeginCommand(
                    "WEB", null, null, "/orders", BINDING_HASH
            )).state();

            assertThatThrownBy(() -> service.complete(new AuthUseCase.CompleteCommand(
                    "code", state, BROWSER_BINDING
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
    void completeDeclaresTheTransactionThatIncludesClaimLoginTimestampAndAudit() throws Exception {
        Method complete = AuthApplicationService.class.getMethod(
                "complete", AuthUseCase.CompleteCommand.class
        );
        assertThat(complete.getAnnotation(Transactional.class)).isNotNull();
    }

    private static AuthApplicationService service(
            FakeStateStore states,
            CapturingIdentityPort identities,
            FakeAuditPort audit,
            boolean mockEnabled
    ) {
        return new AuthApplicationService(
                new FakeWeChat(),
                states,
                identities,
                audit,
                "https://api.example.com",
                "https://store.example.com",
                mockEnabled
        );
    }

    private static final class FakeStateStore implements OAuthStateStore {
        private StatePayload payload;
        private String browserBindingHash;

        @Override
        public String create(StatePayload payload, String browserBindingHash, Duration ttl) {
            this.payload = payload;
            this.browserBindingHash = browserBindingHash;
            return "state-1";
        }

        @Override
        public StateConsumeResult consume(String state, String candidateBindingHash) {
            if (!"state-1".equals(state) || payload == null) {
                return new StateConsumeResult(StateConsumeStatus.MISSING, null);
            }
            if (!MessageDigest.isEqual(
                    browserBindingHash.getBytes(StandardCharsets.US_ASCII),
                    candidateBindingHash.getBytes(StandardCharsets.US_ASCII)
            )) {
                return new StateConsumeResult(StateConsumeStatus.BINDING_MISMATCH, null);
            }
            StatePayload result = payload;
            payload = null;
            return new StateConsumeResult(StateConsumeStatus.CONSUMED, result);
        }
    }

    private static final class FakeWeChat implements WeChatOAuthPort {
        @Override
        public String authorizationUrl(String scene, String state, String callbackUri) {
            return "https://wechat.example/authorize?state=" + state;
        }

        @Override
        public WeChatIdentity exchange(String scene, String code) {
            return new WeChatIdentity(
                    "WECHAT_" + scene,
                    "H5".equals(scene) ? "oa-app-fixture" : "web-app-fixture",
                    "fixture-open-id",
                    "fixture-union-id",
                    "微信用户",
                    null
            );
        }
    }

    private static final class CapturingIdentityPort implements UserIdentityPort {
        private String inviteCode;
        private String claimSecretHash;
        private String status = "ACTIVE";
        private Long recordedLoginUserId;
        private boolean sponsorClaimed;
        private boolean newlyRegistered = true;

        @Override
        public RegistrationResult findOrRegister(
                WeChatIdentity identity,
                String inviteCode,
                String sponsorClaimSecretHash
        ) {
            this.inviteCode = inviteCode;
            this.claimSecretHash = sponsorClaimSecretHash;
            return new RegistrationResult(
                    42, "PUBLIC-ID", identity.nickname(), status, 7L,
                    newlyRegistered, sponsorClaimed
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
