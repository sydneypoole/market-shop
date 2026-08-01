CREATE TABLE trade_order_rule_snapshot (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    rule_code VARCHAR(80) NOT NULL,
    rule_version_id BIGINT UNSIGNED NOT NULL,
    snapshotted_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_rule_snapshot_code (order_id, rule_code),
    KEY idx_order_rule_snapshot_version (rule_version_id, order_id),
    CONSTRAINT fk_order_rule_snapshot_order
        FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_order_rule_snapshot_version
        FOREIGN KEY (rule_version_id) REFERENCES operation_rule_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Preserve the completion-time rule set for orders that predate this migration.
INSERT IGNORE INTO trade_order_rule_snapshot
    (order_id, rule_code, rule_version_id, snapshotted_at)
SELECT orders.id, rules.rule_code, rules.id, orders.completed_at
FROM trade_order orders
JOIN operation_rule_version rules
  ON rules.status = 'ACTIVE'
 AND rules.effective_from <= orders.completed_at
 AND (rules.effective_to IS NULL OR rules.effective_to > orders.completed_at)
WHERE orders.status = 'COMPLETED'
  AND orders.completed_at IS NOT NULL
  AND (
      (
          rules.rule_type IN ('SELF_ORDER_TASK', 'DIRECT_REFERRAL_TASK', 'DIRECT_REFERRAL_POINTS')
          AND EXISTS (
              SELECT 1
              FROM trade_order_item upgrade_item
              WHERE upgrade_item.order_id = orders.id
                AND upgrade_item.sales_scene = 'UPGRADE'
          )
      )
      OR (
          rules.rule_type = 'FROZEN_POINTS_RELEASE'
          AND EXISTS (
              SELECT 1
              FROM trade_order_item repurchase_item
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
  );

ALTER TABLE sys_outbox_event
    ADD COLUMN dead_at TIMESTAMP(3) NULL AFTER last_error,
    ADD COLUMN replay_count INT NOT NULL DEFAULT 0 AFTER dead_at,
    ADD COLUMN last_replayed_at TIMESTAMP(3) NULL AFTER replay_count,
    ADD COLUMN last_replayed_by_admin_id BIGINT UNSIGNED NULL AFTER last_replayed_at,
    ADD KEY idx_outbox_dead (status, dead_at, id),
    ADD CONSTRAINT fk_outbox_last_replay_admin
        FOREIGN KEY (last_replayed_by_admin_id) REFERENCES iam_admin_account (id);

INSERT INTO iam_permission (code, name) VALUES
    ('outbox:read', 'Outbox 死信查看'),
    ('outbox:replay', 'Outbox 死信重放')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT IGNORE INTO iam_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM iam_role role
JOIN iam_permission permission ON permission.code IN ('outbox:read', 'outbox:replay')
WHERE role.code = 'SUPER_ADMIN';

INSERT IGNORE INTO iam_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM iam_role role
JOIN iam_permission permission ON permission.code = 'outbox:read'
WHERE role.code = 'AUDIT_VIEWER';
