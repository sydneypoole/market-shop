package com.marketshop.infrastructure.identity;

import com.marketshop.application.identity.IdentityPorts.OAuthStateStore;
import com.marketshop.application.identity.IdentityPorts.StateConsumeResult;
import com.marketshop.application.identity.IdentityPorts.StateConsumeStatus;
import com.marketshop.application.identity.IdentityPorts.StatePayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class RedisOAuthStateStore implements OAuthStateStore {

    private static final String KEY_PREFIX = "market-shop:oauth-state:";
    private static final String BINDING_MISMATCH = "__BINDING_MISMATCH__";
    private static final String MALFORMED = "__MALFORMED__";
    private static final String ENCRYPTED_CLAIM_PREFIX = "enc1:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then
                return nil
            end
            local separator = string.find(value, '.', 1, true)
            if not separator or separator ~= 65 then
                redis.call('DEL', KEYS[1])
                return '__MALFORMED__'
            end
            local stored_binding = string.sub(value, 1, separator - 1)
            if stored_binding ~= ARGV[1] then
                return '__BINDING_MISMATCH__'
            end
            redis.call('DEL', KEYS[1])
            return string.sub(value, separator + 1)
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final SecretKeySpec claimCipherKey;

    /**
     * Spring constructor.  The bootstrap claim secret is used only as a
     * process/application key to seal the claim hash before it is written to
     * Redis; neither the raw secret nor its reusable SHA-256 hash is persisted
     * in the OAuth state value.  Deployments with multiple instances share the
     * same configured bootstrap secret, so pending states remain consumable.
     */
    @Autowired
    public RedisOAuthStateStore(
            StringRedisTemplate redisTemplate,
            @Value("${market-shop.bootstrap-admin.sponsor-claim-secret:}") String claimCipherSecret
    ) {
        this.redisTemplate = redisTemplate;
        this.claimCipherKey = deriveCipherKey(claimCipherSecret);
    }

    /** Test-friendly constructor retained for lightweight adapter tests. */
    public RedisOAuthStateStore(StringRedisTemplate redisTemplate) {
        this(redisTemplate, UUID.randomUUID().toString() + UUID.randomUUID());
    }

    @Override
    public String create(StatePayload payload, String browserBindingHash, Duration ttl) {
        if (payload == null || browserBindingHash == null || !browserBindingHash.matches("[0-9a-f]{64}")
                || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("OAuth state input is invalid");
        }
        String state = UUID.randomUUID().toString().replace("-", "");
        String encoded = encode(payload.scene())
                + "." + encode(payload.inviteCode())
                + "." + encodeClaimHash(payload.sponsorClaimSecretHash())
                + "." + encode(payload.redirectUri());
        redisTemplate.opsForValue().set(KEY_PREFIX + state, browserBindingHash + "." + encoded, ttl);
        return state;
    }

    @Override
    public StateConsumeResult consume(String state, String browserBindingHash) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{16,128}")
                || browserBindingHash == null || !browserBindingHash.matches("[0-9a-f]{64}")) {
            return new StateConsumeResult(StateConsumeStatus.MISSING, null);
        }
        String value = redisTemplate.execute(
                CONSUME_SCRIPT,
                List.of(KEY_PREFIX + state),
                browserBindingHash
        );
        if (value == null) {
            return new StateConsumeResult(StateConsumeStatus.MISSING, null);
        }
        if (BINDING_MISMATCH.equals(value)) {
            return new StateConsumeResult(StateConsumeStatus.BINDING_MISMATCH, null);
        }
        if (MALFORMED.equals(value)) {
            return new StateConsumeResult(StateConsumeStatus.MISSING, null);
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return new StateConsumeResult(StateConsumeStatus.MISSING, null);
        }
        try {
            StatePayload payload = new StatePayload(
                    decode(parts[0]),
                    nullIfEmpty(decode(parts[1])),
                    decodeClaimHash(parts[2]),
                    decode(parts[3])
            );
            return new StateConsumeResult(StateConsumeStatus.CONSUMED, payload);
        } catch (IllegalArgumentException exception) {
            return new StateConsumeResult(StateConsumeStatus.MISSING, null);
        }
    }

    private static String encode(String value) {
        if (value == null) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String encodeClaimHash(String value) {
        if (value == null) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, claimCipherKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            return ENCRYPTED_CLAIM_PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OAuth state protection is unavailable", exception);
        }
    }

    private String decodeClaimHash(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        // States written by an older node may contain a plain hash.  Accept
        // it only for one consumption and never emit that representation
        // again; this keeps rolling deployments forward compatible.
        if (!value.startsWith(ENCRYPTED_CLAIM_PREFIX)) {
            return value;
        }
        try {
            byte[] packed = Base64.getUrlDecoder().decode(value.substring(ENCRYPTED_CLAIM_PREFIX.length()));
            if (packed.length <= IV_BYTES) {
                throw new GeneralSecurityException("invalid OAuth state ciphertext");
            }
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] ciphertext = java.util.Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, claimCipherKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid OAuth claim state", exception);
        }
    }

    private static SecretKeySpec deriveCipherKey(String secret) {
        String material = secret == null || secret.isBlank()
                ? UUID.randomUUID().toString() + UUID.randomUUID()
                : secret;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
