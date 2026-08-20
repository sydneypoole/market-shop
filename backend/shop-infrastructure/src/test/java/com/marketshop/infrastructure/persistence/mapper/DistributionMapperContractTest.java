package com.marketshop.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
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
}
