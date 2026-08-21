package com.marketshop.application.identity;

import com.marketshop.application.identity.IdentityPorts.RegistrationResult;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.WeChatMiniprogramPort;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthApplicationService implements AuthUseCase {

    private final WeChatMiniprogramPort weChatMiniprogramPort;
    private final MemberAuthenticationTransactionService localTransactions;
    private final boolean mockEnabled;

    public AuthApplicationService(
            WeChatMiniprogramPort weChatMiniprogramPort,
            MemberAuthenticationTransactionService localTransactions,
            @Value("${market-shop.wechat.mock-enabled:false}") boolean mockEnabled
    ) {
        this.weChatMiniprogramPort = weChatMiniprogramPort;
        this.localTransactions = localTransactions;
        this.mockEnabled = mockEnabled;
    }

    @Override
    public LoginResult miniprogramLogin(MiniprogramLoginCommand command) {
        if (command == null || trimToNull(command.code()) == null) {
            throw new DomainException("WECHAT_CODE_REQUIRED", "微信登录凭证不能为空");
        }
        WeChatIdentity identity = weChatMiniprogramPort.exchangeMiniprogramCode(command.code().trim());
        return loginResult(localTransactions.login(identity, null));
    }

    @Override
    public LoginResult miniprogramRegister(MiniprogramRegistrationCommand command) {
        if (command == null || trimToNull(command.code()) == null) {
            throw new DomainException("WECHAT_CODE_REQUIRED", "微信登录凭证不能为空");
        }
        String inviteCode = trimToNull(command.inviteCode());
        String rawClaimSecret = trimToNull(command.sponsorClaimSecret());
        if (inviteCode != null && rawClaimSecret != null) {
            throw new DomainException(
                    "AUTH_CREDENTIAL_AMBIGUOUS",
                    "邀请码和发起人认领密钥不能同时提交"
            );
        }
        if (inviteCode == null && rawClaimSecret == null) {
            throw new DomainException("INVITE_CODE_REQUIRED", "首次注册必须填写有效邀请码");
        }
        if (rawClaimSecret != null && rawClaimSecret.length() < SponsorClaimSecrets.MINIMUM_LENGTH) {
            throw new DomainException("SPONSOR_CLAIM_SECRET_INVALID", "发起人认领密钥无效或已使用");
        }
        String claimSecretHash = rawClaimSecret == null ? null : SponsorClaimSecrets.sha256(rawClaimSecret);

        // Code exchange is external I/O and therefore stays outside the local
        // database transaction. The command contains credentials only; the
        // transaction generates the platform profile and complete account graph.
        WeChatIdentity identity = weChatMiniprogramPort.exchangeMiniprogramCode(command.code().trim());
        return loginResult(localTransactions.register(identity, inviteCode, claimSecretHash));
    }

    @Override
    public LoginResult devLogin(DevLoginCommand command) {
        if (!mockEnabled) {
            throw new DomainException("DEV_LOGIN_DISABLED", "开发登录未启用");
        }
        String openId = trimToNull(command.openId());
        if (openId == null) {
            throw new DomainException("OPEN_ID_REQUIRED", "开发登录标识不能为空");
        }
        WeChatIdentity identity = new WeChatIdentity(
                "WECHAT_MOCK",
                "local",
                openId,
                "mock-union-" + openId,
                trimToNull(command.nickname()) == null ? "微信演示用户" : command.nickname().trim(),
                null
        );
        return loginResult(localTransactions.login(identity, trimToNull(command.inviteCode())));
    }

    private static LoginResult loginResult(RegistrationResult result) {
        return new LoginResult(
                result.userId(),
                result.publicId(),
                result.nickname(),
                result.authEpoch(),
                result.newlyRegistered()
        );
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
