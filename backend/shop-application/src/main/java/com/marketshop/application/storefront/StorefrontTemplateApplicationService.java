package com.marketshop.application.storefront;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketshop.application.storefront.StorefrontTemplatePort.TemplateRecord;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.storefront.StorefrontTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class StorefrontTemplateApplicationService implements StorefrontTemplateUseCase {

    private static final Set<String> SECTION_TYPES = Set.of(
            "ANNOUNCEMENT", "HERO", "CATEGORY_NAV", "PRODUCT_COLLECTION",
            "CONTENT_STORY", "SERVICE_BENEFITS", "QUICK_LINKS"
    );
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Pattern RADIUS = Pattern.compile("^(?:[0-9]|[1-5][0-9]|60)px$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final StorefrontTemplatePort port;

    public StorefrontTemplateApplicationService(StorefrontTemplatePort port) {
        this.port = port;
    }

    @Override
    @Transactional(readOnly = true)
    public TemplateView active() {
        return port.active().map(StorefrontTemplateApplicationService::view)
                .orElseGet(StorefrontTemplateApplicationService::fallback);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TemplateView> templates() {
        return port.findAll().stream().map(StorefrontTemplateApplicationService::view).toList();
    }

    @Override
    public TemplateView create(long adminId, CreateTemplateCommand command) {
        String presetType = normalizePreset(command.presetType());
        var preset = StorefrontTemplatePresets.get(presetType);
        validateConfiguration(preset.designTokensJson(), preset.layoutJson());
        String code = presetType + "_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
        return view(port.insert(adminId, StorefrontTemplate.draft(
                code, command.name(), presetType, preset.designTokensJson(), preset.layoutJson()
        )));
    }

    @Override
    public TemplateView update(long adminId, long templateId, UpdateTemplateCommand command) {
        TemplateRecord record = required(templateId);
        requireVersion(record, command.expectedVersion());
        validateConfiguration(command.designTokensJson(), command.layoutJson());
        StorefrontTemplate template = record.template();
        template.edit(command.name(), compact(command.designTokensJson()), compact(command.layoutJson()));
        return view(port.update(adminId, template, command.expectedVersion()));
    }

    @Override
    public TemplateView duplicate(long adminId, long templateId, String name) {
        StorefrontTemplate source = required(templateId).template();
        String code = source.presetType() + "_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
        return view(port.insert(adminId, StorefrontTemplate.draft(
                code, name, source.presetType(), source.designTokensJson(), source.layoutJson()
        )));
    }

    @Override
    public TemplateView publish(long adminId, long templateId, int expectedVersion) {
        TemplateRecord record = required(templateId);
        requireVersion(record, expectedVersion);
        validateConfiguration(record.template().designTokensJson(), record.template().layoutJson());
        record.template().publish(Instant.now());
        return view(port.publish(adminId, record.template(), expectedVersion));
    }

    @Override
    public void archive(long adminId, long templateId, int expectedVersion) {
        TemplateRecord record = required(templateId);
        requireVersion(record, expectedVersion);
        record.template().archive();
        port.archive(adminId, record.template(), expectedVersion);
    }

    private void validateConfiguration(String designTokensJson, String layoutJson) {
        try {
            JsonNode tokens = JSON.readTree(designTokensJson);
            if (!tokens.isObject() || designTokensJson.length() > 8_000) {
                invalid("主题令牌必须是有效 JSON 对象");
            }
            for (String field : List.of("primary", "accent", "canvas", "surface", "ink", "muted")) {
                if (!COLOR.matcher(tokens.path(field).asText()).matches()) {
                    invalid("主题颜色必须使用 #RRGGBB 格式");
                }
            }
            if (!RADIUS.matcher(tokens.path("radius").asText()).matches()) {
                invalid("圆角必须是 0px 到 60px");
            }
            if (!Set.of("serif", "sans").contains(tokens.path("headingFont").asText())) {
                invalid("标题字体类型无效");
            }

            JsonNode layout = JSON.readTree(layoutJson);
            JsonNode sections = layout.path("sections");
            if (!layout.isObject() || layout.path("schemaVersion").asInt() != 1
                    || !sections.isArray() || sections.isEmpty() || sections.size() > 24
                    || layoutJson.length() > 64_000) {
                invalid("模板必须包含 1 到 24 个有效区块");
            }
            Set<String> ids = new HashSet<>();
            for (JsonNode section : sections) {
                String id = section.path("id").asText();
                String type = section.path("type").asText();
                if (!id.matches("[A-Za-z0-9_-]{3,64}") || !ids.add(id)
                        || !SECTION_TYPES.contains(type) || !section.path("settings").isObject()) {
                    invalid("模板包含未知、重复或格式错误的区块");
                }
                validateNode(section.path("settings"), 0);
            }
        } catch (DomainException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DomainException("STOREFRONT_TEMPLATE_CONFIG_INVALID", "模板配置不是有效 JSON");
        }
    }

    private static void validateNode(JsonNode node, int depth) {
        if (depth > 5) {
            invalid("模板配置嵌套层级过深");
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (value.length() > 500) {
                invalid("模板单项文本不能超过 500 个字符");
            }
            if (value.toLowerCase(Locale.ROOT).contains("javascript:")) {
                invalid("模板链接不允许使用脚本协议");
            }
        }
        if (node.isArray() && node.size() > 24) {
            invalid("模板数组项目不能超过 24 个");
        }
        node.forEach(child -> validateNode(child, depth + 1));
    }

    private String compact(String json) {
        try {
            return JSON.writeValueAsString(JSON.readTree(json));
        } catch (Exception exception) {
            throw new DomainException("STOREFRONT_TEMPLATE_CONFIG_INVALID", "模板配置不是有效 JSON");
        }
    }

    private TemplateRecord required(long templateId) {
        return port.find(templateId)
                .orElseThrow(() -> new DomainException("STOREFRONT_TEMPLATE_NOT_FOUND", "商城模板不存在"));
    }

    private static void requireVersion(TemplateRecord record, int expectedVersion) {
        if (record.template().version() != expectedVersion) {
            throw new DomainException("STOREFRONT_TEMPLATE_CONCURRENT_MODIFICATION", "模板已被其他管理员更新，请刷新后重试");
        }
    }

    private static String normalizePreset(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static TemplateView view(TemplateRecord record) {
        StorefrontTemplate value = record.template();
        return new TemplateView(
                value.id(), value.code(), value.name(), value.presetType(), value.status(), value.active(),
                value.designTokensJson(), value.layoutJson(), value.version(), value.publishedAt(), record.updatedAt()
        );
    }

    private static TemplateView fallback() {
        var preset = StorefrontTemplatePresets.get("EDITORIAL");
        return new TemplateView(
                0, "EDITORIAL_FALLBACK", "序章 · 编辑甄选", "EDITORIAL", "PUBLISHED", true,
                preset.designTokensJson(), preset.layoutJson(), 0, null, null
        );
    }

    private static void invalid(String message) {
        throw new DomainException("STOREFRONT_TEMPLATE_CONFIG_INVALID", message);
    }
}
