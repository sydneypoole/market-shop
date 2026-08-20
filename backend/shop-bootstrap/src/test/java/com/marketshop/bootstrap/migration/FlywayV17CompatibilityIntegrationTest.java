package com.marketshop.bootstrap.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayV17CompatibilityIntegrationTest {

    private static final String MIGRATION = "db/migration/V17__aftersale_completed_unique_and_order_timeouts.sql";
    private static final String EXPECTED_SHA256 = "49c44fb8ba61d5a667d5a9bd86dbcf3c70a64cc612493c23146f2ab97973da0f";

    @Test
    void v17SourceChecksumRemainsImmutable() throws IOException, NoSuchAlgorithmException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as(MIGRATION).isNotNull();
            assertThat(hex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())))
                    .isEqualTo(EXPECTED_SHA256);
        }
    }

    @Test
    void v17KeepsTheGeneratedColumnAndTimeoutBackfillContract() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as(MIGRATION).isNotNull();
            String sql = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("ADD COLUMN completed_order_id BIGINT")
                    .contains("GENERATED ALWAYS AS (CASE WHEN status = 'COMPLETED' THEN order_id ELSE NULL END) STORED")
                    .contains("ADD UNIQUE KEY uk_after_sale_completed_order (completed_order_id)")
                    .contains("pendingSuperiorTimeoutDays")
                    .contains("pendingAdminReviewTimeoutDays")
                    .contains("pendingShipmentTimeoutDays");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
