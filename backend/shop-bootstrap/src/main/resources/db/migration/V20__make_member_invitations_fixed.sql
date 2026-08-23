-- Every built-in membership level may issue an ordinary invitation while the
-- member account and the level itself remain ACTIVE. Eligibility is evaluated
-- at consume time; status changes never mutate the member's fixed code.
UPDATE membership_level
SET invitation_enabled = 1
WHERE code IN ('BASIC', 'EXPERIENCE_OFFICER', 'SUPER_MEMBER', 'DIVIDEND_MEMBER')
  AND invitation_enabled <> 1;

-- Preserve each currently usable ordinary code, but remove its expiry and use
-- limit. Bootstrap invitations are excluded and retain their explicit one-use
-- lifecycle, including the guarded V19.1 runtime repair.
UPDATE customer_invitation_code
SET expires_at = NULL,
    max_uses = NULL
WHERE is_bootstrap = 0
  AND status = 'ACTIVE'
  AND (expires_at IS NOT NULL OR max_uses IS NOT NULL);
