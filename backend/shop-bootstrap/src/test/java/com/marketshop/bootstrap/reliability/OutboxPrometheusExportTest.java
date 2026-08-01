package com.marketshop.bootstrap.reliability;

import com.marketshop.infrastructure.persistence.mapper.ReliabilityMapper;
import com.marketshop.infrastructure.reliability.OutboxMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxPrometheusExportTest {

    @Test
    void outboxGaugesAreExportedInPrometheusFormat() {
        ReliabilityMapper mapper = mock(ReliabilityMapper.class);
        when(mapper.pendingCount()).thenReturn(3L);
        when(mapper.deadCount()).thenReturn(2L);
        when(mapper.oldestPendingAgeSeconds()).thenReturn(45L);
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        new OutboxMetrics(mapper).bindTo(registry);

        assertThat(registry.scrape())
                .contains("market_shop_outbox_pending 3.0")
                .contains("market_shop_outbox_dead 2.0")
                .contains("market_shop_outbox_pending_oldest_age_seconds 45.0");
    }
}
