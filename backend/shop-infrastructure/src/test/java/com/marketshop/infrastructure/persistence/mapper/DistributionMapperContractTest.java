package com.marketshop.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

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
}
