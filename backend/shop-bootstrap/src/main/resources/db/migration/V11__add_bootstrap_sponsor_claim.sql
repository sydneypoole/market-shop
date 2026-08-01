ALTER TABLE iam_user_account
    ADD COLUMN auth_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER version;

ALTER TABLE iam_admin_account
    ADD COLUMN auth_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER version;

CREATE TABLE iam_bootstrap_sponsor_claim (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sponsor_user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL,
    claim_secret_hash CHAR(64) NULL,
    claimed_provider VARCHAR(32) NULL,
    claimed_app_id VARCHAR(64) NULL,
    claimed_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bootstrap_claim_sponsor (sponsor_user_id),
    UNIQUE KEY uk_bootstrap_claim_secret_hash (claim_secret_hash),
    KEY idx_bootstrap_claim_status (status, created_at),
    CONSTRAINT fk_bootstrap_claim_sponsor
        FOREIGN KEY (sponsor_user_id) REFERENCES iam_user_account (id),
    CONSTRAINT chk_bootstrap_claim_status
        CHECK (status IN ('PENDING', 'CLAIMED')),
    CONSTRAINT chk_bootstrap_claim_version
        CHECK (version >= 0),
    CONSTRAINT chk_bootstrap_claim_transition_data CHECK (
        (
            status = 'PENDING'
            AND claim_secret_hash IS NOT NULL
            AND CHAR_LENGTH(claim_secret_hash) = 64
            AND claim_secret_hash REGEXP '^[0-9a-f]{64}$'
            AND claimed_provider IS NULL
            AND claimed_app_id IS NULL
            AND claimed_at IS NULL
        )
        OR
        (
            status = 'CLAIMED'
            AND claim_secret_hash IS NULL
            AND claimed_provider IN ('WECHAT_H5', 'WECHAT_WEB')
            AND claimed_app_id IS NOT NULL
            AND CHAR_LENGTH(TRIM(claimed_app_id)) > 0
            AND claimed_at IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Existing installations may already have a real WeChat identity attached to
-- the root sponsor. Seal those rows as CLAIMED without manufacturing a claim
-- credential from the ordinary invitation code. Unclaimed installations are
-- initialized later by BootstrapIdentityInitializer from the independently
-- supplied high-entropy secret. The no-op duplicate branch makes rehearsal
-- backfill idempotent while preserving the first immutable claim decision.
INSERT INTO iam_bootstrap_sponsor_claim
    (sponsor_user_id, status, claim_secret_hash,
     claimed_provider, claimed_app_id, claimed_at)
SELECT sponsor.id,
       'CLAIMED',
       NULL,
       real_identity.provider,
       real_identity.app_id,
       real_identity.created_at
FROM iam_user_account sponsor
JOIN iam_external_identity real_identity
  ON real_identity.id = (
      SELECT candidate_identity.id
      FROM iam_external_identity candidate_identity
      WHERE candidate_identity.user_id = sponsor.id
        AND candidate_identity.provider IN ('WECHAT_H5', 'WECHAT_WEB')
      ORDER BY candidate_identity.created_at, candidate_identity.id
      LIMIT 1
  )
WHERE EXISTS (
        SELECT 1
        FROM customer_invitation_code invitation
        WHERE invitation.inviter_user_id = sponsor.id
      )
  AND (
        EXISTS (
            SELECT 1
            FROM iam_external_identity bootstrap_identity
            WHERE bootstrap_identity.user_id = sponsor.id
              AND bootstrap_identity.provider = 'WECHAT_MOCK'
              AND bootstrap_identity.app_id = 'local'
              AND bootstrap_identity.open_id = 'bootstrap-sponsor'
        )
        OR (
            sponsor.nickname = '商城发起人'
            AND NOT EXISTS (
                SELECT 1
                FROM customer_relation relation
                WHERE relation.member_user_id = sponsor.id
            )
        )
      )
ON DUPLICATE KEY UPDATE id = iam_bootstrap_sponsor_claim.id;
