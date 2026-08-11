package com.marketshop.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "MARKET_SHOP_RUSTFS_INTEGRATION", matches = "true")
class S3PrivateObjectStorageAdapterIntegrationTest {

    @Test
    void roundTripsPrivateProofsAndMemberAvatarsAgainstRustfs() throws Exception {
        String endpoint = System.getenv().getOrDefault("MARKET_SHOP_RUSTFS_ENDPOINT", "http://127.0.0.1:9000");
        String accessKey = System.getenv().getOrDefault("MARKET_SHOP_RUSTFS_ACCESS_KEY", "marketshop");
        String secretKey = System.getenv("MARKET_SHOP_RUSTFS_SECRET_KEY");
        String bucket = System.getenv().getOrDefault("MARKET_SHOP_RUSTFS_BUCKET", "market-shop-private");
        byte[] content = "market-shop-rustfs-smoke".getBytes(StandardCharsets.UTF_8);
        byte[] avatarContent = "sanitized-avatar-smoke".getBytes(StandardCharsets.UTF_8);
        var adapter = new S3PrivateObjectStorageAdapter(
                endpoint, accessKey, secretKey, bucket, "us-east-1", true
        );
        String proofObjectKey = null;
        String avatarObjectKey = null;
        try {
            var stored = adapter.put(999_999L, "smoke.txt", "text/plain", content);
            proofObjectKey = stored.objectKey();
            assertThat(stored.sha256()).hasSize(64);
            assertThat(stored.sizeBytes()).isEqualTo(content.length);

            HttpClient http = HttpClient.newHttpClient();
            String signedUrl = adapter.signedGetUrl(stored.objectKey(), Duration.ofMinutes(1));
            HttpResponse<byte[]> download = http.send(
                    HttpRequest.newBuilder(URI.create(signedUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo(content);

            adapter.delete(stored.objectKey());
            HttpResponse<Void> deleted = http.send(
                    HttpRequest.newBuilder(URI.create(signedUrl)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            assertThat(deleted.statusCode()).isEqualTo(404);

            var avatar = adapter.putAvatar(
                    999_999L, "avatar.png", "image/png", avatarContent
            );
            avatarObjectKey = avatar.objectKey();
            assertThat(avatar.objectKey()).startsWith("avatars/999999/");
            assertThat(avatar.sha256()).hasSize(64);
            assertThat(avatar.sizeBytes()).isEqualTo(avatarContent.length);
            assertThat(adapter.readAvatar(avatar.objectKey())).isEqualTo(avatarContent);

            adapter.deleteAvatar(avatar.objectKey());
            assertThatThrownBy(() -> adapter.readAvatar(avatar.objectKey()))
                    .isInstanceOfSatisfying(
                            com.marketshop.domain.shared.DomainException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo("MEMBER_AVATAR_NOT_FOUND")
                    );
        } finally {
            if (proofObjectKey != null) {
                adapter.delete(proofObjectKey);
            }
            if (avatarObjectKey != null) {
                adapter.deleteAvatar(avatarObjectKey);
            }
            adapter.closeClients();
        }
    }
}
