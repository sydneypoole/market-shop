package com.marketshop.infrastructure.commerce;

import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import com.marketshop.infrastructure.persistence.mapper.NotificationMapper;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.ProductRow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MyBatisCommerceAdapterTest {

    @Test
    void keepsOneProductSummaryAndReturnsEveryOnSaleSkuInDetail() {
        ProductRow summary = summary();
        List<ProductRow> skus = List.of(
                sku(11L, "SKU-S", "小号", 19_900L, 120),
                sku(12L, "SKU-M", "中号", 29_800L, 80),
                sku(13L, "SKU-L", "大号", 39_800L, 40)
        );
        CommerceMapper mapper = proxy(CommerceMapper.class, (methodName, arguments) -> switch (methodName) {
            case "products" -> List.of(summary);
            case "product" -> summary;
            case "productSkus" -> skus;
            default -> null;
        });
        NotificationMapper notifications = proxy(NotificationMapper.class, (methodName, arguments) -> null);
        var adapter = new MyBatisCommerceAdapter(mapper, notifications);

        assertThat(adapter.products()).singleElement().satisfies(product -> {
            assertThat(product.productId()).isEqualTo(1L);
            assertThat(product.skuCount()).isEqualTo(3);
            assertThat(product.minPriceFen()).isEqualTo(19_900L);
            assertThat(product.maxPriceFen()).isEqualTo(39_800L);
        });
        assertThat(adapter.product(1L)).get().satisfies(detail -> {
            assertThat(detail.product().productId()).isEqualTo(1L);
            assertThat(detail.skus()).extracting(sku -> sku.skuCode())
                    .containsExactly("SKU-S", "SKU-M", "SKU-L");
            assertThat(detail.skus()).extracting(sku -> sku.inventory())
                    .containsExactly(120, 80, 40);
        });
    }

    private static ProductRow summary() {
        ProductRow row = new ProductRow();
        row.productId = 1L;
        row.categoryId = 9L;
        row.categoryName = "精选";
        row.name = "多规格商品";
        row.subtitle = "同一商品只显示一张卡片";
        row.descriptionHtml = "<p>详情</p>";
        row.salesScene = "UPGRADE";
        row.skuId = 11L;
        row.skuName = "小号";
        row.priceFen = 19_900L;
        row.marketPriceFen = 49_800L;
        row.minPriceFen = 19_900L;
        row.maxPriceFen = 39_800L;
        row.skuCount = 3;
        row.inventory = 240;
        return row;
    }

    private static ProductRow sku(long id, String code, String name, long priceFen, int inventory) {
        ProductRow row = new ProductRow();
        row.skuId = id;
        row.skuCode = code;
        row.skuName = name;
        row.priceFen = priceFen;
        row.marketPriceFen = 49_800L;
        row.attributesJson = "{\"size\":\"" + name + "\"}";
        row.inventory = inventory;
        return row;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method.getName(), arguments)
        ));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String methodName, Object[] arguments);
    }
}
