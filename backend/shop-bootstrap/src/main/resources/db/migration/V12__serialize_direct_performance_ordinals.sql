-- Reconstruct the historical completion sequence before enforcing the new
-- invariant. Ordering by creation time and primary key makes duplicate legacy
-- ordinals deterministic without deleting or rewriting ledger history.
CREATE TEMPORARY TABLE tmp_direct_performance_ordinal (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    completed_ordinal INT NOT NULL
) ENGINE=InnoDB;

INSERT INTO tmp_direct_performance_ordinal (id, completed_ordinal)
SELECT id,
       ROW_NUMBER() OVER (
           PARTITION BY beneficiary_user_id
           ORDER BY created_at, id
       ) AS completed_ordinal
FROM distribution_direct_performance;

UPDATE distribution_direct_performance performance
JOIN tmp_direct_performance_ordinal repaired ON repaired.id = performance.id
SET performance.completed_ordinal = repaired.completed_ordinal;

DROP TEMPORARY TABLE tmp_direct_performance_ordinal;

ALTER TABLE distribution_direct_performance
    ADD UNIQUE KEY uk_direct_performance_beneficiary_ordinal
        (beneficiary_user_id, completed_ordinal);
