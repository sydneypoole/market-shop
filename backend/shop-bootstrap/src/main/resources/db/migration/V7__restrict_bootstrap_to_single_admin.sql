-- New environments bootstrap only the configured super administrator.
-- Preserve historical operator identities for foreign-key and audit integrity,
-- but prevent the former default accounts from authenticating after upgrade.
UPDATE iam_admin_account
SET status = 'DISABLED',
    failed_attempts = 0,
    locked_until = NULL,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP(3)
WHERE username IN ('ops-order', 'ops-fulfillment', 'ops-catalog')
  AND status <> 'DISABLED';
