package com.marketshop.application.identity;

import com.marketshop.application.identity.IdentityPorts.OAuthStateStore;
import com.marketshop.application.identity.IdentityPorts.RegistrationResult;
import com.marketshop.application.identity.IdentityPorts.StatePayload;
import com.marketshop.application.identity.IdentityPorts.UserIdentityPort;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatOAuthPort;
import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthApplicationServiceTest {

    @Test
    void completesWechatLoginWithOneTimeStateAndInvite() {
        FakeStateStore states = new FakeStateStore();
        FakeWeChat weChat = new FakeWeChat();
        CapturingIdentityPort identities = new CapturingIdentityPort();
        AuthApplicationService service = new AuthApplicationService(
                weChat,
                states,
                identities,
                "https://shop.example.com",
                false
        );

        var start = service.begin(new AuthUseCase.BeginCommand(
                "h5",
                "INVITE-1",
                "https://h5.example.com/orders"
        ));
        var result = service.complete(new AuthUseCase.CompleteCommand("wechat-code", start.state()));

        assertThat(start.authorizationUrl()).contains("state-1");
        assertThat(result.userId()).isEqualTo(42);
        assertThat(result.redirectUri()).isEqualTo("https://h5.example.com/orders");
        assertThat(identities.inviteCode).isEqualTo("INVITE-1");
        assertThat(states.consume(start.state())).isEmpty();
    }

    @Test
    void rejectsInvalidRedirectBeforeCreatingState() {
        FakeStateStore states = new FakeStateStore();
        AuthApplicationService service = new AuthApplicationService(
                new FakeWeChat(),
                states,
                new CapturingIdentityPort(),
                "https://shop.example.com",
                false
        );

        assertThatThrownBy(() -> service.begin(new AuthUseCase.BeginCommand("WEB", null, "javascript:alert(1)")))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("REDIRECT_URI_INVALID");
        assertThat(states.payload).isNull();
    }

    @Test
    void devLoginIsExplicitlyDisabledByDefault() {
        AuthApplicationService service = new AuthApplicationService(
                new FakeWeChat(),
                new FakeStateStore(),
                new CapturingIdentityPort(),
                "https://shop.example.com",
                false
        );

        assertThatThrownBy(() -> service.devLogin(
                new AuthUseCase.DevLoginCommand("local-user", "用户", "INVITE")
        ))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("DEV_LOGIN_DISABLED");
    }

    private static final class FakeStateStore implements OAuthStateStore {
        private StatePayload payload;

        @Override
        public String create(StatePayload payload, Duration ttl) {
            this.payload = payload;
            return "state-1";
        }

        @Override
        public Optional<StatePayload> consume(String state) {
            if (!"state-1".equals(state) || payload == null) {
                return Optional.empty();
            }
            StatePayload result = payload;
            payload = null;
            return Optional.of(result);
        }
    }

    private static final class FakeWeChat implements WeChatOAuthPort {
        @Override
        public String authorizationUrl(String scene, String state, String callbackUri) {
            return "https://wechat.example/authorize?state=" + state;
        }

        @Override
        public WeChatIdentity exchange(String scene, String code) {
            return new WeChatIdentity("WECHAT_H5", "app", "openid", "unionid", "微信用户", null);
        }
    }

    private static final class CapturingIdentityPort implements UserIdentityPort {
        private String inviteCode;

        @Override
        public RegistrationResult findOrRegister(WeChatIdentity identity, String inviteCode) {
            this.inviteCode = inviteCode;
            return new RegistrationResult(42, "PUBLIC-ID", identity.nickname(), true);
        }
    }
}
