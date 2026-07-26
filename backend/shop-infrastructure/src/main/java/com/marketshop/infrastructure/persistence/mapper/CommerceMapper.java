package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.CartRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.ContentRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderItemRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderNoteRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderPo;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderProofPo;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.OrderRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.ProofRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.ProductRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.ShipmentRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.SkuRow;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface CommerceMapper extends BaseMapper<OrderPo> {

    @Select("""
            SELECT p.id AS product_id, p.name, p.subtitle, p.cover_url, p.description_html, p.sales_scene,
                   s.id AS sku_id, s.name AS sku_name, s.price_fen, COALESCE(s.market_price_fen, s.price_fen) AS market_price_fen,
                   CAST(s.attributes_json AS CHAR) AS attributes_json, i.available_quantity AS inventory
            FROM catalog_product p
            JOIN catalog_sku s ON s.product_id = p.id AND s.status = 'ON_SALE'
            JOIN catalog_inventory i ON i.sku_id = s.id
            WHERE p.status = 'ON_SALE'
            ORDER BY p.sort_order, p.id, s.id
            """)
    List<ProductRow> products();

    @Select("""
            SELECT p.id AS product_id, p.name, p.subtitle, p.cover_url, p.description_html, p.sales_scene,
                   s.id AS sku_id, s.name AS sku_name, s.price_fen, COALESCE(s.market_price_fen, s.price_fen) AS market_price_fen,
                   CAST(s.attributes_json AS CHAR) AS attributes_json, i.available_quantity AS inventory
            FROM catalog_product p
            JOIN catalog_sku s ON s.product_id = p.id AND s.status = 'ON_SALE'
            JOIN catalog_inventory i ON i.sku_id = s.id
            WHERE p.id = #{productId} AND p.status = 'ON_SALE'
            ORDER BY s.id
            LIMIT 1
            """)
    ProductRow product(@Param("productId") long productId);

    @Update("""
            UPDATE catalog_product
            SET name = #{name}, subtitle = #{subtitle}, sales_scene = #{salesScene},
                status = #{status}, version = version + 1
            WHERE id = #{productId}
            """)
    int updateProduct(
            @Param("productId") long productId,
            @Param("name") String name,
            @Param("subtitle") String subtitle,
            @Param("salesScene") String salesScene,
            @Param("status") String status
    );

    @Update("""
            UPDATE catalog_sku
            SET price_fen = #{priceFen}, status = #{status}, version = version + 1
            WHERE id = #{skuId} AND product_id = #{productId}
            """)
    int updateSku(
            @Param("productId") long productId,
            @Param("skuId") long skuId,
            @Param("priceFen") long priceFen,
            @Param("status") String status
    );

    @Update("""
            UPDATE catalog_inventory
            SET available_quantity = #{inventory}, version = version + 1
            WHERE sku_id = #{skuId}
            """)
    int updateInventory(@Param("skuId") long skuId, @Param("inventory") int inventory);

    @Select("""
            SELECT COUNT(*)
            FROM catalog_sku s
            JOIN catalog_product p ON p.id = s.product_id
            WHERE s.id = #{skuId} AND s.status = 'ON_SALE' AND p.status = 'ON_SALE'
            """)
    int productBySkuExists(@Param("skuId") long skuId);

    @Select("""
            SELECT id, content_type, title, summary, target_url, body_html
            FROM operation_content
            WHERE status = 'PUBLISHED'
            ORDER BY sort_order, id
            """)
    List<ContentRow> contents();

    @Select("""
            SELECT c.id, c.sku_id, p.name AS product_name, s.name AS sku_name, p.cover_url,
                   s.price_fen, c.quantity, c.selected, i.available_quantity AS inventory
            FROM trade_cart_item c
            JOIN catalog_sku s ON s.id = c.sku_id
            JOIN catalog_product p ON p.id = s.product_id
            JOIN catalog_inventory i ON i.sku_id = s.id
            WHERE c.user_id = #{userId}
            ORDER BY c.updated_at DESC
            """)
    List<CartRow> cart(@Param("userId") long userId);

    @Insert("""
            INSERT INTO trade_cart_item (user_id, sku_id, quantity, selected)
            VALUES (#{userId}, #{skuId}, #{quantity}, #{selected})
            ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), selected = VALUES(selected)
            """)
    int upsertCart(
            @Param("userId") long userId,
            @Param("skuId") long skuId,
            @Param("quantity") int quantity,
            @Param("selected") boolean selected
    );

    @Delete("DELETE FROM trade_cart_item WHERE user_id = #{userId} AND sku_id = #{skuId}")
    int deleteCart(@Param("userId") long userId, @Param("skuId") long skuId);

    @Select("""
            SELECT superior_user_id
            FROM customer_relation
            WHERE member_user_id = #{userId}
            LIMIT 1
            """)
    Long findSuperiorId(@Param("userId") long userId);

    @Select("""
            SELECT p.id AS product_id, s.id AS sku_id, p.name AS product_name, s.name AS sku_name,
                   p.cover_url, p.sales_scene, s.price_fen AS unit_price_fen,
                   i.available_quantity
            FROM catalog_sku s
            JOIN catalog_product p ON p.id = s.product_id
            JOIN catalog_inventory i ON i.sku_id = s.id
            WHERE s.id = #{skuId} AND s.status = 'ON_SALE' AND p.status = 'ON_SALE'
            FOR UPDATE
            """)
    SkuRow lockSku(@Param("skuId") long skuId);

    @Select("""
            SELECT id, order_no, buyer_user_id, superior_user_id, total_amount_fen, status, reason, created_at
            FROM trade_order
            WHERE buyer_user_id = #{userId} AND client_request_id = #{clientRequestId}
            LIMIT 1
            """)
    OrderRow findByClientRequest(@Param("userId") long userId, @Param("clientRequestId") String clientRequestId);

    @Insert("""
            INSERT INTO trade_order
                (order_no, buyer_user_id, superior_user_id, address_snapshot_json, total_amount_fen,
                 status, source, client_request_id, version)
            VALUES
                (#{orderNo}, #{buyerUserId}, #{superiorUserId}, CAST(#{addressSnapshotJson} AS JSON),
                 #{totalAmountFen}, #{status}, #{source}, #{clientRequestId}, #{version})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrder(OrderPo order);

    @Insert("""
            INSERT INTO trade_order_item
                (order_id, product_id, sku_id, product_name, sku_name, cover_url, sales_scene,
                 unit_price_fen, quantity, subtotal_fen)
            VALUES
                (#{orderId}, #{productId}, #{skuId}, #{productName}, #{skuName}, #{coverUrl}, #{salesScene},
                 #{unitPriceFen}, #{quantity}, #{subtotalFen})
            """)
    int insertOrderItem(
            @Param("orderId") long orderId,
            @Param("productId") long productId,
            @Param("skuId") long skuId,
            @Param("productName") String productName,
            @Param("skuName") String skuName,
            @Param("coverUrl") String coverUrl,
            @Param("salesScene") String salesScene,
            @Param("unitPriceFen") long unitPriceFen,
            @Param("quantity") int quantity,
            @Param("subtotalFen") long subtotalFen
    );

    @Update("""
            UPDATE catalog_inventory
            SET available_quantity = available_quantity - #{quantity},
                reserved_quantity = reserved_quantity + #{quantity},
                version = version + 1
            WHERE sku_id = #{skuId} AND available_quantity >= #{quantity}
            """)
    int reserveInventory(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update("""
            UPDATE catalog_inventory
            SET available_quantity = available_quantity + #{quantity},
                reserved_quantity = reserved_quantity - #{quantity},
                version = version + 1
            WHERE sku_id = #{skuId} AND reserved_quantity >= #{quantity}
            """)
    int releaseReservedInventory(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Update("""
            UPDATE catalog_inventory
            SET reserved_quantity = reserved_quantity - #{quantity},
                version = version + 1
            WHERE sku_id = #{skuId} AND reserved_quantity >= #{quantity}
            """)
    int consumeReservedInventory(@Param("skuId") long skuId, @Param("quantity") int quantity);

    @Delete("DELETE FROM trade_cart_item WHERE user_id = #{userId} AND sku_id = #{skuId}")
    int clearPurchasedCart(@Param("userId") long userId, @Param("skuId") long skuId);

    @Select("""
            SELECT id, order_no, buyer_user_id, superior_user_id, total_amount_fen, status, reason, created_at
            FROM trade_order
            WHERE buyer_user_id = #{userId}
            ORDER BY created_at DESC, id DESC
            """)
    List<OrderRow> buyerOrders(@Param("userId") long userId);

    @Select("""
            SELECT id, order_no, buyer_user_id, superior_user_id, total_amount_fen, status, reason, created_at
            FROM trade_order
            WHERE superior_user_id = #{userId}
            ORDER BY created_at DESC, id DESC
            """)
    List<OrderRow> superiorOrders(@Param("userId") long userId);

    @Select("""
            <script>
            SELECT id, order_no, buyer_user_id, superior_user_id, total_amount_fen, status, reason, created_at
            FROM trade_order
            <if test="status != null">WHERE status = #{status}</if>
            ORDER BY created_at DESC, id DESC
            LIMIT 500
            </script>
            """)
    List<OrderRow> adminOrders(@Param("status") String status);

    @Select("""
            SELECT id, order_no, buyer_user_id, superior_user_id, CAST(address_snapshot_json AS CHAR) AS address_snapshot_json,
                   total_amount_fen, status, reason, superior_confirmed_at, admin_reviewed_at, shipped_at,
                   auto_receive_at, completed_at, created_at, version
            FROM trade_order
            WHERE id = #{orderId}
            LIMIT 1
            """)
    OrderRow order(@Param("orderId") long orderId);

    @Select("""
            SELECT product_id, sku_id, product_name, sku_name, cover_url, sales_scene,
                   unit_price_fen, quantity, subtotal_fen
            FROM trade_order_item
            WHERE order_id = #{orderId}
            ORDER BY id
            """)
    List<OrderItemRow> orderItems(@Param("orderId") long orderId);

    @Select("""
            SELECT carrier_code, carrier_name, tracking_no, shipped_at
            FROM fulfillment_shipment
            WHERE order_id = #{orderId}
            LIMIT 1
            """)
    ShipmentRow shipment(@Param("orderId") long orderId);

    @Update("""
            UPDATE trade_order
            SET status = #{status},
                superior_confirmed_at = #{superiorConfirmedAt},
                admin_reviewed_at = #{adminReviewedAt},
                shipped_at = #{shippedAt},
                auto_receive_at = #{autoReceiveAt},
                completed_at = #{completedAt},
                reason = #{reason},
                version = #{newVersion}
            WHERE id = #{orderId} AND version = #{expectedVersion}
            """)
    int updateTransition(
            @Param("orderId") long orderId,
            @Param("status") String status,
            @Param("superiorConfirmedAt") LocalDateTime superiorConfirmedAt,
            @Param("adminReviewedAt") LocalDateTime adminReviewedAt,
            @Param("shippedAt") LocalDateTime shippedAt,
            @Param("autoReceiveAt") LocalDateTime autoReceiveAt,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("reason") String reason,
            @Param("newVersion") int newVersion,
            @Param("expectedVersion") int expectedVersion
    );

    @Insert("""
            INSERT INTO fulfillment_shipment
                (order_id, carrier_code, carrier_name, tracking_no, shipped_by_admin_id, shipped_at)
            VALUES
                (#{orderId}, #{carrierCode}, #{carrierName}, #{trackingNo}, #{adminId}, #{shippedAt})
            """)
    int insertShipment(
            @Param("orderId") long orderId,
            @Param("carrierCode") String carrierCode,
            @Param("carrierName") String carrierName,
            @Param("trackingNo") String trackingNo,
            @Param("adminId") long adminId,
            @Param("shippedAt") LocalDateTime shippedAt
    );

    @Select("""
            SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.autoReceiveDaysAfterShipment')) AS UNSIGNED)
            FROM operation_rule_version
            WHERE rule_code = 'ORDER_TIMERS' AND status = 'ACTIVE'
              AND effective_from <= CURRENT_TIMESTAMP(3)
              AND (effective_to IS NULL OR effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY version_no DESC
            LIMIT 1
            """)
    Integer autoReceiveDays();

    @Insert("""
            INSERT INTO sys_outbox_event
                (event_id, aggregate_type, aggregate_id, event_type, payload_json,
                 occurred_at, status, next_attempt_at)
            VALUES
                (#{eventId}, 'ORDER', #{aggregateId}, #{eventType}, CAST(#{payloadJson} AS JSON),
                 CURRENT_TIMESTAMP(3), 'PENDING', CURRENT_TIMESTAMP(3))
            """)
    int insertOutbox(
            @Param("eventId") String eventId,
            @Param("aggregateId") String aggregateId,
            @Param("eventType") String eventType,
            @Param("payloadJson") String payloadJson
    );

    @Select("""
            SELECT COUNT(*)
            FROM trade_order
            WHERE id = #{orderId}
              AND (buyer_user_id = #{userId} OR superior_user_id = #{userId})
            """)
    int canUserAccessOrder(@Param("userId") long userId, @Param("orderId") long orderId);

    @Select("SELECT COUNT(*) FROM trade_order_proof WHERE order_id = #{orderId} AND cleaned_at IS NULL")
    int countOrderProofs(@Param("orderId") long orderId);

    @Insert("""
            INSERT INTO trade_order_proof
                (order_id, object_key, sha256, media_type, size_bytes, uploaded_by, retain_until)
            VALUES
                (#{orderId}, #{objectKey}, #{sha256}, #{mediaType}, #{sizeBytes}, #{uploadedBy}, #{retainUntil})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrderProof(OrderProofPo proof);

    @Select("""
            SELECT p.id, p.order_id, p.object_key, p.sha256, p.media_type, p.size_bytes,
                   p.uploaded_by, p.retain_until, p.created_at,
                   o.buyer_user_id, o.superior_user_id, o.status AS order_status
            FROM trade_order_proof p
            JOIN trade_order o ON o.id = p.order_id
            WHERE p.id = #{proofId} AND p.cleaned_at IS NULL
            LIMIT 1
            """)
    ProofRow proof(@Param("proofId") long proofId);

    @Select("""
            SELECT p.id, p.order_id, p.object_key, p.sha256, p.media_type, p.size_bytes,
                   p.uploaded_by, p.retain_until, p.created_at,
                   o.buyer_user_id, o.superior_user_id, o.status AS order_status
            FROM trade_order_proof p
            JOIN trade_order o ON o.id = p.order_id
            WHERE p.order_id = #{orderId} AND p.cleaned_at IS NULL
            ORDER BY p.created_at, p.id
            """)
    List<ProofRow> orderProofs(@Param("orderId") long orderId);

    @Select("""
            SELECT p.id, p.order_id, p.object_key, p.sha256, p.media_type, p.size_bytes,
                   p.uploaded_by, p.retain_until, p.created_at,
                   o.buyer_user_id, o.superior_user_id, o.status AS order_status
            FROM trade_order_proof p
            JOIN trade_order o ON o.id = p.order_id
            WHERE p.cleaned_at IS NULL AND p.retain_until <= CURRENT_TIMESTAMP(3)
            ORDER BY p.retain_until, p.id
            LIMIT #{limit}
            """)
    List<ProofRow> expiredProofs(@Param("limit") int limit);

    @Update("""
            UPDATE trade_order_proof
            SET cleaned_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{proofId} AND cleaned_at IS NULL
            """)
    int markProofCleaned(@Param("proofId") long proofId);

    @Select("""
            SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.maxProofFiles')) AS UNSIGNED)
            FROM operation_rule_version
            WHERE rule_code = 'ORDER_TIMERS' AND status = 'ACTIVE'
              AND effective_from <= CURRENT_TIMESTAMP(3)
              AND (effective_to IS NULL OR effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY version_no DESC
            LIMIT 1
            """)
    Integer maxProofFiles();

    @Select("""
            SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.maxProofSizeBytes')) AS UNSIGNED)
            FROM operation_rule_version
            WHERE rule_code = 'ORDER_TIMERS' AND status = 'ACTIVE'
              AND effective_from <= CURRENT_TIMESTAMP(3)
              AND (effective_to IS NULL OR effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY version_no DESC
            LIMIT 1
            """)
    Long maxProofSizeBytes();

    @Select("""
            SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.proofRetentionDays')) AS UNSIGNED)
            FROM operation_rule_version
            WHERE rule_code = 'ORDER_TIMERS' AND status = 'ACTIVE'
              AND effective_from <= CURRENT_TIMESTAMP(3)
              AND (effective_to IS NULL OR effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY version_no DESC
            LIMIT 1
            """)
    Integer proofRetentionDays();

    @Select("""
            SELECT id, order_no, buyer_user_id, superior_user_id, CAST(address_snapshot_json AS CHAR) AS address_snapshot_json,
                   total_amount_fen, status, reason, superior_confirmed_at, admin_reviewed_at, shipped_at,
                   auto_receive_at, completed_at, created_at, version
            FROM trade_order
            WHERE status = 'SHIPPED' AND auto_receive_at <= CURRENT_TIMESTAMP(3)
            ORDER BY auto_receive_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    OrderRow lockDueAutoReceive();

    @Insert("""
            INSERT INTO sys_job_lease (job_name, owner_id, lease_until, heartbeat_at, version)
            VALUES (#{jobName}, #{ownerId}, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{leaseSeconds} SECOND),
                    CURRENT_TIMESTAMP(3), 0)
            ON DUPLICATE KEY UPDATE
                owner_id = IF(lease_until < CURRENT_TIMESTAMP(3) OR owner_id = VALUES(owner_id),
                              VALUES(owner_id), owner_id),
                lease_until = IF(lease_until < CURRENT_TIMESTAMP(3) OR owner_id = VALUES(owner_id),
                                 VALUES(lease_until), lease_until),
                heartbeat_at = IF(owner_id = VALUES(owner_id), CURRENT_TIMESTAMP(3), heartbeat_at),
                version = version + 1
            """)
    int acquireJobLease(
            @Param("jobName") String jobName,
            @Param("ownerId") String ownerId,
            @Param("leaseSeconds") int leaseSeconds
    );

    @Select("""
            SELECT COUNT(*)
            FROM sys_job_lease
            WHERE job_name = #{jobName} AND owner_id = #{ownerId}
              AND lease_until > CURRENT_TIMESTAMP(3)
            """)
    int ownsJobLease(@Param("jobName") String jobName, @Param("ownerId") String ownerId);

    @Select("""
            <script>
            SELECT id, order_no, buyer_user_id, superior_user_id, total_amount_fen, status, reason, created_at
            FROM trade_order
            <where>
                <if test="orderNo != null">AND order_no LIKE CONCAT('%', #{orderNo}, '%')</if>
                <if test="buyerUserId != null">AND buyer_user_id = #{buyerUserId}</if>
                <if test="superiorUserId != null">AND superior_user_id = #{superiorUserId}</if>
                <if test="status != null">AND status = #{status}</if>
                <if test="from != null">AND created_at &gt;= #{from}</if>
                <if test="to != null">AND created_at &lt;= #{to}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<OrderRow> searchAdminOrders(@Param("orderNo") String orderNo,
                                     @Param("buyerUserId") Long buyerUserId,
                                     @Param("superiorUserId") Long superiorUserId,
                                     @Param("status") String status,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM trade_order
            <where>
                <if test="orderNo != null">AND order_no LIKE CONCAT('%', #{orderNo}, '%')</if>
                <if test="buyerUserId != null">AND buyer_user_id = #{buyerUserId}</if>
                <if test="superiorUserId != null">AND superior_user_id = #{superiorUserId}</if>
                <if test="status != null">AND status = #{status}</if>
                <if test="from != null">AND created_at &gt;= #{from}</if>
                <if test="to != null">AND created_at &lt;= #{to}</if>
            </where>
            </script>
            """)
    long countAdminOrders(@Param("orderNo") String orderNo,
                          @Param("buyerUserId") Long buyerUserId,
                          @Param("superiorUserId") Long superiorUserId,
                          @Param("status") String status,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to);

    @Select("""
            SELECT id, admin_id, note, created_at
            FROM trade_order_note
            WHERE order_id = #{orderId}
            ORDER BY id DESC
            """)
    List<OrderNoteRow> orderNotes(@Param("orderId") long orderId);

    @Insert("""
            INSERT INTO trade_order_note (order_id, admin_id, note)
            SELECT id, #{adminId}, #{note} FROM trade_order WHERE id = #{orderId}
            """)
    int insertOrderNote(@Param("adminId") long adminId,
                        @Param("orderId") long orderId,
                        @Param("note") String note);

    @Select("SELECT COUNT(*) FROM iam_user_account")
    long dashboardMemberCount();

    @Select("SELECT COUNT(*) FROM trade_order WHERE created_at >= CURRENT_DATE")
    long dashboardTodayOrderCount();

    @Select("""
            SELECT COALESCE(SUM(total_amount_fen), 0)
            FROM trade_order
            WHERE status = 'COMPLETED' AND completed_at >= CURRENT_DATE
            """)
    long dashboardTodayCompletedAmount();

    @Select("SELECT COUNT(*) FROM trade_order WHERE status = #{status}")
    long dashboardOrderStatusCount(@Param("status") String status);

    @Select("""
            SELECT COUNT(*) FROM trade_after_sale
            WHERE status NOT IN ('COMPLETED', 'REJECTED', 'CANCELLED')
            """)
    long dashboardActiveAfterSaleCount();

    @Select("SELECT COUNT(*) FROM catalog_product WHERE status = 'ON_SALE'")
    long dashboardOnSaleProductCount();

    @Select("SELECT COUNT(*) FROM catalog_inventory WHERE available_quantity <= #{threshold}")
    long dashboardLowInventoryCount(@Param("threshold") int threshold);
}
