package com.marketshop.domain.trade;

public enum OrderStatus {
    PENDING_SUPERIOR,
    SUPERIOR_REJECTED,
    PENDING_ADMIN_REVIEW,
    ADMIN_REJECTED,
    PENDING_SHIPMENT,
    SHIPPED,
    COMPLETED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUPERIOR_REJECTED
                || this == ADMIN_REJECTED
                || this == COMPLETED
                || this == CANCELLED;
    }
}
