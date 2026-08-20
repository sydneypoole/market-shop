package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.CartRow;
import com.marketshop.infrastructure.persistence.model.CommercePersistenceModels.CategoryRow;
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
import com.marketshop.infrastructure.persistence.model.DistributionPersistenceModels.RuleRow;
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
            SELECT p.id AS product_id, p.category_id, c.name AS category_name,
                   p.name, p.subtitle, p.cover_url, p.description_html, p.sales_scene,
                   CAST(SUBSTRING_INDEX(GROUP_CONCAT(s.id ORDER BY s.price_fen, s.id), ',', 1) AS UNSIGNED) AS sku_id,
                   SUBSTRING_INDEX(GROUP_CONCAT(s.name ORDER BY s.price_fen, s.id SEPARATOR '||'), '||', 1) AS sku_name,
                   MIN(s.price_fen) AS price_fen,
                   MIN(COALESCE(s.market_price_fen, s.price_fen)) AS market_price_fen,
                   MIN(s.price_fen) AS min_price_fen,
                   MAX(s.price_fen) AS max_price_fen,
                   SUM(i.available_quantity) AS inventory,
                   COUNT(*) AS sku_count
            FROM catalog_product p
            JOIN catalog_category c ON c.id = p.category_id AND c.status = 'ACTIVE'
            JOIN catalog_sku s ON s.product_id = p.id AND s.status = 'ON_SALE'
            JOIN catalog_inventory i ON i.sku_id = s.id AND i.available_quantity > 0
            WHERE p.status = 'ON_SALE'
            GROUP BY p.id, p.category_id, c.name, p.name, p.subtitle, p.cover_url,
                     p.description_html, p.sales_scene, p.sort_order
            ORDER BY p.sort_order, p.id
            """)
    List<ProductRow> products();

    @Select("""
            SELECT p.id AS product_id, p.category_id, c.name AS category_name,
                   p.name, p.subtitle, p.cover_url, p.description_html, p.sales_scene,
                   CAST(SUBSTRING_INDEX(GROUP_CONCAT(s.id ORDER BY s.price_fen, s.id), ',', 1) AS UNSIGNED) AS sku_id,
                   SUBSTRING_INDEX(GROUP_CONCAT(s.name ORDER BY s.price_fen, s.id SEPARATOR '||'), '||', 1) AS sku_name,
                   MIN(s.price_fen) AS price_fen,
                   MIN(COALESCE(s.market_price_fen, s.price_fen)) AS market_price_fen,
                   MIN(s.price_fen) AS min_price_fen,
                   MAX(s.price_fen) AS max_price_fen,
                   SUM(i.available_quantity) AS inventory,
                   COUNT(*) AS sku_count
            FROM catalog_product p
            JOIN catalog_category c ON c.id = p.category_id AND c.status = 'ACTIVE'
            JOIN catalog_sku s ON s.product_id = p.id AND s.status = 'ON_SALE'
            JOIN catalog_inventory i ON i.sku_id = s.id
            WHERE p.id = #{productId} AND p.status = 'ON_SALE'
            GROUP BY p.id, p.category_id, c.name, p.name, p.subtitle, p.cover_url,
                     p.description_html, p.sales_scene, p.sort_order
            """)
    ProductRow product(@Param("productId") long productId);

    @Select("""
            SELECT s.id AS sku_id, s.sku_code, s.name AS sku_name, s.price_fen,
                   COALESCE(s.market_price_fen, s.price_fen) AS market_price_fen,
                   CAST(s.attributes_json AS CHAR) AS attributes_json,
                   i.available_quantity AS inventory
            FROM catalog_sku s
            JOIN catalog_inventory i ON i.sku_id = s.id
            WHERE s.product_id = #{productId} AND s.status = 'ON_SALE'
            ORDER BY s.price_fen, s.id
            """)
    List<ProductRow> productSkus(@Param("productId") long productId);

    @Select("""
            SELECT c.id, c.parent_id, c.name, c.code, c.sort_order,
                   COUNT(DISTINCT p.id) AS product_count
            FROM catalog_category c
            LEFT JOIN catalog_product p ON p.category_id = c.id AND p.status = 'ON_SALE'
            WHERE c.status = 'ACTIVE'
            GROUP BY c.id, c.parent_id, c.name, c.code, c.sort_order
            ORDER BY c.sort_order, c.id
            """)
    List<CategoryRow> categories();

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
            SELECT id, content_type, title, summary, cover_url, target_url, body_html
            FROM operation_content
            WHERE status = 'PUBLISHED'
            ORDER BY sort_order, id
            """)
    List<ContentRow> contents();

    @Select("""
            SELECT id, content_type, title, summary, cover_url, target_url, body_html
            FROM operation_content
            WHERE id = #{contentId} AND status = 'PUBLISHED'
            """)
    ContentRow content(@Param("contentId") long contentId);

    @Select("""
            SELECT c.id, c.sku_id, p.name AS product_name, s.name AS sku_name, p.cover_url,
                   s.price_fen, c.quantity, c.selected, i.available_quantity AS inventory,
                   CASE WHEN s.status = 'ON_SALE' AND p.status = 'ON_SALE' THEN 'ON_SALE' ELSE 'OFF_SALE' END AS sku_status
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
            SELECT superior.id
            FROM customer_relation relation
            JOIN iam_user_account superior ON superior.id = relation.superior_user_id
            JOIN membership_account membership ON membership.user_id = superior.id
            JOIN membership_level current_level ON current_level.id = membership.current_level_id
            WHERE relation.member_user_id = #{buyerUserId}
              AND relation.superior_user_id = #{superiorUserId}
              AND superior.status = 'ACTIVE'
              AND current_level.status = 'ACTIVE'
            LIMIT 1
            """)
    Long availableSuperior(@Param("buyerUserId") long buyerUserId,
                           @Param("superiorUserId") long superiorUserId);

    @Select("""
            SELECT superior.id
            FROM customer_relation relation
            JOIN iam_user_account superior ON superior.id = relation.superior_user_id
            JOIN membership_account membership ON membership.user_id = superior.id
            JOIN membership_level current_level ON current_level.id = membership.current_level_id
            WHERE relation.member_user_id = #{buyerUserId}
              AND relation.superior_user_id = #{superiorUserId}
              AND superior.status = 'ACTIVE'
              AND current_level.status = 'ACTIVE'
            LIMIT 1
            FOR UPDATE
            """)
    Long lockAvailableSuperior(@Param("buyerUserId") long buyerUserId,
                               @Param("superiorUserId") long superiorUserId);

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
            SELECT p.id AS product_id, s.id AS sku_id, p.name AS product_name, s.name AS sku_name,
                   p.cover_url, p.sales_scene, s.price_fen AS unit_price_fen,
                   i.available_quantity
            FROM catalog_sku s
            JOIN catalog_product p ON p.id = s.product_id
            JOIN catalog_inventory i ON i.sku_id = s.id
            WHERE s.id = #{skuId} AND s.status = 'ON_SALE' AND p.status = 'ON_SALE'
            """)
    SkuRow findSku(@Param("skuId") long skuId);

    @Select("""
            SELECT id, order_no, buyer_user_id, superior_user_id, total_amount_fen, status, reason, created_at
            FROM trade_order
            WHERE buyer_user_id = #{userId} AND client_request_id = #{clientRequestId}
            LIMIT 1
            """)
    OrderRow findByClientRequest(@Param("userId") long userId, @Param("clientRequestId") String clientRequestId);

    @Insert("""
            INSERT INTO trade_order
                (order_no, buyer_user_id, superior_user_id, address_snapshot_json, buyer_note,
                 total_amount_fen, status, source, client_request_id, version)
            VALUES
                (#{orderNo}, #{buyerUserId}, #{superiorUserId}, CAST(#{addressSnapshotJson} AS JSON),
                 #{buyerNote}, #{totalAmountFen}, #{status}, #{source}, #{clientRequestId}, #{version})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrder(OrderPo order);

    @Insert("""
            INSERT INTO trade_order_rule_snapshot
                (order_id, rule_code, rule_version_id, snapshotted_at)
            SELECT #{orderId}, rule_code, id, CURRENT_TIMESTAMP(3)
            FROM operation_rule_version
            WHERE id = #{ruleVersionId}
              AND rule_code = 'ORDER_TIMERS'
            """)
    int snapshotOrderTimer(@Param("orderId") long orderId,
                            @Param("ruleVersionId") long ruleVersionId);

    @Update("""
            UPDATE trade_order
            SET status_due_at = TIMESTAMPADD(DAY, #{pendingSuperiorTimeoutDays}, created_at)
            WHERE id = #{orderId} AND status = 'PENDING_SUPERIOR'
            """)
    int initializeOrderStatusDueAt(@Param("orderId") long orderId,
                                   @Param("pendingSuperiorTimeoutDays") int pendingSuperiorTimeoutDays);

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

    @Update("""
            UPDATE catalog_inventory
            SET available_quantity = available_quantity + #{quantity},
                version = version + 1
            WHERE sku_id = #{skuId}
            """)
    int restockAvailableInventory(@Param("skuId") long skuId, @Param("quantity") int quantity);

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
                   buyer_note, total_amount_fen, status, reason, superior_confirmed_at, admin_reviewed_at, shipped_at,
                   auto_receive_at, completed_at, created_at, version
            FROM trade_order
            WHERE id = #{orderId}
            LIMIT 1
            """)
    OrderRow order(@Param("orderId") long orderId);

    @Select("""
            SELECT id, order_no, buyer_user_id, superior_user_id, total_amount_fen,
                   status, reason, created_at, version
            FROM trade_order
            WHERE id = #{orderId}
            LIMIT 1
            FOR UPDATE
            """)
    OrderRow lockOrderForProofUpload(@Param("orderId") long orderId);

    @Select("""
            SELECT id, order_no, buyer_user_id, superior_user_id, total_amount_fen,
                   status, reason, created_at, version
            FROM trade_order
            WHERE id = #{orderId}
            LIMIT 1
            FOR UPDATE
            """)
    OrderRow lockOrderForUpdate(@Param("orderId") long orderId);

    @Select("""
            SELECT COUNT(*)
            FROM trade_after_sale
            WHERE order_id = #{orderId}
              AND status NOT IN ('REJECTED', 'CANCELLED')
            """)
    int countBlockingAfterSales(@Param("orderId") long orderId);

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
                status_due_at = #{statusDueAt},
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
            @Param("statusDueAt") LocalDateTime statusDueAt,
            @Param("reason") String reason,
            @Param("newVersion") int newVersion,
            @Param("expectedVersion") int expectedVersion
    );

    @Insert("""
            INSERT IGNORE INTO trade_order_rule_snapshot
                (order_id, rule_code, rule_version_id, snapshotted_at)
            SELECT orders.id, rules.rule_code, rules.id, orders.completed_at
            FROM trade_order orders
            JOIN operation_rule_version rules
              ON rules.status = 'ACTIVE'
             AND rules.effective_from <= orders.completed_at
             AND (rules.effective_to IS NULL OR rules.effective_to > orders.completed_at)
            WHERE orders.id = #{orderId}
              AND orders.status = 'COMPLETED'
              AND orders.completed_at IS NOT NULL
              AND (
                  (
                      rules.rule_code IN (
                          'EXPERIENCE_OFFICER_UPGRADE',
                          'SUPER_MEMBER_UPGRADE',
                          'DIVIDEND_MEMBER_QUALIFICATION',
                          'DIRECT_REFERRAL_POINTS'
                      )
                      AND EXISTS (
                          SELECT 1 FROM trade_order_item upgrade_item
                          WHERE upgrade_item.order_id = orders.id
                            AND upgrade_item.sales_scene = 'UPGRADE'
                      )
                  )
                  OR (
                      rules.rule_code = 'REPURCHASE_RELEASE'
                      AND EXISTS (
                          SELECT 1 FROM trade_order_item repurchase_item
                          WHERE repurchase_item.order_id = orders.id
                            AND repurchase_item.sales_scene = 'REPURCHASE'
                      )
                  )
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM operation_rule_version newer
                  WHERE newer.rule_code = rules.rule_code
                    AND newer.status = 'ACTIVE'
                    AND newer.version_no > rules.version_no
                    AND newer.effective_from <= orders.completed_at
                    AND (newer.effective_to IS NULL OR newer.effective_to > orders.completed_at)
              )
            """)
    int snapshotApplicableRules(@Param("orderId") long orderId);

    @Select("""
            SELECT CASE WHEN
                (
                    NOT EXISTS (
                        SELECT 1 FROM trade_order_item item
                        WHERE item.order_id = #{orderId} AND item.sales_scene = 'UPGRADE'
                    )
                    OR (
                        EXISTS (
                            SELECT 1
                            FROM trade_order_rule_snapshot snapshot
                            JOIN operation_rule_version rule_version
                              ON rule_version.id = snapshot.rule_version_id
                            WHERE snapshot.order_id = #{orderId}
                              AND rule_version.rule_code IN ('EXPERIENCE_OFFICER_UPGRADE', 'SUPER_MEMBER_UPGRADE')
                        )
                        AND EXISTS (
                            SELECT 1
                            FROM trade_order_rule_snapshot snapshot
                            JOIN operation_rule_version rule_version
                              ON rule_version.id = snapshot.rule_version_id
                            WHERE snapshot.order_id = #{orderId}
                              AND rule_version.rule_code = 'DIVIDEND_MEMBER_QUALIFICATION'
                        )
                        AND EXISTS (
                            SELECT 1
                            FROM trade_order_rule_snapshot snapshot
                            JOIN operation_rule_version rule_version
                              ON rule_version.id = snapshot.rule_version_id
                            WHERE snapshot.order_id = #{orderId}
                              AND rule_version.rule_code = 'DIRECT_REFERRAL_POINTS'
                        )
                    )
                )
                AND (
                    NOT EXISTS (
                        SELECT 1 FROM trade_order_item item
                        WHERE item.order_id = #{orderId} AND item.sales_scene = 'REPURCHASE'
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM trade_order_rule_snapshot snapshot
                        JOIN operation_rule_version rule_version
                          ON rule_version.id = snapshot.rule_version_id
                        WHERE snapshot.order_id = #{orderId}
                          AND rule_version.rule_code = 'REPURCHASE_RELEASE'
                    )
                )
            THEN 1 ELSE 0 END
            """)
    int orderRuleSnapshotComplete(@Param("orderId") long orderId);

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
            SELECT id, rule_code, version_no, rule_type,
                   CAST(parameters_json AS CHAR) AS parameters_json,
                   status, effective_from, effective_to
            FROM operation_rule_version
            WHERE rule_code = 'ORDER_TIMERS'
              AND status = 'ACTIVE'
              AND effective_from <= CURRENT_TIMESTAMP(3)
              AND (effective_to IS NULL OR effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY version_no DESC
            LIMIT 1
            """)
    RuleRow activeOrderTimerRule();

    @Select("""
            SELECT snapshot.rule_code AS rule_code,
                   rule_version.id, rule_version.version_no, rule_version.rule_type,
                   CAST(rule_version.parameters_json AS CHAR) AS parameters_json,
                   rule_version.status, rule_version.effective_from, rule_version.effective_to
            FROM trade_order_rule_snapshot snapshot
            JOIN operation_rule_version rule_version
              ON rule_version.id = snapshot.rule_version_id
            WHERE snapshot.order_id = #{orderId}
              AND snapshot.rule_code = 'ORDER_TIMERS'
            LIMIT 1
            """)
    RuleRow snapshottedOrderTimerRule(@Param("orderId") long orderId);

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

    @Insert("""
            INSERT INTO sys_outbox_event
                (event_id, aggregate_type, aggregate_id, event_type, payload_json,
                 occurred_at, status, next_attempt_at)
            VALUES
                (
                    #{eventId},
                    'ORDER',
                    #{orderId},
                    'ORDER_COMPLETED',
                    JSON_OBJECT(
                        'orderId', #{orderId},
                        'status', 'COMPLETED',
                        'source', #{source},
                        'ruleVersionIds', COALESCE(
                            (
                                SELECT JSON_OBJECTAGG(snapshot.rule_code, snapshot.rule_version_id)
                                FROM trade_order_rule_snapshot snapshot
                                WHERE snapshot.order_id = #{orderId}
                            ),
                            JSON_OBJECT()
                        )
                    ),
                    CURRENT_TIMESTAMP(3),
                    'PENDING',
                    CURRENT_TIMESTAMP(3)
                )
            """)
    int insertCompletedOutbox(
            @Param("eventId") String eventId,
            @Param("orderId") long orderId,
            @Param("source") String source
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

    /**
     * Lock the proof and its owning order before a destructive storage
     * operation. This closes the check-then-delete race where a superior
     * decision could transition the order after the caller checked its old
     * status but before the object was removed.
     */
    @Select("""
            SELECT p.id, p.order_id, p.object_key, p.sha256, p.media_type, p.size_bytes,
                   p.uploaded_by, p.retain_until, p.created_at,
                   o.buyer_user_id, o.superior_user_id, o.status AS order_status
            FROM trade_order_proof p
            JOIN trade_order o ON o.id = p.order_id
            WHERE p.id = #{proofId} AND p.cleaned_at IS NULL
            LIMIT 1
            FOR UPDATE
            """)
    ProofRow lockProof(@Param("proofId") long proofId);

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
            SELECT o.id, o.order_no, o.buyer_user_id, o.superior_user_id,
                   CAST(o.address_snapshot_json AS CHAR) AS address_snapshot_json,
                   o.buyer_note, o.total_amount_fen, o.status, o.reason,
                   o.superior_confirmed_at, o.admin_reviewed_at, o.shipped_at,
                   o.auto_receive_at, o.completed_at, o.created_at, o.version,
                   o.status_due_at,
                   snapshot.rule_code AS timer_rule_code,
                   timer.rule_type AS timer_rule_type,
                   CAST(timer.parameters_json AS CHAR) AS timer_parameters_json
            FROM trade_order o
            LEFT JOIN trade_order_rule_snapshot snapshot
              ON snapshot.order_id = o.id AND snapshot.rule_code = 'ORDER_TIMERS'
            LEFT JOIN operation_rule_version timer
              ON timer.id = snapshot.rule_version_id
            WHERE o.status = 'SHIPPED'
              AND o.auto_receive_at <= CURRENT_TIMESTAMP(3)
              AND NOT EXISTS (
                    SELECT 1
                    FROM trade_after_sale
                    WHERE trade_after_sale.order_id = o.id
                      AND trade_after_sale.status NOT IN ('REJECTED', 'CANCELLED')
              )
            ORDER BY o.auto_receive_at, o.id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    OrderRow lockDueAutoReceive();

    @Select("""
            SELECT o.id, o.order_no, o.buyer_user_id, o.superior_user_id,
                   CAST(o.address_snapshot_json AS CHAR) AS address_snapshot_json,
                   o.buyer_note, o.total_amount_fen, o.status, o.reason,
                   o.superior_confirmed_at, o.admin_reviewed_at, o.shipped_at,
                   o.auto_receive_at, o.completed_at, o.created_at, o.version,
                   o.status_due_at,
                   snapshot.rule_code AS timer_rule_code,
                   timer.rule_type AS timer_rule_type,
                   CAST(timer.parameters_json AS CHAR) AS timer_parameters_json
            FROM trade_order o
            LEFT JOIN trade_order_rule_snapshot snapshot
              ON snapshot.order_id = o.id AND snapshot.rule_code = 'ORDER_TIMERS'
            LEFT JOIN operation_rule_version timer
              ON timer.id = snapshot.rule_version_id
            WHERE o.status IN ('PENDING_SUPERIOR', 'PENDING_ADMIN_REVIEW', 'PENDING_SHIPMENT')
              AND o.status_due_at <= CURRENT_TIMESTAMP(3)
            ORDER BY o.status_due_at, o.id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    OrderRow lockDueOrderTimeout();

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
