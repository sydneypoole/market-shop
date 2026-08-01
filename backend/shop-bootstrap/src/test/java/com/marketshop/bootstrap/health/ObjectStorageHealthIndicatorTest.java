package com.marketshop.bootstrap.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ObjectStorageHealthIndicatorTest {

    @Test
    void localProviderChecksActualReadWriteRoundTrip(@TempDir Path root) {
        ObjectStorageHealthIndicator indicator = new ObjectStorageHealthIndicator(
                "local",
                root,
                null,
                "unused"
        );

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(root.resolve(".readiness-probe")).doesNotExist();
    }

    @Test
    void localProviderIsDownWhenRootIsAFile(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("not-a-directory"), "data");
        ObjectStorageHealthIndicator indicator = new ObjectStorageHealthIndicator(
                "local",
                file,
                null,
                "unused"
        );

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void s3ProviderIsDownWhenBucketProbeFails(@TempDir Path root) {
        S3Client client = mock(S3Client.class);
        doThrow(new IllegalStateException("endpoint unavailable"))
                .when(client).headBucket(any(HeadBucketRequest.class));
        ObjectStorageHealthIndicator indicator = new ObjectStorageHealthIndicator(
                "s3",
                root,
                client,
                "market-shop-private"
        );

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).doesNotContainValue("endpoint unavailable");
    }
}
