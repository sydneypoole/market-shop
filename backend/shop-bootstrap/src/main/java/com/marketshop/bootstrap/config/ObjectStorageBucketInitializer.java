package com.marketshop.bootstrap.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.time.Duration;

@Component
@ConditionalOnProperty(
        prefix = "market-shop.object-storage",
        name = "create-bucket",
        havingValue = "true"
)
public final class ObjectStorageBucketInitializer implements ApplicationRunner {

    private final String provider;
    private final S3Client client;
    private final String bucket;

    @Autowired
    public ObjectStorageBucketInitializer(
            @Value("${market-shop.object-storage.provider:s3}") String provider,
            @Value("${market-shop.object-storage.endpoint:http://127.0.0.1:9000}") String endpoint,
            @Value("${market-shop.object-storage.access-key:}") String accessKey,
            @Value("${market-shop.object-storage.secret-key:}") String secretKey,
            @Value("${market-shop.object-storage.bucket:market-shop-private}") String bucket,
            @Value("${market-shop.object-storage.region:us-east-1}") String region,
            @Value("${market-shop.object-storage.api-timeout-seconds:15}") long apiTimeoutSeconds
    ) {
        this.provider = provider;
        this.bucket = bucket;
        if ("s3".equalsIgnoreCase(provider)) {
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)
            );
            this.client = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of(region))
                    .credentialsProvider(credentials)
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(boundedApiTimeout(apiTimeoutSeconds))
                            .apiCallAttemptTimeout(boundedApiTimeout(apiTimeoutSeconds).minusSeconds(1))
                            .build())
                    .build();
        } else {
            this.client = null;
        }
    }

    ObjectStorageBucketInitializer(String provider, S3Client client, String bucket) {
        this.provider = provider;
        this.client = client;
        this.bucket = bucket;
    }

    static Duration boundedApiTimeout(long seconds) {
        return Duration.ofSeconds(Math.max(5, Math.min(60, seconds)));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"s3".equalsIgnoreCase(provider)) {
            return;
        }
        try {
            initializeBucket();
        } finally {
            client.close();
        }
    }

    private void initializeBucket() {
        HeadBucketRequest head = HeadBucketRequest.builder().bucket(bucket).build();
        try {
            client.headBucket(head);
            return;
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw exception;
            }
        }
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
            // Another explicitly enabled initializer won the first-start race.
        }
        client.headBucket(head);
    }
}
