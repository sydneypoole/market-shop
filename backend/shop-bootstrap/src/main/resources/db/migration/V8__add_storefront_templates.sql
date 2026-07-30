CREATE TABLE operation_storefront_template (
    id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    template_code VARCHAR(64) NOT NULL,
    template_name VARCHAR(120) NOT NULL,
    preset_type VARCHAR(24) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    is_active TINYINT(1) NOT NULL DEFAULT 0,
    active_guard TINYINT GENERATED ALWAYS AS (IF(is_active = 1, 1, NULL)) STORED,
    design_tokens_json JSON NOT NULL,
    layout_json JSON NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_by_admin_id BIGINT UNSIGNED NULL,
    updated_by_admin_id BIGINT UNSIGNED NULL,
    published_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_storefront_template_code UNIQUE (template_code),
    CONSTRAINT uk_storefront_single_active UNIQUE (active_guard),
    CONSTRAINT ck_storefront_template_preset CHECK (preset_type IN ('EDITORIAL', 'VIBRANT', 'MINIMAL')),
    CONSTRAINT ck_storefront_template_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT fk_storefront_template_creator FOREIGN KEY (created_by_admin_id) REFERENCES iam_admin_account (id),
    CONSTRAINT fk_storefront_template_updater FOREIGN KEY (updated_by_admin_id) REFERENCES iam_admin_account (id),
    INDEX idx_storefront_template_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO operation_storefront_template
    (template_code, template_name, preset_type, status, is_active, design_tokens_json, layout_json, published_at)
VALUES
    (
        'EDITORIAL_DEFAULT',
        '序章 · 编辑甄选',
        'EDITORIAL',
        'PUBLISHED',
        1,
        JSON_OBJECT(
            'primary', '#173F35',
            'accent', '#C75B45',
            'canvas', '#F4F0E8',
            'surface', '#FFFEFA',
            'ink', '#17201C',
            'muted', '#707970',
            'radius', '24px',
            'headingFont', 'serif'
        ),
        JSON_OBJECT(
            'schemaVersion', 1,
            'sections', JSON_ARRAY(
                JSON_OBJECT('id', 'editorial-announcement', 'type', 'ANNOUNCEMENT', 'enabled', TRUE,
                    'settings', JSON_OBJECT('limit', 3, 'style', 'ticker')),
                JSON_OBJECT('id', 'editorial-hero', 'type', 'HERO', 'enabled', TRUE,
                    'settings', JSON_OBJECT('eyebrow', '本期编辑甄选', 'title', '把日常过成，值得收藏的篇章',
                        'description', '从产地、工艺到日常体验，我们替你认真筛选每一件好物。',
                        'primaryLabel', '浏览本期精选', 'primaryLink', '#products', 'contentType', 'BANNER')),
                JSON_OBJECT('id', 'editorial-categories', 'type', 'CATEGORY_NAV', 'enabled', TRUE,
                    'settings', JSON_OBJECT('title', '按生活场景探索')),
                JSON_OBJECT('id', 'editorial-products', 'type', 'PRODUCT_COLLECTION', 'enabled', TRUE,
                    'settings', JSON_OBJECT('eyebrow', 'SELECTED OBJECTS', 'title', '值得反复使用的日常之物',
                        'description', '简洁、可靠，也保留一点让人愉悦的细节。', 'limit', 8, 'columns', 4, 'scene', 'ALL')),
                JSON_OBJECT('id', 'editorial-story', 'type', 'CONTENT_STORY', 'enabled', TRUE,
                    'settings', JSON_OBJECT('contentType', 'HELP', 'layout', 'split')),
                JSON_OBJECT('id', 'editorial-benefits', 'type', 'SERVICE_BENEFITS', 'enabled', TRUE,
                    'settings', JSON_OBJECT('items', JSON_ARRAY('严格甄选', '上级确认', '平台审核', '售后留痕'))),
                JSON_OBJECT('id', 'editorial-links', 'type', 'QUICK_LINKS', 'enabled', TRUE,
                    'settings', JSON_OBJECT('title', '继续探索'))
            )
        ),
        CURRENT_TIMESTAMP(3)
    ),
    (
        'VIBRANT_DEFAULT',
        '好物热场 · 活力市集',
        'VIBRANT',
        'DRAFT',
        0,
        JSON_OBJECT(
            'primary', '#171717',
            'accent', '#FF5A36',
            'canvas', '#F7F42E',
            'surface', '#FFFDF4',
            'ink', '#111111',
            'muted', '#595959',
            'radius', '16px',
            'headingFont', 'sans'
        ),
        JSON_OBJECT(
            'schemaVersion', 1,
            'sections', JSON_ARRAY(
                JSON_OBJECT('id', 'vibrant-announcement', 'type', 'ANNOUNCEMENT', 'enabled', TRUE,
                    'settings', JSON_OBJECT('limit', 5, 'style', 'ticker')),
                JSON_OBJECT('id', 'vibrant-hero', 'type', 'HERO', 'enabled', TRUE,
                    'settings', JSON_OBJECT('eyebrow', 'TODAY IS A GOOD DAY', 'title', '今天，就挑点真正好用的',
                        'description', '直给的价格信息、清晰的规格选择，让下单更快一步。',
                        'primaryLabel', '马上开逛', 'primaryLink', '#products', 'contentType', 'BANNER')),
                JSON_OBJECT('id', 'vibrant-categories', 'type', 'CATEGORY_NAV', 'enabled', TRUE,
                    'settings', JSON_OBJECT('title', '热门分类')),
                JSON_OBJECT('id', 'vibrant-links', 'type', 'QUICK_LINKS', 'enabled', TRUE,
                    'settings', JSON_OBJECT('title', '快捷入口')),
                JSON_OBJECT('id', 'vibrant-products', 'type', 'PRODUCT_COLLECTION', 'enabled', TRUE,
                    'settings', JSON_OBJECT('eyebrow', 'HOT PICKS', 'title', '本周大家都在买',
                        'description', '高频好物集中陈列，快速比较，直接选择。', 'limit', 12, 'columns', 4, 'scene', 'ALL')),
                JSON_OBJECT('id', 'vibrant-benefits', 'type', 'SERVICE_BENEFITS', 'enabled', TRUE,
                    'settings', JSON_OBJECT('items', JSON_ARRAY('现货库存', '线下确认', '极速审核', '售后可追踪')))
            )
        ),
        NULL
    ),
    (
        'MINIMAL_DEFAULT',
        '留白 · 极简精品',
        'MINIMAL',
        'DRAFT',
        0,
        JSON_OBJECT(
            'primary', '#191919',
            'accent', '#8B7355',
            'canvas', '#F7F7F5',
            'surface', '#FFFFFF',
            'ink', '#171717',
            'muted', '#747474',
            'radius', '4px',
            'headingFont', 'sans'
        ),
        JSON_OBJECT(
            'schemaVersion', 1,
            'sections', JSON_ARRAY(
                JSON_OBJECT('id', 'minimal-hero', 'type', 'HERO', 'enabled', TRUE,
                    'settings', JSON_OBJECT('eyebrow', 'ESSENTIAL COLLECTION', 'title', '少一点，但每一件都更好',
                        'description', '克制的选择，清楚的材料与规格，把注意力重新交还给产品。',
                        'primaryLabel', '查看系列', 'primaryLink', '#products', 'contentType', 'BANNER')),
                JSON_OBJECT('id', 'minimal-products', 'type', 'PRODUCT_COLLECTION', 'enabled', TRUE,
                    'settings', JSON_OBJECT('eyebrow', 'THE COLLECTION', 'title', '日常精选',
                        'description', '不追逐短暂潮流，只留下经得起长期使用的物品。', 'limit', 8, 'columns', 3, 'scene', 'ALL')),
                JSON_OBJECT('id', 'minimal-story', 'type', 'CONTENT_STORY', 'enabled', TRUE,
                    'settings', JSON_OBJECT('contentType', 'HELP', 'layout', 'full')),
                JSON_OBJECT('id', 'minimal-categories', 'type', 'CATEGORY_NAV', 'enabled', TRUE,
                    'settings', JSON_OBJECT('title', '分类')),
                JSON_OBJECT('id', 'minimal-benefits', 'type', 'SERVICE_BENEFITS', 'enabled', TRUE,
                    'settings', JSON_OBJECT('items', JSON_ARRAY('精选商品', '透明规格', '完整履约', '可追溯售后')))
            )
        ),
        NULL
    );

INSERT INTO iam_permission (code, name)
VALUES ('storefront:template:manage', '商城模板管理');

INSERT IGNORE INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code = 'storefront:template:manage'
WHERE r.code IN ('SUPER_ADMIN', 'CATALOG_OPERATOR');

-- 旧演示公告会在新版模板首页形成固定提示，升级后改为草稿，由运营人员按需发布真实公告。
UPDATE operation_content
SET status = 'DRAFT', published_at = NULL
WHERE content_type = 'ANNOUNCEMENT'
  AND title = '演示商城使用说明';
