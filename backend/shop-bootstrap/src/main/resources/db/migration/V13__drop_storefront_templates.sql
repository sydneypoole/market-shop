DELETE rp FROM iam_role_permission rp
JOIN iam_permission p ON p.id = rp.permission_id
WHERE p.code = 'storefront:template:manage';

DELETE FROM iam_permission WHERE code = 'storefront:template:manage';

DROP TABLE IF EXISTS operation_storefront_template;
