package com.marketshop.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRuleSnapshotMapperContractTest {

    @Test
    void completionSnapshotsRulesAtTheOrderCompletionTimestamp() throws Exception {
        String sql = String.join("\n", CommerceMapper.class
                .getMethod("snapshotApplicableRules", long.class)
                .getAnnotation(Insert.class)
                .value());

        assertThat(sql)
                .contains("trade_order_rule_snapshot")
                .contains("rules.effective_from <= orders.completed_at")
                .contains("rules.effective_to > orders.completed_at")
                .contains("upgrade_item.sales_scene = 'UPGRADE'")
                .contains("repurchase_item.sales_scene = 'REPURCHASE'");
    }

    @Test
    void projectionQueriesReadTheSnapshottedVersionInsteadOfCurrentTime() throws Exception {
        String points = String.join("\n", DistributionMapper.class
                .getMethod("snapshottedPointsRule", long.class)
                .getAnnotation(Select.class)
                .value());
        String release = String.join("\n", DistributionMapper.class
                .getMethod("snapshottedReleaseRule", long.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(points).contains("trade_order_rule_snapshot").doesNotContain("CURRENT_TIMESTAMP");
        assertThat(release).contains("trade_order_rule_snapshot").doesNotContain("CURRENT_TIMESTAMP");
    }

    @Test
    void completedEventPayloadCarriesTheFrozenRuleVersionMap() throws Exception {
        String sql = String.join("\n", CommerceMapper.class
                .getMethod("insertCompletedOutbox", String.class, long.class, String.class)
                .getAnnotation(Insert.class)
                .value());

        assertThat(sql)
                .contains("'ORDER_COMPLETED'")
                .contains("'ruleVersionIds'")
                .contains("JSON_OBJECTAGG(snapshot.rule_code, snapshot.rule_version_id)");
    }

    @Test
    void proofUploadEligibilityLocksTheOrderRowBeforePersistingMetadata() throws Exception {
        String sql = String.join("\n", CommerceMapper.class
                .getMethod("lockOrderForProofUpload", long.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("buyer_user_id")
                .contains("superior_user_id")
                .contains("status")
                .contains("FROM trade_order")
                .contains("FOR UPDATE");
    }
}
