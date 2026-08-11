package com.marketshop.infrastructure.storage;

import com.marketshop.application.catalog.CatalogAssetStoragePort;
import com.marketshop.application.catalog.CatalogAssetStoragePort.StoredAsset;
import com.marketshop.application.identity.IdentityAvatarStoragePort;
import com.marketshop.application.identity.IdentityAvatarStoragePort.StoredAvatar;
import com.marketshop.application.proof.OrderProofPorts.PrivateObjectStoragePort;
import com.marketshop.application.proof.OrderProofPorts.StoredObject;
import com.marketshop.domain.shared.DomainException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
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
import java.time.Duration;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "market-shop.object-storage",
        name = "provider",
        havingValue = "s3",
        matchIfMissing = true
)
public class S3PrivateObjectStorageAdapter
        implements PrivateObjectStoragePort, CatalogAssetStoragePort, IdentityAvatarStoragePort {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final boolean createBucket;

    /**
     * The runtime adapter must opt into bucket creation explicitly.  Keeping a
     * five-argument overload makes local/integration callers source-compatible,
     * while production Spring wiring always uses the property-aware constructor
     * below (whose default is fail-closed).
     */
    public S3PrivateObjectStorageAdapter(
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket,
            String region
    ) {
        this(endpoint, accessKey, secretKey, bucket, region, false);
    }

    public S3PrivateObjectStorageAdapter(
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket,
            String region,
            boolean createBucket
    ) {
        this(createClients(endpoint, accessKey, secretKey, region, 15), bucket, createBucket);
    }

    @Autowired
    public S3PrivateObjectStorageAdapter(
            @Value("${market-shop.object-storage.endpoint}") String endpoint,
            @Value("${market-shop.object-storage.access-key}") String accessKey,
            @Value("${market-shop.object-storage.secret-key}") String secretKey,
            @Value("${market-shop.object-storage.bucket}") String bucket,
            @Value("${market-shop.object-storage.region}") String region,
            @Value("${market-shop.object-storage.create-bucket:false}") boolean createBucket,
            @Value("${market-shop.object-storage.api-timeout-seconds:15}") long apiTimeoutSeconds
    ) {
        this(createClients(endpoint, accessKey, secretKey, region, apiTimeoutSeconds), bucket, createBucket);
    }

    private S3PrivateObjectStorageAdapter(ClientBundle clients, String bucket, boolean createBucket) {
        this.client = clients.client();
        this.presigner = clients.presigner();
        this.bucket = bucket;
        this.createBucket = createBucket;
    }

    private static ClientBundle createClients(
            String endpoint,
            String accessKey,
            String secretKey,
            String region,
            long apiTimeoutSeconds
    ) {
        URI endpointUri = URI.create(endpoint);
        Region storageRegion = Region.of(region);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)
        );
        S3Configuration pathStyle = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();
        Duration apiTimeout = Duration.ofSeconds(Math.max(5, Math.min(60, apiTimeoutSeconds)));
        ClientOverrideConfiguration timeoutConfiguration = ClientOverrideConfiguration.builder()
                .apiCallTimeout(apiTimeout)
                .apiCallAttemptTimeout(apiTimeout.minusSeconds(1))
                .build();
        S3Client client = S3Client.builder()
                .endpointOverride(endpointUri)
                .region(storageRegion)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .overrideConfiguration(timeoutConfiguration)
                .build();
        S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(endpointUri)
                .region(storageRegion)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        return new ClientBundle(client, presigner);
    }

    S3PrivateObjectStorageAdapter(S3Client client, S3Presigner presigner,
                                  String bucket, boolean createBucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.createBucket = createBucket;
    }

    private record ClientBundle(S3Client client, S3Presigner presigner) {
    }

    @Override
    public StoredObject put(long orderId, String originalFilename, String mediaType, byte[] bytes) {
        String objectKey = "orders/" + orderId + "/"
                + UUID.randomUUID() + "-" + StorageSupport.safeFilename(originalFilename, "proof");
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
            return new StoredObject(objectKey, StorageSupport.sha256(bytes), bytes.length);
        } catch (Exception exception) {
            throw new DomainException("OBJECT_STORAGE_FAILED", "付款凭证存储失败，请稍后重试", exception);
        }
    }

    @Override
    public StoredAsset put(String originalFilename, String mediaType, byte[] bytes) {
        String objectKey = "catalog/" + UUID.randomUUID() + "-"
                + StorageSupport.safeFilename(originalFilename, "image");
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
            return new StoredAsset(objectKey, StorageSupport.sha256(bytes), bytes.length);
        } catch (Exception exception) {
            throw new DomainException("CATALOG_ASSET_STORAGE_FAILED", "商品素材存储失败，请稍后重试", exception);
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
            throw new DomainException("CATALOG_ASSET_READ_FAILED", "商品素材读取失败", exception);
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
            throw new DomainException("CATALOG_ASSET_DELETE_FAILED", "商品素材删除失败", exception);
        }
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
            return new StoredAvatar(objectKey, StorageSupport.sha256(bytes), bytes.length);
        } catch (Exception exception) {
            throw new DomainException("AVATAR_STORAGE_FAILED", "会员头像存储失败，请稍后重试");
        }
    }

    @Override
    public byte[] readAvatar(String objectKey) {
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .build())
                    .asByteArray();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new DomainException("MEMBER_AVATAR_NOT_FOUND", "会员头像不存在");
            }
            throw new DomainException("AVATAR_READ_FAILED", "会员头像读取失败");
        } catch (Exception exception) {
            throw new DomainException("AVATAR_READ_FAILED", "会员头像读取失败");
        }
    }

    @Override
    public void deleteAvatar(String objectKey) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new DomainException("AVATAR_DELETE_FAILED", "会员头像清理失败");
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
            throw new DomainException("OBJECT_SIGNING_FAILED", "付款凭证访问链接生成失败", exception);
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
            throw new DomainException("OBJECT_DELETE_FAILED", "付款凭证清理失败，将由任务重试", exception);
        }
    }

    private void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404 || !createBucket) {
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

}
