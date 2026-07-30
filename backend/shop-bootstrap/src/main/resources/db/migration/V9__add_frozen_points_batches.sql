CREATE TABLE ledger_frozen_batch (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id BIGINT UNSIGNED NOT NULL,
    source_ledger_entry_id BIGINT UNSIGNED NOT NULL,
    source_order_id BIGINT UNSIGNED NOT NULL,
    rule_version_id BIGINT UNSIGNED NOT NULL,
    original_points BIGINT NOT NULL,
    remaining_points BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_frozen_batch_source_entry (source_ledger_entry_id),
    KEY idx_frozen_batch_account_fifo (account_id, status, created_at, id),
    KEY idx_frozen_batch_source_order (source_order_id),
    CONSTRAINT fk_frozen_batch_account FOREIGN KEY (account_id) REFERENCES ledger_account (id),
    CONSTRAINT fk_frozen_batch_source_entry FOREIGN KEY (source_ledger_entry_id) REFERENCES ledger_entry (id),
    CONSTRAINT fk_frozen_batch_source_order FOREIGN KEY (source_order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_frozen_batch_rule FOREIGN KEY (rule_version_id) REFERENCES operation_rule_version (id),
    CONSTRAINT chk_frozen_batch_points CHECK (
        original_points > 0 AND remaining_points >= 0 AND remaining_points <= original_points
    ),
    CONSTRAINT chk_frozen_batch_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'REVERSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ledger_frozen_release (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account_id BIGINT UNSIGNED NOT NULL,
    source_order_id BIGINT UNSIGNED NOT NULL,
    rule_version_id BIGINT UNSIGNED NOT NULL,
    requested_points BIGINT NOT NULL,
    released_points BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    reversed_by_after_sale_id BIGINT UNSIGNED NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at TIMESTAMP(3) NULL,
    reversed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_frozen_release_order (source_order_id),
    KEY idx_frozen_release_account_time (account_id, created_at, id),
    CONSTRAINT fk_frozen_release_account FOREIGN KEY (account_id) REFERENCES ledger_account (id),
    CONSTRAINT fk_frozen_release_order FOREIGN KEY (source_order_id) REFERENCES trade_order (id),
    CONSTRAINT fk_frozen_release_rule FOREIGN KEY (rule_version_id) REFERENCES operation_rule_version (id),
    CONSTRAINT fk_frozen_release_after_sale FOREIGN KEY (reversed_by_after_sale_id) REFERENCES trade_after_sale (id),
    CONSTRAINT chk_frozen_release_points CHECK (
        requested_points > 0 AND released_points >= 0 AND released_points <= requested_points
    ),
    CONSTRAINT chk_frozen_release_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'REVERSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO ledger_frozen_batch (
    account_id,
    source_ledger_entry_id,
    source_order_id,
    rule_version_id,
    original_points,
    remaining_points,
    status,
    created_at
)
SELECT
    awards.account_id,
    awards.id,
    awards.source_order_id,
    awards.rule_version_id,
    awards.frozen_delta,
    GREATEST(
        awards.frozen_delta - LEAST(
            awards.frozen_delta,
            GREATEST(COALESCE(releases.released_points, 0) - awards.prior_frozen_points, 0)
        ),
        0
    ) AS remaining_points,
    CASE
        WHEN COALESCE(releases.released_points, 0) - awards.prior_frozen_points >= awards.frozen_delta
            THEN 'CONSUMED'
        ELSE 'ACTIVE'
    END AS status,
    awards.occurred_at
FROM (
    SELECT
        entry.id,
        entry.account_id,
        entry.source_order_id,
        entry.rule_version_id,
        entry.frozen_delta,
        entry.occurred_at,
        COALESCE(
            SUM(entry.frozen_delta) OVER (
                PARTITION BY entry.account_id
                ORDER BY entry.occurred_at, entry.id
                ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
            ),
            0
        ) AS prior_frozen_points
    FROM ledger_entry entry
    WHERE entry.entry_type = 'DIRECT_REFERRAL_AWARD'
      AND entry.frozen_delta > 0
      AND entry.source_order_id IS NOT NULL
      AND entry.rule_version_id IS NOT NULL
      AND NOT EXISTS (
          SELECT 1
          FROM ledger_entry reversal
          WHERE reversal.original_entry_id = entry.id
            AND reversal.entry_type = 'REVERSAL'
      )
) awards
LEFT JOIN (
    SELECT
        entry.account_id,
        SUM(-entry.frozen_delta) AS released_points
    FROM ledger_entry entry
    WHERE entry.entry_type = 'FROZEN_POINTS_RELEASED'
      AND entry.frozen_delta < 0
      AND NOT EXISTS (
          SELECT 1
          FROM ledger_entry reversal
          WHERE reversal.original_entry_id = entry.id
            AND reversal.entry_type = 'REVERSAL'
      )
    GROUP BY entry.account_id
) releases ON releases.account_id = awards.account_id;

CREATE TABLE ledger_frozen_release_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    release_ledger_entry_id BIGINT UNSIGNED NOT NULL,
    frozen_batch_id BIGINT UNSIGNED NOT NULL,
    points BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_frozen_release_item_entry_batch (release_ledger_entry_id, frozen_batch_id),
    KEY idx_frozen_release_item_batch (frozen_batch_id),
    CONSTRAINT fk_frozen_release_item_entry
        FOREIGN KEY (release_ledger_entry_id) REFERENCES ledger_entry (id),
    CONSTRAINT fk_frozen_release_item_batch
        FOREIGN KEY (frozen_batch_id) REFERENCES ledger_frozen_batch (id),
    CONSTRAINT chk_frozen_release_item_points CHECK (points > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO ledger_frozen_release_item (
    release_ledger_entry_id,
    frozen_batch_id,
    points,
    created_at
)
SELECT
    releases.id,
    batches.id,
    LEAST(releases.range_end, batches.range_end)
        - GREATEST(releases.range_start, batches.range_start) AS points,
    releases.occurred_at
FROM (
    SELECT
        entry.id,
        entry.account_id,
        entry.occurred_at,
        SUM(-entry.frozen_delta) OVER (
            PARTITION BY entry.account_id
            ORDER BY entry.occurred_at, entry.id
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) + entry.frozen_delta AS range_start,
        SUM(-entry.frozen_delta) OVER (
            PARTITION BY entry.account_id
            ORDER BY entry.occurred_at, entry.id
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS range_end
    FROM ledger_entry entry
    WHERE entry.entry_type = 'FROZEN_POINTS_RELEASED'
      AND entry.frozen_delta < 0
      AND NOT EXISTS (
          SELECT 1
          FROM ledger_entry reversal
          WHERE reversal.original_entry_id = entry.id
            AND reversal.entry_type = 'REVERSAL'
      )
) releases
JOIN (
    SELECT
        batch.id,
        batch.account_id,
        SUM(batch.original_points) OVER (
            PARTITION BY batch.account_id
            ORDER BY batch.created_at, batch.id
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) - batch.original_points AS range_start,
        SUM(batch.original_points) OVER (
            PARTITION BY batch.account_id
            ORDER BY batch.created_at, batch.id
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS range_end
    FROM ledger_frozen_batch batch
) batches
  ON batches.account_id = releases.account_id
 AND releases.range_start < batches.range_end
 AND batches.range_start < releases.range_end;

INSERT INTO ledger_frozen_release (
    account_id,
    source_order_id,
    rule_version_id,
    requested_points,
    released_points,
    status,
    reversed_by_after_sale_id,
    created_at,
    completed_at,
    reversed_at
)
SELECT
    entry.account_id,
    entry.source_order_id,
    entry.rule_version_id,
    SUM(entry.available_delta),
    SUM(entry.available_delta),
    CASE
        WHEN SUM(CASE WHEN reversal.id IS NULL THEN 1 ELSE 0 END) = 0
            THEN 'REVERSED'
        ELSE 'COMPLETED'
    END,
    MAX(CASE WHEN reversal.source_type = 'AFTERSALE' THEN reversal.source_id END),
    MIN(entry.occurred_at),
    MAX(entry.occurred_at),
    MAX(reversal.occurred_at)
FROM ledger_entry entry
LEFT JOIN ledger_entry reversal
  ON reversal.original_entry_id = entry.id
 AND reversal.entry_type = 'REVERSAL'
WHERE entry.entry_type = 'FROZEN_POINTS_RELEASED'
  AND entry.available_delta > 0
  AND entry.source_order_id IS NOT NULL
  AND entry.rule_version_id IS NOT NULL
GROUP BY entry.account_id, entry.source_order_id, entry.rule_version_id;
