package com.marketshop.bootstrap.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectStorageBucketInitializerTest {

    @Test
    void boundsConfiguredApiTimeoutForBucketInitialization() {
        assertThat(ObjectStorageBucketInitializer.boundedApiTimeout(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(ObjectStorageBucketInitializer.boundedApiTimeout(15)).isEqualTo(Duration.ofSeconds(15));
        assertThat(ObjectStorageBucketInitializer.boundedApiTimeout(120)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void localProviderDoesNotCreateBuckets() {
        ObjectStorageBucketInitializer initializer = new ObjectStorageBucketInitializer(
                "local",
                null,
                "unused"
        );

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
    }

    @Test
    void explicitlyEnabledS3InitializerCreatesOnlyAMissingBucketAndClosesClient() {
        S3Client client = mock(S3Client.class);
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("missing").build())
                .thenReturn(HeadBucketResponse.builder().build());
        ObjectStorageBucketInitializer initializer = new ObjectStorageBucketInitializer(
                "s3",
                client,
                "market-shop-private"
        );

        initializer.run(null);

        verify(client).createBucket(any(CreateBucketRequest.class));
        verify(client).close();
    }

    @Test
    void existingBucketIsOnlyReadAndClientIsClosed() {
        S3Client client = mock(S3Client.class);
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(HeadBucketResponse.builder().build());
        ObjectStorageBucketInitializer initializer = new ObjectStorageBucketInitializer(
                "s3",
                client,
                "market-shop-private"
        );

        initializer.run(null);

        verify(client, never()).createBucket(any(CreateBucketRequest.class));
        verify(client).close();
    }
}
