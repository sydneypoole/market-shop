ALTER TABLE trade_order
    ADD COLUMN buyer_note VARCHAR(500) NULL AFTER address_snapshot_json;
