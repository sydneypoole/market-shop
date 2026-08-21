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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WechatMemberProfileMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("market_shop_profile_migration")
            .withUsername("market_shop")
            .withPassword("market_shop");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(MYSQL.getJdbcUrl());
        source.setUsername(MYSQL.getUsername());
        source.setPassword(MYSQL.getPassword());
        dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        flyway().clean();
    }

    @Test
    void freshDatabaseMigratesFromV1ToLatestWithEnforcedProfileConstraints() {
        Flyway flyway = flyway();

        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("15");
        long userId = insertMember("01JPROFILEFRESH00000000001", "新会员");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE iam_user_account
                SET phone_masked = '138****8000'
                WHERE id = ?
                """, userId)).isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(jdbc.update("""
                UPDATE iam_user_account
                SET phone_masked = '138****8000', phone_verified_at = CURRENT_TIMESTAMP(3)
                WHERE id = ?
                """, userId)).isEqualTo(1);
        assertThat(jdbc.update("""
                INSERT INTO iam_bootstrap_sponsor_claim
                    (sponsor_user_id, status, claim_secret_hash,
                     claimed_provider, claimed_app_id, claimed_at)
                VALUES (?, 'CLAIMED', NULL, 'WECHAT_MP', 'wx-profile-test', CURRENT_TIMESTAMP(3))
                """, userId)).isEqualTo(1);
    }

    @Test
    void populatedV14DatabaseUpgradesForwardAndAllowsAWechatMpClaim() {
        Flyway legacy = Flyway.configure()
                .dataSource(dataSource)
                .target("14")
                .cleanDisabled(false)
                .load();
        legacy.migrate();
        long userId = insertMember("01JPROFILELEGACY000000001", "旧会员");
        jdbc.update("""
                INSERT INTO iam_bootstrap_sponsor_claim
                    (sponsor_user_id, status, claim_secret_hash,
                     claimed_provider, claimed_app_id, claimed_at)
                VALUES (?, 'PENDING', ?, NULL, NULL, NULL)
                """, userId, "a".repeat(64));

        Flyway latest = flyway();
        latest.migrate();

        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("15");
        assertThat(jdbc.queryForMap("""
                SELECT phone_masked, phone_verified_at, avatar_object_key, avatar_media_type,
                       avatar_sha256, avatar_size_bytes, avatar_updated_at
                FROM iam_user_account
                WHERE id = ?
                """, userId).values()).containsOnlyNulls();
        assertThat(jdbc.update("""
                UPDATE iam_bootstrap_sponsor_claim
                SET status = 'CLAIMED', claim_secret_hash = NULL,
                    claimed_provider = 'WECHAT_MP', claimed_app_id = 'wx-upgrade-test',
                    claimed_at = CURRENT_TIMESTAMP(3)
                WHERE sponsor_user_id = ?
                """, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT claimed_provider
                FROM iam_bootstrap_sponsor_claim
                WHERE sponsor_user_id = ?
                """, String.class, userId)).isEqualTo("WECHAT_MP");
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .target("15")
                .cleanDisabled(false)
                .load();
    }

    private long insertMember(String publicId, String nickname) {
        jdbc.update("""
                INSERT INTO iam_user_account (public_id, status, nickname)
                VALUES (?, 'ACTIVE', ?)
                """, publicId, nickname);
        return jdbc.queryForObject(
                "SELECT id FROM iam_user_account WHERE public_id = ?",
                Long.class,
                publicId
        );
    }
}
