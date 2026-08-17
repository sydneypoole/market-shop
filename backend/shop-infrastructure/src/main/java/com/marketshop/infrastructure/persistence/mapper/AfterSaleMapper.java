package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleRow;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.EligibilityRow;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleProofPo;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleProofRow;
import com.marketshop.infrastructure.persistence.model.AfterSalePersistenceModels.AfterSaleProofUploadAccessRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AfterSaleMapper {

    @Select("""
            SELECT o.id AS order_id, o.buyer_user_id, o.status, o.completed_at,
                   (SELECT COUNT(*) FROM trade_after_sale a
                    WHERE a.order_id = o.id AND a.status NOT IN ('REJECTED', 'COMPLETED', 'CANCELLED'))
                       AS active_after_sale_count
            FROM trade_order o
            WHERE o.id = #{orderId}
            LIMIT 1
            """)
    EligibilityRow orderEligibility(@Param("orderId") long orderId);

    @Select("""
            SELECT a.id, a.after_sale_no, a.order_id, a.applicant_user_id, o.superior_user_id,
                   a.type, a.status, a.reason, a.admin_reason, CAST(a.return_address_json AS CHAR) AS return_address_json,
                   a.return_carrier, a.return_tracking_no, a.created_at, a.completed_at
            FROM trade_after_sale a
            JOIN trade_order o ON o.id = a.order_id
            WHERE a.applicant_user_id = #{userId} AND a.client_request_id = #{clientRequestId}
            LIMIT 1
            """)
    AfterSaleRow findByClientRequest(@Param("userId") long userId, @Param("clientRequestId") String clientRequestId);

    @Insert("""
            INSERT INTO trade_after_sale
                (after_sale_no, order_id, applicant_user_id, type, status, reason, description, client_request_id)
            VALUES
                (#{afterSaleNo}, #{orderId}, #{userId}, #{type}, 'PENDING_ADMIN_REVIEW',
                 #{reason}, #{description}, #{clientRequestId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insertAfterSale(
            @Param("row") InsertRow row,
            @Param("afterSaleNo") String afterSaleNo,
            @Param("orderId") long orderId,
            @Param("userId") long userId,
            @Param("type") String type,
            @Param("reason") String reason,
            @Param("description") String description,
            @Param("clientRequestId") String clientRequestId
    );

    @Select("""
            SELECT a.id, a.after_sale_no, a.order_id, a.applicant_user_id, o.superior_user_id,
                   a.type, a.status, a.reason, a.admin_reason, CAST(a.return_address_json AS CHAR) AS return_address_json,
                   a.return_carrier, a.return_tracking_no, a.created_at, a.completed_at
            FROM trade_after_sale a
            JOIN trade_order o ON o.id = a.order_id
            WHERE a.applicant_user_id = #{userId}
            ORDER BY a.created_at DESC, a.id DESC
            """)
    List<AfterSaleRow> userAfterSales(@Param("userId") long userId);

    @Select("""
            SELECT a.id, a.after_sale_no, a.order_id, a.applicant_user_id, o.superior_user_id,
                   a.type, a.status, a.reason, a.admin_reason, CAST(a.return_address_json AS CHAR) AS return_address_json,
                   a.return_carrier, a.return_tracking_no, a.created_at, a.completed_at
            FROM trade_after_sale a
            JOIN trade_order o ON o.id = a.order_id
            WHERE o.superior_user_id = #{superiorUserId}
            ORDER BY a.id DESC
            """)
    List<AfterSaleRow> superiorAfterSales(@Param("superiorUserId") long superiorUserId);

    @Select("""
            <script>
            SELECT a.id, a.after_sale_no, a.order_id, a.applicant_user_id, o.superior_user_id,
                   a.type, a.status, a.reason, a.admin_reason, CAST(a.return_address_json AS CHAR) AS return_address_json,
                   a.return_carrier, a.return_tracking_no, a.created_at, a.completed_at
            FROM trade_after_sale a
            JOIN trade_order o ON o.id = a.order_id
            <if test="status != null">WHERE a.status = #{status}</if>
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT 500
            </script>
            """)
    List<AfterSaleRow> adminAfterSales(@Param("status") String status);

    @Select("""
            SELECT a.id, a.after_sale_no, a.order_id, a.applicant_user_id, o.superior_user_id,
                   a.type, a.status, a.reason, a.admin_reason, CAST(a.return_address_json AS CHAR) AS return_address_json,
                   a.return_carrier, a.return_tracking_no, a.created_at, a.completed_at
            FROM trade_after_sale a
            JOIN trade_order o ON o.id = a.order_id
            WHERE a.id = #{afterSaleId}
            LIMIT 1
            """)
    AfterSaleRow afterSale(@Param("afterSaleId") long afterSaleId);

    @Update("""
            UPDATE trade_after_sale
            SET status = #{targetStatus},
                state_entered_at = CURRENT_TIMESTAMP(3),
                admin_reason = COALESCE(#{adminReason}, admin_reason),
                return_address_json = COALESCE(CAST(#{returnAddressJson} AS JSON), return_address_json),
                return_carrier = COALESCE(#{returnCarrier}, return_carrier),
                return_tracking_no = COALESCE(#{returnTrackingNo}, return_tracking_no),
                refund_confirmed_by_user_id = COALESCE(#{refundConfirmedByUserId}, refund_confirmed_by_user_id),
                refund_confirmed_at = COALESCE(#{refundConfirmedAt}, refund_confirmed_at),
                completed_at = COALESCE(#{completedAt}, completed_at),
                version = version + 1
            WHERE id = #{afterSaleId} AND status = #{expectedStatus}
            """)
    int transition(
            @Param("afterSaleId") long afterSaleId,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus,
            @Param("adminReason") String adminReason,
            @Param("returnAddressJson") String returnAddressJson,
            @Param("returnCarrier") String returnCarrier,
            @Param("returnTrackingNo") String returnTrackingNo,
            @Param("refundConfirmedByUserId") Long refundConfirmedByUserId,
            @Param("refundConfirmedAt") LocalDateTime refundConfirmedAt,
            @Param("completedAt") LocalDateTime completedAt
    );

    @Select("""
            SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.afterSaleDaysAfterCompletion')) AS UNSIGNED)
            FROM operation_rule_version
            WHERE rule_code = 'ORDER_TIMERS' AND status = 'ACTIVE'
              AND effective_from <= CURRENT_TIMESTAMP(3)
              AND (effective_to IS NULL OR effective_to > CURRENT_TIMESTAMP(3))
            ORDER BY version_no DESC
            LIMIT 1
            """)
    Integer afterSaleWindowDays();

    @Select("""
            SELECT a.id, a.after_sale_no, a.order_id, a.applicant_user_id, o.superior_user_id,
                   a.type, a.status, a.reason, a.admin_reason, CAST(a.return_address_json AS CHAR) AS return_address_json,
                   a.return_carrier, a.return_tracking_no, a.created_at, a.completed_at
            FROM trade_after_sale a
            JOIN trade_order o ON o.id = a.order_id
            WHERE a.status IN ('AWAITING_RETURN', 'RETURN_SHIPPED', 'PENDING_OFFLINE_REFUND', 'PENDING_BUYER_REFUND_CONFIRMATION')
              AND (
                  (a.status = 'AWAITING_RETURN'
                      AND a.state_entered_at + INTERVAL #{awaitingReturnDays} DAY < CURRENT_TIMESTAMP(3))
                  OR (a.status = 'RETURN_SHIPPED'
                      AND a.state_entered_at + INTERVAL #{returnShippedDays} DAY < CURRENT_TIMESTAMP(3))
                  OR (a.status = 'PENDING_OFFLINE_REFUND'
                      AND a.state_entered_at + INTERVAL #{offlineRefundDays} DAY < CURRENT_TIMESTAMP(3))
                  OR (a.status = 'PENDING_BUYER_REFUND_CONFIRMATION'
                      AND a.state_entered_at + INTERVAL #{buyerConfirmDays} DAY < CURRENT_TIMESTAMP(3))
              )
            ORDER BY a.state_entered_at, a.id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    AfterSaleRow lockDueAftersaleTimeout(
            @Param("awaitingReturnDays") int awaitingReturnDays,
            @Param("returnShippedDays") int returnShippedDays,
            @Param("offlineRefundDays") int offlineRefundDays,
            @Param("buyerConfirmDays") int buyerConfirmDays
    );

    @Insert("""
            INSERT INTO sys_outbox_event
                (event_id, aggregate_type, aggregate_id, event_type, payload_json,
                 occurred_at, status, next_attempt_at)
            VALUES
                (#{eventId}, 'AFTERSALE', #{aggregateId}, 'AFTERSALE_COMPLETED',
                 JSON_OBJECT('afterSaleId', #{aggregateId}),
                 CURRENT_TIMESTAMP(3), 'PENDING', CURRENT_TIMESTAMP(3))
            """)
    int insertCompletedOutbox(@Param("eventId") String eventId, @Param("aggregateId") String aggregateId);

    @Select("""
            SELECT COUNT(*)
            FROM trade_after_sale a
            JOIN trade_order o ON o.id = a.order_id
            WHERE a.id = #{afterSaleId}
              AND (a.applicant_user_id = #{userId} OR o.superior_user_id = #{userId})
            """)
    int canUserAccessAfterSale(@Param("userId") long userId, @Param("afterSaleId") long afterSaleId);

    /**
     * Serialize proof uploads with after-sale status transitions and the
     * max-files check. The parent row is the lock anchor; proof rows are then
     * counted and inserted in the same application transaction.
     */
    @Select("""
            SELECT a.applicant_user_id, o.superior_user_id, a.status
            FROM trade_after_sale a
            JOIN trade_order o ON o.id = a.order_id
            WHERE a.id = #{afterSaleId}
            LIMIT 1
            FOR UPDATE
            """)
    AfterSaleProofUploadAccessRow lockAfterSaleForProofUpload(@Param("afterSaleId") long afterSaleId);

    @Select("""
            SELECT COUNT(*) FROM trade_after_sale_proof
            WHERE after_sale_id = #{afterSaleId} AND cleaned_at IS NULL
            """)
    int countAfterSaleProofs(@Param("afterSaleId") long afterSaleId);

    @Insert("""
            INSERT INTO trade_after_sale_proof
                (after_sale_id, proof_type, object_key, sha256, media_type, size_bytes,
                 uploaded_by_user_id, retain_until)
            VALUES
                (#{afterSaleId}, #{proofType}, #{objectKey}, #{sha256}, #{mediaType}, #{sizeBytes},
                 #{uploadedByUserId}, #{retainUntil})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAfterSaleProof(AfterSaleProofPo row);

    @Select("""
            SELECT p.id, p.after_sale_id, p.proof_type, p.object_key, p.sha256, p.media_type,
                   p.size_bytes, p.uploaded_by_user_id, p.retain_until, p.created_at,
                   a.applicant_user_id, o.superior_user_id
            FROM trade_after_sale_proof p
            JOIN trade_after_sale a ON a.id = p.after_sale_id
            JOIN trade_order o ON o.id = a.order_id
            WHERE p.after_sale_id = #{afterSaleId} AND p.cleaned_at IS NULL
            ORDER BY p.id
            """)
    List<AfterSaleProofRow> afterSaleProofs(@Param("afterSaleId") long afterSaleId);

    @Select("""
            SELECT p.id, p.after_sale_id, p.proof_type, p.object_key, p.sha256, p.media_type,
                   p.size_bytes, p.uploaded_by_user_id, p.retain_until, p.created_at,
                   a.applicant_user_id, o.superior_user_id
            FROM trade_after_sale_proof p
            JOIN trade_after_sale a ON a.id = p.after_sale_id
            JOIN trade_order o ON o.id = a.order_id
            WHERE p.id = #{proofId} AND p.cleaned_at IS NULL
            """)
    AfterSaleProofRow afterSaleProof(@Param("proofId") long proofId);

    @Select("""
            SELECT p.id, p.after_sale_id, p.proof_type, p.object_key, p.sha256, p.media_type,
                   p.size_bytes, p.uploaded_by_user_id, p.retain_until, p.created_at,
                   a.applicant_user_id, o.superior_user_id
            FROM trade_after_sale_proof p
            JOIN trade_after_sale a ON a.id = p.after_sale_id
            JOIN trade_order o ON o.id = a.order_id
            WHERE p.id = #{proofId} AND p.cleaned_at IS NULL
            LIMIT 1
            FOR UPDATE
            """)
    AfterSaleProofRow lockAfterSaleProof(@Param("proofId") long proofId);

    @Select("""
            SELECT p.id, p.after_sale_id, p.proof_type, p.object_key, p.sha256, p.media_type,
                   p.size_bytes, p.uploaded_by_user_id, p.retain_until, p.created_at,
                   a.applicant_user_id, o.superior_user_id
            FROM trade_after_sale_proof p
            JOIN trade_after_sale a ON a.id = p.after_sale_id
            JOIN trade_order o ON o.id = a.order_id
            WHERE p.cleaned_at IS NULL AND p.retain_until <= CURRENT_TIMESTAMP(3)
            ORDER BY p.retain_until, p.id
            LIMIT #{limit}
            """)
    List<AfterSaleProofRow> expiredAfterSaleProofs(@Param("limit") int limit);

    @Update("""
            UPDATE trade_after_sale_proof SET cleaned_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{proofId} AND cleaned_at IS NULL
            """)
    int markAfterSaleProofCleaned(@Param("proofId") long proofId);

    class InsertRow {
        public Long id;
    }
}
