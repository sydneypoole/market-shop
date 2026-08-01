package com.marketshop.bootstrap.health;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.net.URI;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

@Component("objectStorageHealthIndicator")
public class ObjectStorageHealthIndicator implements HealthIndicator {

    private final String provider;
    private final Path localRoot;
    private final S3Client s3Client;
    private final String bucket;
    private final boolean ownsClient;

    @Autowired
    public ObjectStorageHealthIndicator(
            @Value("${market-shop.object-storage.provider:s3}") String provider,
            @Value("${market-shop.object-storage.local.root:./data/uploads}") String localRoot,
            @Value("${market-shop.object-storage.endpoint:http://127.0.0.1:9000}") String endpoint,
            @Value("${market-shop.object-storage.access-key:}") String accessKey,
            @Value("${market-shop.object-storage.secret-key:}") String secretKey,
            @Value("${market-shop.object-storage.bucket:market-shop-private}") String bucket,
            @Value("${market-shop.object-storage.region:us-east-1}") String region,
            @Value("${market-shop.object-storage.health-timeout-seconds:4}") long healthTimeoutSeconds
    ) {
        this.provider = normalizeProvider(provider);
        this.localRoot = Path.of(localRoot).toAbsolutePath().normalize();
        this.bucket = bucket;
        if ("s3".equals(this.provider)) {
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)
            );
            this.s3Client = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of(region))
                    .credentialsProvider(credentials)
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(Duration.ofSeconds(Math.max(2, Math.min(10, healthTimeoutSeconds))))
                            .apiCallAttemptTimeout(Duration.ofSeconds(Math.max(1, Math.min(5, healthTimeoutSeconds - 1))))
                            .build())
                    .build();
            this.ownsClient = true;
        } else {
            this.s3Client = null;
            this.ownsClient = false;
        }
    }

    ObjectStorageHealthIndicator(String provider, Path localRoot, S3Client s3Client, String bucket) {
        this.provider = normalizeProvider(provider);
        this.localRoot = localRoot.toAbsolutePath().normalize();
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.ownsClient = false;
    }

    @Override
    public Health health() {
        try {
            if ("local".equals(provider)) {
                probeLocalStorage();
            } else if ("s3".equals(provider)) {
                probeS3Storage();
            } else {
                return Health.down().withDetail("provider", "unsupported").build();
            }
            return Health.up().withDetail("provider", provider).build();
        } catch (Exception exception) {
            return Health.down().withDetail("provider", provider).build();
        }
    }

    private void probeLocalStorage() throws Exception {
        Files.createDirectories(localRoot);
        if (!Files.isDirectory(localRoot) || !Files.isReadable(localRoot) || !Files.isWritable(localRoot)) {
            throw new IllegalStateException("local object storage is not readable and writable");
        }
        Path probe = localRoot.resolve(".readiness-" + UUID.randomUUID());
        try {
            Files.writeString(probe, "ready", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (!"ready".equals(Files.readString(probe))) {
                throw new IllegalStateException("local object storage readiness probe mismatch");
            }
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private void probeS3Storage() {
        HeadBucketRequest request = HeadBucketRequest.builder().bucket(bucket).build();
        s3Client.headBucket(request);
    }

    @PreDestroy
    void close() {
        if (ownsClient && s3Client != null) {
            s3Client.close();
        }
    }

    private static String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
