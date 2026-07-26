ALTER TABLE customer_address
    ADD COLUMN deleted_at TIMESTAMP(3) NULL AFTER is_default,
    ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER deleted_at,
    ADD KEY idx_address_user_active (user_id, deleted_at, is_default);

ALTER TABLE trade_after_sale_proof
    ADD COLUMN retain_until TIMESTAMP(3) NULL AFTER uploaded_by_admin_id,
    ADD COLUMN cleaned_at TIMESTAMP(3) NULL AFTER retain_until,
    ADD KEY idx_after_sale_proof_retention (cleaned_at, retain_until);

CREATE TABLE catalog_inventory_adjustment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sku_id BIGINT UNSIGNED NOT NULL,
    admin_id BIGINT UNSIGNED NOT NULL,
    before_quantity INT NOT NULL,
    after_quantity INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_adjustment_request (request_id),
    KEY idx_inventory_adjustment_sku (sku_id, created_at),
    CONSTRAINT fk_inventory_adjustment_sku FOREIGN KEY (sku_id) REFERENCES catalog_sku (id),
    CONSTRAINT fk_inventory_adjustment_admin FOREIGN KEY (admin_id) REFERENCES iam_admin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE trade_order_note (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    admin_id BIGINT UNSIGNED NOT NULL,
    note VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_order_note_order (order_id, created_at),
    CONSTRAINT fk_order_note_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_order_note_admin FOREIGN KEY (admin_id) REFERENCES iam_admin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE membership_level_change (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    before_level_code VARCHAR(40) NOT NULL,
    after_level_code VARCHAR(40) NOT NULL,
    trigger_type VARCHAR(64) NOT NULL,
    trigger_id VARCHAR(80) NULL,
    rule_version_id BIGINT UNSIGNED NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(80) NOT NULL,
    reason VARCHAR(500) NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_level_change_idempotency (idempotency_key),
    KEY idx_level_change_user (user_id, occurred_at),
    CONSTRAINT fk_level_change_user FOREIGN KEY (user_id) REFERENCES iam_user_account (id),
    CONSTRAINT fk_level_change_rule FOREIGN KEY (rule_version_id) REFERENCES operation_rule_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE operation_notification (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    recipient_type VARCHAR(32) NOT NULL,
    recipient_id BIGINT UNSIGNED NOT NULL,
    channel VARCHAR(32) NOT NULL,
    template_code VARCHAR(80) NOT NULL,
    title VARCHAR(160) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    business_type VARCHAR(64) NOT NULL,
    business_id VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    sent_at TIMESTAMP(3) NULL,
    read_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_idempotency (idempotency_key),
    KEY idx_notification_recipient (recipient_type, recipient_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO iam_permission (code, name) VALUES
    ('content:write', '内容运营'),
    ('notification:read', '通知查看'),
    ('order:audit', '订单凭证审计')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT IGNORE INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code IN ('content:write', 'notification:read', 'order:audit')
WHERE r.code = 'SUPER_ADMIN';

INSERT IGNORE INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code = 'content:write'
WHERE r.code = 'CATALOG_OPERATOR';
