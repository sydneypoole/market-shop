package com.marketshop.application.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketshop.domain.shared.DomainException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class CatalogAdminApplicationService implements CatalogAdminUseCase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ITEM_STATUS = Set.of("ON_SALE", "OFF_SALE");
    private static final Set<String> CATEGORY_STATUS = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> CONTENT_STATUS = Set.of("DRAFT", "PUBLISHED", "OFFLINE");
    private final CatalogAdminPort port;

    public CatalogAdminApplicationService(CatalogAdminPort port) {
        this.port = port;
    }

    @Override
    public List<CategoryView> categories() {
        return port.categories();
    }

    @Override
    public CategoryView saveCategory(SaveCategoryCommand command) {
        require(command.name(), "分类名称不能为空");
        require(command.code(), "分类编码不能为空");
        String status = upper(command.status());
        if (!CATEGORY_STATUS.contains(status)) {
            throw invalid("分类状态无效");
        }
        if (!command.code().matches("[A-Za-z0-9_-]{2,64}")) {
            throw invalid("分类编码只能包含字母、数字、下划线和短横线");
        }
        if (command.id() != null && command.id().equals(command.parentId())) {
            throw invalid("分类不能以自身作为上级");
        }
        return port.saveCategory(new SaveCategoryCommand(
                command.id(), command.parentId(), command.name().trim(),
                command.code().trim().toUpperCase(Locale.ROOT), command.sortOrder(), status
        ));
    }

    @Override
    public void disableCategory(long categoryId) {
        port.disableCategory(categoryId);
    }

    @Override
    public List<ProductAdminView> products() {
        return port.products();
    }

    @Override
    public ProductAdminView saveProduct(SaveProductCommand command) {
        require(command.name(), "商品名称不能为空");
        require(command.skuName(), "规格名称不能为空");
        require(command.skuCode(), "规格编码不能为空");
        String salesScene = upper(command.salesScene());
        if (!Set.of("UPGRADE", "REPURCHASE").contains(salesScene)) {
            throw invalid("销售场景仅支持 UPGRADE 或 REPURCHASE");
        }
        String status = upper(command.status());
        String skuStatus = upper(command.skuStatus());
        if (!ITEM_STATUS.contains(status) || !ITEM_STATUS.contains(skuStatus)) {
            throw invalid("商品或规格状态无效");
        }
        if (command.priceFen() < 0 || command.marketPriceFen() != null && command.marketPriceFen() < 0
                || command.initialInventory() < 0) {
            throw invalid("价格或库存不能为负数");
        }
        String attributes = command.attributesJson() == null || command.attributesJson().isBlank()
                ? "{}" : command.attributesJson().trim();
        validateAttributes(attributes);
        return port.saveProduct(new SaveProductCommand(
                command.productId(), command.categoryId(), command.name().trim(), trim(command.subtitle()),
                validateUrl(command.coverUrl(), "商品封面图"), CatalogRichTextSanitizer.sanitize(command.descriptionHtml()),
                salesScene, status, command.sortOrder(),
                command.skuId(), command.skuCode().trim().toUpperCase(Locale.ROOT), command.skuName().trim(),
                command.priceFen(), command.marketPriceFen(), attributes, skuStatus, command.initialInventory()
        ));
    }

    @Override
    public void adjustInventory(long adminId, InventoryAdjustmentCommand command) {
        if (command.afterQuantity() < 0) {
            throw invalid("库存不能为负数");
        }
        require(command.reason(), "库存调整原因不能为空");
        require(command.requestId(), "库存调整请求号不能为空");
        port.adjustInventory(adminId, new InventoryAdjustmentCommand(
                command.skuId(), command.afterQuantity(), command.reason().trim(), command.requestId().trim()
        ));
    }

    @Override
    public List<InventoryAdjustmentView> inventoryAdjustments(long skuId) {
        return port.inventoryAdjustments(skuId);
    }

    @Override
    public List<ContentAdminView> contents() {
        return port.contents();
    }

    @Override
    public ContentAdminView saveContent(SaveContentCommand command) {
        require(command.contentType(), "内容类型不能为空");
        require(command.title(), "内容标题不能为空");
        String status = upper(command.status());
        if (!CONTENT_STATUS.contains(status)) {
            throw invalid("内容状态无效");
        }
        return port.saveContent(new SaveContentCommand(
                command.id(), upper(command.contentType()), command.title().trim(), trim(command.summary()),
                validateUrl(command.coverUrl(), "内容封面图"), validateUrl(command.targetUrl(), "内容跳转链接"),
                CatalogRichTextSanitizer.sanitize(command.bodyHtml()), status,
                command.sortOrder()
        ));
    }

    @Override
    public void deleteContent(long contentId) {
        port.deleteContent(contentId);
    }

    private static String upper(String value) {
        require(value, "必填字段不能为空");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String validateUrl(String url, String fieldName) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && !trimmed.startsWith("/")) {
            throw new DomainException("URL_INVALID", fieldName + "必须以 http://、https:// 或 / 开头");
        }
        return trimmed;
    }

    private static void validateAttributes(String attributes) {
        try {
            JsonNode value = JSON.readTree(attributes);
            if (value == null || !value.isObject()) {
                throw invalid("规格属性必须是 JSON 对象");
            }
        } catch (JsonProcessingException exception) {
            throw invalid("规格属性必须是合法 JSON 对象");
        }
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw invalid(message);
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException("CATALOG_COMMAND_INVALID", message);
    }
}
