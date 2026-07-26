INSERT INTO iam_role (id, code, name, builtin) VALUES
    (1, 'SUPER_ADMIN', '超级管理员', 1),
    (2, 'ORDER_REVIEWER', '订单审核', 1),
    (3, 'FULFILLMENT_OPERATOR', '履约发货', 1),
    (4, 'CATALOG_OPERATOR', '商品运营', 1),
    (5, 'MEMBER_OPERATOR', '会员运营', 1),
    (6, 'AUDIT_VIEWER', '只读审计', 1);

INSERT INTO iam_permission (id, code, name) VALUES
    (1, 'admin:account:manage', '后台账号管理'),
    (2, 'admin:role:manage', '角色权限管理'),
    (3, 'order:read', '订单查看'),
    (4, 'order:review', '订单审核'),
    (5, 'order:ship', '订单发货'),
    (6, 'catalog:read', '商品查看'),
    (7, 'catalog:write', '商品维护'),
    (8, 'member:read', '会员查看'),
    (9, 'member:write', '会员维护'),
    (10, 'rule:publish', '规则发布'),
    (11, 'aftersale:review', '售后审核'),
    (12, 'audit:read', '审计查看');

INSERT INTO iam_role_permission (role_id, permission_id)
SELECT 1, id FROM iam_permission;

INSERT INTO iam_role_permission (role_id, permission_id) VALUES
    (2, 3), (2, 4), (2, 11),
    (3, 3), (3, 5),
    (4, 6), (4, 7),
    (5, 8), (5, 9), (5, 10),
    (6, 3), (6, 6), (6, 8), (6, 12);

INSERT INTO membership_level (id, code, name, rank_no, invitation_enabled, status) VALUES
    (1, 'BASIC', '基础会员', 0, 0, 'ACTIVE'),
    (2, 'EXPERIENCE_OFFICER', '体验官', 10, 0, 'ACTIVE'),
    (3, 'SUPER_MEMBER', '超级会员', 20, 1, 'ACTIVE'),
    (4, 'DIVIDEND_MEMBER', '分红会员', 30, 1, 'ACTIVE');

INSERT INTO operation_rule_version
    (id, rule_code, version_no, rule_type, parameters_json, status, effective_from)
VALUES
    (
        1,
        'EXPERIENCE_OFFICER_UPGRADE',
        1,
        'SELF_ORDER_TASK',
        JSON_OBJECT(
            'minimumCompletedOrderAmountFen', 29800,
            'eligibleSalesScenes', JSON_ARRAY('UPGRADE'),
            'targetLevel', 'EXPERIENCE_OFFICER'
        ),
        'ACTIVE',
        '2026-01-01 00:00:00.000'
    ),
    (
        2,
        'SUPER_MEMBER_UPGRADE',
        1,
        'SELF_ORDER_TASK',
        JSON_OBJECT(
            'minimumCompletedOrderAmountFen', 199800,
            'eligibleSalesScenes', JSON_ARRAY('UPGRADE'),
            'targetLevel', 'SUPER_MEMBER'
        ),
        'ACTIVE',
        '2026-01-01 00:00:00.000'
    ),
    (
        3,
        'DIVIDEND_MEMBER_QUALIFICATION',
        1,
        'DIRECT_REFERRAL_TASK',
        JSON_OBJECT(
            'requiredCompletedDirectReferrals', 5,
            'minimumReferralOrderAmountFen', 199800,
            'requiredReferralLevel', 'SUPER_MEMBER',
            'targetLevel', 'DIVIDEND_MEMBER'
        ),
        'ACTIVE',
        '2026-01-01 00:00:00.000'
    ),
    (
        4,
        'DIRECT_REFERRAL_POINTS',
        1,
        'DIRECT_REFERRAL_POINTS',
        JSON_OBJECT(
            'qualificationCount', 5,
            'pointsStartOrdinal', 6,
            'totalPoints', 320,
            'availableAPoints', 160,
            'frozenBPoints', 160,
            'maxRewardDepth', 1
        ),
        'ACTIVE',
        '2026-01-01 00:00:00.000'
    ),
    (
        5,
        'REPURCHASE_RELEASE',
        1,
        'FROZEN_POINTS_RELEASE',
        JSON_OBJECT(
            'eligibleSalesScenes', JSON_ARRAY('REPURCHASE'),
            'minimumCompletedOrderAmountFen', 199800,
            'releaseMode', 'FIXED',
            'releasePointsPerOrder', 160,
            'batchOrder', 'FIFO'
        ),
        'ACTIVE',
        '2026-01-01 00:00:00.000'
    ),
    (
        6,
        'DIVIDEND_INACTIVITY_DOWNGRADE',
        1,
        'INACTIVITY_DOWNGRADE',
        JSON_OBJECT(
            'inactiveMonths', 5,
            'sourceLevel', 'DIVIDEND_MEMBER',
            'targetLevel', 'SUPER_MEMBER'
        ),
        'ACTIVE',
        '2026-01-01 00:00:00.000'
    ),
    (
        7,
        'ORDER_TIMERS',
        1,
        'ORDER_TIMER',
        JSON_OBJECT(
            'autoReceiveDaysAfterShipment', 7,
            'afterSaleDaysAfterCompletion', 7,
            'proofRetentionDays', 180,
            'maxProofFiles', 3,
            'maxProofSizeBytes', 8388608
        ),
        'ACTIVE',
        '2026-01-01 00:00:00.000'
    );

INSERT INTO catalog_category (id, parent_id, name, code, sort_order, status) VALUES
    (1, NULL, '升级专区', 'UPGRADE', 10, 'ACTIVE'),
    (2, NULL, '复购专区', 'REPURCHASE', 20, 'ACTIVE');

INSERT INTO catalog_product
    (id, category_id, name, subtitle, cover_url, description_html, sales_scene, status, sort_order)
VALUES
    (
        1,
        1,
        '体验官任务组合',
        '完成订单后按规则累计体验官升级证据',
        NULL,
        '<p>本商品仅用于商城演示，不代表投资、分红或现金收益。</p>',
        'UPGRADE',
        'ON_SALE',
        10
    ),
    (
        2,
        1,
        '超级会员任务组合',
        '完成订单后按规则累计超级会员升级证据',
        NULL,
        '<p>所有积分均为不可提现、不可转账的演示积分。</p>',
        'UPGRADE',
        'ON_SALE',
        20
    ),
    (
        3,
        2,
        '精选复购组合',
        '符合规则的完成订单可释放 B 池冻结演示积分',
        NULL,
        '<p>复购释放规则以订单规则快照为准。</p>',
        'REPURCHASE',
        'ON_SALE',
        30
    );

INSERT INTO catalog_sku
    (id, product_id, sku_code, name, price_fen, market_price_fen, attributes_json, status)
VALUES
    (1, 1, 'DEMO-EXP-298', '体验官组合 298', 29800, 39800, JSON_OBJECT('package', '体验装'), 'ON_SALE'),
    (2, 2, 'DEMO-SUPER-1998', '超级会员组合 1998', 199800, 219800, JSON_OBJECT('package', '升级装'), 'ON_SALE'),
    (3, 3, 'DEMO-REPURCHASE-1998', '复购组合 1998', 199800, 219800, JSON_OBJECT('package', '复购装'), 'ON_SALE');

INSERT INTO catalog_inventory (sku_id, available_quantity, reserved_quantity) VALUES
    (1, 10000, 0),
    (2, 10000, 0),
    (3, 10000, 0);

INSERT INTO operation_content
    (content_type, title, summary, cover_url, target_url, body_html, status, sort_order, published_at)
VALUES
    (
        'ANNOUNCEMENT',
        '演示商城使用说明',
        '本系统采用线下收款确认，不提供在线支付、提现或积分兑现金。',
        NULL,
        NULL,
        '<p>请勿在付款凭证中上传完整银行卡号、账户余额等与订单核验无关的信息。</p>',
        'PUBLISHED',
        10,
        CURRENT_TIMESTAMP(3)
    ),
    (
        'QUICK_ENTRY',
        '规则说明',
        '了解会员任务、订单完成和售后冲正规则。',
        NULL,
        '/rules',
        NULL,
        'PUBLISHED',
        20,
        CURRENT_TIMESTAMP(3)
    ),
    (
        'QUICK_ENTRY',
        '我的邀请',
        '查看专属邀请二维码与直属客户。',
        NULL,
        '/invitation',
        NULL,
        'PUBLISHED',
        30,
        CURRENT_TIMESTAMP(3)
    );
