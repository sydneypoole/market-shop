package com.marketshop.application.identity;

public interface AuthUseCase {

    LoginResult miniprogramLogin(MiniprogramLoginCommand command);

    LoginResult devLogin(DevLoginCommand command);

    record MiniprogramLoginCommand(String code, String inviteCode, String sponsorClaimSecret) {
    }

    record DevLoginCommand(String openId, String nickname, String inviteCode) {
    }

    record LoginResult(
            long userId,
            String publicId,
            String nickname,
            long authEpoch,
            boolean newlyRegistered
    ) {
    }
}
