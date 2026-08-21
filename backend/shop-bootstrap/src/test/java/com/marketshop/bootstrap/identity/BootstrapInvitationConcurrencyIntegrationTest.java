package com.marketshop.bootstrap.identity;

import com.marketshop.application.identity.IdentityPorts.RegistrationResult;
import com.marketshop.application.identity.IdentityPorts.WeChatIdentity;
import com.marketshop.application.identity.IdentityPorts.UserIdentityPort;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.infrastructure.identity.BootstrapIdentityInitializer;
import com.marketshop.infrastructure.persistence.mapper.IdentityMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(BootstrapInvitationConcurrencyIntegrationTest.TestConfiguration.class)
class BootstrapInvitationConcurrencyIntegrationTest {

    private static final long SPONSOR_ID = 1001L;
    private static final String INVITE_CODE = "BOOTSTRAP-CONCURRENT";
    private static final String REPAIR_FAILURE_TRIGGER = "bootstrap_repair_test_failure";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("market_shop_bootstrap_invite")
            .withUsername("market_shop")
            .withPassword("market_shop");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserIdentityPort identityPort;

    @Autowired
    @Qualifier("bootstrapInitializerMissingCode")
    private ApplicationRunner bootstrapInitializerMissingCode;

    @Autowired
    @Qualifier("bootstrapInitializerWrongCode")
    private ApplicationRunner bootstrapInitializerWrongCode;

    @Autowired
    @Qualifier("configuredBootstrapInitializer")
    private ApplicationRunner configuredBootstrapInitializer;

    @BeforeEach
    void resetDatabase() {
        Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .cleanDisabled(false)
                .load()
                .migrate();
        jdbc.update("""
                INSERT INTO iam_user_account (id, public_id, status, nickname)
                VALUES (?, '01BOOTSTRAPSPONSOR00000001', 'ACTIVE', 'bootstrap sponsor')
                """, SPONSOR_ID);
        jdbc.update("""
                INSERT INTO membership_account (user_id, current_level_id, qualified_at)
                VALUES (?, 3, CURRENT_TIMESTAMP(3))
                """, SPONSOR_ID);
        jdbc.update("""
                INSERT INTO customer_invitation_code
                    (code, inviter_user_id, status, max_uses, is_bootstrap)
                VALUES (?, ?, 'ACTIVE', 1, 1)
                """, INVITE_CODE, SPONSOR_ID);
        Long invitationId = jdbc.queryForObject("""
                SELECT id FROM customer_invitation_code WHERE code = ?
                """, Long.class, INVITE_CODE);
        jdbc.update("""
                INSERT INTO iam_bootstrap_sponsor_claim
                    (sponsor_user_id, bootstrap_invitation_id, status, claim_secret_hash,
                     invitation_repair_required)
                VALUES (?, ?, 'PENDING', ?, 0)
                """, SPONSOR_ID, invitationId, "b".repeat(64));
    }

    @Test
    void concurrentFirstRegistrationsProduceOneMemberAndTerminalizeTheBootstrapCode() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<RegistrationResult> first = workers.submit(
                    () -> registerAfter(start, "concurrent-open-a"));
            Future<RegistrationResult> second = workers.submit(
                    () -> registerAfter(start, "concurrent-open-b"));
            start.countDown();

            List<RegistrationResult> successes = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            collect(first, successes, failures);
            collect(second, successes, failures);

            assertThat(successes).singleElement().extracting(RegistrationResult::newlyRegistered)
                    .isEqualTo(true);
            assertThat(failures).singleElement().isInstanceOf(DomainException.class);
            assertThat(((DomainException) failures.getFirst()).code())
                    .isIn("INVITE_CODE_INVALID", "INVITE_CODE_EXHAUSTED");
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_user_account WHERE id <> ?", Integer.class, SPONSOR_ID
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_relation", Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(status, ':', use_count, ':', max_uses, ':', is_bootstrap)
                FROM customer_invitation_code
                WHERE code = ?
                """, String.class, INVITE_CODE)).isEqualTo("REVOKED:1:1:1");
    }

    @Test
    void unresolvedRepairBlocksNewRegistrationWhenDisabledAndCodeIsMissingOrWrong() throws Exception {
        prepareLegacyUnresolved(0);
        bootstrapInitializerMissingCode.run(null);

        assertThatThrownBy(() -> identityPort.findOrRegister(
                new WeChatIdentity("WECHAT_MP", "fixture-app", "blocked-open", null, null, null),
                INVITE_CODE,
                null
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("BOOTSTRAP_INVITATION_REPAIR_REQUIRED"));

        bootstrapInitializerWrongCode.run(null);
        assertThatThrownBy(() -> identityPort.findOrRegister(
                new WeChatIdentity("WECHAT_MP", "fixture-app", "blocked-open-2", null, null, null),
                INVITE_CODE,
                null
        )).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.code())
                        .isEqualTo("BOOTSTRAP_INVITATION_REPAIR_REQUIRED"));
    }

    @Test
    void legacyInvitationWithoutClaimNeedsIndependentSecretToCreateTheExactAssociation() throws Exception {
        jdbc.update("DELETE FROM iam_bootstrap_sponsor_claim WHERE sponsor_user_id = ?", SPONSOR_ID);
        prepareLegacyUnresolved(0);

        configuredBootstrapInitializer.run(null);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM iam_bootstrap_sponsor_claim
                WHERE sponsor_user_id = ?
                  AND invitation_repair_required = 0
                  AND bootstrap_invitation_id = (
                      SELECT id FROM customer_invitation_code WHERE code = ?
                  )
                """, Integer.class, SPONSOR_ID, INVITE_CODE)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(is_bootstrap, ':', max_uses, ':', status)
                FROM customer_invitation_code WHERE code = ?
                """, String.class, INVITE_CODE)).isEqualTo("1:1:ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT repair_required FROM iam_bootstrap_invitation_repair_guard WHERE id = 1",
                Integer.class)).isZero();
    }

    @Test
    void legacyRepairMarksOnlyTheConfiguredCodeAndSealsAPreviouslyUsedRow() throws Exception {
        prepareLegacyUnresolved(1);
        jdbc.update("""
                INSERT INTO customer_invitation_code
                    (code, inviter_user_id, status, max_uses, is_bootstrap)
                VALUES ('ORDINARY-SPONSOR-CODE', ?, 'ACTIVE', NULL, 0)
                """, SPONSOR_ID);
        configuredBootstrapInitializer.run(null);
        configuredBootstrapInitializer.run(null);

        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(status, ':', use_count, ':', max_uses, ':', is_bootstrap)
                FROM customer_invitation_code
                WHERE code = ?
                """, String.class, INVITE_CODE)).isEqualTo("REVOKED:1:1:1");
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(status, ':', COALESCE(max_uses, 'NULL'), ':', is_bootstrap)
                FROM customer_invitation_code
                WHERE code = 'ORDINARY-SPONSOR-CODE'
                """, String.class)).isEqualTo("ACTIVE:NULL:0");
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(invitation_repair_required, ':', bootstrap_invitation_id IS NOT NULL)
                FROM iam_bootstrap_sponsor_claim
                WHERE sponsor_user_id = ?
                """, String.class, SPONSOR_ID)).isEqualTo("0:1");
        assertThat(jdbc.queryForObject("""
                SELECT repair_required FROM iam_bootstrap_invitation_repair_guard WHERE id = 1
                """, Integer.class)).isZero();
    }

    @Test
    void managedRepairRollsBackEarlierWritesWhenTheFinalGuardUpdateFails() {
        prepareLegacyUnresolved(1);
        jdbc.execute("DROP TRIGGER IF EXISTS " + REPAIR_FAILURE_TRIGGER);
        jdbc.execute("""
                CREATE TRIGGER %s
                BEFORE UPDATE ON iam_bootstrap_invitation_repair_guard
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'intentional bootstrap repair failure'
                """.formatted(REPAIR_FAILURE_TRIGGER));
        try {
            assertThat(AopUtils.isAopProxy(configuredBootstrapInitializer)).isTrue();
            assertThatThrownBy(() -> configuredBootstrapInitializer.run(null))
                    .isInstanceOf(RuntimeException.class);

            assertThat(jdbc.queryForObject("""
                    SELECT CONCAT(status, ':', use_count, ':', COALESCE(max_uses, 'NULL'), ':', is_bootstrap)
                    FROM customer_invitation_code
                    WHERE code = ?
                    """, String.class, INVITE_CODE)).isEqualTo("ACTIVE:1:NULL:0");
            assertThat(jdbc.queryForObject("""
                    SELECT CONCAT(invitation_repair_required, ':', bootstrap_invitation_id IS NULL)
                    FROM iam_bootstrap_sponsor_claim
                    WHERE sponsor_user_id = ?
                    """, String.class, SPONSOR_ID)).isEqualTo("1:1");
            assertThat(jdbc.queryForObject("""
                    SELECT CONCAT(repair_required, ':', version)
                    FROM iam_bootstrap_invitation_repair_guard
                    WHERE id = 1
                    """, String.class)).isEqualTo("1:1");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + REPAIR_FAILURE_TRIGGER);
        }
    }

    private void prepareLegacyUnresolved(int useCount) {
        jdbc.update("""
                UPDATE customer_invitation_code
                SET is_bootstrap = 0, max_uses = NULL, use_count = ?, status = 'ACTIVE'
                WHERE code = ?
                """, useCount, INVITE_CODE);
        jdbc.update("""
                UPDATE iam_bootstrap_sponsor_claim
                SET bootstrap_invitation_id = NULL,
                    invitation_repair_required = 1,
                    claim_secret_hash = ?,
                    version = version + 1
                WHERE sponsor_user_id = ?
                """, "a".repeat(64), SPONSOR_ID);
        jdbc.update("""
                UPDATE iam_bootstrap_invitation_repair_guard
                SET repair_required = 1, version = version + 1
                WHERE id = 1
                """);
    }

    private RegistrationResult registerAfter(CountDownLatch start, String openId) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return identityPort.findOrRegister(
                new WeChatIdentity("WECHAT_MP", "fixture-app", openId, null, null, null),
                INVITE_CODE,
                null
        );
    }

    private static void collect(
            Future<RegistrationResult> future,
            List<RegistrationResult> successes,
            List<Throwable> failures
    ) throws Exception {
        try {
            successes.add(future.get(10, TimeUnit.SECONDS));
        } catch (java.util.concurrent.ExecutionException exception) {
            failures.add(exception.getCause());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @MapperScan(basePackages = "com.marketshop.infrastructure.persistence.mapper")
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            return DataSourceBuilder.create()
                    .driverClassName("com.mysql.cj.jdbc.Driver")
                    .url(MYSQL.getJdbcUrl())
                    .username(MYSQL.getUsername())
                    .password(MYSQL.getPassword())
                    .build();
        }

        @Bean(initMethod = "migrate")
        Flyway flyway(DataSource dataSource) {
            return Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            org.apache.ibatis.session.Configuration configuration =
                    new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean(name = "bootstrapInitializerMissingCode")
        BootstrapIdentityInitializer bootstrapInitializerMissingCode(IdentityMapper mapper) {
            return new BootstrapIdentityInitializer(
                    mapper, false, "admin", "", "", "", true
            );
        }

        @Bean(name = "bootstrapInitializerWrongCode")
        BootstrapIdentityInitializer bootstrapInitializerWrongCode(IdentityMapper mapper) {
            return new BootstrapIdentityInitializer(
                    mapper, false, "admin", "", "WRONG-CODE", "", true
            );
        }

        @Bean(name = "configuredBootstrapInitializer")
        BootstrapIdentityInitializer configuredBootstrapInitializer(IdentityMapper mapper) {
            return new BootstrapIdentityInitializer(
                    mapper,
                    false,
                    "admin",
                    "",
                    INVITE_CODE,
                    "owner-only-claim-secret-2026-abcdef",
                    true
            );
        }

        @Bean
        UserIdentityPort identityAdapter(IdentityMapper mapper) {
            return new com.marketshop.infrastructure.identity.MyBatisIdentityAdapter(mapper);
        }
    }
}
