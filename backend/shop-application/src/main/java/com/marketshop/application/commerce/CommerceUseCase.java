package com.marketshop.application.commerce;

import java.time.Instant;
import java.util.List;

public interface CommerceUseCase {

    List<ProductView> products();

    ProductDetail product(long productId);

    List<CategoryView> categories();

    void updateProduct(long adminId, UpdateProductCommand command);

    List<ContentView> contents();

    ContentView content(long contentId);

    List<CartItemView> cart(long userId);

    void setCartItem(long userId, long skuId, int quantity, boolean selected);

    OrderView submit(long userId, SubmitOrderCommand command);

    List<OrderView> buyerOrders(long userId);

    List<OrderView> superiorOrders(long userId);

    List<OrderView> adminOrders(String status);

    OrderDetail order(long userId, long orderId);

    OrderDetail adminOrder(long orderId);

    void superiorDecision(long superiorUserId, long orderId, boolean approve, String reason);

    void adminDecision(long adminId, long orderId, boolean approve, String reason);

    void ship(long adminId, long orderId, ShipmentCommand command);

    void receive(long buyerUserId, long orderId);

    void cancel(long buyerUserId, long orderId, String reason);

    record ProductView(
            long productId,
            long categoryId,
            String categoryName,
            String name,
            String subtitle,
            String coverUrl,
            String salesScene,
            long skuId,
            String skuName,
            long priceFen,
            long marketPriceFen,
            int inventory,
            long minPriceFen,
            long maxPriceFen,
            int skuCount
    ) {
    }

    record SkuView(long skuId, String skuCode, String skuName, long priceFen, long marketPriceFen,
                   int inventory, String attributesJson) {
    }

    record ProductDetail(ProductView product, String descriptionHtml, List<SkuView> skus) {
    }

    record CategoryView(long id, Long parentId, String name, String code, int sortOrder, int productCount) {
    }

    record UpdateProductCommand(long productId, long skuId, String name, String subtitle,
                                String salesScene, long priceFen, int inventory, String status) {
    }

    record ContentView(long id, String type, String title, String summary, String coverUrl,
                       String targetUrl, String bodyHtml) {
    }

    record CartItemView(long id, long skuId, String productName, String skuName, String coverUrl,
                        long priceFen, int quantity, boolean selected, int inventory) {
    }

    record SubmitOrderCommand(
            String clientRequestId,
            String source,
            AddressSnapshot address,
            List<OrderItemCommand> items
    ) {
    }

    record AddressSnapshot(String recipientName, String phone, String province, String city,
                           String district, String detailAddress, String postalCode) {
    }

    record OrderItemCommand(long skuId, int quantity) {
    }

    record OrderView(long id, String orderNo, long buyerUserId, long superiorUserId,
                     long totalAmountFen, String status, String reason, Instant createdAt) {
    }

    record OrderItemView(long skuId, String productName, String skuName, String coverUrl,
                         String salesScene, long unitPriceFen, int quantity, long subtotalFen) {
    }

    record ShipmentView(String carrierCode, String carrierName, String trackingNo, Instant shippedAt) {
    }

    record OrderDetail(
            OrderView order,
            String addressJson,
            List<OrderItemView> items,
            ShipmentView shipment,
            Instant superiorConfirmedAt,
            Instant adminReviewedAt,
            Instant autoReceiveAt,
            Instant completedAt,
            OrderActorCapabilities actorCapabilities
    ) {
    }

    record OrderActorCapabilities(
            boolean canReceive,
            boolean canUploadProof,
            boolean canCancel,
            boolean canSuperiorDecide
    ) {
        public static OrderActorCapabilities none() {
            return new OrderActorCapabilities(false, false, false, false);
        }
    }

    record ShipmentCommand(String carrierCode, String carrierName, String trackingNo) {
    }
}
