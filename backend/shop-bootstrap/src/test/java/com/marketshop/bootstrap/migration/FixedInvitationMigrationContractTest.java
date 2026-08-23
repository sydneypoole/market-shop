package com.marketshop.bootstrap.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class FixedInvitationMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V20__make_member_invitations_fixed.sql";

    @Test
    void enablesBuiltInLevelsAndNormalizesOnlyActiveOrdinaryInvitations() throws IOException {
        String sql = migration().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("update membership_level set invitation_enabled = 1")
                .contains("'basic', 'experience_officer', 'super_member', 'dividend_member'")
                .contains("update customer_invitation_code set expires_at = null, max_uses = null")
                .contains("is_bootstrap = 0")
                .contains("status = 'active'")
                .doesNotContain("update customer_invitation_code set status")
                .doesNotContain("is_bootstrap = 1");
    }

    private static String migration() throws IOException {
        try (InputStream stream = FixedInvitationMigrationContractTest.class
                .getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as(MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
