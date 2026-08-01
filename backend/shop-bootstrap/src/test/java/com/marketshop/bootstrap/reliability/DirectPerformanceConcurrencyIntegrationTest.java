package com.marketshop.bootstrap.reliability;

import com.marketshop.infrastructure.persistence.mapper.DistributionMapper;
import com.marketshop.infrastructure.reliability.OutboxProjectionProcessor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(DirectPerformanceConcurrencyIntegrationTest.TestConfiguration.class)
class DirectPerformanceConcurrencyIntegrationTest {

    private static final long SUPERIOR_ID = 100;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("market_shop")
            .withUsername("market_shop")
            .withPassword("market_shop");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxProjectionProcessor processor;

    @Test
    void concurrentOrdersForSameSuperiorAllocateContinuousOrdinalsAndRewardTheSixth() throws Exception {
        migrateLegacyDuplicateOrdinals();
        seedProjectionFixture();

        try (Connection blocker = dataSource.getConnection();
             ExecutorService workers = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            lockSuperiorMembership(blocker);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch enteredProcessor = new CountDownLatch(2);
            Future<Boolean> first = workers.submit(() -> processAfter(start, enteredProcessor));
            Future<Boolean> second = workers.submit(() -> processAfter(start, enteredProcessor));
            start.countDown();

            assertThat(enteredProcessor.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);
            assertWorkerIsWaiting(first, "first");
            assertWorkerIsWaiting(second, "second");
            blocker.commit();

            assertThat(first.get()).isTrue();
            assertThat(second.get()).isTrue();
        }

        jdbcTemplate.update("""
                INSERT INTO sys_outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type, payload_json,
                     occurred_at, status, next_attempt_at)
                VALUES (?, 'ORDER', '1006', 'ORDER_COMPLETED', JSON_OBJECT(),
                        CURRENT_TIMESTAMP(3), 'PENDING', CURRENT_TIMESTAMP(3))
                """, UUID.randomUUID().toString());
        assertThat(processor.processNext()).isTrue();

        assertThat(jdbcTemplate.queryForList("""
                SELECT completed_ordinal
                FROM distribution_direct_performance
                WHERE beneficiary_user_id = ?
                ORDER BY completed_ordinal
                """, Integer.class, SUPERIOR_ID))
                .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_entry
                WHERE account_id = 100
                  AND entry_type = 'DIRECT_REFERRAL_AWARD'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_frozen_batch
                WHERE account_id = 100
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                SELECT status
                FROM sys_outbox_event
                ORDER BY id
                """, String.class)).containsExactly("PUBLISHED", "PUBLISHED", "PUBLISHED");

        jdbcTemplate.update("""
                INSERT INTO trade_order
                    (id, order_no, buyer_user_id, superior_user_id, address_snapshot_json,
                     total_amount_fen, status, source, client_request_id, completed_at)
                VALUES (1007, 'CONC-7', 101, ?, JSON_OBJECT(), 199800, 'COMPLETED', 'H5',
                        'concurrency-7', CURRENT_TIMESTAMP(3))
                """, SUPERIOR_ID);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO distribution_direct_performance
                    (beneficiary_user_id, referred_user_id, source_order_id, rule_version_id,
                     completed_ordinal, performance_fen, status)
                VALUES (?, 101, 1007, 3, 6, 199800, 'ACTIVE')
                """, SUPERIOR_ID))
                .as("the database is the final guard for beneficiary ordinal uniqueness")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void migrateLegacyDuplicateOrdinals() {
        jdbcTemplate.batchUpdate("""
                INSERT INTO iam_user_account (id, public_id, status, nickname)
                VALUES (?, ?, 'ACTIVE', ?)
                """, List.of(
                new Object[]{200L, publicId(200), "legacy-superior"},
                new Object[]{201L, publicId(201), "legacy-buyer-1"},
                new Object[]{202L, publicId(202), "legacy-buyer-2"}
        ));
        for (int offset = 1; offset <= 2; offset++) {
            long orderId = 2_000L + offset;
            long buyerId = 200L + offset;
            jdbcTemplate.update("""
                    INSERT INTO trade_order
                        (id, order_no, buyer_user_id, superior_user_id, address_snapshot_json,
                         total_amount_fen, status, source, client_request_id, completed_at)
                    VALUES (?, ?, ?, 200, JSON_OBJECT(), 199800, 'COMPLETED', 'H5', ?,
                            CURRENT_TIMESTAMP(3))
                    """, orderId, "LEGACY-CONC-" + offset, buyerId, "legacy-concurrency-" + offset);
            jdbcTemplate.update("""
                    INSERT INTO distribution_direct_performance
                        (beneficiary_user_id, referred_user_id, source_order_id, rule_version_id,
                         completed_ordinal, performance_fen, status)
                    VALUES (200, ?, ?, 3, 1, 199800, 'ACTIVE')
                    """, buyerId, orderId);
        }

        Flyway.configure().dataSource(dataSource).target("12").load().migrate();

        assertThat(jdbcTemplate.queryForList("""
                SELECT completed_ordinal
                FROM distribution_direct_performance
                WHERE beneficiary_user_id = 200
                ORDER BY completed_ordinal
                """, Integer.class)).containsExactly(1, 2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'distribution_direct_performance'
                  AND index_name = 'uk_direct_performance_beneficiary_ordinal'
                  AND non_unique = 0
                """, Integer.class)).isEqualTo(2);
    }

    private boolean processAfter(CountDownLatch start, CountDownLatch enteredProcessor) throws Exception {
        start.await();
        enteredProcessor.countDown();
        return processor.processNext();
    }

    private static void assertWorkerIsWaiting(Future<Boolean> worker, String name) throws Exception {
        if (worker.isDone()) {
            throw new AssertionError(name + " worker completed before superior lock release: " + worker.get());
        }
    }

    private void lockSuperiorMembership(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id
                FROM membership_account
                WHERE user_id = ?
                FOR UPDATE
                """)) {
            statement.setLong(1, SUPERIOR_ID);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    private void seedProjectionFixture() {
        jdbcTemplate.batchUpdate("""
                INSERT INTO iam_user_account (id, public_id, status, nickname)
                VALUES (?, ?, 'ACTIVE', ?)
                """, List.of(
                new Object[]{SUPERIOR_ID, publicId(SUPERIOR_ID), "superior"},
                new Object[]{101L, publicId(101), "buyer-1"},
                new Object[]{102L, publicId(102), "buyer-2"},
                new Object[]{103L, publicId(103), "buyer-3"},
                new Object[]{104L, publicId(104), "buyer-4"},
                new Object[]{105L, publicId(105), "buyer-5"},
                new Object[]{106L, publicId(106), "buyer-6"}
        ));
        jdbcTemplate.batchUpdate("""
                INSERT INTO membership_account
                    (id, user_id, current_level_id, qualified_at)
                VALUES (?, ?, 3, CURRENT_TIMESTAMP(3))
                """, List.of(
                new Object[]{100L, SUPERIOR_ID},
                new Object[]{101L, 101L},
                new Object[]{102L, 102L},
                new Object[]{103L, 103L},
                new Object[]{104L, 104L},
                new Object[]{105L, 105L},
                new Object[]{106L, 106L}
        ));
        jdbcTemplate.update("""
                INSERT INTO ledger_account
                    (id, user_id, account_type, available_points, frozen_points)
                VALUES (100, ?, 'DEMO_POINTS', 0, 0)
                """, SUPERIOR_ID);

        for (int ordinal = 1; ordinal <= 6; ordinal++) {
            long orderId = 1_000L + ordinal;
            long buyerId = 100L + ordinal;
            jdbcTemplate.update("""
                    INSERT INTO trade_order
                        (id, order_no, buyer_user_id, superior_user_id, address_snapshot_json,
                         total_amount_fen, status, source, client_request_id, completed_at)
                    VALUES (?, ?, ?, ?, JSON_OBJECT(), 199800, 'COMPLETED', 'H5', ?, CURRENT_TIMESTAMP(3))
                    """, orderId, "CONC-" + ordinal, buyerId, SUPERIOR_ID, "concurrency-" + ordinal);
            if (ordinal <= 4) {
                jdbcTemplate.update("""
                        INSERT INTO distribution_direct_performance
                            (beneficiary_user_id, referred_user_id, source_order_id, rule_version_id,
                             completed_ordinal, performance_fen, status)
                        VALUES (?, ?, ?, 3, ?, 199800, 'ACTIVE')
                        """, SUPERIOR_ID, buyerId, orderId, ordinal);
            } else {
                jdbcTemplate.update("""
                        INSERT INTO trade_order_item
                            (order_id, product_id, sku_id, product_name, sku_name, sales_scene,
                             unit_price_fen, quantity, subtotal_fen)
                        VALUES (?, 2, 2, 'concurrency product', 'concurrency sku', 'UPGRADE',
                                199800, 1, 199800)
                        """, orderId);
                jdbcTemplate.batchUpdate("""
                        INSERT INTO trade_order_rule_snapshot
                            (order_id, rule_code, rule_version_id)
                        VALUES (?, ?, ?)
                        """, List.of(
                        new Object[]{orderId, "EXPERIENCE_OFFICER_UPGRADE", 1L},
                        new Object[]{orderId, "SUPER_MEMBER_UPGRADE", 2L},
                        new Object[]{orderId, "DIVIDEND_MEMBER_QUALIFICATION", 3L},
                        new Object[]{orderId, "DIRECT_REFERRAL_POINTS", 4L}
                ));
                jdbcTemplate.update("""
                        INSERT INTO sys_outbox_event
                            (event_id, aggregate_type, aggregate_id, event_type, payload_json,
                             occurred_at, status, next_attempt_at)
                        VALUES (?, 'ORDER', ?, 'ORDER_COMPLETED', JSON_OBJECT(),
                                CURRENT_TIMESTAMP(3), 'PENDING', CURRENT_TIMESTAMP(3))
                        """, UUID.randomUUID().toString(), Long.toString(orderId));
            }
        }
    }

    private static String publicId(long id) {
        return "01JCONCURRENCY" + String.format("%012d", id);
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
            return Flyway.configure().dataSource(dataSource).target("11").load();
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

        @Bean
        OutboxProjectionProcessor outboxProjectionProcessor(DistributionMapper mapper) {
            return new OutboxProjectionProcessor(mapper);
        }
    }
}
