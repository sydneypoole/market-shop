package com.marketshop.bootstrap.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WechatMemberProfileMigrationContractTest {

    private static final String MIGRATION = "db/migration/V15__add_wechat_member_profile.sql";

    @Test
    void addsNullableProfileMetadataWithoutPersistingARawPhoneNumber() throws IOException {
        String sql = migration();

        assertThat(sql)
                .contains("phone_verified_at TIMESTAMP(3) NULL")
                .contains("avatar_object_key VARCHAR(500) NULL")
                .contains("avatar_media_type VARCHAR(80) NULL")
                .contains("avatar_sha256 CHAR(64) NULL")
                .contains("avatar_size_bytes BIGINT UNSIGNED NULL")
                .contains("avatar_updated_at TIMESTAMP(3) NULL")
                .contains("chk_iam_user_phone_verification")
                .doesNotContain("phone_number")
                .doesNotContain("pure_phone");
    }

    @Test
    void replacesTheHistoricalClaimConstraintWithMiniprogramProviderSupport() throws IOException {
        String sql = migration();

        assertThat(sql)
                .contains("DROP CHECK chk_bootstrap_claim_transition_data")
                .contains("ADD CONSTRAINT chk_bootstrap_claim_transition_data")
                .contains("claimed_provider IN ('WECHAT_H5', 'WECHAT_WEB', 'WECHAT_MP')");
    }

    private String migration() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as(MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
