INSERT INTO iam_external_identity (user_id, provider, app_id, open_id, union_id)
SELECT u.id, 'WECHAT_MOCK', 'local', 'bootstrap-sponsor', 'mock-union-bootstrap-sponsor'
FROM iam_user_account u
WHERE u.nickname = '商城发起人'
  AND NOT EXISTS (
      SELECT 1
      FROM iam_external_identity e
      WHERE e.provider = 'WECHAT_MOCK'
        AND e.app_id = 'local'
        AND e.open_id = 'bootstrap-sponsor'
  )
ORDER BY u.id
LIMIT 1;

INSERT INTO iam_union_principal (union_id, user_id)
SELECT 'mock-union-bootstrap-sponsor', u.id
FROM iam_user_account u
WHERE u.nickname = '商城发起人'
  AND NOT EXISTS (
      SELECT 1 FROM iam_union_principal p WHERE p.union_id = 'mock-union-bootstrap-sponsor'
  )
ORDER BY u.id
LIMIT 1;
