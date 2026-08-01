package com.marketshop.infrastructure.reliability;

public final class OutboxProjectionFailure extends RuntimeException {

    private final long outboxId;
    private final String eventId;
    private final int attemptCount;

    public OutboxProjectionFailure(long outboxId, String eventId, int attemptCount, RuntimeException cause) {
        super("Outbox projection failed", cause);
        this.outboxId = outboxId;
        this.eventId = eventId;
        this.attemptCount = attemptCount;
    }

    public long outboxId() {
        return outboxId;
    }

    public String eventId() {
        return eventId;
    }

    public int attemptCount() {
        return attemptCount;
    }
}
