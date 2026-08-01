package com.marketshop.infrastructure.reliability;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.persistence.mapper.ReliabilityMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class OutboxFailureRecorder {

    private static final int MAX_ERROR_LENGTH = 1000;
    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);

    private final ReliabilityMapper mapper;
    private final int maxAttempts;
    private final long baseDelaySeconds;
    private final long maxDelaySeconds;

    public OutboxFailureRecorder(
            ReliabilityMapper mapper,
            @Value("${market-shop.jobs.outbox-max-attempts:5}") int maxAttempts,
            @Value("${market-shop.jobs.outbox-base-delay-seconds:5}") long baseDelaySeconds,
            @Value("${market-shop.jobs.outbox-max-delay-seconds:3600}") long maxDelaySeconds
    ) {
        this.mapper = mapper;
        this.maxAttempts = Math.max(maxAttempts, 1);
        this.baseDelaySeconds = Math.max(baseDelaySeconds, 1);
        this.maxDelaySeconds = Math.max(maxDelaySeconds, this.baseDelaySeconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureRecordResult record(OutboxProjectionFailure failure) {
        int nextAttempt = failure.attemptCount() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : failure.attemptCount() + 1;
        boolean dead = nextAttempt >= maxAttempts;
        long delaySeconds = dead ? 0 : backoffSeconds(nextAttempt);
        LocalDateTime nextAttemptAt = LocalDateTime.now(BUSINESS_ZONE).plusSeconds(delaySeconds);
        int updated = mapper.recordFailure(
                failure.outboxId(),
                failure.attemptCount(),
                nextAttemptAt,
                errorSummary(failure.getCause()),
                dead
        );
        return new FailureRecordResult(updated == 1, nextAttempt, dead ? "DEAD" : "PENDING", delaySeconds);
    }

    long backoffSeconds(int attempt) {
        int exponent = Math.max(attempt - 1, 0);
        if (exponent >= 62) return maxDelaySeconds;
        long multiplier = 1L << exponent;
        if (baseDelaySeconds > maxDelaySeconds / multiplier) return maxDelaySeconds;
        return Math.min(baseDelaySeconds * multiplier, maxDelaySeconds);
    }

    static String errorSummary(Throwable cause) {
        String summary;
        if (cause instanceof DomainException domain) {
            summary = domain.code() + ": " + safeMessage(domain.getMessage());
        } else if (cause == null) {
            summary = "ProjectionFailure";
        } else {
            summary = cause.getClass().getSimpleName() + ": projection failed";
        }
        return summary.substring(0, Math.min(summary.length(), MAX_ERROR_LENGTH));
    }

    private static String safeMessage(String value) {
        if (value == null || value.isBlank()) return "projection failed";
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    public record FailureRecordResult(boolean updated, int attemptCount, String status, long delaySeconds) {
    }
}
