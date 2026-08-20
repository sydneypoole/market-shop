ALTER TABLE trade_order
    ADD COLUMN status_due_at TIMESTAMP(3) NULL AFTER status,
    ADD KEY idx_order_status_due (status, status_due_at, id);

ALTER TABLE trade_after_sale
    ADD COLUMN state_due_at TIMESTAMP(3) NULL AFTER state_entered_at,
    ADD KEY idx_after_sale_state_due (status, state_due_at, id);

-- Normalize legacy active ORDER_TIMERS rows before they become immutable order
-- snapshots. The old autoReceiveDaysAfterShipment key is a read-only persisted
-- alias; new publication and runtime output use autoReceiveDays.
UPDATE operation_rule_version
SET parameters_json = JSON_SET(
        JSON_REMOVE(parameters_json, '$.autoReceiveDaysAfterShipment'),
        '$.autoReceiveDays',
            COALESCE(
                JSON_EXTRACT(parameters_json, '$.autoReceiveDays'),
                JSON_EXTRACT(parameters_json, '$.autoReceiveDaysAfterShipment')
            ),
        '$.awaitingReturnTimeoutDays',
            COALESCE(JSON_EXTRACT(parameters_json, '$.awaitingReturnTimeoutDays'), 15),
        '$.returnShippedTimeoutDays',
            COALESCE(JSON_EXTRACT(parameters_json, '$.returnShippedTimeoutDays'), 15),
        '$.offlineRefundTimeoutDays',
            COALESCE(JSON_EXTRACT(parameters_json, '$.offlineRefundTimeoutDays'), 7),
        '$.buyerRefundConfirmTimeoutDays',
            COALESCE(JSON_EXTRACT(parameters_json, '$.buyerRefundConfirmTimeoutDays'), 7)
    )
WHERE rule_code = 'ORDER_TIMERS'
  AND status = 'ACTIVE';

-- Preserve the timer policy that was effective when each legacy order was
-- submitted. Version ordering is explicit so overlapping historical rows are
-- deterministic: the highest version, then highest id, wins.
INSERT IGNORE INTO trade_order_rule_snapshot
    (order_id, rule_code, rule_version_id, snapshotted_at)
SELECT orders.id, rules.rule_code, rules.id, orders.created_at
FROM trade_order orders
JOIN operation_rule_version rules
  ON rules.rule_code = 'ORDER_TIMERS'
 AND rules.status = 'ACTIVE'
 AND rules.effective_from <= orders.created_at
 AND (rules.effective_to IS NULL OR rules.effective_to > orders.created_at)
WHERE NOT EXISTS (
          SELECT 1
          FROM trade_order_rule_snapshot existing
          WHERE existing.order_id = orders.id
            AND existing.rule_code = 'ORDER_TIMERS'
      )
  AND NOT EXISTS (
          SELECT 1
          FROM operation_rule_version newer
          WHERE newer.rule_code = rules.rule_code
            AND newer.status = 'ACTIVE'
            AND newer.effective_from <= orders.created_at
            AND (newer.effective_to IS NULL OR newer.effective_to > orders.created_at)
            AND (
                newer.version_no > rules.version_no
                OR (newer.version_no = rules.version_no AND newer.id > rules.id)
            )
      );

-- Preserve the existing shipment due-time column while aligning every legacy
-- shipped row to its immutable snapshot, including rows that already contain a
-- stale non-null due time. A bad snapshot clears the old value rather than
-- grandfathering it.
UPDATE trade_order orders
LEFT JOIN trade_order_rule_snapshot snapshot
  ON snapshot.order_id = orders.id
 AND snapshot.rule_code = 'ORDER_TIMERS'
LEFT JOIN operation_rule_version rules
  ON rules.id = snapshot.rule_version_id
SET orders.auto_receive_at = NULL
WHERE orders.status = 'SHIPPED'
  AND orders.shipped_at IS NOT NULL
  AND (
      snapshot.id IS NULL
      OR rules.id IS NULL
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')) IS NULL
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')) <> 'INTEGER'
      OR JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')) NOT BETWEEN 1 AND 365
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.afterSaleDaysAfterCompletion')) IS NULL
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.afterSaleDaysAfterCompletion')) <> 'INTEGER'
      OR JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.afterSaleDaysAfterCompletion')) NOT BETWEEN 1 AND 365
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays')) IS NULL
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays')) <> 'INTEGER'
      OR JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays')) NOT BETWEEN 1 AND 365
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays')) IS NULL
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays')) <> 'INTEGER'
      OR JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays')) NOT BETWEEN 1 AND 365
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays')) IS NULL
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays')) <> 'INTEGER'
      OR JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays')) NOT BETWEEN 1 AND 365
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays')) IS NULL
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays')) <> 'INTEGER'
      OR JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays')) NOT BETWEEN 1 AND 365
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays')) IS NULL
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays')) <> 'INTEGER'
      OR JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays')) NOT BETWEEN 1 AND 365
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays')) IS NULL
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays')) <> 'INTEGER'
      OR JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays')) NOT BETWEEN 1 AND 365
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')) IS NULL
      OR JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')) <> 'INTEGER'
      OR JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')) NOT BETWEEN 1 AND 365
  );

UPDATE trade_order orders
JOIN trade_order_rule_snapshot snapshot
  ON snapshot.order_id = orders.id
 AND snapshot.rule_code = 'ORDER_TIMERS'
JOIN operation_rule_version rules
  ON rules.id = snapshot.rule_version_id
SET orders.auto_receive_at = TIMESTAMPADD(
        DAY,
        JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')),
        orders.shipped_at
    )
WHERE orders.status = 'SHIPPED'
  AND orders.shipped_at IS NOT NULL
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
            AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')) BETWEEN 1 AND 365;

-- Backfill due timestamps only from the already-selected immutable snapshot.
-- JSON extraction here is migration-time data conversion, not a runtime policy
-- fallback; an absent or malformed value leaves the due timestamp NULL.
UPDATE trade_order orders
JOIN trade_order_rule_snapshot snapshot
  ON snapshot.order_id = orders.id
 AND snapshot.rule_code = 'ORDER_TIMERS'
JOIN operation_rule_version rules
  ON rules.id = snapshot.rule_version_id
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
  AND (
      JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')) = 'INTEGER'
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
  )
  AND (
      (
          orders.status = 'PENDING_SUPERIOR'
          AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays')) = 'INTEGER'
          AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingSuperiorTimeoutDays'))
                  BETWEEN 1 AND 365
      )
      OR (
          orders.status = 'PENDING_ADMIN_REVIEW'
          AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays')) = 'INTEGER'
          AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingAdminReviewTimeoutDays'))
                  BETWEEN 1 AND 365
      )
      OR (
          orders.status = 'PENDING_SHIPMENT'
          AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays')) = 'INTEGER'
          AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.pendingShipmentTimeoutDays'))
                  BETWEEN 1 AND 365
      )
  );

UPDATE trade_after_sale sales
JOIN trade_order orders
  ON orders.id = sales.order_id
JOIN trade_order_rule_snapshot snapshot
  ON snapshot.order_id = orders.id
 AND snapshot.rule_code = 'ORDER_TIMERS'
JOIN operation_rule_version rules
  ON rules.id = snapshot.rule_version_id
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
  AND (
      JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.autoReceiveDays')) = 'INTEGER'
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
  )
  AND (
      (
          sales.status = 'AWAITING_RETURN'
          AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays')) = 'INTEGER'
          AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.awaitingReturnTimeoutDays'))
                  BETWEEN 1 AND 365
      )
      OR (
          sales.status = 'RETURN_SHIPPED'
          AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays')) = 'INTEGER'
          AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.returnShippedTimeoutDays'))
                  BETWEEN 1 AND 365
      )
      OR (
          sales.status = 'PENDING_OFFLINE_REFUND'
          AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays')) = 'INTEGER'
          AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.offlineRefundTimeoutDays'))
                  BETWEEN 1 AND 365
      )
      OR (
          sales.status = 'PENDING_BUYER_REFUND_CONFIRMATION'
          AND JSON_TYPE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays')) = 'INTEGER'
          AND JSON_UNQUOTE(JSON_EXTRACT(rules.parameters_json, '$.buyerRefundConfirmTimeoutDays'))
                  BETWEEN 1 AND 365
      )
  );
