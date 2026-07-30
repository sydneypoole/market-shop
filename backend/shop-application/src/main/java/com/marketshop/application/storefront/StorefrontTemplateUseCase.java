package com.marketshop.application.storefront;

import java.time.Instant;
import java.util.List;

public interface StorefrontTemplateUseCase {

    TemplateView active();

    List<TemplateView> templates();

    TemplateView create(long adminId, CreateTemplateCommand command);

    TemplateView update(long adminId, long templateId, UpdateTemplateCommand command);

    TemplateView duplicate(long adminId, long templateId, String name);

    TemplateView publish(long adminId, long templateId, int expectedVersion);

    void archive(long adminId, long templateId, int expectedVersion);

    record TemplateView(
            long id,
            String code,
            String name,
            String presetType,
            String status,
            boolean active,
            String designTokensJson,
            String layoutJson,
            int version,
            Instant publishedAt,
            Instant updatedAt
    ) {
    }

    record CreateTemplateCommand(String name, String presetType) {
    }

    record UpdateTemplateCommand(String name, String designTokensJson, String layoutJson, int expectedVersion) {
    }
}
