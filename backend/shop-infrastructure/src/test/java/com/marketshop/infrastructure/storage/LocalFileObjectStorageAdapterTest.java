package com.marketshop.infrastructure.storage;

import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileObjectStorageAdapterTest {

    private static final String SIGNING_SECRET = "local-storage-test-secret-at-least-32-characters";
    private static final Instant NOW = Instant.parse("2026-07-27T08:00:00Z");
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    );

    @TempDir
    Path root;

    @Test
    void storesAndReadsCatalogAssetsLocally() {
        var adapter = adapter(NOW);

        var stored = adapter.put("cover.png", "image/png", PNG);

        assertThat(stored.objectKey()).startsWith("catalog/");
        assertThat(stored.sha256()).hasSize(64);
        assertThat(adapter.get(stored.objectKey())).isEqualTo(PNG);

        adapter.deleteAsset(stored.objectKey());
        assertThatThrownBy(() -> adapter.get(stored.objectKey()))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CATALOG_ASSET_READ_FAILED"));
    }

    @Test
    void storesReadsAndDeletesOwnedMemberAvatarsLocally() {
        var adapter = adapter(NOW);

        var stored = adapter.putAvatar(42, "avatar.png", "image/png", PNG);

        assertThat(stored.objectKey()).startsWith("avatars/42/");
        assertThat(stored.sha256()).hasSize(64);
        assertThat(adapter.readAvatar(stored.objectKey())).isEqualTo(PNG);

        adapter.deleteAvatar(stored.objectKey());
        assertThatThrownBy(() -> adapter.readAvatar(stored.objectKey()))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MEMBER_AVATAR_NOT_FOUND"));
    }

    @Test
    void distinguishesMissingAvatarFromUnreadableAvatarStorage() throws Exception {
        var adapter = adapter(NOW);
        Files.createDirectories(root.resolve("avatars/42/not-a-file"));

        assertThatThrownBy(() -> adapter.readAvatar("avatars/42/not-a-file"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AVATAR_READ_FAILED"));
        assertThatThrownBy(() -> adapter.readAvatar("avatars/42/missing.png"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MEMBER_AVATAR_NOT_FOUND"));
    }

    @Test
    void signsPrivateLocalDeliveryWithoutExposingTheFilesystem() {
        var adapter = adapter(NOW);
        var stored = adapter.put(42L, "proof.png", "image/png", PNG);

        String signedUrl = adapter.signedGetUrl(stored.objectKey(), Duration.ofMinutes(5));
        String token = signedUrl.substring(signedUrl.lastIndexOf('/') + 1);
        var content = adapter.readSigned(token);

        assertThat(signedUrl).startsWith("/api/v1/storage/private/");
        assertThat(signedUrl).doesNotContain(root.toString());
        assertThat(content.mediaType()).isEqualTo("image/png");
        assertThat(content.bytes()).isEqualTo(PNG);
    }

    @Test
    void rejectsTamperedAndExpiredPrivateLinks() {
        var issuer = adapter(NOW);
        var stored = issuer.put(42L, "proof.png", "image/png", PNG);
        String token = issuer.signedGetUrl(stored.objectKey(), Duration.ofMinutes(1))
                .substring("/api/v1/storage/private/".length());
        String[] parts = token.split("\\.", 3);
        String tamperedSignature = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);

        assertInvalid(() -> issuer.readSigned(parts[0] + "." + parts[1] + "." + tamperedSignature));
        assertInvalid(() -> adapter(NOW.plus(Duration.ofMinutes(2))).readSigned(token));

        String traversalToken = issuer.signedGetUrl("../outside.png", Duration.ofMinutes(1))
                .substring("/api/v1/storage/private/".length());
        assertInvalid(() -> issuer.readSigned(traversalToken));
    }

    @Test
    void requiresAProductionLengthSigningSecret() {
        assertThatThrownBy(() -> new LocalFileObjectStorageAdapter(
                root, "too-short", "/api/v1/storage/private", Clock.systemUTC()
        )).isInstanceOf(IllegalStateException.class);
    }

    private LocalFileObjectStorageAdapter adapter(Instant now) {
        return new LocalFileObjectStorageAdapter(
                root,
                SIGNING_SECRET,
                "/api/v1/storage/private",
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("OBJECT_SIGNING_INVALID"));
    }
}
