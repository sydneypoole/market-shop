package com.marketshop.application.storefront;

import com.marketshop.domain.storefront.StorefrontTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StorefrontTemplatePort {

    Optional<TemplateRecord> active();

    List<TemplateRecord> findAll();

    Optional<TemplateRecord> find(long templateId);

    TemplateRecord insert(long adminId, StorefrontTemplate template);

    TemplateRecord update(long adminId, StorefrontTemplate template, int expectedVersion);

    TemplateRecord publish(long adminId, StorefrontTemplate template, int expectedVersion);

    void archive(long adminId, StorefrontTemplate template, int expectedVersion);

    record TemplateRecord(StorefrontTemplate template, Instant updatedAt) {
    }
}
