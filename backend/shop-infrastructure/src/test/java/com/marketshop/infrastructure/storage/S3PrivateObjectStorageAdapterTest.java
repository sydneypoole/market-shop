package com.marketshop.infrastructure.storage;

import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3PrivateObjectStorageAdapterTest {

    @Test
    void missingBucketFailsClosedWithoutCreatePermission() {
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("missing").build());
        S3PrivateObjectStorageAdapter adapter = new S3PrivateObjectStorageAdapter(
                client, presigner, "market-shop-private", false
        );

        assertThatThrownBy(() -> adapter.put(42L, "proof.png", "image/png", new byte[]{1}))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("OBJECT_STORAGE_FAILED"));
        verify(client, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void explicitlyEnabledCreationIsUsedOnlyAfterMissingBucket() {
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("missing").build())
                .thenReturn(HeadBucketResponse.builder().build());
        when(client.putObject(any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
                any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        S3PrivateObjectStorageAdapter adapter = new S3PrivateObjectStorageAdapter(
                client, presigner, "market-shop-private", true
        );

        adapter.put(42L, "proof.png", "image/png", new byte[]{1});

        verify(client).createBucket(any(CreateBucketRequest.class));
    }
}
