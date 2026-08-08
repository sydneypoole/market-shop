package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderPo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceMapperContractTest {

    @Test
    void orderInsertAndDetailReadRoundTripTheBuyerNoteColumn() throws Exception {
        String insert = String.join("\n", CommerceMapper.class
                .getMethod("insertOrder", OrderPo.class)
                .getAnnotation(Insert.class)
                .value());
        String detail = String.join("\n", CommerceMapper.class
                .getMethod("order", long.class)
                .getAnnotation(Select.class)
                .value());
        String autoReceive = String.join("\n", CommerceMapper.class
                .getMethod("lockDueAutoReceive")
                .getAnnotation(Select.class)
                .value());

        assertThat(insert)
                .contains("buyer_note")
                .contains("#{buyerNote}");
        assertThat(detail).contains("buyer_note");
        assertThat(autoReceive).contains("buyer_note");
    }
}
