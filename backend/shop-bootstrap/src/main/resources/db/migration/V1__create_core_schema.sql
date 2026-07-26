CREATE TABLE iam_user_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    status VARCHAR(32) NOT NULL,
    nickname VARCHAR(80) NOT NULL,
    avatar_url VARCHAR(500) NULL,
    phone_masked VARCHAR(32) NULL,
    last_login_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_iam_user_public_id (public_id),
    KEY idx_iam_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE iam_external_identity (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    provider VARCHAR(32) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    open_id VARCHAR(128) NOT NULL,
    union_id VARCHAR(128) NULL,
    profile_json JSON NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_external_identity (provider, app_id, open_id),
    KEY idx_external_union_id (union_id),
    KEY idx_external_user_id (user_id),
    CONSTRAINT fk_external_identity_user FOREIGN KEY (user_id) REFERENCES iam_user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE iam_union_principal (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    union_id VARCHAR(128) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_union_principal_union (union_id),
    KEY idx_union_principal_user (user_id),
    CONSTRAINT fk_union_principal_user FOREIGN KEY (user_id) REFERENCES iam_user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE iam_admin_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    linked_user_id BIGINT UNSIGNED NULL,
    must_change_password TINYINT(1) NOT NULL DEFAULT 1,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP(3) NULL,
    last_login_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username),
    UNIQUE KEY uk_admin_linked_user (linked_user_id),
    CONSTRAINT fk_admin_linked_user FOREIGN KEY (linked_user_id) REFERENCES iam_user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE iam_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL,
    builtin TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE iam_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE iam_admin_role (
    admin_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    granted_by BIGINT UNSIGNED NULL,
    granted_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (admin_id, role_id),
    CONSTRAINT fk_admin_role_admin FOREIGN KEY (admin_id) REFERENCES iam_admin_account (id),
    CONSTRAINT fk_admin_role_role FOREIGN KEY (role_id) REFERENCES iam_role (id),
    CONSTRAINT fk_admin_role_granted_by FOREIGN KEY (granted_by) REFERENCES iam_admin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE iam_role_permission (
    role_id BIGINT UNSIGNED NOT NULL,
    permission_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES iam_role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES iam_permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE customer_profile (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    real_name_masked VARCHAR(80) NULL,
    gender VARCHAR(16) NULL,
    locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_profile_user (user_id),
    CONSTRAINT fk_customer_profile_user FOREIGN KEY (user_id) REFERENCES iam_user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE customer_invitation_code (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    inviter_user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP(3) NULL,
    max_uses INT NULL,
    use_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at TIMESTAMP(3) NULL,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_invitation_code (code),
    KEY idx_invitation_inviter (inviter_user_id),
    CONSTRAINT fk_invitation_inviter FOREIGN KEY (inviter_user_id) REFERENCES iam_user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE customer_relation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_user_id BIGINT UNSIGNED NOT NULL,
    superior_user_id BIGINT UNSIGNED NOT NULL,
    invitation_id BIGINT UNSIGNED NOT NULL,
    bound_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_relation_member (member_user_id),
    KEY idx_relation_superior (superior_user_id),
    CONSTRAINT fk_relation_member FOREIGN KEY (member_user_id) REFERENCES iam_user_account (id),
    CONSTRAINT fk_relation_superior FOREIGN KEY (superior_user_id) REFERENCES iam_user_account (id),
    CONSTRAINT fk_relation_invitation FOREIGN KEY (invitation_id) REFERENCES customer_invitation_code (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE customer_address (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    recipient_name VARCHAR(80) NOT NULL,
    phone_masked VARCHAR(32) NOT NULL,
    province VARCHAR(80) NOT NULL,
    city VARCHAR(80) NOT NULL,
    district VARCHAR(80) NOT NULL,
    detail_address VARCHAR(255) NOT NULL,
    postal_code VARCHAR(20) NULL,
    is_default TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_address_user (user_id),
    CONSTRAINT fk_address_user FOREIGN KEY (user_id) REFERENCES iam_user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE catalog_category (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    parent_id BIGINT UNSIGNED NULL,
    name VARCHAR(80) NOT NULL,
    code VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_code (code),
    KEY idx_category_parent (parent_id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES catalog_category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE catalog_product (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    category_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(160) NOT NULL,
    subtitle VARCHAR(255) NULL,
    cover_url VARCHAR(500) NULL,
    description_html MEDIUMTEXT NULL,
    sales_scene VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_product_category_status (category_id, status),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES catalog_category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE catalog_sku (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id BIGINT UNSIGNED NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    price_fen BIGINT NOT NULL,
    market_price_fen BIGINT NULL,
    attributes_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_code (sku_code),
    KEY idx_sku_product_status (product_id, status),
    CONSTRAINT fk_sku_product FOREIGN KEY (product_id) REFERENCES catalog_product (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE catalog_inventory (
    sku_id BIGINT UNSIGNED NOT NULL,
    available_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (sku_id),
    CONSTRAINT fk_inventory_sku FOREIGN KEY (sku_id) REFERENCES catalog_sku (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE trade_cart_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL,
    quantity INT NOT NULL,
    selected TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cart_user_sku (user_id, sku_id),
    CONSTRAINT fk_cart_user FOREIGN KEY (user_id) REFERENCES iam_user_account (id),
    CONSTRAINT fk_cart_sku FOREIGN KEY (sku_id) REFERENCES catalog_sku (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE trade_order (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(40) NOT NULL,
    buyer_user_id BIGINT UNSIGNED NOT NULL,
    superior_user_id BIGINT UNSIGNED NOT NULL,
    address_snapshot_json JSON NOT NULL,
    total_amount_fen BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL,
    source VARCHAR(24) NOT NULL,
    client_request_id VARCHAR(80) NOT NULL,
    superior_confirmed_at TIMESTAMP(3) NULL,
    admin_reviewed_at TIMESTAMP(3) NULL,
    shipped_at TIMESTAMP(3) NULL,
    auto_receive_at TIMESTAMP(3) NULL,
    completed_at TIMESTAMP(3) NULL,
    cancelled_at TIMESTAMP(3) NULL,
    reason VARCHAR(500) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_order_client_request (buyer_user_id, client_request_id),
    KEY idx_order_buyer_status (buyer_user_id, status, created_at),
    KEY idx_order_superior_status (superior_user_id, status, created_at),
    KEY idx_order_auto_receive (status, auto_receive_at),
    CONSTRAINT fk_order_buyer FOREIGN KEY (buyer_user_id) REFERENCES iam_user_account (id),
    CONSTRAINT fk_order_superior FOREIGN KEY (superior_user_id) REFERENCES iam_user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE trade_order_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL,
    product_name VARCHAR(160) NOT NULL,
    sku_name VARCHAR(160) NOT NULL,
    cover_url VARCHAR(500) NULL,
    sales_scene VARCHAR(32) NOT NULL,
    unit_price_fen BIGINT NOT NULL,
    quantity INT NOT NULL,
    subtotal_fen BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_order_item_order (order_id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) REFERENCES catalog_product (id),
    CONSTRAINT fk_order_item_sku FOREIGN KEY (sku_id) REFERENCES catalog_sku (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE trade_order_proof (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    media_type VARCHAR(80) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_by BIGINT UNSIGNED NOT NULL,
    retain_until TIMESTAMP(3) NULL,
    cleaned_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_proof_object_key (object_key),
    KEY idx_order_proof_order (order_id),
    KEY idx_order_proof_retention (cleaned_at, retain_until),
    CONSTRAINT fk_order_proof_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_order_proof_uploader FOREIGN KEY (uploaded_by) REFERENCES iam_user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fulfillment_shipment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id BIGINT UNSIGNED NOT NULL,
    carrier_code VARCHAR(64) NOT NULL,
    carrier_name VARCHAR(80) NOT NULL,
    tracking_no VARCHAR(100) NOT NULL,
    shipped_by_admin_id BIGINT UNSIGNED NOT NULL,
    shipped_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_order (order_id),
    KEY idx_shipment_tracking (tracking_no),
    CONSTRAINT fk_shipment_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_shipment_admin FOREIGN KEY (shipped_by_admin_id) REFERENCES iam_admin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE operation_rule_version (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rule_code VARCHAR(80) NOT NULL,
    version_no INT NOT NULL,
    rule_type VARCHAR(64) NOT NULL,
    parameters_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    effective_from TIMESTAMP(3) NOT NULL,
    effective_to TIMESTAMP(3) NULL,
    published_by_admin_id BIGINT UNSIGNED NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_code_version (rule_code, version_no),
    KEY idx_rule_effective (rule_code, status, effective_from, effective_to),
    CONSTRAINT fk_rule_publisher FOREIGN KEY (published_by_admin_id) REFERENCES iam_admin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE membership_level (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(80) NOT NULL,
    rank_no INT NOT NULL,
    invitation_enabled TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_membership_level_code (code),
    UNIQUE KEY uk_membership_level_rank (rank_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE membership_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    current_level_id BIGINT UNSIGNED NOT NULL,
    qualified_at TIMESTAMP(3) NOT NULL,
    last_performance_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_membership_user (user_id),
    KEY idx_membership_level (current_level_id),
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES iam_user_account (id),
    CONSTRAINT fk_membership_level FOREIGN KEY (current_level_id) REFERENCES membership_level (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE membership_evidence (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    source_order_id BIGINT UNSIGNED NOT NULL,
    rule_version_id BIGINT UNSIGNED NOT NULL,
    value_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    invalidated_by_after_sale_id BIGINT UNSIGNED NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    invalidated_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_membership_evidence_source (user_id, evidence_type, source_order_id, rule_version_id),
    KEY idx_membership_evidence_user_status (user_id, status),
    CONSTRAINT fk_evidence_user FOREIGN KEY (user_id) REFERENCES iam_user_account (id),
    CONSTRAINT fk_evidence_order FOREIGN KEY (source_order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_evidence_rule FOREIGN KEY (rule_version_id) REFERENCES operation_rule_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE distribution_direct_performance (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    beneficiary_user_id BIGINT UNSIGNED NOT NULL,
    referred_user_id BIGINT UNSIGNED NOT NULL,
    source_order_id BIGINT UNSIGNED NOT NULL,
    rule_version_id BIGINT UNSIGNED NOT NULL,
    completed_ordinal INT NOT NULL,
    performance_fen BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    reversed_by_after_sale_id BIGINT UNSIGNED NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reversed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_direct_performance_order (beneficiary_user_id, source_order_id),
    KEY idx_direct_performance_beneficiary (beneficiary_user_id, status, completed_ordinal),
    CONSTRAINT fk_direct_performance_beneficiary FOREIGN KEY (beneficiary_user_id) REFERENCES iam_user_account (id),
    CONSTRAINT fk_direct_performance_referred FOREIGN KEY (referred_user_id) REFERENCES iam_user_account (id),
    CONSTRAINT fk_direct_performance_order FOREIGN KEY (source_order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_direct_performance_rule FOREIGN KEY (rule_version_id) REFERENCES operation_rule_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ledger_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    account_type VARCHAR(40) NOT NULL,
    available_points BIGINT NOT NULL DEFAULT 0,
    frozen_points BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_user_type (user_id, account_type),
    CONSTRAINT fk_ledger_account_user FOREIGN KEY (user_id) REFERENCES iam_user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ledger_entry (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id BIGINT UNSIGNED NOT NULL,
    entry_type VARCHAR(48) NOT NULL,
    available_delta BIGINT NOT NULL,
    frozen_delta BIGINT NOT NULL,
    source_type VARCHAR(48) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    source_order_id BIGINT UNSIGNED NULL,
    rule_version_id BIGINT UNSIGNED NULL,
    original_entry_id BIGINT UNSIGNED NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_idempotency (idempotency_key),
    KEY idx_ledger_account_time (account_id, occurred_at),
    KEY idx_ledger_source_order (source_order_id),
    CONSTRAINT fk_ledger_entry_account FOREIGN KEY (account_id) REFERENCES ledger_account (id),
    CONSTRAINT fk_ledger_entry_order FOREIGN KEY (source_order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_ledger_entry_rule FOREIGN KEY (rule_version_id) REFERENCES operation_rule_version (id),
    CONSTRAINT fk_ledger_entry_original FOREIGN KEY (original_entry_id) REFERENCES ledger_entry (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE trade_after_sale (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    after_sale_no VARCHAR(40) NOT NULL,
    order_id BIGINT UNSIGNED NOT NULL,
    applicant_user_id BIGINT UNSIGNED NOT NULL,
    type VARCHAR(40) NOT NULL,
    status VARCHAR(48) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    description VARCHAR(2000) NULL,
    admin_reason VARCHAR(500) NULL,
    return_address_json JSON NULL,
    return_carrier VARCHAR(80) NULL,
    return_tracking_no VARCHAR(100) NULL,
    refund_confirmed_by_user_id BIGINT UNSIGNED NULL,
    refund_confirmed_at TIMESTAMP(3) NULL,
    completed_at TIMESTAMP(3) NULL,
    client_request_id VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_after_sale_no (after_sale_no),
    UNIQUE KEY uk_after_sale_client_request (applicant_user_id, client_request_id),
    KEY idx_after_sale_order_status (order_id, status),
    CONSTRAINT fk_after_sale_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_after_sale_applicant FOREIGN KEY (applicant_user_id) REFERENCES iam_user_account (id),
    CONSTRAINT fk_after_sale_refund_confirmer FOREIGN KEY (refund_confirmed_by_user_id) REFERENCES iam_user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE membership_evidence
    ADD CONSTRAINT fk_evidence_after_sale
        FOREIGN KEY (invalidated_by_after_sale_id) REFERENCES trade_after_sale (id);

ALTER TABLE distribution_direct_performance
    ADD CONSTRAINT fk_direct_performance_after_sale
        FOREIGN KEY (reversed_by_after_sale_id) REFERENCES trade_after_sale (id);

CREATE TABLE trade_after_sale_proof (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    after_sale_id BIGINT UNSIGNED NOT NULL,
    proof_type VARCHAR(40) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    media_type VARCHAR(80) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_by_user_id BIGINT UNSIGNED NULL,
    uploaded_by_admin_id BIGINT UNSIGNED NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_after_sale_proof_object_key (object_key),
    KEY idx_after_sale_proof_after_sale (after_sale_id),
    CONSTRAINT fk_after_sale_proof_after_sale FOREIGN KEY (after_sale_id) REFERENCES trade_after_sale (id),
    CONSTRAINT fk_after_sale_proof_user FOREIGN KEY (uploaded_by_user_id) REFERENCES iam_user_account (id),
    CONSTRAINT fk_after_sale_proof_admin FOREIGN KEY (uploaded_by_admin_id) REFERENCES iam_admin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE operation_content (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    content_type VARCHAR(40) NOT NULL,
    title VARCHAR(160) NOT NULL,
    summary VARCHAR(500) NULL,
    cover_url VARCHAR(500) NULL,
    target_url VARCHAR(500) NULL,
    body_html MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    published_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_content_type_status (content_type, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE operation_audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(80) NOT NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(80) NOT NULL,
    before_json JSON NULL,
    after_json JSON NULL,
    reason VARCHAR(500) NULL,
    request_id VARCHAR(80) NOT NULL,
    ip_masked VARCHAR(80) NULL,
    user_agent_summary VARCHAR(255) NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_resource (resource_type, resource_id, occurred_at),
    KEY idx_audit_actor (actor_type, actor_id, occurred_at),
    KEY idx_audit_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_outbox_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload_json JSON NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL,
    published_at TIMESTAMP(3) NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_pending (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_inbox_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    consumer_name VARCHAR(100) NOT NULL,
    event_id CHAR(36) NOT NULL,
    processed_at TIMESTAMP(3) NOT NULL,
    result VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbox_consumer_event (consumer_name, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_idempotency_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    namespace VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_type VARCHAR(80) NULL,
    resource_id VARCHAR(80) NULL,
    status VARCHAR(32) NOT NULL,
    response_json JSON NULL,
    expires_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_namespace_key (namespace, idempotency_key),
    KEY idx_idempotency_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_job_lease (
    job_name VARCHAR(100) NOT NULL,
    owner_id VARCHAR(100) NOT NULL,
    lease_until TIMESTAMP(3) NOT NULL,
    heartbeat_at TIMESTAMP(3) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (job_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
