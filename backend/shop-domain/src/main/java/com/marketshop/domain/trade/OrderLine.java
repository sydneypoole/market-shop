package com.marketshop.domain.trade;

import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.shared.Money;

import java.util.Objects;

public record OrderLine(
        long skuId,
        String skuName,
        Money unitPrice,
        int quantity,
        String salesScene
) {
    public OrderLine {
        if (skuId <= 0) {
            throw new DomainException("SKU_INVALID", "SKU 标识无效");
        }
        if (skuName == null || skuName.isBlank()) {
            throw new DomainException("SKU_NAME_REQUIRED", "SKU 名称不能为空");
        }
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (quantity <= 0) {
            throw new DomainException("QUANTITY_INVALID", "商品数量必须大于零");
        }
        if (salesScene == null || salesScene.isBlank()) {
            throw new DomainException("SALES_SCENE_REQUIRED", "销售场景不能为空");
        }
    }

    public Money subtotal() {
        return unitPrice.multiply(quantity);
    }
}
