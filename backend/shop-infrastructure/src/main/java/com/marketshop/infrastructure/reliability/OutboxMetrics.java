package com.marketshop.infrastructure.reliability;

import com.marketshop.infrastructure.persistence.mapper.ReliabilityMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics implements MeterBinder {

    private final ReliabilityMapper mapper;

    public OutboxMetrics(ReliabilityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("market.shop.outbox.pending", mapper, value -> value.pendingCount())
                .description("Current pending outbox event count")
                .register(registry);
        Gauge.builder("market.shop.outbox.dead", mapper, value -> value.deadCount())
                .description("Current dead outbox event count")
                .register(registry);
        Gauge.builder("market.shop.outbox.pending.oldest.age", mapper, value -> value.oldestPendingAgeSeconds())
                .baseUnit("seconds")
                .description("Age of the oldest pending outbox event")
                .register(registry);
    }
}
