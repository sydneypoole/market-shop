-- V19.1 adds an explicit, persistent link between the bootstrap sponsor claim
-- and its one configured invitation. Existing claims remain unresolved: no
-- invitation is inferred from owner, age, status, or code shape.
ALTER TABLE customer_invitation_code
    ADD COLUMN is_bootstrap TINYINT(1) NOT NULL DEFAULT 0 AFTER max_uses,
    ADD KEY idx_invitation_bootstrap (is_bootstrap, status, id);

ALTER TABLE iam_bootstrap_sponsor_claim
    ADD COLUMN bootstrap_invitation_id BIGINT UNSIGNED NULL AFTER sponsor_user_id,
    ADD COLUMN invitation_repair_required TINYINT(1) NOT NULL DEFAULT 1 AFTER version,
    ADD UNIQUE KEY uk_bootstrap_claim_invitation (bootstrap_invitation_id),
    ADD KEY idx_bootstrap_claim_repair (invitation_repair_required, id),
    ADD CONSTRAINT fk_bootstrap_claim_invitation
        FOREIGN KEY (bootstrap_invitation_id) REFERENCES customer_invitation_code (id),
    ADD CONSTRAINT chk_bootstrap_claim_invitation_repair CHECK (
        (invitation_repair_required = 1 AND bootstrap_invitation_id IS NULL)
        OR (invitation_repair_required = 0 AND bootstrap_invitation_id IS NOT NULL)
    );

-- Every pre-existing claim is intentionally unresolved. The exact invitation
-- association is supplied later by the guarded runtime repair path.
UPDATE iam_bootstrap_sponsor_claim
SET bootstrap_invitation_id = NULL,
    invitation_repair_required = 1;

CREATE TABLE iam_bootstrap_invitation_repair_guard (
    id TINYINT UNSIGNED NOT NULL,
    repair_required TINYINT(1) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT chk_bootstrap_invitation_repair_guard_id CHECK (id = 1),
    CONSTRAINT chk_bootstrap_invitation_repair_guard_required CHECK (repair_required IN (0, 1)),
    CONSTRAINT chk_bootstrap_invitation_repair_guard_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- An empty database has no legacy invitation to identify. Any existing user
-- means the old unlimited bootstrap credential needs an operator-confirmed
-- repair, regardless of whether the old claim row still exists.
INSERT INTO iam_bootstrap_invitation_repair_guard (id, repair_required)
SELECT 1,
       CASE WHEN EXISTS (SELECT 1 FROM iam_user_account) THEN 1 ELSE 0 END;
