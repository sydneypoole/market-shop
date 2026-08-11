ALTER TABLE iam_user_account
    ADD COLUMN phone_verified_at TIMESTAMP(3) NULL,
    ADD COLUMN avatar_object_key VARCHAR(500) NULL,
    ADD COLUMN avatar_media_type VARCHAR(80) NULL,
    ADD COLUMN avatar_sha256 CHAR(64) NULL,
    ADD COLUMN avatar_size_bytes BIGINT UNSIGNED NULL,
    ADD COLUMN avatar_updated_at TIMESTAMP(3) NULL,
    ADD CONSTRAINT chk_iam_user_phone_verification CHECK (
        (phone_masked IS NULL AND phone_verified_at IS NULL)
        OR (phone_masked IS NOT NULL AND phone_verified_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_iam_user_avatar_metadata CHECK (
        (
            avatar_object_key IS NULL
            AND avatar_media_type IS NULL
            AND avatar_sha256 IS NULL
            AND avatar_size_bytes IS NULL
            AND avatar_updated_at IS NULL
        )
        OR
        (
            avatar_object_key IS NOT NULL
            AND CHAR_LENGTH(TRIM(avatar_object_key)) > 0
            AND avatar_media_type IN ('image/jpeg', 'image/png', 'image/webp')
            AND avatar_sha256 REGEXP '^[0-9a-f]{64}$'
            AND avatar_size_bytes > 0
            AND avatar_updated_at IS NOT NULL
            AND avatar_url REGEXP '^/api/v1/member-avatars/[0-9]+$'
        )
    );

ALTER TABLE iam_bootstrap_sponsor_claim
    DROP CHECK chk_bootstrap_claim_transition_data;

ALTER TABLE iam_bootstrap_sponsor_claim
    ADD CONSTRAINT chk_bootstrap_claim_transition_data CHECK (
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
            AND claimed_provider IN ('WECHAT_H5', 'WECHAT_WEB', 'WECHAT_MP')
            AND claimed_app_id IS NOT NULL
            AND CHAR_LENGTH(TRIM(claimed_app_id)) > 0
            AND claimed_at IS NOT NULL
        )
    );
