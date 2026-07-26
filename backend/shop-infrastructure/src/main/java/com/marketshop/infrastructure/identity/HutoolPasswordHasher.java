package com.marketshop.infrastructure.identity;

import cn.hutool.crypto.digest.BCrypt;
import com.marketshop.application.identity.IdentityPorts.PasswordHasher;
import org.springframework.stereotype.Component;

@Component
public class HutoolPasswordHasher implements PasswordHasher {

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return rawPassword != null && encodedPassword != null && BCrypt.checkpw(rawPassword, encodedPassword);
    }

    @Override
    public String encode(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(12));
    }
}
