package com.marketshop.infrastructure.storefront;

import com.marketshop.application.storefront.StorefrontTemplatePort;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.storefront.StorefrontTemplate;
import com.marketshop.infrastructure.persistence.mapper.StorefrontTemplateMapper;
import com.marketshop.infrastructure.persistence.model.StorefrontTemplatePersistenceModels.TemplateRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisStorefrontTemplateAdapter implements StorefrontTemplatePort {

    private static final ZoneOffset BUSINESS_ZONE = ZoneOffset.ofHours(8);
    private final StorefrontTemplateMapper mapper;

    public MyBatisStorefrontTemplateAdapter(StorefrontTemplateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<TemplateRecord> active() {
        return Optional.ofNullable(mapper.active()).map(MyBatisStorefrontTemplateAdapter::record);
    }

    @Override
    public List<TemplateRecord> findAll() {
        return mapper.findAll().stream().map(MyBatisStorefrontTemplateAdapter::record).toList();
    }

    @Override
    public Optional<TemplateRecord> find(long templateId) {
        return Optional.ofNullable(mapper.find(templateId)).map(MyBatisStorefrontTemplateAdapter::record);
    }

    @Override
    public TemplateRecord insert(long adminId, StorefrontTemplate template) {
        TemplateRow row = row(template);
        if (mapper.insert(adminId, row) != 1) {
            throw new DomainException("STOREFRONT_TEMPLATE_WRITE_FAILED", "商城模板创建失败");
        }
        return required(row.id);
    }

    @Override
    public TemplateRecord update(long adminId, StorefrontTemplate template, int expectedVersion) {
        if (mapper.update(adminId, row(template), expectedVersion) != 1) {
            concurrent();
        }
        return required(template.id());
    }

    @Override
    @Transactional
    public TemplateRecord publish(long adminId, StorefrontTemplate template, int expectedVersion) {
        mapper.deactivateCurrent();
        if (mapper.publish(
                adminId,
                template.id(),
                expectedVersion,
                template.version(),
                local(template.publishedAt())
        ) != 1) {
            concurrent();
        }
        return required(template.id());
    }

    @Override
    public void archive(long adminId, StorefrontTemplate template, int expectedVersion) {
        if (mapper.archive(adminId, template.id(), expectedVersion, template.version()) != 1) {
            concurrent();
        }
    }

    private TemplateRecord required(long templateId) {
        TemplateRow row = mapper.find(templateId);
        if (row == null) {
            throw new DomainException("STOREFRONT_TEMPLATE_NOT_FOUND", "商城模板不存在");
        }
        return record(row);
    }

    private static TemplateRecord record(TemplateRow row) {
        return new TemplateRecord(StorefrontTemplate.rehydrate(
                row.id,
                row.templateCode,
                row.templateName,
                row.presetType,
                row.status,
                Boolean.TRUE.equals(row.active),
                row.designTokensJson,
                row.layoutJson,
                row.version,
                instant(row.publishedAt)
        ), instant(row.updatedAt));
    }

    private static TemplateRow row(StorefrontTemplate value) {
        TemplateRow row = new TemplateRow();
        row.id = value.id() == 0 ? null : value.id();
        row.templateCode = value.code();
        row.templateName = value.name();
        row.presetType = value.presetType();
        row.status = value.status();
        row.active = value.active();
        row.designTokensJson = value.designTokensJson();
        row.layoutJson = value.layoutJson();
        row.version = value.version();
        row.publishedAt = local(value.publishedAt());
        return row;
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(BUSINESS_ZONE);
    }

    private static LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, BUSINESS_ZONE);
    }

    private static void concurrent() {
        throw new DomainException("STOREFRONT_TEMPLATE_CONCURRENT_MODIFICATION", "模板已被其他管理员更新，请刷新后重试");
    }
}
