package com.marketshop.infrastructure.commerce;

import com.marketshop.application.commerce.CommerceUseCase.AddressSnapshot;
import com.marketshop.domain.shared.Money;
import com.marketshop.domain.shared.DomainException;
import com.marketshop.domain.trade.Order;
import com.marketshop.domain.trade.OrderLine;
import com.marketshop.domain.trade.OrderStatus;
import com.marketshop.infrastructure.persistence.mapper.CommerceMapper;
import com.marketshop.infrastructure.persistence.mapper.NotificationMapper;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.ProductRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderPo;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class MyBatisCommerceAdapterTest {

    @Test
    void persistsTheNormalizedBuyerNoteOnOrderSubmission() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        NotificationMapper notifications = mock(NotificationMapper.class);
        doAnswer(invocation -> {
            OrderPo row = invocation.getArgument(0);
            row.id = 100L;
            return 1;
        }).when(mapper).insertOrder(any(OrderPo.class));
        Order order = Order.submit(
                "MS100",
                10,
                20,
                List.of(new OrderLine(11, "默认规格", new Money(2_980), 1, "UPGRADE")),
                "  工作日配送  "
        );

        new MyBatisCommerceAdapter(mapper, notifications).saveSubmitted(
                order,
                new AddressSnapshot("张三", "13800138000", "广东省", "深圳市", "南山区", "科技园 1 号", null),
                "MINIPROGRAM",
                "mp-request-100",
                List.of()
        );

        ArgumentCaptor<OrderPo> row = ArgumentCaptor.forClass(OrderPo.class);
        verify(mapper).insertOrder(row.capture());
        assertThat(row.getValue().buyerNote).isEqualTo("工作日配送");
        assertThat(row.getValue().source).isEqualTo("MINIPROGRAM");
    }

    @Test
    void exposesPersistedBuyerNoteInTheOrderDetailView() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        OrderRow row = new OrderRow();
        row.id = 100L;
        row.orderNo = "MS100";
        row.buyerUserId = 10L;
        row.superiorUserId = 20L;
        row.addressSnapshotJson = "{\"recipientName\":\"张三\"}";
        row.buyerNote = "工作日配送";
        row.totalAmountFen = 2_980L;
        row.status = "PENDING_SUPERIOR";
        row.createdAt = LocalDateTime.of(2026, 8, 9, 12, 0);
        row.version = 0;
        when(mapper.order(100)).thenReturn(row);
        when(mapper.orderItems(100)).thenReturn(List.of());

        var detail = new MyBatisCommerceAdapter(mapper, mock(NotificationMapper.class)).order(100);

        assertThat(detail.buyerNote()).isEqualTo("工作日配送");
    }

    @Test
    void concurrentIdempotentInsertReturnsTheExistingOrderWithoutRepeatingSideEffects() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        OrderRow existing = new OrderRow();
        existing.id = 88L;
        existing.orderNo = "MS88";
        existing.buyerUserId = 10L;
        existing.superiorUserId = 20L;
        existing.totalAmountFen = 2_980L;
        existing.status = "PENDING_SUPERIOR";
        existing.createdAt = LocalDateTime.of(2026, 8, 9, 12, 0);
        when(mapper.insertOrder(any(OrderPo.class)))
                .thenThrow(new DuplicateKeyException("idempotency race"));
        when(mapper.findByClientRequest(10L, "mp-request-100")).thenReturn(existing);
        Order order = Order.submit(
                "MS100",
                10,
                20,
                List.of(new OrderLine(11, "默认规格", new Money(2_980), 1, "UPGRADE"))
        );

        var result = new MyBatisCommerceAdapter(mapper, mock(NotificationMapper.class)).saveSubmitted(
                order,
                new AddressSnapshot("张三", "13800138000", "广东省", "深圳市", "南山区", "科技园 1 号", null),
                "MINIPROGRAM",
                "mp-request-100",
                List.of()
        );

        assertThat(result.id()).isEqualTo(88L);
        verify(mapper, never()).reserveInventory(anyLong(), anyInt());
        verify(mapper, never()).insertOrderItem(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(), nullable(String.class),
                anyString(), anyLong(), anyInt(), anyLong()
        );
    }

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

    @Test
    void completedTransitionRejectsWhenABlockingAftersaleExists() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        when(mapper.lockOrderForUpdate(900)).thenReturn(new OrderRow());
        when(mapper.countBlockingAfterSales(900)).thenReturn(1);
        Order completed = completedOrder();

        assertThatThrownBy(() -> new MyBatisCommerceAdapter(mapper, mock(NotificationMapper.class))
                .persistTransition(completed, 0, "ORDER_COMPLETED"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AFTERSALE_BLOCKS_RECEIVE"));
        verify(mapper, never()).updateTransition(
                anyLong(), anyString(), nullable(LocalDateTime.class), nullable(LocalDateTime.class),
                nullable(LocalDateTime.class), nullable(LocalDateTime.class),
                nullable(LocalDateTime.class), nullable(String.class), anyInt(), anyInt()
        );
        verify(mapper, never()).insertCompletedOutbox(anyString(), anyLong(), anyString());
    }

    @Test
    void completedTransitionSnapshotsRulesBeforeWritingTheCompletedEvent() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        NotificationMapper notifications = mock(NotificationMapper.class);
        when(mapper.lockOrderForUpdate(900)).thenReturn(new OrderRow());
        when(mapper.countBlockingAfterSales(900)).thenReturn(0);
        when(mapper.updateTransition(
                eq(900L), eq("COMPLETED"), eq(null), eq(null), eq(null), eq(null),
                eq(java.time.LocalDateTime.of(2026, 8, 1, 8, 0)), eq(null), eq(1), eq(0)
        )).thenReturn(1);
        when(mapper.orderRuleSnapshotComplete(900)).thenReturn(1);
        Order completed = completedOrder();

        new MyBatisCommerceAdapter(mapper, notifications)
                .persistTransition(completed, 0, "ORDER_COMPLETED");

        var order = inOrder(mapper);
        order.verify(mapper).lockOrderForUpdate(900);
        order.verify(mapper).countBlockingAfterSales(900);
        order.verify(mapper).updateTransition(
                900, "COMPLETED", null, null, null, null,
                java.time.LocalDateTime.of(2026, 8, 1, 8, 0), null, 1, 0
        );
        order.verify(mapper).snapshotApplicableRules(900);
        order.verify(mapper).orderRuleSnapshotComplete(900);
        order.verify(mapper).insertCompletedOutbox(anyString(), eq(900L), eq("BUYER_RECEIVE"));
        verify(mapper, never()).insertOutbox(anyString(), anyString(), eq("ORDER_COMPLETED"), anyString());
    }

    @Test
    void missingOrOutOfRangeAutoReceiveRuleFailsClosed() {
        CommerceMapper mapper = mock(CommerceMapper.class);
        MyBatisCommerceAdapter adapter = new MyBatisCommerceAdapter(mapper, mock(NotificationMapper.class));

        when(mapper.autoReceiveDays()).thenReturn(null, 0, 366);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(adapter::autoReceiveDays)
                    .isInstanceOf(DomainException.class)
                    .extracting("code")
                    .isEqualTo("ORDER_TIMER_SETTINGS_INVALID");
        }
    }

    private static Order completedOrder() {
        return Order.rehydrate(
                900,
                "MS900",
                42,
                7,
                List.of(new OrderLine(11, "规格", new Money(199_800), 1, "UPGRADE")),
                new Money(199_800),
                OrderStatus.COMPLETED,
                null,
                null,
                null,
                null,
                Instant.parse("2026-08-01T00:00:00Z"),
                null,
                1
        );
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
