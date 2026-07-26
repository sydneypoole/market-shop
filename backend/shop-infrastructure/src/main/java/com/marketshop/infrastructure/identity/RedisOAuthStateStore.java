package com.marketshop.infrastructure.identity;

import com.marketshop.application.identity.IdentityPorts.OAuthStateStore;
import com.marketshop.application.identity.IdentityPorts.StatePayload;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisOAuthStateStore implements OAuthStateStore {

    private static final String KEY_PREFIX = "market-shop:oauth-state:";

    private final StringRedisTemplate redisTemplate;

    public RedisOAuthStateStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String create(StatePayload payload, Duration ttl) {
        String state = UUID.randomUUID().toString().replace("-", "");
        String encoded = encode(payload.scene()) + "." + encode(payload.inviteCode()) + "." + encode(payload.redirectUri());
        redisTemplate.opsForValue().set(KEY_PREFIX + state, encoded, ttl);
        return state;
    }

    @Override
    public Optional<StatePayload> consume(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + state);
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        return Optional.of(new StatePayload(decode(parts[0]), nullIfEmpty(decode(parts[1])), decode(parts[2])));
    }

    private static String encode(String value) {
        if (value == null) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value.isEmpty()) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String nullIfEmpty(String value) {
        return value.isEmpty() ? null : value;
    }
}
