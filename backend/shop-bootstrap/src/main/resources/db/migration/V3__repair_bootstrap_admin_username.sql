UPDATE iam_admin_account
SET username = 'admin'
WHERE username = ''
  AND display_name = '超级管理员';
