-- V19 supplied the four after-sale stage defaults through COALESCE. MySQL
-- persisted those fallback values as JSON strings, while the runtime rule
-- codec accepts only JSON integers. Repair only those known fields and leave
-- every other policy value untouched.
UPDATE operation_rule_version
SET parameters_json = JSON_SET(
        parameters_json,
        '$.awaitingReturnTimeoutDays',
            CAST(CASE
                WHEN JSON_TYPE(JSON_EXTRACT(parameters_json, '$.awaitingReturnTimeoutDays')) = 'INTEGER'
                     AND JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.awaitingReturnTimeoutDays'))
                             BETWEEN 1 AND 365
                    THEN JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.awaitingReturnTimeoutDays'))
                WHEN JSON_TYPE(JSON_EXTRACT(parameters_json, '$.awaitingReturnTimeoutDays')) = 'STRING'
                     AND JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.awaitingReturnTimeoutDays'))
                             REGEXP '^0*[1-9][0-9]{0,2}$'
                     AND CAST(JSON_UNQUOTE(JSON_EXTRACT(
                             parameters_json, '$.awaitingReturnTimeoutDays')) AS SIGNED)
                             BETWEEN 1 AND 365
                    THEN JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.awaitingReturnTimeoutDays'))
                ELSE 15
            END AS SIGNED),
        '$.returnShippedTimeoutDays',
            CAST(CASE
                WHEN JSON_TYPE(JSON_EXTRACT(parameters_json, '$.returnShippedTimeoutDays')) = 'INTEGER'
                     AND JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.returnShippedTimeoutDays'))
                             BETWEEN 1 AND 365
                    THEN JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.returnShippedTimeoutDays'))
                WHEN JSON_TYPE(JSON_EXTRACT(parameters_json, '$.returnShippedTimeoutDays')) = 'STRING'
                     AND JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.returnShippedTimeoutDays'))
                             REGEXP '^0*[1-9][0-9]{0,2}$'
                     AND CAST(JSON_UNQUOTE(JSON_EXTRACT(
                             parameters_json, '$.returnShippedTimeoutDays')) AS SIGNED)
                             BETWEEN 1 AND 365
                    THEN JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.returnShippedTimeoutDays'))
                ELSE 15
            END AS SIGNED),
        '$.offlineRefundTimeoutDays',
            CAST(CASE
                WHEN JSON_TYPE(JSON_EXTRACT(parameters_json, '$.offlineRefundTimeoutDays')) = 'INTEGER'
                     AND JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.offlineRefundTimeoutDays'))
                             BETWEEN 1 AND 365
                    THEN JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.offlineRefundTimeoutDays'))
                WHEN JSON_TYPE(JSON_EXTRACT(parameters_json, '$.offlineRefundTimeoutDays')) = 'STRING'
                     AND JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.offlineRefundTimeoutDays'))
                             REGEXP '^0*[1-9][0-9]{0,2}$'
                     AND CAST(JSON_UNQUOTE(JSON_EXTRACT(
                             parameters_json, '$.offlineRefundTimeoutDays')) AS SIGNED)
                             BETWEEN 1 AND 365
                    THEN JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.offlineRefundTimeoutDays'))
                ELSE 7
            END AS SIGNED),
        '$.buyerRefundConfirmTimeoutDays',
            CAST(CASE
                WHEN JSON_TYPE(JSON_EXTRACT(parameters_json, '$.buyerRefundConfirmTimeoutDays')) = 'INTEGER'
                     AND JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.buyerRefundConfirmTimeoutDays'))
                             BETWEEN 1 AND 365
                    THEN JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.buyerRefundConfirmTimeoutDays'))
                WHEN JSON_TYPE(JSON_EXTRACT(parameters_json, '$.buyerRefundConfirmTimeoutDays')) = 'STRING'
                     AND JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.buyerRefundConfirmTimeoutDays'))
                             REGEXP '^0*[1-9][0-9]{0,2}$'
                     AND CAST(JSON_UNQUOTE(JSON_EXTRACT(
                             parameters_json, '$.buyerRefundConfirmTimeoutDays')) AS SIGNED)
                             BETWEEN 1 AND 365
                    THEN JSON_UNQUOTE(JSON_EXTRACT(parameters_json, '$.buyerRefundConfirmTimeoutDays'))
                ELSE 7
            END AS SIGNED)
    )
WHERE rule_code = 'ORDER_TIMERS'
  AND rule_type = 'ORDER_TIMER';

-- Restore missing deadlines only from the order's immutable timer snapshot.
-- Existing non-null deadlines are intentionally preserved.
UPDATE trade_order orders
JOIN trade_order_rule_snapshot snapshot
  ON snapshot.order_id = orders.id
 AND snapshot.rule_code = 'ORDER_TIMERS'
JOIN operation_rule_version rules
  ON rules.id = snapshot.rule_version_id
 AND rules.rule_code = 'ORDER_TIMERS'
 AND rules.rule_type = 'ORDER_TIMER'
SET orders.status_due_at = CASE orders.status
    WHEN 'PENDING_SUPERIOR' THEN TIMESTAMPADD(
        DAY,
        JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays')),
        orders.created_at
    )
    WHEN 'PENDING_ADMIN_REVIEW' THEN TIMESTAMPADD(
        DAY,
        JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays')),
        orders.superior_confirmed_at
    )
    WHEN 'PENDING_SHIPMENT' THEN TIMESTAMPADD(
        DAY,
        JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays')),
        orders.admin_reviewed_at
    )
    ELSE NULL
END
WHERE orders.status IN ('PENDING_SUPERIOR', 'PENDING_ADMIN_REVIEW', 'PENDING_SHIPMENT')
  AND orders.status_due_at IS NULL
  AND JSON_TYPE(rules.parameters_json) = 'OBJECT'
  AND JSON_LENGTH(rules.parameters_json) = 12
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.afterSaleDaysAfterCompletion')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.afterSaleDaysAfterCompletion')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.proofRetentionDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.proofRetentionDays')) BETWEEN 1 AND 3650
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.maxProofFiles')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.maxProofFiles')) BETWEEN 1 AND 20
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.maxProofSizeBytes')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.maxProofSizeBytes')) BETWEEN 1024 AND 20971520;

UPDATE trade_order orders
JOIN trade_order_rule_snapshot snapshot
  ON snapshot.order_id = orders.id
 AND snapshot.rule_code = 'ORDER_TIMERS'
JOIN operation_rule_version rules
  ON rules.id = snapshot.rule_version_id
 AND rules.rule_code = 'ORDER_TIMERS'
 AND rules.rule_type = 'ORDER_TIMER'
SET orders.auto_receive_at = TIMESTAMPADD(
        DAY,
        JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')),
        orders.shipped_at
    )
WHERE orders.status = 'SHIPPED'
  AND orders.shipped_at IS NOT NULL
  AND orders.auto_receive_at IS NULL
  AND JSON_TYPE(rules.parameters_json) = 'OBJECT'
  AND JSON_LENGTH(rules.parameters_json) = 12
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.afterSaleDaysAfterCompletion')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.afterSaleDaysAfterCompletion')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.proofRetentionDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.proofRetentionDays')) BETWEEN 1 AND 3650
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.maxProofFiles')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.maxProofFiles')) BETWEEN 1 AND 20
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.maxProofSizeBytes')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.maxProofSizeBytes')) BETWEEN 1024 AND 20971520;

UPDATE trade_after_sale sales
JOIN trade_order orders
  ON orders.id = sales.order_id
JOIN trade_order_rule_snapshot snapshot
  ON snapshot.order_id = orders.id
 AND snapshot.rule_code = 'ORDER_TIMERS'
JOIN operation_rule_version rules
  ON rules.id = snapshot.rule_version_id
 AND rules.rule_code = 'ORDER_TIMERS'
 AND rules.rule_type = 'ORDER_TIMER'
SET sales.state_due_at = CASE sales.status
    WHEN 'AWAITING_RETURN' THEN TIMESTAMPADD(
        DAY,
        JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays')),
        sales.state_entered_at
    )
    WHEN 'RETURN_SHIPPED' THEN TIMESTAMPADD(
        DAY,
        JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays')),
        sales.state_entered_at
    )
    WHEN 'PENDING_OFFLINE_REFUND' THEN TIMESTAMPADD(
        DAY,
        JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays')),
        sales.state_entered_at
    )
    WHEN 'PENDING_BUYER_REFUND_CONFIRMATION' THEN TIMESTAMPADD(
        DAY,
        JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')),
        sales.state_entered_at
    )
    ELSE NULL
END
WHERE sales.status IN (
          'AWAITING_RETURN',
          'RETURN_SHIPPED',
          'PENDING_OFFLINE_REFUND',
          'PENDING_BUYER_REFUND_CONFIRMATION'
      )
  AND sales.state_due_at IS NULL
  AND JSON_TYPE(rules.parameters_json) = 'OBJECT'
  AND JSON_LENGTH(rules.parameters_json) = 12
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.afterSaleDaysAfterCompletion')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.afterSaleDaysAfterCompletion')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')) BETWEEN 1 AND 365
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.proofRetentionDays')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.proofRetentionDays')) BETWEEN 1 AND 3650
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.maxProofFiles')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.maxProofFiles')) BETWEEN 1 AND 20
  AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.maxProofSizeBytes')) = 'INTEGER'
  AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.maxProofSizeBytes')) BETWEEN 1024 AND 20971520;
