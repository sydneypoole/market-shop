ALTER TABLE trade_after_sale
    ADD COLUMN completed_order_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN status = 'COMPLETED' THEN order_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_after_sale_completed_order (completed_order_id);

UPDATE operation_rule_version
SET parameters_json = JSON_SET(
        parameters_json,
        '$.pendingSuperiorTimeoutDays', 7,
        '$.pendingAdminReviewTimeoutDays', 7,
        '$.pendingShipmentTimeoutDays', 7
    )
WHERE rule_code = 'ORDER_TIMERS'
  AND status = 'ACTIVE';
