package com.marketshop.infrastructure.storage;

import com.marketshop.application.catalog.CatalogAssetStoragePort;
import com.marketshop.application.catalog.CatalogAssetStoragePort.StoredAsset;
import com.marketshop.application.identity.IdentityAvatarStoragePort;
import com.marketshop.application.identity.IdentityAvatarStoragePort.StoredAvatar;
import com.marketshop.application.proof.OrderProofPorts.PrivateObjectStoragePort;
import com.marketshop.application.proof.OrderProofPorts.StoredObject;
import com.marketshop.application.proof.PrivateObjectDeliveryPort;
import com.marketshop.application.proof.PrivateObjectDeliveryPort.PrivateContent;
import com.marketshop.domain.shared.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "market-shop.object-storage", name = "provider", havingValue = "local")
public class LocalFileObjectStorageAdapter
        implements PrivateObjectStoragePort, CatalogAssetStoragePort, PrivateObjectDeliveryPort,
        IdentityAvatarStoragePort {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final Path root;
    private final byte[] signingSecret;
    private final String privateBaseUrl;
    private final Clock clock;

    @Autowired
    public LocalFileObjectStorageAdapter(
            @Value("${market-shop.object-storage.local.root}") String root,
            @Value("${market-shop.object-storage.local.signing-secret}") String signingSecret,
            @Value("${market-shop.object-storage.local.private-base-url:/api/v1/storage/private}") String privateBaseUrl
    ) {
        this(Path.of(root), signingSecret, privateBaseUrl, Clock.systemUTC());
    }

    LocalFileObjectStorageAdapter(Path root, String signingSecret, String privateBaseUrl, Clock clock) {
        if (signingSecret == null || signingSecret.length() < 32) {
            throw new IllegalStateException(
                    "Local object storage requires MARKET_SHOP_LOCAL_STORAGE_SIGNING_SECRET with at least 32 characters"
            );
        }
        if (privateBaseUrl == null || !privateBaseUrl.startsWith("/") || privateBaseUrl.startsWith("//")) {
            throw new IllegalStateException("Local object storage private base URL must be an application-relative path");
        }
        this.root = root.toAbsolutePath().normalize();
        this.signingSecret = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.privateBaseUrl = privateBaseUrl.endsWith("/")
                ? privateBaseUrl.substring(0, privateBaseUrl.length() - 1)
                : privateBaseUrl;
        this.clock = clock;
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException("Local object storage directory cannot be created", exception);
        }
    }

    @Override
    public StoredObject put(long orderId, String originalFilename, String mediaType, byte[] bytes) {
        String objectKey = "private/" + orderId + "/" + UUID.randomUUID() + "-"
                + StorageSupport.safeFilename(originalFilename, "proof");
        write(objectKey, bytes, "OBJECT_STORAGE_FAILED", "付款凭证存储失败，请稍后重试");
        return new StoredObject(objectKey, StorageSupport.sha256(bytes), bytes.length);
    }

    @Override
    public StoredAsset put(String originalFilename, String mediaType, byte[] bytes) {
        String objectKey = "catalog/" + UUID.randomUUID() + "-"
                + StorageSupport.safeFilename(originalFilename, "image");
        write(objectKey, bytes, "CATALOG_ASSET_STORAGE_FAILED", "商品素材存储失败，请稍后重试");
        return new StoredAsset(objectKey, StorageSupport.sha256(bytes), bytes.length);
    }

    @Override
    public byte[] get(String objectKey) {
        return read(
                objectKey,
                "CATALOG_ASSET_READ_FAILED",
                "商品素材读取失败"
        );
    }

    @Override
    public void deleteAsset(String objectKey) {
        deleteFile(
                objectKey,
                "CATALOG_ASSET_DELETE_FAILED",
                "商品素材删除失败"
        );
    }

    @Override
    public StoredAvatar putAvatar(
            long userId,
            String originalFilename,
            String mediaType,
            byte[] bytes
    ) {
        String objectKey = "avatars/" + userId + "/" + UUID.randomUUID() + "-"
                + StorageSupport.safeFilename(originalFilename, "avatar");
        write(objectKey, bytes, "AVATAR_STORAGE_FAILED", "会员头像存储失败，请稍后重试");
        return new StoredAvatar(objectKey, StorageSupport.sha256(bytes), bytes.length);
    }

    @Override
    public byte[] readAvatar(String objectKey) {
        Path target = resolve(objectKey, "AVATAR_READ_FAILED", "会员头像读取失败");
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new DomainException("MEMBER_AVATAR_NOT_FOUND", "会员头像不存在");
        }
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new DomainException("AVATAR_READ_FAILED", "会员头像读取失败");
            }
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw new DomainException("AVATAR_READ_FAILED", "会员头像读取失败");
        }
    }

    @Override
    public void deleteAvatar(String objectKey) {
        deleteFile(objectKey, "AVATAR_DELETE_FAILED", "会员头像清理失败");
    }

    @Override
    public String signedGetUrl(String objectKey, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new DomainException("OBJECT_SIGNING_FAILED", "付款凭证访问链接生成失败");
        }
        long expiresAt = Instant.now(clock).plus(duration).getEpochSecond();
        String encodedKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectKey.getBytes(StandardCharsets.UTF_8));
        String payload = expiresAt + "." + encodedKey;
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        return privateBaseUrl + "/" + payload + "." + signature;
    }

    @Override
    public PrivateContent readSigned(String token) {
        String objectKey = verifyAndReadObjectKey(token);
        byte[] bytes = read(objectKey, "OBJECT_SIGNING_INVALID", "付款凭证访问链接无效或已过期");
        String mediaType = StorageSupport.imageMediaType(bytes);
        if (mediaType == null) {
            throw new DomainException("OBJECT_SIGNING_INVALID", "付款凭证访问链接无效或已过期");
        }
        return new PrivateContent(mediaType, bytes);
    }

    @Override
    public void delete(String objectKey) {
        deleteFile(
                objectKey,
                "OBJECT_DELETE_FAILED",
                "付款凭证清理失败，将由任务重试"
        );
    }

    private void write(String objectKey, byte[] bytes, String code, String message) {
        Path target = resolve(objectKey, code, message);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
        } catch (IOException exception) {
            cleanupTemporary(temporary, exception);
            throw new DomainException(code, message);
        }
    }

    private byte[] read(String objectKey, String code, String message) {
        Path target = resolve(objectKey, code, message);
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new DomainException(code, message);
            }
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw new DomainException(code, message);
        }
    }

    private void deleteFile(String objectKey, String code, String message) {
        Path target = resolve(objectKey, code, message);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new DomainException(code, message);
        }
    }

    private Path resolve(String objectKey, String code, String message) {
        if (objectKey == null || objectKey.isBlank() || objectKey.indexOf('\\') >= 0) {
            throw new DomainException(code, message);
        }
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new DomainException(code, message);
        }
        return target;
    }

    private String verifyAndReadObjectKey(String token) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", 3);
            if (parts.length != 3) {
                throw invalidSignedUrl();
            }
            long expiresAt = Long.parseLong(parts[0]);
            if (Instant.now(clock).getEpochSecond() > expiresAt) {
                throw invalidSignedUrl();
            }
            String payload = parts[0] + "." + parts[1];
            byte[] provided = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(sign(payload), provided)) {
                throw invalidSignedUrl();
            }
            return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalidSignedUrl();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingSecret, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Local object storage signing is unavailable", exception);
        }
    }

    private static DomainException invalidSignedUrl() {
        return new DomainException("OBJECT_SIGNING_INVALID", "付款凭证访问链接无效或已过期");
    }

    private static void cleanupTemporary(Path temporary, IOException original) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }
}
