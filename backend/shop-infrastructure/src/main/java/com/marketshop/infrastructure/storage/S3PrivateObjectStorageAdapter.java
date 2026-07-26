package com.marketshop.infrastructure.storage;

import com.marketshop.application.catalog.CatalogAssetStoragePort;
import com.marketshop.application.catalog.CatalogAssetStoragePort.StoredAsset;
import com.marketshop.application.proof.OrderProofPorts.PrivateObjectStoragePort;
import com.marketshop.application.proof.OrderProofPorts.StoredObject;
import com.marketshop.domain.shared.DomainException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Component
public class S3PrivateObjectStorageAdapter implements PrivateObjectStoragePort, CatalogAssetStoragePort {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3PrivateObjectStorageAdapter(
            @Value("${market-shop.object-storage.endpoint}") String endpoint,
            @Value("${market-shop.object-storage.access-key}") String accessKey,
            @Value("${market-shop.object-storage.secret-key}") String secretKey,
            @Value("${market-shop.object-storage.bucket}") String bucket,
            @Value("${market-shop.object-storage.region}") String region
    ) {
        URI endpointUri = URI.create(endpoint);
        Region storageRegion = Region.of(region);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)
        );
        S3Configuration pathStyle = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
        this.client = S3Client.builder()
                .endpointOverride(endpointUri)
                .region(storageRegion)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpointUri)
                .region(storageRegion)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        this.bucket = bucket;
    }

    @Override
    public StoredObject put(long orderId, String originalFilename, String mediaType, byte[] bytes) {
        String objectKey = "orders/" + orderId + "/"
                + UUID.randomUUID() + "-" + safeFilename(originalFilename);
        try {
            ensureBucket();
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(mediaType)
                            .build(),
                    RequestBody.fromBytes(bytes)
            );
            return new StoredObject(objectKey, sha256(bytes), bytes.length);
        } catch (Exception exception) {
            throw new DomainException("OBJECT_STORAGE_FAILED", "付款凭证存储失败，请稍后重试");
        }
    }

    @Override
    public StoredAsset put(String originalFilename, String mediaType, byte[] bytes) {
        String objectKey = "catalog/" + UUID.randomUUID() + "-" + safeFilename(originalFilename);
        try {
            ensureBucket();
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(mediaType)
                            .build(),
                    RequestBody.fromBytes(bytes)
            );
            return new StoredAsset(objectKey, sha256(bytes), bytes.length);
        } catch (Exception exception) {
            throw new DomainException("CATALOG_ASSET_STORAGE_FAILED", "商品素材存储失败，请稍后重试");
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .build())
                    .asByteArray();
        } catch (Exception exception) {
            throw new DomainException("CATALOG_ASSET_READ_FAILED", "商品素材读取失败");
        }
    }

    @Override
    public void deleteAsset(String objectKey) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new DomainException("CATALOG_ASSET_DELETE_FAILED", "商品素材删除失败");
        }
    }

    @Override
    public String signedGetUrl(String objectKey, Duration duration) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build();
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .getObjectRequest(getObjectRequest)
                            .signatureDuration(duration)
                            .build())
                    .url()
                    .toExternalForm();
        } catch (Exception exception) {
            throw new DomainException("OBJECT_SIGNING_FAILED", "付款凭证访问链接生成失败");
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new DomainException("OBJECT_DELETE_FAILED", "付款凭证清理失败，将由任务重试");
        }
    }

    private void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw exception;
            }
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
                // Concurrent first uploads may both observe a missing bucket.
            }
        }
    }

    @PreDestroy
    void closeClients() {
        presigner.close();
        client.close();
    }

    private static String safeFilename(String filename) {
        String value = filename == null ? "proof" : filename;
        value = value.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase(Locale.ROOT);
        return value.isBlank() ? "proof" : value.substring(0, Math.min(value.length(), 100));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
