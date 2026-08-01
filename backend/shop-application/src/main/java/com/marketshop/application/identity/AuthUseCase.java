package com.marketshop.application.identity;

public interface AuthUseCase {

    StartResult begin(BeginCommand command);

    LoginResult complete(CompleteCommand command);

    LoginResult devLogin(DevLoginCommand command);

    record BeginCommand(
            String scene,
            String inviteCode,
            String sponsorClaimSecret,
            String redirectUri,
            String browserBindingHash
    ) {
    }

    record CompleteCommand(String code, String state, String browserBinding) {
    }

    record DevLoginCommand(String openId, String nickname, String inviteCode) {
    }

    record StartResult(String authorizationUrl, String state, long expiresInSeconds) {
    }

    record LoginResult(
            long userId,
            String publicId,
            String nickname,
            long authEpoch,
            boolean newlyRegistered,
            String redirectUri
    ) {
    }
}
