package com.marketshop.application.identity;

import java.util.Set;

public interface AdminAuthUseCase {

    LoginResult login(LoginCommand command);

    record LoginCommand(String username, String password) {
    }

    record LoginResult(long adminId, String username, String displayName, boolean mustChangePassword,
                       Set<String> roles, Set<String> permissions) {
    }
}
