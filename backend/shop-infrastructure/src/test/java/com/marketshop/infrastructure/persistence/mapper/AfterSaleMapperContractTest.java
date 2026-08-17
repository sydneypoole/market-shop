package com.marketshop.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AfterSaleMapperContractTest {

    @Test
    void proofUploadLocksTheOwningAfterSaleRowBeforeTheCountCheck() throws Exception {
        String sql = String.join("\n", AfterSaleMapper.class
                .getMethod("lockAfterSaleForProofUpload", long.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("trade_after_sale a")
                .contains("a.applicant_user_id")
                .contains("a.status")
                .contains("WHERE a.id")
                .contains("FOR UPDATE");
    }

    @Test
    void eligibilityCountsCompletedAfterSalesSeparatelyFromActiveOnes() throws Exception {
        String sql = String.join("\n", AfterSaleMapper.class
                .getMethod("orderEligibility", long.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("completed_after_sale_count")
                .contains("status = 'COMPLETED'")
                .contains("active_after_sale_count");
    }
}
