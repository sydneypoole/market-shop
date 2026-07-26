package com.marketshop.application.identity;

public interface AuthUseCase {

    StartResult begin(BeginCommand command);

    LoginResult complete(CompleteCommand command);

    LoginResult devLogin(DevLoginCommand command);

    record BeginCommand(String scene, String inviteCode, String redirectUri) {
    }

    record CompleteCommand(String code, String state) {
    }

    record DevLoginCommand(String openId, String nickname, String inviteCode) {
    }

    record StartResult(String authorizationUrl, String state, long expiresInSeconds) {
    }

    record LoginResult(long userId, String publicId, String nickname, boolean newlyRegistered, String redirectUri) {
    }
}
