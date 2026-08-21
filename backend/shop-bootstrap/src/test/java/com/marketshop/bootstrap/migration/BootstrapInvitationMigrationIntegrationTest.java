package com.marketshop.bootstrap.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class BootstrapInvitationMigrationIntegrationTest {

    private static final long SPONSOR_ID = 1001L;
    private static final String BOOTSTRAP_CODE = "LEGACY-BOOTSTRAP";
    private static final String ORDINARY_CODE = "ORDINARY-SPONSOR";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("market_shop_bootstrap_migration")
            .withUsername("market_shop")
            .withPassword("market_shop");

    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc = new JdbcTemplate(dataSource());
        flyway().clean();
    }

    @Test
    void upgradesV19WithoutAClaimByRaisingTheGlobalRepairGuard() {
        flyway("19").migrate();
        seedLegacyInvitationWithoutClaim();

        Flyway latest = flyway();
        latest.migrate();

        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("19.1");
        assertThat(jdbc.queryForObject(
                "SELECT repair_required FROM iam_bootstrap_invitation_repair_guard WHERE id = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_bootstrap_sponsor_claim", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(is_bootstrap, ':', COALESCE(max_uses, 'NULL'), ':', use_count, ':', status)
                FROM customer_invitation_code WHERE code = ?
                """, String.class, BOOTSTRAP_CODE)).isEqualTo("0:NULL:1:ACTIVE");
    }

    @Test
    void upgradesV19WithoutGuessingBootstrapOrOrdinaryInvitationLinks() {
        flyway("19").migrate();
        seedLegacyBootstrapClaimAndInvitations();

        Flyway latest = flyway();
        latest.migrate();

        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("19.1");
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(invitation_repair_required, ':', bootstrap_invitation_id IS NULL)
                FROM iam_bootstrap_sponsor_claim
                WHERE sponsor_user_id = ?
                """, String.class, SPONSOR_ID)).isEqualTo("1:1");
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(is_bootstrap, ':', COALESCE(max_uses, 'NULL'), ':', use_count, ':', status)
                FROM customer_invitation_code
                WHERE code = ?
                """, String.class, BOOTSTRAP_CODE)).isEqualTo("0:NULL:1:ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(is_bootstrap, ':', COALESCE(max_uses, 'NULL'), ':', use_count, ':', status)
                FROM customer_invitation_code
                WHERE code = ?
                """, String.class, ORDINARY_CODE)).isEqualTo("0:NULL:0:ACTIVE");
    }

    private void seedLegacyBootstrapClaimAndInvitations() {
        seedLegacyInvitationWithoutClaim();
        jdbc.update("""
                INSERT INTO iam_bootstrap_sponsor_claim
                    (sponsor_user_id, status, claim_secret_hash)
                VALUES (?, 'PENDING', ?)
                """, SPONSOR_ID, "a".repeat(64));
    }

    private void seedLegacyInvitationWithoutClaim() {
        jdbc.update("""
                INSERT INTO iam_user_account (id, public_id, status, nickname)
                VALUES (?, '01BOOTSTRAPSPONSOR00000001', 'ACTIVE', '商城发起人')
                """, SPONSOR_ID);
        jdbc.update("""
                INSERT INTO membership_account (user_id, current_level_id, qualified_at)
                VALUES (?, 3, CURRENT_TIMESTAMP(3))
                """, SPONSOR_ID);
        jdbc.update("""
                INSERT INTO customer_invitation_code
                    (code, inviter_user_id, status, max_uses, use_count)
                VALUES (?, ?, 'ACTIVE', NULL, 1), (?, ?, 'ACTIVE', NULL, 0)
                """, BOOTSTRAP_CODE, SPONSOR_ID, ORDINARY_CODE, SPONSOR_ID);
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource())
                .cleanDisabled(false)
                .load();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource())
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private DataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(MYSQL.getJdbcUrl());
        source.setUsername(MYSQL.getUsername());
        source.setPassword(MYSQL.getPassword());
        return source;
    }
}
