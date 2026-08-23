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
class FixedInvitationMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("market_shop_fixed_invitation")
            .withUsername("market_shop")
            .withPassword("market_shop");

    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareV19_1Database() {
        jdbc = new JdbcTemplate(dataSource());
        flyway().clean();
        flyway("19.1").migrate();
        jdbc.update("""
                INSERT INTO iam_user_account (id, public_id, status, nickname)
                VALUES (1001, '01FIXEDINVITATION000000001', 'ACTIVE', '固定邀请人')
                """);
        jdbc.update("UPDATE membership_level SET invitation_enabled = 0");
        jdbc.update("""
                INSERT INTO customer_invitation_code
                    (code, inviter_user_id, status, expires_at, max_uses, is_bootstrap)
                VALUES
                    ('ORDINARY-FIXED', 1001, 'ACTIVE', '2025-01-01 00:00:00.000', 3, 0),
                    ('BOOTSTRAP-ONE-USE', 1001, 'ACTIVE', '2027-01-01 00:00:00.000', 1, 1),
                    ('REVOKED-HISTORY', 1001, 'REVOKED', '2025-01-01 00:00:00.000', 4, 0)
                """);
    }

    @Test
    void migrationMakesOrdinaryCodesPermanentWithoutChangingBootstrapOrHistory() {
        Flyway latest = flyway();
        latest.migrate();

        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("21");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM membership_level
                WHERE code IN ('BASIC', 'EXPERIENCE_OFFICER', 'SUPER_MEMBER', 'DIVIDEND_MEMBER')
                  AND invitation_enabled = 1
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(expires_at IS NULL, ':', max_uses IS NULL, ':', is_bootstrap)
                FROM customer_invitation_code WHERE code = 'ORDINARY-FIXED'
                """, String.class)).isEqualTo("1:1:0");
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(expires_at IS NULL, ':', max_uses, ':', is_bootstrap)
                FROM customer_invitation_code WHERE code = 'BOOTSTRAP-ONE-USE'
                """, String.class)).isEqualTo("0:1:1");
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(expires_at IS NULL, ':', max_uses, ':', status)
                FROM customer_invitation_code WHERE code = 'REVOKED-HISTORY'
                """, String.class)).isEqualTo("0:4:REVOKED");
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
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
