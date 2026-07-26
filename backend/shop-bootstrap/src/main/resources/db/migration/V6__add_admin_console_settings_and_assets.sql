CREATE TABLE operation_setting (
    setting_key VARCHAR(80) NOT NULL,
    setting_value VARCHAR(1000) NOT NULL,
    updated_by_admin_id BIGINT UNSIGNED NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (setting_key),
    CONSTRAINT fk_operation_setting_admin
        FOREIGN KEY (updated_by_admin_id) REFERENCES iam_admin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO operation_setting (setting_key, setting_value) VALUES
    ('AFTERSALE_RETURN_RECEIVER', '售后仓'),
    ('AFTERSALE_RETURN_PHONE', '400-000-0000'),
    ('AFTERSALE_RETURN_ADDRESS', '请在运营后台配置真实退货地址'),
    ('LOW_INVENTORY_THRESHOLD', '10');

CREATE TABLE catalog_media_asset (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    object_key VARCHAR(500) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    uploaded_by_admin_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_catalog_asset_object_key (object_key),
    KEY idx_catalog_asset_status_created (status, created_at),
    CONSTRAINT fk_catalog_asset_admin
        FOREIGN KEY (uploaded_by_admin_id) REFERENCES iam_admin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO iam_permission (code, name) VALUES
    ('system:setting:manage', '系统配置管理')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT IGNORE INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code = 'system:setting:manage'
WHERE r.code = 'SUPER_ADMIN';
