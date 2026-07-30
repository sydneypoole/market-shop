package com.marketshop.domain.storefront;

import com.marketshop.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorefrontTemplateTest {

    private static final String TOKENS = """
            {"primary":"#173F35"}
            """;
    private static final String LAYOUT = """
            {"schemaVersion":1,"sections":[]}
            """;

    @Test
    void draftCanBeEditedPublishedAndVersioned() {
        StorefrontTemplate template = StorefrontTemplate.draft(
                "editorial_custom", "编辑甄选", "editorial", TOKENS, LAYOUT
        );

        template.edit("编辑甄选二版", TOKENS, LAYOUT);
        template.publish(Instant.parse("2026-07-30T10:00:00Z"));

        assertThat(template.code()).isEqualTo("EDITORIAL_CUSTOM");
        assertThat(template.name()).isEqualTo("编辑甄选二版");
        assertThat(template.status()).isEqualTo("PUBLISHED");
        assertThat(template.active()).isTrue();
        assertThat(template.version()).isEqualTo(2);
        assertThat(template.publishedAt()).isEqualTo(Instant.parse("2026-07-30T10:00:00Z"));
    }

    @Test
    void activeTemplateMustBeDuplicatedBeforeEditingOrArchiving() {
        StorefrontTemplate template = StorefrontTemplate.rehydrate(
                1, "EDITORIAL_DEFAULT", "当前模板", "EDITORIAL", "PUBLISHED",
                true, TOKENS, LAYOUT, 3, Instant.parse("2026-07-30T10:00:00Z")
        );

        assertThatThrownBy(() -> template.edit("不能直改", TOKENS, LAYOUT))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("STOREFRONT_TEMPLATE_STATE_CONFLICT");
        assertThatThrownBy(template::archive)
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("STOREFRONT_TEMPLATE_STATE_CONFLICT");
    }

    @Test
    void archivedTemplateCannotBePublished() {
        StorefrontTemplate template = StorefrontTemplate.rehydrate(
                2, "MINIMAL_ARCHIVED", "已归档模板", "MINIMAL", "ARCHIVED",
                false, TOKENS, LAYOUT, 5, null
        );

        assertThatThrownBy(() -> template.publish(Instant.now()))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("STOREFRONT_TEMPLATE_STATE_CONFLICT");
    }
}
