ALTER TABLE trade_after_sale
    ADD COLUMN state_entered_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER status;

UPDATE trade_after_sale SET state_entered_at = created_at;
