package com.marketshop.bootstrap.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBuyerNoteMigrationContractTest {

    private static final String MIGRATION = "db/migration/V14__add_order_buyer_note.sql";

    @Test
    void addsAnOptionalBuyerNoteWithTheSameLengthAsTheDomainContract() throws IOException {
        String sql;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as(MIGRATION).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("ALTER TABLE trade_order")
                .contains("buyer_note VARCHAR(500) NULL")
                .doesNotContain("DEFAULT");
    }
}
