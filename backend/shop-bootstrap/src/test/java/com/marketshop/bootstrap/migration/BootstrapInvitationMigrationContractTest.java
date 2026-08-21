package com.marketshop.bootstrap.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapInvitationMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V19_1__make_bootstrap_invitation_single_use.sql";

    @Test
    void addsAnExplicitBootstrapMarkerWithoutReclassifyingExistingRows() throws IOException {
        String sql = migration()
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("alter table customer_invitation_code")
                .contains("add column is_bootstrap tinyint(1) not null default 0")
                .contains("alter table iam_bootstrap_sponsor_claim")
                .contains("add column bootstrap_invitation_id bigint unsigned null")
                .contains("add column invitation_repair_required tinyint(1) not null default 1")
                .contains("unique key uk_bootstrap_claim_invitation")
                .contains("foreign key (bootstrap_invitation_id)")
                .contains("create table iam_bootstrap_invitation_repair_guard")
                .contains("insert into iam_bootstrap_invitation_repair_guard")
                .contains("exists (select 1 from iam_user_account)")
                .contains("existing claims remain unresolved")
                .contains("update iam_bootstrap_sponsor_claim")
                .contains("bootstrap_invitation_id = null")
                .contains("invitation_repair_required = 1")
                .doesNotContain("update customer_invitation_code");
    }

    private static String migration() throws IOException {
        try (InputStream stream = BootstrapInvitationMigrationContractTest.class
                .getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as(MIGRATION).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
