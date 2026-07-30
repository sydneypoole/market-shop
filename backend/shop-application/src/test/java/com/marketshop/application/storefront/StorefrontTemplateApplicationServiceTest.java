package com.marketshop.application.storefront;

import com.marketshop.application.storefront.StorefrontTemplatePort.TemplateRecord;
import com.marketshop.application.storefront.StorefrontTemplateUseCase.CreateTemplateCommand;
import com.marketshop.application.storefront.StorefrontTemplateUseCase.UpdateTemplateCommand;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.storefront.StorefrontTemplate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorefrontTemplateApplicationServiceTest {

    @Test
    void createsAllThreePresetTypesAsIndependentDrafts() {
        FakePort port = new FakePort();
        var service = new StorefrontTemplateApplicationService(port);

        var editorial = service.create(7, new CreateTemplateCommand("编辑模板", "editorial"));
        var vibrant = service.create(7, new CreateTemplateCommand("活力模板", "VIBRANT"));
        var minimal = service.create(7, new CreateTemplateCommand("极简模板", "MINIMAL"));

        assertThat(List.of(editorial.presetType(), vibrant.presetType(), minimal.presetType()))
                .containsExactly("EDITORIAL", "VIBRANT", "MINIMAL");
        assertThat(port.findAll()).hasSize(3);
        assertThat(port.findAll()).allSatisfy(record -> {
            assertThat(record.template().status()).isEqualTo("DRAFT");
            assertThat(record.template().active()).isFalse();
        });
    }

    @Test
    void rejectsUnknownSectionsAndScriptProtocolBeforePersistence() {
        FakePort port = new FakePort();
        var service = new StorefrontTemplateApplicationService(port);
        var draft = service.create(7, new CreateTemplateCommand("安全模板", "EDITORIAL"));

        String maliciousLayout = """
                {"schemaVersion":1,"sections":[
                  {"id":"unsafe-link","type":"HERO","enabled":true,
                   "settings":{"primaryLink":"javascript:alert(1)"}}
                ]}
                """;

        assertThatThrownBy(() -> service.update(7, draft.id(), new UpdateTemplateCommand(
                draft.name(), draft.designTokensJson(), maliciousLayout, draft.version()
        )))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("STOREFRONT_TEMPLATE_CONFIG_INVALID");
        assertThat(port.find(draft.id()).orElseThrow().template().version()).isZero();
    }

    @Test
    void optimisticVersionPreventsOverwritingAnotherEditorsDraft() {
        FakePort port = new FakePort();
        var service = new StorefrontTemplateApplicationService(port);
        var draft = service.create(7, new CreateTemplateCommand("协作模板", "MINIMAL"));

        service.update(7, draft.id(), new UpdateTemplateCommand(
                "协作模板二版", draft.designTokensJson(), draft.layoutJson(), draft.version()
        ));

        assertThatThrownBy(() -> service.update(8, draft.id(), new UpdateTemplateCommand(
                "过期修改", draft.designTokensJson(), draft.layoutJson(), draft.version()
        )))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("STOREFRONT_TEMPLATE_CONCURRENT_MODIFICATION");
    }

    @Test
    void returnsEditorialFallbackWhenNoPublishedTemplateExists() {
        var service = new StorefrontTemplateApplicationService(new FakePort());

        var active = service.active();

        assertThat(active.code()).isEqualTo("EDITORIAL_FALLBACK");
        assertThat(active.active()).isTrue();
        assertThat(active.layoutJson()).contains("PRODUCT_COLLECTION");
    }

    private static final class FakePort implements StorefrontTemplatePort {
        private final Map<Long, TemplateRecord> records = new LinkedHashMap<>();
        private long sequence = 1;

        @Override
        public Optional<TemplateRecord> active() {
            return records.values().stream().filter(record -> record.template().active()).findFirst();
        }

        @Override
        public List<TemplateRecord> findAll() {
            return new ArrayList<>(records.values());
        }

        @Override
        public Optional<TemplateRecord> find(long templateId) {
            return Optional.ofNullable(records.get(templateId));
        }

        @Override
        public TemplateRecord insert(long adminId, StorefrontTemplate template) {
            long id = sequence++;
            TemplateRecord inserted = record(id, template);
            records.put(id, inserted);
            return inserted;
        }

        @Override
        public TemplateRecord update(long adminId, StorefrontTemplate template, int expectedVersion) {
            TemplateRecord updated = record(template.id(), template);
            records.put(template.id(), updated);
            return updated;
        }

        @Override
        public TemplateRecord publish(long adminId, StorefrontTemplate template, int expectedVersion) {
            TemplateRecord published = record(template.id(), template);
            records.put(template.id(), published);
            return published;
        }

        @Override
        public void archive(long adminId, StorefrontTemplate template, int expectedVersion) {
            records.put(template.id(), record(template.id(), template));
        }

        private static TemplateRecord record(long id, StorefrontTemplate template) {
            return new TemplateRecord(StorefrontTemplate.rehydrate(
                    id,
                    template.code(),
                    template.name(),
                    template.presetType(),
                    template.status(),
                    template.active(),
                    template.designTokensJson(),
                    template.layoutJson(),
                    template.version(),
                    template.publishedAt()
            ), Instant.parse("2026-07-30T12:00:00Z"));
        }
    }
}
