package com.marketshop.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class DistributionMapperContractTest {

    @Test
    void afterSaleQueryIncludesSourceLinkedReleaseEntriesButExcludesReversals() throws Exception {
        Select select = DistributionMapper.class
                .getMethod("reversibleEntries", long.class)
                .getAnnotation(Select.class);
        String sql = String.join("\n", select.value());

        assertThat(sql)
                .contains("e.entry_type <> 'REVERSAL'")
                .doesNotContain("e.original_entry_id IS NULL");
    }

    @Test
    void activeDirectCountsUseDistinctActiveReferredUsers() throws Exception {
        assertDistinctActiveReferredUsers("activeDirectCount", long.class);
        assertDistinctActiveReferredUsers("profile", long.class);
        assertDistinctActiveReferredUsers(
                "adminMembers", String.class, String.class, String.class, int.class, int.class
        );
        assertDistinctActiveReferredUsers("adminMember", long.class);
    }

    @Test
    void frozenBatchReversalUsesTheExactSourceEntryAndExpectedRemainingAmount() throws Exception {
        String lockSql = selectSql(DistributionMapper.class.getMethod(
                "lockFrozenBatchBySourceEntry", long.class));
        String reverseSql = updateSql(DistributionMapper.class.getMethod(
                "reverseFrozenBatch", long.class, long.class));

        assertThat(lockSql)
                .contains("batch.source_ledger_entry_id = #{sourceLedgerEntryId}")
                .contains("source_entry.entry_type = 'DIRECT_REFERRAL_AWARD'");
        assertThat(reverseSql)
                .contains("source_ledger_entry_id = #{sourceLedgerEntryId}")
                .contains("remaining_points = #{remainingPoints}")
                .contains("status IN ('ACTIVE', 'CONSUMED')");
    }

    @Test
    void releaseItemsJoinTheirImmutableSourceLedgerFact() throws Exception {
        String sql = selectSql(DistributionMapper.class.getMethod(
                "lockFrozenReleaseItems", long.class));

        assertThat(sql)
                .contains("JOIN ledger_entry source_entry ON source_entry.id = batch.source_ledger_entry_id")
                .contains("source_entry.account_id AS source_account_id")
                .contains("source_entry.entry_type AS source_entry_type")
                .contains("source_entry.frozen_delta AS source_frozen_delta");
    }

    @Test
    void releaseRestoreRequiresBatchAccountAndSourceEntryIdentity() throws Exception {
        String sql = updateSql(DistributionMapper.class.getMethod(
                "restoreFrozenBatchById", long.class, long.class, long.class, long.class));

        assertThat(sql)
                .contains("id = #{batchId}")
                .contains("account_id = #{accountId}")
                .contains("source_ledger_entry_id = #{sourceLedgerEntryId}");
    }

    @Test
    void directPerformanceInsertGuardsAnExistingActiveBeneficiaryReferredPair() throws Exception {
        String sql = insertSql(DistributionMapper.class.getMethod(
                "insertDirectPerformance", long.class, long.class, long.class, long.class, int.class, long.class
        ));

        assertThat(sql)
                .contains("existing.beneficiary_user_id = #{beneficiaryUserId}")
                .contains("existing.referred_user_id = #{referredUserId}")
                .contains("existing.status = 'ACTIVE'");
    }

    @Test
    void runtimeRuleProjectionsReturnWholeImmutablePayloads() throws Exception {
        String self = selectSql(DistributionMapper.class.getMethod("snapshottedSelfRuleVersions", long.class));
        String points = selectSql(DistributionMapper.class.getMethod("snapshottedPointsRuleVersion", long.class));
        String direct = selectSql(DistributionMapper.class.getMethod("activeDirectRuleVersion"));
        String release = selectSql(DistributionMapper.class.getMethod("snapshottedReleaseRuleVersion", long.class));
        assertThat(self)
.contains("rule_code IN ('EXPERIENCE_OFFICER_UPGRADE', 'SUPER_MEMBER_UPGRADE')")
.doesNotContain("rule_type = 'SELF_ORDER_TASK'");
        assertThat(release)
                .contains("rule_code = 'REPURCHASE_RELEASE'")
                .doesNotContain("rule_type = 'FROZEN_POINTS_RELEASE'")
                .doesNotContain("JSON_EXTRACT");
        assertThat(points)
                .contains("CAST(rule_version.parameters_json AS CHAR)")
                .contains("trade_order_rule_snapshot")
                .doesNotContain("JSON_EXTRACT");
        assertThat(direct)
                .contains("CAST(r.parameters_json AS CHAR)")
                .contains("rule_code = 'DIVIDEND_MEMBER_QUALIFICATION'")
                .doesNotContain("rule_type = 'DIRECT_REFERRAL_TASK'")
                .doesNotContain("JSON_EXTRACT");
    }

    @Test
    void activeLevelLookupIsRestrictedToEnabledMembershipLevels() throws Exception {
        String sql = selectSql(DistributionMapper.class.getMethod("activeMembershipLevelExists", String.class));
        assertThat(sql).contains("status = 'ACTIVE'").contains("membership_level");
    }

    @Test
    void directMemberProjectionCollapsesPerformanceHistoryToOneRowPerReferredUser() throws Exception {
        String sql = selectSql(DistributionMapper.class.getMethod("directMembers", long.class));

        assertThat(sql)
                .contains("ROW_NUMBER() OVER")
                .contains("PARTITION BY d.referred_user_id")
                .contains("performance_rank = 1");
    }

    private static void assertDistinctActiveReferredUsers(String methodName, Class<?>... parameterTypes)
            throws Exception {
        String sql = selectSql(DistributionMapper.class.getMethod(methodName, parameterTypes));
        assertThat(sql)
                .contains("COUNT(DISTINCT d.referred_user_id)")
                .contains("d.status = 'ACTIVE'");
    }

    private static String selectSql(Method method) {
        Select select = method.getAnnotation(Select.class);
        return String.join("\n", select.value());
    }

    private static String insertSql(Method method) {
        Insert insert = method.getAnnotation(Insert.class);
        return String.join("\n", insert.value());
    }

    private static String updateSql(Method method) {
        Update update = method.getAnnotation(Update.class);
        return String.join("\n", update.value());
    }
}
