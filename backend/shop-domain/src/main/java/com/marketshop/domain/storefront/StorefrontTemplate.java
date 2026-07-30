package com.marketshop.domain.storefront;

import com.marketshop.domain.shared.DomainException;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

public final class StorefrontTemplate {

    private static final Set<String> PRESETS = Set.of("EDITORIAL", "VIBRANT", "MINIMAL");
    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");

    private final long id;
    private final String code;
    private String name;
    private final String presetType;
    private String status;
    private boolean active;
    private String designTokensJson;
    private String layoutJson;
    private int version;
    private Instant publishedAt;

    private StorefrontTemplate(long id, String code, String name, String presetType, String status,
                               boolean active, String designTokensJson, String layoutJson,
                               int version, Instant publishedAt) {
        this.id = id;
        this.code = requireCode(code);
        this.name = requireName(name);
        this.presetType = requirePreset(presetType);
        this.status = requireStatus(status);
        this.active = active;
        this.designTokensJson = requireJson(designTokensJson);
        this.layoutJson = requireJson(layoutJson);
        this.version = version;
        this.publishedAt = publishedAt;
    }

    public static StorefrontTemplate draft(String code, String name, String presetType,
                                           String designTokensJson, String layoutJson) {
        return new StorefrontTemplate(
                0, code, name, presetType, "DRAFT", false,
                designTokensJson, layoutJson, 0, null
        );
    }

    public static StorefrontTemplate rehydrate(long id, String code, String name, String presetType,
                                               String status, boolean active, String designTokensJson,
                                               String layoutJson, int version, Instant publishedAt) {
        return new StorefrontTemplate(
                id, code, name, presetType, status, active,
                designTokensJson, layoutJson, version, publishedAt
        );
    }

    public void edit(String name, String designTokensJson, String layoutJson) {
        if ("ARCHIVED".equals(status)) {
            throw new DomainException("STOREFRONT_TEMPLATE_STATE_CONFLICT", "已归档模板不能编辑");
        }
        if (active) {
            throw new DomainException(
                    "STOREFRONT_TEMPLATE_STATE_CONFLICT",
                    "当前生效模板不能直接编辑，请先复制为草稿"
            );
        }
        this.name = requireName(name);
        this.designTokensJson = requireJson(designTokensJson);
        this.layoutJson = requireJson(layoutJson);
        this.status = "DRAFT";
        this.active = false;
        this.publishedAt = null;
        this.version++;
    }

    public void publish(Instant now) {
        if ("ARCHIVED".equals(status)) {
            throw new DomainException("STOREFRONT_TEMPLATE_STATE_CONFLICT", "已归档模板不能发布");
        }
        status = "PUBLISHED";
        active = true;
        publishedAt = now;
        version++;
    }

    public void archive() {
        if (active) {
            throw new DomainException("STOREFRONT_TEMPLATE_STATE_CONFLICT", "当前生效模板不能归档");
        }
        status = "ARCHIVED";
        version++;
    }

    public long id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public String presetType() { return presetType; }
    public String status() { return status; }
    public boolean active() { return active; }
    public String designTokensJson() { return designTokensJson; }
    public String layoutJson() { return layoutJson; }
    public int version() { return version; }
    public Instant publishedAt() { return publishedAt; }

    private static String requireCode(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_]{3,64}")) {
            throw new DomainException("STOREFRONT_TEMPLATE_CODE_INVALID", "模板编码格式无效");
        }
        return code;
    }

    private static String requireName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 120) {
            throw new DomainException("STOREFRONT_TEMPLATE_NAME_INVALID", "模板名称长度必须在 1 到 120 个字符之间");
        }
        return name;
    }

    private static String requirePreset(String value) {
        String preset = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!PRESETS.contains(preset)) {
            throw new DomainException("STOREFRONT_TEMPLATE_PRESET_INVALID", "模板预设类型无效");
        }
        return preset;
    }

    private static String requireStatus(String value) {
        String status = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new DomainException("STOREFRONT_TEMPLATE_STATUS_INVALID", "模板状态无效");
        }
        return status;
    }

    private static String requireJson(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainException("STOREFRONT_TEMPLATE_CONFIG_INVALID", "模板配置不能为空");
        }
        return value;
    }
}
